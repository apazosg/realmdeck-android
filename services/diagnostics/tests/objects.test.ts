// Objetos exactos: pathname/MIME/tamaño/SHA verificados al completar;
// caducidad y borrado con cuota liberada solo tras confirmar.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  handleCleanup, handleCompleteReport, handleCreateReport, handleGetAttachment, HttpError,
  MAX_COMPLETE_ATTEMPTS,
} from "../src/handlers.js";
import { blobPathname, uploadAllowlist } from "../src/blob.js";
import { freshCapability, sha256HexText, testDeps, validBody } from "./helpers.js";

const WINE_LOG = "wine: err:module:import_dll library L\"dxgi.dll\" not found\n";

function bodyWithWine(text: string = WINE_LOG): Record<string, unknown> {
  return validBody({
    attachments: [{ name: "wine", bytes: Buffer.byteLength(text, "utf8"), sha256: sha256HexText(text), contentType: "text/plain; charset=utf-8" }],
  });
}

async function reserveWithLog() {
  const deps = testDeps();
  const { header } = freshCapability();
  const created = await handleCreateReport(deps, {
    authHeader: header, idempotencyKey: randomUUID(),
    bodyText: JSON.stringify(bodyWithWine()), clientIp: "203.0.113.7",
  });
  const id = created.body["id"] as string;
  return { deps, header, id };
}

describe("server-derived objects", () => {
  it("issues exact pathname, MIME and byte cap", async () => {
    const { deps, id } = await reserveWithLog();
    assert.equal(deps.blob.issuedUploads.length, 1);
    const issued = deps.blob.issuedUploads[0];
    assert.equal(issued?.pathname, blobPathname(id, "wine"));
    assert.equal(issued?.contentType, "text/plain; charset=utf-8");
    assert.equal(issued?.maxBytes, Buffer.byteLength(WINE_LOG, "utf8"));
  });

  it("completes only when size, type, SHA and UTF-8 match", async () => {
    const { deps, header, id } = await reserveWithLog();
    deps.blob.objects.set(blobPathname(id, "wine"), { bytes: Buffer.from(WINE_LOG, "utf8"), contentType: "text/plain; charset=utf-8" });
    const completed = await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal(completed.status, 200);
    assert.equal(completed.body["state"], "complete");
    // Repetir es idempotente y no redescarga.
    const again = await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal(again.body["state"], "complete");
  });

  it("rejects tampered, missing or mistyped blobs without completing", async () => {
    const { deps, header, id } = await reserveWithLog();
    const pathname = blobPathname(id, "wine");
    // Ausente.
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.code === "attachment-missing",
    );
    // Manipulado (misma longitud, distinto SHA).
    deps.blob.objects.set(pathname, { bytes: Buffer.from(WINE_LOG.replace("dxgi", "dxgX"), "utf8"), contentType: "text/plain; charset=utf-8" });
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.code === "attachment-hash",
    );
    // Tipo incorrecto.
    deps.blob.objects.set(pathname, { bytes: Buffer.from(WINE_LOG, "utf8"), contentType: "application/zip" });
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && (error.code === "attachment-type" || error.code === "attachment-hash"),
    );
    assert.equal((await deps.store.getById(id))?.state, "pending");
  });

  it("wrong capability learns nothing (404) and pending has no read URL", async () => {
    const { deps, id } = await reserveWithLog();
    const { header: other } = freshCapability();
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: other }),
      (error: unknown) => error instanceof HttpError && error.status === 404,
    );
    const readAuth = `Bearer ${deps.config.readToken}`;
    await assert.rejects(
      () => handleGetAttachment(deps, { authHeader: readAuth, reportId: id, name: "wine" }),
      (error: unknown) => error instanceof HttpError && error.status === 404,
    );
  });

  it("expired sessions cannot complete", async () => {
    const { deps, header, id } = await reserveWithLog();
    deps.now.ms += 2 * 3600 * 1000;
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.status === 410,
    );
  });
});

describe("fail-closed sanitization on complete", () => {
  it("rejects attachments that would need redaction without exposing them", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const dirty = "contacto tester@example.com password=hunter2\n";
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(bodyWithWine(dirty)),
      clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    deps.blob.objects.set(blobPathname(id, "wine"), { bytes: Buffer.from(dirty, "utf8"), contentType: "text/plain; charset=utf-8" });
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.status === 422 && error.code === "attachment-needs-sanitization",
    );
    // Sigue pendiente, sin verified ni URL de lectura.
    const stored = await deps.store.getById(id);
    assert.equal(stored?.state, "pending");
    const readAuth = `Bearer ${deps.config.readToken}`;
    await assert.rejects(
      () => handleGetAttachment(deps, { authHeader: readAuth, reportId: id, name: "wine" }),
      (error: unknown) => error instanceof HttpError && error.status === 404,
    );
  });

  it("accepts already-redacted client markers without false rejection", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const clean = "contacto [redacted:email] clave [redacted:secret-assign]\n";
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody({
        attachments: [{ name: "wine", bytes: Buffer.byteLength(clean, "utf8"), sha256: sha256HexText(clean), contentType: "text/plain; charset=utf-8" }],
      })),
      clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    deps.blob.objects.set(blobPathname(id, "wine"), { bytes: Buffer.from(clean, "utf8"), contentType: "text/plain; charset=utf-8" });
    const completed = await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal(completed.body["state"], "complete");
  });
});

describe("expiry and deletion", () => {
  it("cleanup deletes derived blobs first and only then releases quota", async () => {
    const { deps, header, id } = await reserveWithLog();
    deps.blob.objects.set(blobPathname(id, "wine"), { bytes: Buffer.from(WINE_LOG, "utf8"), contentType: "text/plain; charset=utf-8" });
    await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal((await deps.store.count()).reports, 1);
    // Aún dentro de retención: nada que borrar.
    const cron = `Bearer ${deps.config.cronSecret}`;
    const early = await handleCleanup(deps, { authHeader: cron });
    assert.equal(early.body["deleted"], 0);
    // Tras 7 días: borra blobs y fila; repetir es idempotente.
    deps.now.ms += 8 * 24 * 3600 * 1000;
    const cleaned = await handleCleanup(deps, { authHeader: cron });
    assert.equal(cleaned.body["deleted"], 1);
    assert.equal(cleaned.body["orphansDeleted"], 0);
    assert.equal(deps.blob.objects.size, 0);
    assert.equal((await deps.store.count()).reports, 0);
    const repeated = await handleCleanup(deps, { authHeader: cron });
    assert.equal(repeated.body["deleted"], 0);
  });

  it("pending reports expire after session plus one hour grace, then cleanup removes them", async () => {
    const { deps, id } = await reserveWithLog();
    assert.equal((await deps.store.findExpired(deps.now.ms, 0)).length, 0);
    // Dentro de la gracia (PUT tardío aún puede confirmar): no se lista.
    deps.now.ms += 3600 * 1000 + 60_000;
    assert.equal((await deps.store.findExpired(deps.now.ms, 0)).length, 0);
    const cron = `Bearer ${deps.config.cronSecret}`;
    const withinGrace = await handleCleanup(deps, { authHeader: cron });
    assert.equal(withinGrace.body["deleted"], 0);
    assert.notEqual(await deps.store.getById(id), null);
    // Tras la gracia sí se borra.
    deps.now.ms += 3600 * 1000;
    assert.deepEqual(await deps.store.findExpired(deps.now.ms, 0), [id]);
    const cleaned = await handleCleanup(deps, { authHeader: cron });
    assert.equal(cleaned.body["deleted"], 1);
  });

  it("sweep deletes only old derived orphans without rows", async () => {
    const deps = testDeps();
    const cron = `Bearer ${deps.config.cronSecret}`;
    const old = deps.now.ms - 8 * 24 * 3600 * 1000;
    const put = (pathname: string, uploadedAtMs: number): void => {
      deps.blob.objects.set(pathname, { bytes: Buffer.from("x"), contentType: "text/plain; charset=utf-8", uploadedAtMs });
    };
    const orphanOld = `reports/${randomUUID()}/wine.txt`;
    const orphanLive = `reports/${randomUUID()}/stdout.txt`;
    put(orphanOld, old);
    put(orphanLive, deps.now.ms);
    put("reports/not-a-uuid/wine.txt", old);
    put(`reports/${randomUUID()}/evil.txt`, old);
    put("other/stuff.txt", old);
    // Blob con fila viva no se toca aunque sea viejo.
    const { header } = freshCapability();
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(bodyWithWine()), clientIp: "203.0.113.7",
    });
    const liveId = created.body["id"] as string;
    put(blobPathname(liveId, "wine"), old);
    const cleaned = await handleCleanup(deps, { authHeader: cron });
    assert.equal(cleaned.body["orphansDeleted"], 1);
    assert.ok(!deps.blob.objects.has(orphanOld));
    assert.ok(deps.blob.objects.has(orphanLive));
    assert.ok(deps.blob.objects.has("reports/not-a-uuid/wine.txt"));
    assert.ok(deps.blob.objects.has("other/stuff.txt"));
    assert.ok(deps.blob.objects.has(blobPathname(liveId, "wine")));
  });

  it("cleanup without cron secret is rejected", async () => {
    const deps = testDeps();
    await assert.rejects(
      () => handleCleanup(deps, { authHeader: null }),
      (error: unknown) => error instanceof HttpError && error.status === 401,
    );
  });
});

describe("complete attempt budget", () => {
  it("bounds expensive attempts including concurrent calls", async () => {
    const { deps, header, id } = await reserveWithLog();
    // Sin blobs plantados: cada intento consume presupuesto y falla 422.
    const outcomes = await Promise.all(
      Array.from({ length: MAX_COMPLETE_ATTEMPTS + 2 }, () =>
        handleCompleteReport(deps, { reportId: id, authHeader: header }).then(
          () => "ok",
          (error: unknown) => error instanceof HttpError ? `${error.status}:${error.code}` : "other",
        ),
      ),
    );
    const limited = outcomes.filter((o) => o === "429:complete-attempts").length;
    const missing = outcomes.filter((o) => o === "422:attachment-missing").length;
    assert.equal(missing, MAX_COMPLETE_ATTEMPTS);
    assert.equal(limited, 2);
    assert.equal((await deps.store.getById(id))?.state, "pending");
  });

  it("replay-complete stays cheap and idempotent after failures", async () => {
    const { deps, header, id } = await reserveWithLog();
    deps.blob.objects.set(blobPathname(id, "wine"), { bytes: Buffer.from(WINE_LOG, "utf8"), contentType: "text/plain; charset=utf-8" });
    const completed = await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal(completed.body["state"], "complete");
    const attemptsBefore = (await deps.store.getById(id))?.completeAttempts;
    const again = await handleCompleteReport(deps, { reportId: id, authHeader: header });
    assert.equal(again.body["state"], "complete");
    assert.equal((await deps.store.getById(id))?.completeAttempts, attemptsBefore);
  });
});

describe("guarded state transitions", () => {
  it("keeps cleanup results when the orphan listing is unavailable", async () => {
    const deps = testDeps();
    deps.blob.listObjects = async () => { throw new Error("listing unavailable"); };
    const result = await handleCleanup(deps, { authHeader: `Bearer ${deps.config.cronSecret}` });
    assert.equal(result.status, 200);
    assert.equal(result.body["failed"], 1);
    assert.equal(result.body["orphansDeleted"], 0);
  });

  it("expired reports cannot claim attempts or complete (404/410, never 500)", async () => {
    const { deps, header, id } = await reserveWithLog();
    deps.now.ms += 2 * 3600 * 1000;
    assert.equal(await deps.store.claimCompleteAttempt(id, deps.now.ms), null);
    assert.equal(await deps.store.markComplete(id, 0, deps.now.ms), null);
    await assert.rejects(
      () => handleCompleteReport(deps, { reportId: id, authHeader: header }),
      (error: unknown) => error instanceof HttpError && error.status === 410,
    );
  });

  it("removeExpiredReport skips live reports without touching them", async () => {
    const { deps, id } = await reserveWithLog();
    const result = await deps.store.removeExpiredReport(id, deps.now.ms, 0, async () => {
      throw new Error("must not delete");
    });
    assert.equal(result, "skipped");
    assert.notEqual(await deps.store.getById(id), null);
  });

  it("per-report blob failures do not abort other reports or leak quota early", async () => {
    const deps = testDeps();
    const cron = `Bearer ${deps.config.cronSecret}`;
    const ids: string[] = [];
    for (let i = 0; i < 2; i++) {
      const { header } = freshCapability();
      const created = await handleCreateReport(deps, {
        authHeader: header, idempotencyKey: randomUUID(),
        bodyText: JSON.stringify(bodyWithWine()), clientIp: "203.0.113.7",
      });
      ids.push(created.body["id"] as string);
    }
    deps.now.ms += 2 * 3600 * 1000;
    let calls = 0;
    const original = deps.blob.deletePathname.bind(deps.blob);
    deps.blob.deletePathname = async (pathname: string) => {
      calls += 1;
      if (calls === 1) throw new Error("blob boom");
      return original(pathname);
    };
    const cleaned = await handleCleanup(deps, { authHeader: cron });
    assert.equal(cleaned.body["deleted"], 1);
    assert.equal(cleaned.body["failed"], 1);
    // La cuota del informe fallido NO se libera: su fila sigue pendiente
    // de borrado para el próximo ciclo.
    assert.equal((await deps.store.count()).reports, 1);
    assert.equal((await deps.store.findExpired(deps.now.ms, 0)).length, 1);
  });
});

describe("upload MIME allowlist", () => {
  it("signs the base type while the manifest keeps the exact wire type", async () => {
    assert.deepEqual(uploadAllowlist("text/plain; charset=utf-8"), ["text/plain"]);
    const { deps } = await reserveWithLog();
    assert.equal(deps.blob.issuedUploads[0]?.contentType, "text/plain; charset=utf-8");
  });
});
