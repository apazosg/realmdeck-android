// Ingestión anónima permitida; la capacidad de escritura jamás autoriza lectura.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  handleCreateReport, handleGetAttachment, handleGetReport, handleListReports, HttpError,
} from "../src/handlers.js";
import { freshCapability, testDeps, validBody } from "./helpers.js";

describe("anonymous ingestion", () => {
  it("allows creation without identity and returns derived uploads", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const response = await handleCreateReport(deps, {
      authHeader: header,
      idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody()),
      clientIp: "203.0.113.7",
    });
    assert.equal(response.status, 201);
    assert.match(response.body["id"] as string, /^[0-9a-f-]{36}$/i);
    assert.equal(response.body["state"], "pending");
    assert.deepEqual(response.body["uploads"], []);
  });

  it("rejects missing or malformed capability without leaking", async () => {
    const deps = testDeps();
    for (const authHeader of [null, "Bearer corto", "Token abc", "Bearer " + "x".repeat(44), "Bearer " + "!".repeat(43)]) {
      await assert.rejects(
        () => handleCreateReport(deps, {
          authHeader, idempotencyKey: randomUUID(),
          bodyText: JSON.stringify(validBody()), clientIp: "203.0.113.7",
        }),
        (error: unknown) => error instanceof HttpError && error.status === 401 && error.code === "unauthorized",
      );
    }
  });

  it("fails closed without trusted IP", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    await assert.rejects(
      () => handleCreateReport(deps, {
        authHeader: header, idempotencyKey: randomUUID(),
        bodyText: JSON.stringify(validBody()), clientIp: null,
      }),
      (error: unknown) => error instanceof HttpError && error.code === "ip-unavailable",
    );
  });
});

describe("write capability grants no read access", () => {
  it("cannot list, detail or fetch attachments", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody()), clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    await assert.rejects(
      () => handleListReports(deps, { authHeader: header, limitRaw: null, cursorRaw: null, filtersRaw: {} }),
      (error: unknown) => error instanceof HttpError && error.status === 401,
    );
    await assert.rejects(
      () => handleGetReport(deps, { authHeader: header, reportId: id }),
      (error: unknown) => error instanceof HttpError && error.status === 401,
    );
    await assert.rejects(
      () => handleGetAttachment(deps, { authHeader: header, reportId: id, name: "wine" }),
      (error: unknown) => error instanceof HttpError && error.status === 401,
    );
    // Sin auth tampoco.
    await assert.rejects(
      () => handleListReports(deps, { authHeader: null, limitRaw: null, cursorRaw: null, filtersRaw: {} }),
      (error: unknown) => error instanceof HttpError && error.status === 401,
    );
  });

  it("read token lists and details but reveals no secrets", async () => {
    const deps = testDeps();
    const { header } = freshCapability();
    const created = await handleCreateReport(deps, {
      authHeader: header, idempotencyKey: randomUUID(),
      bodyText: JSON.stringify(validBody()), clientIp: "203.0.113.7",
    });
    const id = created.body["id"] as string;
    const readAuth = `Bearer ${deps.config.readToken}`;
    const listed = await handleListReports(deps, { authHeader: readAuth, limitRaw: null, cursorRaw: null, filtersRaw: {} });
    assert.equal(listed.status, 200);
    assert.equal((listed.body["items"] as unknown[]).length, 1);
    const detail = await handleGetReport(deps, { authHeader: readAuth, reportId: id });
    assert.equal((detail.body as Record<string, unknown>)["id"], id);
    const serialized = JSON.stringify(detail.body);
    assert.doesNotMatch(serialized, /test-read-token|test-cron-secret|test-salt/);
  });
});
