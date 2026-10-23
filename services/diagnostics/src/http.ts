// Adaptadores HTTP mínimos para Vercel Functions (sin Next.js).
// Sin tipos de @vercel/node: interfaces estructurales compatibles.
// Este fichero vive en src/ a propósito: nada bajo api/ debe convertirse
// en función accidental; solo los ficheros con default export en api/ son rutas.
import { ConfigError, type Config, loadConfig } from "./config.js";
import { createPool, PostgresStore } from "./store-postgres.js";
import { VercelBlobIssuer, type BlobIssuer } from "./blob.js";
import { type Deps, HttpError, noStoreHeaders } from "./handlers.js";

export interface ApiRequest {
  method?: string;
  headers: Record<string, string | string[] | undefined>;
  query: Record<string, string | string[] | undefined>;
  body?: unknown;
}

export interface ApiResponse {
  status(code: number): ApiResponse;
  setHeader(name: string, value: string): void;
  json(data: unknown): void;
}

/** Cabecera de IP fijada a la plataforma; no configurable por entorno. */
export const TRUSTED_IP_HEADER = "x-vercel-forwarded-for";

export function header(req: ApiRequest, name: string): string | null {
  const value = req.headers[name.toLowerCase()];
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return typeof value[0] === "string" ? (value[0] as string) : null;
  return null;
}

export function bodyText(req: ApiRequest, maxBytes: number): string {
  const text = typeof req.body === "string" ? req.body : JSON.stringify(req.body ?? null);
  if (Buffer.byteLength(text, "utf8") > maxBytes) {
    throw new HttpError(413, "metadata-too-large");
  }
  return text;
}

/**
 * Extrae la IP SOLO de la cabecera Vercel confiable. Nunca usa
 * x-forwarded-for arbitrario; ausente = null (fallo cerrado aguas arriba).
 */
export function trustedClientIp(req: ApiRequest): string | null {
  const value = header(req, TRUSTED_IP_HEADER);
  if (value === null) return null;
  const first = value.split(",")[0]?.trim() ?? "";
  if (first === "" || /[\s"';\\]/.test(first) || first.length > 64) return null;
  return first;
}

let cached: Deps | null = null;

export function deps(): Deps {
  if (cached !== null) return cached;
  const config = loadConfig();
  const store = new PostgresStore(createPool(config.databaseUrl));
  const blob: BlobIssuer = new VercelBlobIssuer(config.blobReadWriteToken);
  cached = { config, store, blob, nowMs: () => Date.now() };
  return cached;
}

/** Envía JSON con no-store y errores saneados (sin tokens/URLs/SQL). */
export function send(res: ApiResponse, status: number, body: Record<string, unknown>): void {
  for (const [name, value] of Object.entries(noStoreHeaders())) {
    res.setHeader(name, value);
  }
  res.status(status).json(body);
}

export function sendError(res: ApiResponse, error: unknown): void {
  if (error instanceof HttpError) {
    send(res, error.status, { error: error.code });
    return;
  }
  if (error instanceof ConfigError) {
    send(res, error.status, { error: error.code });
    return;
  }
  send(res, 500, { error: "internal" });
}

export function methodNotAllowed(res: ApiResponse): void {
  send(res, 405, { error: "method-not-allowed" });
}
