// Configuración solo por entorno. Ausencia de cualquier secreto = fallo cerrado.
// Nunca se registra ni se devuelve ningún valor secreto.
export interface Config {
  databaseUrl: string;
  blobReadWriteToken: string;
  readToken: string;
  cronSecret: string;
  ipSalt: string;
  retentionDays: number;
  pendingTtlSeconds: number;
  uploadUrlTtlSeconds: number;
  readUrlTtlSeconds: number;
  maxReports: number;
  maxReservedBytes: number;
  metaMaxBytes: number;
  maxUploadIssues: number;
}

export class ConfigError extends Error {
  readonly status = 500;
  readonly code = "config-unavailable";
  constructor() {
    super("service unavailable");
  }
}

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === "") throw new ConfigError();
  return value;
}

function optionalInt(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new ConfigError();
  return parsed;
}

export function loadConfig(): Config {
  return {
    databaseUrl: requiredEnv("DATABASE_URL"),
    blobReadWriteToken: requiredEnv("BLOB_READ_WRITE_TOKEN"),
    readToken: requiredEnv("DIAGNOSTICS_READ_TOKEN"),
    cronSecret: requiredEnv("CRON_SECRET"),
    ipSalt: requiredEnv("DIAGNOSTICS_IP_SALT"),
    retentionDays: optionalInt("RETENTION_DAYS", 7),
    pendingTtlSeconds: optionalInt("PENDING_TTL_SECONDS", 3600),
    uploadUrlTtlSeconds: optionalInt("UPLOAD_URL_TTL_SECONDS", 600),
    readUrlTtlSeconds: optionalInt("READ_URL_TTL_SECONDS", 60),
    maxReports: optionalInt("MAX_REPORTS", 100),
    maxReservedBytes: optionalInt("MAX_RESERVED_BYTES", 512 * 1024 * 1024),
    metaMaxBytes: optionalInt("META_MAX_BYTES", 256 * 1024),
    maxUploadIssues: optionalInt("MAX_UPLOAD_ISSUES", 3),
  };
}
