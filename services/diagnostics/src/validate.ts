// Validación del JSON de creación (schema1 del contrato).
// Rechaza campos desconocidos, tipos y tamaños inválidos. Solo datos,
// nunca URLs ni rutas aportadas por el cliente.
import { isUuid } from "./crypto.js";

export const ATTACHMENT_NAMES = ["diagnostics", "stdout", "stderr", "wine", "wow", "launcher"] as const;
export type AttachmentName = (typeof ATTACHMENT_NAMES)[number];

export const PLAINTEXT_UTF8 = "text/plain; charset=utf-8";
export const MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
export const MAX_TOTAL_ATTACHMENT_BYTES = 24 * 1024 * 1024;
export const MAX_ATTACHMENTS = 6;
export const MAX_COMMENT_CHARS = 4000;
export const MAX_EVENTS = 200;
export const MAX_SOURCES = 32;
export const MAX_TECH_STRING = 512;

const ROOT_KEYS = ["schema", "installationId", "capturedAt", "comment", "config", "attempt", "sources", "events", "attachments"] as const;

const DEVICE_KEYS = ["manufacturer", "model", "soc", "gpu", "androidRelease", "androidApi", "kernel", "driver"] as const;
const APP_KEYS = ["packageName", "versionName", "versionCode", "buildRevision"] as const;
const CLIENT_KEYS = ["build", "architecture", "locale", "status"] as const;
const PROFILE_KEYS = ["profileId", "profileRevision", "gpuBackend", "resolution", "wine", "dxvk", "panvk", "graphics"] as const;
const CONFIG_KEYS = ["device", "app", "client", "requested", "observed"] as const;
const ATTEMPT_KEYS = ["id", "runId", "status", "stage", "startedAt", "endedAt", "result", "exitCode", "terminationUnknown"] as const;
const SOURCE_KEYS = ["name", "status", "reason", "truncated", "bytes"] as const;
const EVENT_KEYS = ["at", "action", "outcome"] as const;
const ATTACHMENT_KEYS = ["name", "bytes", "sha256", "contentType"] as const;

const EVENT_ACTIONS = new Set([
  "screen", "launch", "pause", "resume", "profile-change", "resolution-change",
  "gpu-change", "cancel", "retry", "error", "exception", "wine-start",
  "wine-exit", "wow-start", "wow-exit", "timeout", "precondition-failed",
]);
const EVENT_OUTCOMES = new Set(["ok", "failed", "cancelled", "unknown", "pending"]);

export class ValidationError extends Error {
  readonly status = 400;
  readonly code = "invalid-body";
  constructor(message: string) {
    super(message);
  }
}

export interface AttachmentManifest {
  name: AttachmentName;
  bytes: number;
  sha256: string;
  contentType: typeof PLAINTEXT_UTF8;
}

export interface ValidReport {
  installationId: string;
  capturedAt: string;
  comment: string;
  config: Record<string, unknown>;
  attempt: Record<string, unknown> | null;
  sources: Array<Record<string, unknown>>;
  events: Array<Record<string, unknown>>;
  attachments: AttachmentManifest[];
  /** Hash canónico del snapshot (metadata+manifiesto) para idempotencia. */
  snapshotHashInput: string;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rejectUnknown(obj: Record<string, unknown>, allowed: readonly string[], where: string): void {
  for (const key of Object.keys(obj)) {
    if (!(allowed as readonly string[]).includes(key)) {
      throw new ValidationError(`unknown field ${where}.${key}`);
    }
  }
}

function techValue(value: unknown, where: string): string | number | boolean | null {
  if (value === null) return null;
  if (typeof value === "number" || typeof value === "boolean") {
    if (typeof value === "number" && (!Number.isFinite(value) || !Number.isSafeInteger(value))) {
      throw new ValidationError(`invalid number at ${where}`);
    }
    return value;
  }
  if (typeof value === "string") {
    if (value.length > MAX_TECH_STRING) throw new ValidationError(`too long at ${where}`);
    return value;
  }
  throw new ValidationError(`invalid type at ${where}`);
}

function techObject(value: unknown, allowed: readonly string[], where: string): Record<string, unknown> {
  if (!isPlainObject(value)) throw new ValidationError(`invalid object at ${where}`);
  rejectUnknown(value, allowed, where);
  const out: Record<string, unknown> = {};
  for (const key of allowed) {
    if (!(key in value)) {
      out[key] = null;
      continue;
    }
    out[key] = techValue(value[key], `${where}.${key}`);
  }
  return out;
}

function isoDate(value: unknown, where: string): string {
  if (typeof value !== "string") throw new ValidationError(`invalid date at ${where}`);
  const time = Date.parse(value);
  if (Number.isNaN(time)) throw new ValidationError(`invalid date at ${where}`);
  return new Date(time).toISOString();
}

export function validateReportBody(body: unknown): ValidReport {
  if (!isPlainObject(body)) throw new ValidationError("body must be an object");
  rejectUnknown(body, ROOT_KEYS, "root");
  if (body["schema"] !== 1) throw new ValidationError("unsupported schema");

  const installationId = body["installationId"];
  if (!isUuid(installationId)) throw new ValidationError("invalid installationId");
  const capturedAt = isoDate(body["capturedAt"], "capturedAt");

  const comment = body["comment"];
  if (typeof comment !== "string") throw new ValidationError("invalid comment");
  if (comment.length > MAX_COMMENT_CHARS) throw new ValidationError("comment too long");

  const configRaw = body["config"];
  if (!isPlainObject(configRaw)) throw new ValidationError("invalid config");
  rejectUnknown(configRaw, CONFIG_KEYS, "config");
  const config: Record<string, unknown> = {
    device: techObject(configRaw["device"] ?? {}, DEVICE_KEYS, "config.device"),
    app: techObject(configRaw["app"] ?? {}, APP_KEYS, "config.app"),
    client: techObject(configRaw["client"] ?? {}, CLIENT_KEYS, "config.client"),
    requested: techObject(configRaw["requested"] ?? {}, PROFILE_KEYS, "config.requested"),
    observed: techObject(configRaw["observed"] ?? {}, PROFILE_KEYS, "config.observed"),
  };

  let attempt: Record<string, unknown> | null = null;
  if (body["attempt"] !== null && body["attempt"] !== undefined) {
    if (!isPlainObject(body["attempt"])) throw new ValidationError("invalid attempt");
    rejectUnknown(body["attempt"], ATTEMPT_KEYS, "attempt");
    attempt = {};
    for (const key of ATTEMPT_KEYS) {
      const value = body["attempt"][key];
      if (value === undefined || value === null) {
        attempt[key] = null;
        continue;
      }
      if (key === "exitCode") {
        if (typeof value !== "number" || !Number.isSafeInteger(value)) {
          throw new ValidationError("invalid attempt.exitCode");
        }
        attempt[key] = value;
        continue;
      }
      if (key === "terminationUnknown") {
        if (typeof value !== "boolean") throw new ValidationError("invalid attempt.terminationUnknown");
        attempt[key] = value;
        continue;
      }
      if (key === "startedAt" || key === "endedAt") {
        attempt[key] = isoDate(value, `attempt.${key}`);
        continue;
      }
      attempt[key] = techValue(value, `attempt.${key}`);
    }
  }

  const sourcesRaw = body["sources"] ?? [];
  if (!Array.isArray(sourcesRaw) || sourcesRaw.length > MAX_SOURCES) {
    throw new ValidationError("invalid sources");
  }
  const sources = sourcesRaw.map((entry, index) => {
    if (!isPlainObject(entry)) throw new ValidationError(`invalid sources[${index}]`);
    rejectUnknown(entry, SOURCE_KEYS, `sources[${index}]`);
    return {
      name: techValue(entry["name"], `sources[${index}].name`),
      status: techValue(entry["status"], `sources[${index}].status`),
      reason: techValue(entry["reason"], `sources[${index}].reason`),
      truncated: typeof entry["truncated"] === "boolean" ? entry["truncated"] : null,
      bytes: typeof entry["bytes"] === "number" && Number.isSafeInteger(entry["bytes"]) && (entry["bytes"] as number) >= 0
        ? entry["bytes"]
        : null,
    };
  });

  const eventsRaw = body["events"] ?? [];
  if (!Array.isArray(eventsRaw) || eventsRaw.length > MAX_EVENTS) {
    throw new ValidationError("invalid events");
  }
  const events = eventsRaw.map((entry, index) => {
    if (!isPlainObject(entry)) throw new ValidationError(`invalid events[${index}]`);
    rejectUnknown(entry, EVENT_KEYS, `events[${index}]`);
    const at = isoDate(entry["at"], `events[${index}].at`);
    if (typeof entry["action"] !== "string" || !EVENT_ACTIONS.has(entry["action"])) {
      throw new ValidationError(`invalid events[${index}].action`);
    }
    if (typeof entry["outcome"] !== "string" || !EVENT_OUTCOMES.has(entry["outcome"])) {
      throw new ValidationError(`invalid events[${index}].outcome`);
    }
    return { at, action: entry["action"], outcome: entry["outcome"] };
  });

  const attachmentsRaw = body["attachments"] ?? [];
  if (!Array.isArray(attachmentsRaw) || attachmentsRaw.length > MAX_ATTACHMENTS) {
    throw new ValidationError("invalid attachments");
  }
  const seen = new Set<string>();
  let totalBytes = 0;
  const attachments = attachmentsRaw.map((entry, index) => {
    if (!isPlainObject(entry)) throw new ValidationError(`invalid attachments[${index}]`);
    rejectUnknown(entry, ATTACHMENT_KEYS, `attachments[${index}]`);
    const name = entry["name"];
    if (typeof name !== "string" || !(ATTACHMENT_NAMES as readonly string[]).includes(name)) {
      throw new ValidationError(`invalid attachments[${index}].name`);
    }
    if (seen.has(name)) throw new ValidationError(`duplicate attachments[${index}].name`);
    seen.add(name);
    const bytes = entry["bytes"];
    if (typeof bytes !== "number" || !Number.isSafeInteger(bytes) || bytes < 0 || bytes > MAX_ATTACHMENT_BYTES) {
      throw new ValidationError(`invalid attachments[${index}].bytes`);
    }
    const sha256 = entry["sha256"];
    if (typeof sha256 !== "string" || !/^[0-9a-f]{64}$/.test(sha256)) {
      throw new ValidationError(`invalid attachments[${index}].sha256`);
    }
    if (entry["contentType"] !== PLAINTEXT_UTF8) {
      throw new ValidationError(`invalid attachments[${index}].contentType`);
    }
    totalBytes += bytes;
    return { name: name as AttachmentName, bytes, sha256, contentType: PLAINTEXT_UTF8 as typeof PLAINTEXT_UTF8 };
  });
  if (totalBytes > MAX_TOTAL_ATTACHMENT_BYTES) throw new ValidationError("attachments exceed total size");

  const snapshotHashInput = JSON.stringify({
    installationId, capturedAt, comment, config, attempt, sources, events, attachments,
  });

  return { installationId, capturedAt, comment, config, attempt, sources, events, attachments, snapshotHashInput };
}
