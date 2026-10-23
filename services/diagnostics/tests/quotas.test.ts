// Cuotas atómicas: la reserva comprueba antes de emitir URLs; el fallo
// de cuota no emite nada. Además verifica el orden transaccional SQL.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { handleCreateReport, HttpError } from "../src/handlers.js";
import { POOL_SSL, PostgresStore } from "../src/store-postgres.js";
import { freshCapability, testDeps, validBody } from "./helpers.js";

async function create(deps: ReturnType<typeof testDeps>, installationId: string, ip: string) {
  const { header } = freshCapability();
  return handleCreateReport(deps, {
    authHeader: header,
    idempotencyKey: randomUUID(),
    bodyText: JSON.stringify(validBody({ installationId })),
    clientIp: ip,
  });
}

describe("quotas", () => {
  it("limits 3 per installation per day", async () => {
    const deps = testDeps();
    const installationId = randomUUID();
    for (let i = 0; i < 3; i++) {
      await create(deps, installationId, `203.0.113.${i + 1}`);
    }
    await assert.rejects(
      () => create(deps, installationId, "203.0.113.9"),
      (error: unknown) => error instanceof HttpError && error.status === 429 && error.code === "quota-exceeded",
    );
  });

  it("limits 5 per IP and 5 global per day", async () => {
    const deps = testDeps();
    for (let i = 0; i < 5; i++) {
      await create(deps, randomUUID(), "198.51.100.4");
    }
    // Sexto con la misma IP: cuota por IP.
    await assert.rejects(
      () => create(deps, randomUUID(), "198.51.100.4"),
      (error: unknown) => error instanceof HttpError && error.status === 429,
    );
    // Otra IP pero tope global alcanzado (5/día): también 429.
    await assert.rejects(
      () => create(deps, randomUUID(), "192.0.2.9"),
      (error: unknown) => error instanceof HttpError && error.status === 429,
    );
  });

  it("enforces storage caps and emits no URLs on quota failure", async () => {
    const deps = testDeps({ maxReports: 2 });
    await create(deps, randomUUID(), "203.0.113.1");
    await create(deps, randomUUID(), "203.0.113.2");
    const issuedBefore = deps.blob.issuedUploads.length;
    await assert.rejects(() => create(deps, randomUUID(), "203.0.113.3"),
      (error: unknown) => error instanceof HttpError && error.status === 429);
    assert.equal(deps.blob.issuedUploads.length, issuedBefore);
  });

  it("rejects oversized metadata", async () => {
    const deps = testDeps({ metaMaxBytes: 64 });
    const { header } = freshCapability();
    await assert.rejects(
      () => handleCreateReport(deps, {
        authHeader: header, idempotencyKey: randomUUID(),
        bodyText: JSON.stringify(validBody()), clientIp: "203.0.113.1",
      }),
      (error: unknown) => error instanceof HttpError && error.status === 413,
    );
  });

  it("rejects invalid bodies without reserving", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    await assert.rejects(
      () => handleCreateReport(deps, {
        authHeader: header, idempotencyKey: randomUUID(),
        bodyText: JSON.stringify({ schema: 1, extra: true }), clientIp: "203.0.113.1",
      }),
      (error: unknown) => error instanceof HttpError && error.status === 400,
    );
    assert.equal((await deps.store.count()).reports, 0);
    assert.equal(deps.blob.issuedUploads.length, 0);
  });
});

describe("postgres reservation is one guarded transaction", () => {
  it("runs BEGIN, advisory lock, checks, inserts and COMMIT in order", async () => {
    const seen: string[] = [];
    const insertedRow = {
      id: "11111111-2222-4333-8444-555555555555",
      idempotency_key: "key-1",
      installation_id: "inst-1",
      cap_hash: "cap",
      snapshot_hash: "snap",
      state: "pending",
      metadata_json: "{}",
      manifest_json: "{}",
      ip_hmac: null,
      created_at: new Date(1_800_000_000_000),
      expires_at: new Date(1_800_003_600_000),
      completed_at: null,
      upload_issues: 0,
      complete_attempts: 0,
      total_bytes: 0,
      redaction_count: 0,
    };
    const fakeClient = {
      query: async (text: string) => {
        seen.push(text);
        if (text.startsWith("SELECT * FROM reports WHERE idempotency_key")) return { rows: [], rowCount: 0 };
        if (text.startsWith("SELECT * FROM reports WHERE id =")) return { rows: [insertedRow], rowCount: 1 };
        if (text.startsWith("SELECT COUNT(*) AS n, COALESCE")) return { rows: [{ n: "0", b: "0" }], rowCount: 1 };
        if (text.startsWith("SELECT COUNT")) return { rows: [{ n: "0" }], rowCount: 1 };
        return { rows: [], rowCount: 0 };
      },
      release: () => {},
    };
    const fakePool = {
      connect: async () => fakeClient,
      query: async () => {
        throw new Error("pool must not be re-entered inside reserve");
      },
    };
    const store = new PostgresStore(fakePool as never);
    const result = await store.reserve(
      {
        id: insertedRow.id, idempotencyKey: "key-1", installationId: "inst-1",
        capHash: "cap", snapshotHash: "snap", metadataJson: "{}", manifestJson: "{}",
        ipHmac: null, nowMs: 1_800_000_000_000, expiresAtMs: 1_800_003_600_000,
        totalBytes: 0, redactions: 0, attachments: [],
      },
      { perInstallationPerDay: 3, perIpPerDay: 5, globalPerDay: 5, maxReports: 100, maxReservedBytes: 1, dayStartMs: 0 },
    );
    assert.equal(result.outcome, "reserved");
    // Los timestamptz llegan como Date desde pg: se convierten sin NaN.
    if (result.outcome === "reserved") {
      assert.equal(result.report.createdAtMs, 1_800_000_000_000);
      assert.equal(result.report.expiresAtMs, 1_800_003_600_000);
    }
    // Sin reentrada al pool: la relectura usa el cliente de la transacción.
    assert.deepEqual(seen[0], "BEGIN ISOLATION LEVEL READ COMMITTED");
    const lockAt = seen.findIndex((s) => s.startsWith("SELECT pg_advisory_xact_lock"));
    const firstRead = seen.findIndex((s) => s.startsWith("SELECT * FROM reports WHERE idempotency_key"));
    const insertAt = seen.findIndex((s) => s.startsWith("INSERT INTO reports"));
    const rereadAt = seen.findIndex((s) => s.startsWith("SELECT * FROM reports WHERE id ="));
    const commitAt = seen.lastIndexOf("COMMIT");
    for (const [name, at] of [["lock", lockAt], ["read", firstRead], ["insert", insertAt], ["reread", rereadAt], ["commit", commitAt]] as const) {
      assert.ok(at >= 0, `missing ${name}: ${seen.join(" | ")}`);
    }
    assert.ok(lockAt < firstRead && firstRead < insertAt && insertAt < rereadAt && rereadAt < commitAt);
    // READ COMMITTED con lock adquirido antes de leer: sin fallos de
    // serialización entre reservas concurrentes.
  });

  it("never disables TLS verification", () => {
    assert.deepEqual(POOL_SSL, { rejectUnauthorized: true });
  });

  it("filters use nested config paths (config.app, not root app)", async () => {
    const seen: string[] = [];
    const fakePool = {
      query: async (text: string) => {
        seen.push(text);
        return { rows: [], rowCount: 0 };
      },
    };
    const store = new PostgresStore(fakePool as never);
    await store.list(20, null, { device: "xaga", gpu: "Mali-G610", appVersion: "1.2.3", result: "failed" });
    const sql = seen.join("\n");
    assert.match(sql, /metadata_json::jsonb->'config'->'device'->>'model'/);
    assert.match(sql, /metadata_json::jsonb->'config'->'device'->>'gpu'/);
    assert.match(sql, /metadata_json::jsonb->'config'->'app'->>'versionName'/);
    assert.match(sql, /metadata_json::jsonb->'attempt'->>'result'/);
  });
});
