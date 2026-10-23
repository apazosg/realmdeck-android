// Primitivas criptográficas. Comparaciones siempre en tiempo constante;
// los hashes de capacidad e IP nunca salen del servidor.
import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export function sha256Hex(data: string | Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

/** Compara dos cadenas en tiempo constante. Longitud distinta = falso. */
export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a, "utf8");
  const bb = Buffer.from(b, "utf8");
  if (ab.length !== bb.length) return false;
  return timingSafeEqual(ab, bb);
}

const B64URL_32 = /^[A-Za-z0-9_-]{43}$/;

/**
 * Valida `Authorization: Bearer <base64url de 32 bytes>` y devuelve el hash
 * SHA256 en hex que se persiste. Null si el formato es inválido.
 */
export function parseWriteCapability(authHeader: string | null): string | null {
  if (authHeader === null) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authHeader.trim());
  if (match === null || !B64URL_32.test(match[1] as string)) return null;
  let raw: Buffer;
  try {
    raw = Buffer.from(match[1] as string, "base64url");
  } catch {
    return null;
  }
  if (raw.length !== 32) return null;
  return sha256Hex(raw);
}

/** Seudonimiza la IP con HMAC-SHA256 y salt del servidor. Nunca IP cruda. */
export function hmacIp(ip: string, salt: string): string {
  return createHmac("sha256", salt).update(ip, "utf8").digest("hex");
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_RE.test(value);
}
