// Ayudas compartidas de los tests (sin framework adicional).
import { randomBytes, randomUUID } from "node:crypto";
import type { Config } from "../src/config.js";
import { sha256Hex } from "../src/crypto.js";
import { FakeBlobIssuer } from "../src/blob.js";
import { MemoryStore } from "../src/store.js";
import type { Deps } from "../src/handlers.js";

export function testConfig(overrides: Partial<Config> = {}): Config {
  return {
    databaseUrl: "postgres://test/test",
    blobReadWriteToken: "test-blob-token",
    readToken: "test-read-token",
    cronSecret: "test-cron-secret",
    ipSalt: "test-salt",
    retentionDays: 7,
    pendingTtlSeconds: 3600,
    uploadUrlTtlSeconds: 600,
    readUrlTtlSeconds: 60,
    maxReports: 100,
    maxReservedBytes: 512 * 1024 * 1024,
    metaMaxBytes: 256 * 1024,
    maxUploadIssues: 3,
    ...overrides,
  };
}

export interface TestContext extends Deps {
  blob: FakeBlobIssuer;
  store: MemoryStore;
  now: { ms: number };
}

export function testDeps(overrides: Partial<Config> = {}): TestContext {
  const now = { ms: 1_800_000_000_000 };
  const blob = new FakeBlobIssuer();
  return {
    config: testConfig(overrides),
    store: new MemoryStore(),
    blob,
    nowMs: () => now.ms,
    now,
  };
}

/** Capacidad de escritura fresca: 32 bytes aleatorios en base64url. */
export function freshCapability(): { header: string } {
  const header = `Bearer ${randomBytes(32).toString("base64url")}`;
  return { header };
}

export function validBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    schema: 1,
    installationId: randomUUID(),
    capturedAt: new Date(1_800_000_000_000).toISOString(),
    comment: "No arranca Wine en este perfil.",
    config: {
      device: { manufacturer: "Xiaomi", model: "xaga", soc: "Dimensity 1200", gpu: "Mali-G610", androidRelease: "14", androidApi: 34, kernel: "5.10", driver: "PanVK v25" },
      app: { packageName: "com.example.wow", versionName: "1.2.3", versionCode: 7, buildRevision: "c116" },
      client: { build: "1.60.1", architecture: "x86_64", locale: "esES", status: "installed" },
      requested: { profileId: "balanced", profileRevision: 3, gpuBackend: "panvk", resolution: "1280x720", wine: "9.0", dxvk: "2.3", panvk: "v25", graphics: "vulkan" },
      observed: { profileId: "balanced", profileRevision: 3, gpuBackend: null, resolution: null, wine: null, dxvk: null, panvk: null, graphics: null },
    },
    attempt: {
      id: randomUUID(), runId: "run-1", status: "failed", stage: "wine-start",
      startedAt: new Date(1_799_999_990_000).toISOString(), endedAt: new Date(1_800_000_000_000).toISOString(),
      result: "wine-start-failed", exitCode: null, terminationUnknown: false,
    },
    sources: [{ name: "wine-log", status: "present", reason: null, truncated: true, bytes: 11 }],
    events: [{ at: new Date(1_799_999_995_000).toISOString(), action: "launch", outcome: "ok" }],
    attachments: [],
    ...overrides,
  };
}

export function sha256HexText(text: string): string {
  return sha256Hex(text);
}
