// La ruta admite GET (cron Vercel) y POST (manual), ambos autenticados.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { routeCleanup } from "../api/cleanup.js";
import type { ApiRequest, ApiResponse } from "../src/http.js";
import { testDeps } from "./helpers.js";

function request(method: string | undefined, auth: string | null): ApiRequest {
  const headers = auth === null ? {} : { authorization: auth };
  if (method === undefined) return { headers, query: {} };
  return { method, headers, query: {} };
}

function capture(): ApiResponse & { statusCode: number; payload: unknown } {
  const captured = {
    statusCode: 0,
    payload: null as unknown,
    status(code: number): ApiResponse {
      captured.statusCode = code;
      return captured as unknown as ApiResponse;
    },
    setHeader(): void {},
    json(data: unknown): void {
      captured.payload = data;
    },
  };
  return captured as ApiResponse & { statusCode: number; payload: unknown };
}

describe("cleanup route", () => {
  it("accepts authenticated GET (Vercel cron) and POST (manual)", async () => {
    for (const method of ["GET", "POST"]) {
      const deps = testDeps();
      const res = capture();
      await routeCleanup(request(method, `Bearer ${deps.config.cronSecret}`), res, deps);
      assert.equal(res.statusCode, 200);
      assert.deepEqual(res.payload, { deleted: 0, failed: 0, orphansDeleted: 0 });
    }
  });

  it("rejects other methods and missing secrets", async () => {
    const deps = testDeps();
    const notAllowed = capture();
    await routeCleanup(request("DELETE", `Bearer ${deps.config.cronSecret}`), notAllowed, deps);
    assert.equal(notAllowed.statusCode, 405);
    const unauthorized = capture();
    await routeCleanup(request("GET", null), unauthorized, deps);
    assert.equal(unauthorized.statusCode, 401);
    assert.deepEqual(unauthorized.payload, { error: "unauthorized" });
  });
});
