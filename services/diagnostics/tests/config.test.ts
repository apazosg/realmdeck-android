// Configuración ausente = fallo cerrado; IP solo de cabecera confiable.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { ConfigError, loadConfig } from "../src/config.js";
import { trustedClientIp, type ApiRequest } from "../src/http.js";

function requestWith(headers: Record<string, string>): ApiRequest {
  return { method: "GET", headers, query: {} };
}

describe("config", () => {
  it("fails closed when secrets are missing", async () => {
    const saved = { ...process.env };
    for (const key of ["DATABASE_URL", "BLOB_READ_WRITE_TOKEN", "DIAGNOSTICS_READ_TOKEN", "CRON_SECRET", "DIAGNOSTICS_IP_SALT"]) {
      delete process.env[key];
    }
    try {
      assert.throws(() => loadConfig(), (error: unknown) => error instanceof ConfigError);
    } finally {
      process.env = saved;
    }
  });

  it("trusts only the platform header, never arbitrary x-forwarded-for", async () => {
    const spoofed = requestWith({
      "x-forwarded-for": "1.2.3.4",
      "x-vercel-forwarded-for": "203.0.113.7",
    });
    assert.equal(trustedClientIp(spoofed), "203.0.113.7");
    const missing = requestWith({ "x-forwarded-for": "1.2.3.4" });
    assert.equal(trustedClientIp(missing), null);
    const malformed = requestWith({ "x-vercel-forwarded-for": "no\"to;trust" });
    assert.equal(trustedClientIp(malformed), null);
  });
});
