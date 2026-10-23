// Saneado: secretos semilla en comentario y adjuntos se redactan;
// dos informes distintos conservan su configuración e hitos.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { handleCompleteReport, handleCreateReport, handleGetAttachment, handleGetReport, handleListReports, HttpError } from "../src/handlers.js";
import { blobPathname } from "../src/blob.js";
import { freshCapability, sha256HexText, testDeps, validBody } from "./helpers.js";

const SECRET_COMMENT = "Falla al arrancar. Mi correo es tester@example.com y mi clave password=hunter2 secreto. IP 192.168.1.10.";
const SECRET_LOG = "contacto tester@example.com\ntoken=abc123\n/home/jugador/secreto\nBearer abc.def.ghi\n";

describe("sanitizer", () => {
  it("redacts seeded secrets in stored comment", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody({ comment: SECRET_COMMENT })), clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    const detail = await handleGetReport(deps, { authHeader: `Bearer ${deps.config.readToken}`, reportId: id });
    const metadata = detail.body["metadata"] as Record<string, unknown>;
    const stored = metadata["comment"] as string;
    assert.doesNotMatch(stored, /tester@example\.com|hunter2|192\.168\.1\.10/);
    assert.match(stored, /\[redacted:(email|secret-assign|ipv4)\]/);
  });

  it("rejects secret-bearing attachments instead of storing them", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody({
        attachments: [{ name: "stdout", bytes: Buffer.byteLength(SECRET_LOG, "utf8"), sha256: sha256HexText(SECRET_LOG), contentType: "text/plain; charset=utf-8" }],
      })),
      clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    deps.blob.objects.set(blobPathname(id, "stdout"), { bytes: Buffer.from(SECRET_LOG, "utf8"), contentType: "text/plain; charset=utf-8" });
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.code === "attachment-needs-sanitization",
    );
    // Ni verified ni lectura: el blob sensible queda pendiente de expirar.
    const readAuth = `Bearer ${deps.config.readToken}`;
    await assert.rejects(
      () => handleGetAttachment(deps, { authHeader: readAuth, reportId: id, name: "stdout" }),
      (error: unknown) => error instanceof HttpError && error.status === 404,
    );
    assert.equal((await deps.store.getById(id))?.state, "pending");
  });
});

describe("two reports keep their own config and milestones", () => {
  it("filters by device and result without mixing", async () => {
    const deps = testDeps();
    const readAuth = `Bearer ${deps.config.readToken}`;
    const first = await handleCreateReport(deps, {
      authHeader: freshCapability().header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody()), clientIp: "203.0.113.1",
    });
    const secondBody = validBody({
      comment: "Login visible pero sin auth.",
      attempt: {
        id: randomUUID(), runId: "run-2", status: "partial", stage: "render",
        startedAt: new Date(1_799_999_990_000).toISOString(), endedAt: new Date(1_800_000_000_000).toISOString(),
        result: "login-visible", exitCode: null, terminationUnknown: false,
      },
    });
    (secondBody["config"] as Record<string, unknown>)["device"] = {
      manufacturer: "Google", model: "Pixel 8", soc: "Tensor G3", gpu: "Mali-G715",
      androidRelease: "14", androidApi: 34, kernel: "5.15", driver: "PanVK v25",
    };
    const second = await handleCreateReport(deps, {
      authHeader: freshCapability().header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(secondBody), clientIp: "203.0.113.2",
    });
    assert.notEqual(first.body["id"], second.body["id"]);

    const byDevice = await handleListReports(deps, {
      authHeader: readAuth, limitRaw: null, cursorRaw: null,
      filtersRaw: { device: "Pixel 8" },
    });
    const items = byDevice.body["items"] as Array<Record<string, unknown>>;
    assert.equal(items.length, 1);
    assert.equal(items[0]?.["id"], second.body["id"]);

    const byResult = await handleListReports(deps, {
      authHeader: readAuth, limitRaw: null, cursorRaw: null,
      filtersRaw: { result: "wine-start-failed" },
    });
    assert.equal((byResult.body["items"] as unknown[]).length, 1);
    assert.equal((byResult.body["items"] as Array<Record<string, unknown>>)[0]?.["id"], first.body["id"]);

    const detail = await handleGetReport(deps, { authHeader: readAuth, reportId: second.body["id"] as string });
    const metadata = detail.body["metadata"] as Record<string, unknown>;
    assert.equal(metadata["comment"], "Login visible pero sin auth.");
    assert.equal(((metadata["attempt"] as Record<string, unknown>) ?? {})["result"], "login-visible");
  });
});

describe("all metadata strings sanitized before persistence", () => {
  it("redacts secrets in config, attempt and sources, not just comment", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const body = validBody({ comment: "limpio" });
    (body["config"] as Record<string, unknown>)["client"] = {
      build: "1.60.1", architecture: "x86_64", locale: "esES",
      status: "token=abc123",
    };
    body["attempt"] = {
      id: randomUUID(), runId: "run-1", status: "cuenta tester@example.com",
      stage: "wine-start", startedAt: new Date(1_799_999_990_000).toISOString(),
      endedAt: new Date(1_800_000_000_000).toISOString(),
      result: "failed", exitCode: 1, terminationUnknown: false,
    };
    body["sources"] = [{ name: "wine-log", status: "ok", reason: "/home/jugador/x", truncated: false, bytes: 3 }];
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(body), clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    const detail = await handleGetReport(deps, { authHeader: `Bearer ${deps.config.readToken}`, reportId: id });
    const serialized = JSON.stringify(detail.body["metadata"]);
    assert.doesNotMatch(serialized, /abc123|tester@example\.com|\/home\/jugador/);
    assert.match(serialized, /\[redacted:(secret-assign|email|home-path)\]/);
    const stored = await deps.store.getById(id);
    assert.ok((stored?.redactionCount ?? 0) >= 3);
  });

  it("identical secret-bearing content replays the same report", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const key = randomUUID();
    const bodyText = JSON.stringify(validBody({ comment: SECRET_COMMENT }));
    const first = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7",
    });
    const second = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7",
    });
    assert.equal(first.status, 201);
    assert.equal(second.status, 200);
    assert.equal(first.body["id"], second.body["id"]);
  });
});
