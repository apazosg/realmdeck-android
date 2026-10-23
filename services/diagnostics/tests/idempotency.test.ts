// Idempotencia: misma clave/capacidad/contenido reemite; cualquier
// diferencia en capacidad o contenido no modifica el informe.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { handleCreateReport, HttpError } from "../src/handlers.js";
import { freshCapability, sha256HexText, testDeps, validBody } from "./helpers.js";

const LOG = "wine: err:module:import_dll\n";

function bodyWithLog(size = LOG.length): Record<string, unknown> {
  const text = LOG.repeat(Math.max(1, Math.ceil(size / LOG.length))).slice(0, size);
  return validBody({
    attachments: [{ name: "wine", bytes: text.length, sha256: sha256HexText(text), contentType: "text/plain; charset=utf-8" }],
  });
}

describe("idempotency", () => {
  it("same key/capability/content replays the same report", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const key = randomUUID();
    const bodyText = JSON.stringify(bodyWithLog());
    const first = await handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" });
    const second = await handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" });
    assert.equal(first.status, 201);
    assert.equal(second.status, 200);
    assert.equal(first.body["id"], second.body["id"]);
    assert.equal((await deps.store.count()).reports, 1);
    // Misma URL derivada del servidor, sin rutas aportadas por el cliente.
    const expected = `reports/${first.body["id"] as string}/wine.txt`;
    assert.equal(deps.blob.issuedUploads[0]?.pathname, expected);
    assert.equal(deps.blob.issuedUploads[1]?.pathname, expected);
  });

  it("same key with different capability or content conflicts without edits", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const key = randomUUID();
    const bodyText = JSON.stringify(bodyWithLog());
    const created = await handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" });
    const issuedBefore = deps.blob.issuedUploads.length;

    const otherCap = `Bearer ${randomBytes(32).toString("base64url")}`;
    await assert.rejects(
      () => handleCreateReport(deps, { authHeader: otherCap, idempotencyKey: key, bodyText, clientIp: "203.0.113.9" }),
      (error: unknown) => error instanceof HttpError && error.status === 409,
    );
    const altered = JSON.stringify({ ...bodyWithLog(), comment: "Otro comentario" });
    await assert.rejects(
      () => handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText: altered, clientIp: "203.0.113.7" }),
      (error: unknown) => error instanceof HttpError && error.status === 409,
    );
    // Sin modificaciones ni nuevas URLs.
    assert.equal((await deps.store.count()).reports, 1);
    assert.equal(deps.blob.issuedUploads.length, issuedBefore);
    assert.equal(created.body["id"], (await deps.store.getById(created.body["id"] as string))?.id);
  });

  it("re-emissions are bounded", async () => {
    const deps = testDeps({ maxUploadIssues: 2 });
    const { header } = freshCapability();
    const key = randomUUID();
    const bodyText = JSON.stringify(bodyWithLog());
    await handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" });
    await handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" });
    await assert.rejects(
      () => handleCreateReport(deps, { authHeader: header, idempotencyKey: key, bodyText, clientIp: "203.0.113.7" }),
      (error: unknown) => error instanceof HttpError && error.status === 429 && error.code === "upload-limit",
    );
  });
});
