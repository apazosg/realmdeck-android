// Lógica de rutas sin framework: testeable sin red.
// Reglas transversales: Cache-Control no-store, errores sin secretos,
// la capacidad de escritura jamás autoriza lectura, y ningún URL de
// subida se emite antes de que la reserva de cuota confirme.
import { randomUUID } from "node:crypto";
import { TextDecoder } from "node:util";
import { type BlobIssuer, blobPathname, BlobMissingError, parseDerivedPathname } from "./blob.js";
import { type Config } from "./config.js";
import { hmacIp, isUuid, parseWriteCapability, safeEqual, sha256Hex } from "./crypto.js";
import { sanitizeJsonStrings, sanitizeReportTexts } from "./sanitize.js";
import {
  decodeCursor,
  type ListFilters,
  type ReportRow,
  type ReserveQuotas,
  type Store,
} from "./store.js";
import { validateReportBody, MAX_ATTACHMENT_BYTES, ValidationError } from "./validate.js";

export interface Deps {
  config: Config;
  store: Store;
  blob: BlobIssuer;
  nowMs: () => number;
}

export interface HandlerResponse {
  status: number;
  body: Record<string, unknown>;
}

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

/** Intentos caros de complete por informe (verificación con descarga). */
export const MAX_COMPLETE_ATTEMPTS = 3;

/** Barrido de huérfanos: página y tope fijos, siempre acotado. */
export const ORPHAN_LIST_LIMIT = 100;
export const ORPHAN_MAX_PAGES = 7;

export function noStoreHeaders(): Record<string, string> {
  return { "Cache-Control": "no-store" };
}

function dayStartMs(nowMs: number): number {
  const date = new Date(nowMs);
  date.setUTCHours(0, 0, 0, 0);
  return date.getTime();
}

function quotasFor(config: Config, nowMs: number): ReserveQuotas {
  return {
    perInstallationPerDay: 3,
    perIpPerDay: 5,
    globalPerDay: 5,
    maxReports: config.maxReports,
    maxReservedBytes: config.maxReservedBytes,
    dayStartMs: dayStartMs(nowMs),
  };
}

function requireReadAuth(config: Config, authHeader: string | null): void {
  if (authHeader === null || !safeEqual(authHeader.trim(), `Bearer ${config.readToken}`)) {
    throw new HttpError(401, "unauthorized");
  }
}

function requireCleanupAuth(config: Config, authHeader: string | null): void {
  if (authHeader === null || !safeEqual(authHeader.trim(), `Bearer ${config.cronSecret}`)) {
    throw new HttpError(401, "unauthorized");
  }
}

export interface CreateRequest {
  authHeader: string | null;
  idempotencyKey: string | null;
  bodyText: string;
  /** IP ya extraída SOLO de la cabecera Vercel confiable; null si ausente. */
  clientIp: string | null;
}

/** POST /api/reports — ingestión anónima con reserva atómica previa. */
export async function handleCreateReport(deps: Deps, req: CreateRequest): Promise<HandlerResponse> {
  const { config, store, blob } = deps;
  const now = deps.nowMs();

  const capHash = parseWriteCapability(req.authHeader);
  if (capHash === null) throw new HttpError(401, "unauthorized");
  if (req.idempotencyKey === null || !isUuid(req.idempotencyKey)) {
    throw new HttpError(400, "invalid-idempotency-key");
  }
  // Sin IP confiable no se puede aplicar la cuota por IP: fallo cerrado.
  if (req.clientIp === null) throw new HttpError(400, "ip-unavailable");
  if (Buffer.byteLength(req.bodyText, "utf8") > config.metaMaxBytes) {
    throw new HttpError(413, "metadata-too-large");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(req.bodyText) as unknown;
  } catch {
    throw new HttpError(400, "invalid-body");
  }
  let valid;
  try {
    valid = validateReportBody(parsed);
  } catch (error) {
    if (error instanceof ValidationError) throw new HttpError(400, error.code);
    throw error;
  }
  // El hash de idempotencia se calcula sobre el original canónico: reintentos
  // del cliente con el mismo contenido reemiten el mismo informe aunque el
  // servidor persista la versión saneada.
  const snapshotHash = sha256Hex(valid.snapshotHashInput);

  // Saneado de TODAS las cadenas allowlistadas antes de persistir.
  const sanitized = sanitizeJsonStrings({
    schema: 1,
    installationId: valid.installationId,
    capturedAt: valid.capturedAt,
    comment: valid.comment,
    config: valid.config,
    attempt: valid.attempt,
    sources: valid.sources,
    events: valid.events,
  });
  const metadataJson = JSON.stringify(sanitized.value);
  if (Buffer.byteLength(metadataJson, "utf8") > config.metaMaxBytes) {
    throw new HttpError(413, "metadata-too-large");
  }
  const manifestJson = JSON.stringify({ attachments: valid.attachments });
  const reportId = randomUUID();
  const totalBytes = valid.attachments.reduce((sum, a) => sum + a.bytes, 0);

  const reserved = await store.reserve(
    {
      id: reportId,
      idempotencyKey: req.idempotencyKey,
      installationId: valid.installationId,
      capHash,
      snapshotHash,
      metadataJson,
      manifestJson,
      ipHmac: hmacIp(req.clientIp, config.ipSalt),
      nowMs: now,
      expiresAtMs: now + config.pendingTtlSeconds * 1000,
      totalBytes,
      redactions: sanitized.redactions,
      attachments: valid.attachments.map((a) => ({
        name: a.name,
        bytes: a.bytes,
        sha256: a.sha256,
        contentType: a.contentType,
        blobPathname: blobPathname(reportId, a.name),
        verified: false,
      })),
    },
    quotasFor(config, now),
  );

  if (reserved.outcome === "conflict") throw new HttpError(409, "conflict");
  if (reserved.outcome === "quota") throw new HttpError(429, "quota-exceeded");
  const report = reserved.report;
  if (reserved.outcome === "replayed" && report.state === "complete") {
    // Sin reemisión tras completar: solo estado.
    return { status: 200, body: { id: report.id, state: "complete" } };
  }
  if (report.expiresAtMs <= now) throw new HttpError(410, "expired");

  // Reemisiones acotadas: el contador solo crece, nunca URLs ilimitadas.
  const issues = await store.incrementUploadIssues(report.id);
  if (issues > config.maxUploadIssues) throw new HttpError(429, "upload-limit");

  const uploadValidUntil = Math.min(now + config.uploadUrlTtlSeconds * 1000, report.expiresAtMs);
  const attachments = await store.getAttachments(report.id);
  const uploads = [];
  for (const attachment of attachments) {
    const url = await blob.issueUploadUrl(
      attachment.blobPathname, attachment.contentType, attachment.bytes, uploadValidUntil,
    );
    uploads.push({ name: attachment.name, url, contentType: attachment.contentType });
  }
  return {
    status: reserved.outcome === "replayed" ? 200 : 201,
    body: {
      id: report.id,
      state: report.state,
      expiresAt: new Date(report.expiresAtMs).toISOString(),
      uploads,
    },
  };
}

export interface CompleteRequest {
  reportId: string | null;
  authHeader: string | null;
}

/**
 * POST /api/reports/{id}/complete — verifica los objetos EXACTOS derivados
 * por el servidor (tamaño, tipo, SHA256, UTF-8). Si algún texto requeriría
 * redacción, RECHAZA con 422 y el informe sigue pendiente: el blob original
 * inmutable expira con el cleanup, sin segunda copia ni overwrite, y nunca
 * hay URL de lectura para un blob inseguro. Nunca acepta URLs del cliente.
 * Idempotente una vez completo; intentos caros acotados por informe.
 */
export async function handleCompleteReport(deps: Deps, req: CompleteRequest): Promise<HandlerResponse> {
  const { store, blob } = deps;
  const now = deps.nowMs();
  const capHash = parseWriteCapability(req.authHeader);
  if (capHash === null || req.reportId === null || !isUuid(req.reportId)) {
    // 404 genérico: no distingue id inexistente de capacidad incorrecta.
    throw new HttpError(404, "not-found");
  }
  const report = await store.getById(req.reportId);
  if (report === null || !safeEqual(report.capHash, capHash)) {
    throw new HttpError(404, "not-found");
  }
  if (report.state === "complete") {
    return {
      status: 200,
      body: { id: report.id, state: "complete", completedAt: isoOf(report.completedAtMs) },
    };
  }
  // Intento caro acotado y atómico (incluye concurrentes); la guarda exige
  // pendiente y sin expirar.
  const claim = await store.claimCompleteAttempt(report.id, now);
  if (claim === null) return mapGone(deps, report.id, capHash);
  if (claim.attempts > MAX_COMPLETE_ATTEMPTS) throw new HttpError(429, "complete-attempts");

  const attachments = await store.getAttachments(report.id);
  const bodies = new Map<string, string>();
  for (const attachment of attachments) {
    let fetched;
    try {
      fetched = await blob.fetchBytes(attachment.blobPathname, MAX_ATTACHMENT_BYTES);
    } catch (error) {
      if (error instanceof BlobMissingError) throw new HttpError(422, "attachment-missing");
      throw error;
    }
    if (fetched.bytes.length !== attachment.bytes) throw new HttpError(422, "attachment-size");
    if (fetched.contentType !== null && fetched.contentType.split(";")[0]?.trim() !== "text/plain") {
      throw new HttpError(422, "attachment-type");
    }
    if (sha256Hex(fetched.bytes) !== attachment.sha256) throw new HttpError(422, "attachment-hash");
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(fetched.bytes);
    } catch {
      throw new HttpError(422, "attachment-encoding");
    }
    bodies.set(attachment.name, text);
  }
  const { redactionCount } = sanitizeReportTexts("", bodies);
  if (redactionCount > 0) {
    // Fail-closed: el blob sensible queda pendiente, sin verified/complete
    // ni URL de lectura; el cleanup lo borra al expirar la sesión.
    throw new HttpError(422, "attachment-needs-sanitization");
  }
  const completed = await store.markComplete(report.id, 0, now);
  if (completed === null) return mapGone(deps, report.id, capHash);
  return {
    status: 200,
    body: { id: completed.id, state: "complete", completedAt: isoOf(completed.completedAtMs) },
  };
}

/** Mapea una carrera perdida/expiración a 404/410 seguros, nunca 500. */
async function mapGone(deps: Deps, reportId: string, capHash: string): Promise<HandlerResponse> {
  const fresh = await deps.store.getById(reportId);
  if (fresh === null || !safeEqual(fresh.capHash, capHash)) {
    throw new HttpError(404, "not-found");
  }
  if (fresh.state === "complete") {
    return {
      status: 200,
      body: { id: fresh.id, state: "complete", completedAt: isoOf(fresh.completedAtMs) },
    };
  }
  throw new HttpError(410, "expired");
}

function isoOf(value: number | null): string | null {
  return value === null ? null : new Date(value).toISOString();
}

export interface ListRequest {
  authHeader: string | null;
  limitRaw: string | string[] | null;
  cursorRaw: string | string[] | null;
  filtersRaw: Record<string, string | string[] | null | undefined>;
}

/** GET /api/reports — solo mantenedor con token de lectura. */
export async function handleListReports(deps: Deps, req: ListRequest): Promise<HandlerResponse> {
  requireReadAuth(deps.config, req.authHeader);
  const limit = parseLimit(req.limitRaw);
  const cursor = parseCursor(req.cursorRaw);
  const filters = parseFilters(req.filtersRaw);
  const { items, nextCursor } = await deps.store.list(limit, cursor, filters);
  return {
    status: 200,
    body: {
      items: items.map((r) => summarize(r)),
      nextCursor,
    },
  };
}

function first(value: string | string[] | null | undefined): string | null {
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return typeof value[0] === "string" ? (value[0] as string) : null;
  return null;
}

function parseLimit(raw: string | string[] | null): number {
  if (raw === null || raw === undefined) return 20;
  const text = first(raw);
  if (text === null || !/^[0-9]{1,3}$/.test(text)) throw new HttpError(400, "invalid-limit");
  const limit = Number.parseInt(text, 10);
  if (limit < 1 || limit > 50) throw new HttpError(400, "invalid-limit");
  return limit;
}

function parseCursor(raw: string | string[] | null): string | null {
  const text = first(raw ?? null);
  if (text === null || text === "") return null;
  if (text.length > 512 || decodeCursor(text) === null) throw new HttpError(400, "invalid-cursor");
  return text;
}

function parseFilters(raw: Record<string, string | string[] | null | undefined>): ListFilters {
  const filters: ListFilters = {};
  const device = first(raw["device"] ?? null);
  const gpu = first(raw["gpu"] ?? null);
  const appVersion = first(raw["appVersion"] ?? null);
  const result = first(raw["result"] ?? null);
  for (const value of [device, gpu, appVersion, result]) {
    if (value !== null && value.length > 128) throw new HttpError(400, "invalid-filter");
  }
  if (device !== null) filters.device = device;
  if (gpu !== null) filters.gpu = gpu;
  if (appVersion !== null) filters.appVersion = appVersion;
  if (result !== null) filters.result = result;
  const since = first(raw["since"] ?? null);
  const until = first(raw["until"] ?? null);
  if (since !== null) {
    const time = Date.parse(since);
    if (Number.isNaN(time)) throw new HttpError(400, "invalid-filter");
    filters.sinceMs = time;
  }
  if (until !== null) {
    const time = Date.parse(until);
    if (Number.isNaN(time)) throw new HttpError(400, "invalid-filter");
    filters.untilMs = time;
  }
  return filters;
}

function summarize(report: ReportRow): Record<string, unknown> {
  let model: unknown = null;
  let gpu: unknown = null;
  let appVersion: unknown = null;
  let result: unknown = null;
  try {
    const metadata = JSON.parse(report.metadataJson) as Record<string, unknown>;
    const config = (metadata["config"] ?? {}) as Record<string, unknown>;
    const device = (config["device"] ?? {}) as Record<string, unknown>;
    const app = (config["app"] ?? {}) as Record<string, unknown>;
    const attempt = (metadata["attempt"] ?? {}) as Record<string, unknown>;
    model = device["model"] ?? null;
    gpu = device["gpu"] ?? null;
    appVersion = app["versionName"] ?? null;
    result = attempt["result"] ?? null;
  } catch { /* resumen degradado, nunca falla */ }
  return {
    id: report.id,
    state: report.state,
    createdAt: isoOf(report.createdAtMs),
    completedAt: isoOf(report.completedAtMs),
    model, gpu, appVersion, result,
    totalBytes: report.totalBytes,
  };
}

export interface DetailRequest {
  authHeader: string | null;
  reportId: string | null;
}

/** GET /api/reports/{id} — detalle sin bytes de adjuntos. */
export async function handleGetReport(deps: Deps, req: DetailRequest): Promise<HandlerResponse> {
  requireReadAuth(deps.config, req.authHeader);
  if (req.reportId === null || !isUuid(req.reportId)) throw new HttpError(404, "not-found");
  const report = await deps.store.getById(req.reportId);
  if (report === null) throw new HttpError(404, "not-found");
  const attachments = await deps.store.getAttachments(report.id);
  let metadata: unknown = null;
  try {
    metadata = JSON.parse(report.metadataJson) as unknown;
  } catch { /* nunca rompe el detalle */ }
  return {
    status: 200,
    body: {
      id: report.id,
      state: report.state,
      createdAt: isoOf(report.createdAtMs),
      completedAt: isoOf(report.completedAtMs),
      expiresAt: isoOf(report.expiresAtMs),
      metadata,
      attachments: attachments.map((a) => ({
        name: a.name, bytes: a.bytes, sha256: a.sha256,
        contentType: a.contentType, verified: a.verified,
      })),
      redactionCount: report.redactionCount,
    },
  };
}

export interface AttachmentRequest {
  authHeader: string | null;
  reportId: string | null;
  name: string | null;
}

/**
 * GET /api/reports/{id}/attachments/{name} — emite un GET firmado de 60s.
 * Solo para informes completos: un blob pendiente o rechazado por saneado
 * jamás obtiene URL de lectura. El script descarga sin imprimir ni persistir
 * la URL firmada.
 */
export async function handleGetAttachment(deps: Deps, req: AttachmentRequest): Promise<HandlerResponse> {
  requireReadAuth(deps.config, req.authHeader);
  if (req.reportId === null || !isUuid(req.reportId)) throw new HttpError(404, "not-found");
  const report = await deps.store.getById(req.reportId);
  if (report === null || report.state !== "complete") throw new HttpError(404, "not-found");
  const attachments = await deps.store.getAttachments(report.id);
  const attachment = attachments.find((a) => a.name === req.name);
  if (attachment === undefined) throw new HttpError(404, "not-found");
  const now = deps.nowMs();
  const url = await deps.blob.issueReadUrl(
    attachment.blobPathname, now + deps.config.readUrlTtlSeconds * 1000,
  );
  return {
    status: 200,
    body: { url, expiresAt: new Date(now + deps.config.readUrlTtlSeconds * 1000).toISOString() },
  };
}

export interface CleanupRequest {
  authHeader: string | null;
}

/**
 * POST/GET /api/cleanup → borrado autenticado (cron diario + manual).
 * Cada informe se reclama bajo coordinación: si entre el listado y el
 * borrado un complete concurrente lo salvó, se omite sin tocar nada.
 * Los blobs se borran primero y la fila (y su cuota) solo tras confirmar.
 * PUTs en vuelo: las URLs de subida caducan con el propio informe, así que
 * un cliente conforme termina antes de expires_at; un PUT aceptado por la
 * CDN justo al expirar puede confirmarse tarde y por eso los pendientes
 * solo se borran tras una hora de gracia (PENDING_CLEANUP_GRACE_MS).
 * Tras los informes, un barrido acotado elimina blobs huérfanos (sin fila)
 * más viejos que la retención: el proveedor no ofrece garantía que evite
 * crearlos (las firmas solo acotan objeto/MIME/tamaño/expiración), así que
 * se reconcilian con el listado oficial. Idempotente.
 */
export async function handleCleanup(deps: Deps, req: CleanupRequest): Promise<HandlerResponse> {
  requireCleanupAuth(deps.config, req.authHeader);
  const now = deps.nowMs();
  const retentionCutoff = now - deps.config.retentionDays * 24 * 60 * 60 * 1000;
  const expired = await deps.store.findExpired(now, retentionCutoff);
  let deleted = 0;
  let failed = 0;
  for (const reportId of expired) {
    try {
      const result = await deps.store.removeExpiredReport(
        reportId, now, retentionCutoff, (pathname) => deps.blob.deletePathname(pathname),
      );
      if (result === "deleted") deleted += 1;
    } catch {
      // Un informe no aborta a los demás; conteo acotado sin secretos.
      failed += 1;
    }
  }
  const orphansDeleted = await sweepOrphans(deps, retentionCutoff, (failedIncrement) => { failed += failedIncrement; });
  return { status: 200, body: { deleted, failed, orphansDeleted } };
}

/**
 * Elimina blobs bajo `reports/` sin fila y más viejos que la retención.
 * Solo pathnames derivados exactos (UUID + nombre allowlistado); cualquier
 * otra forma se ignora y un fallo no interrumpe el resto.
 */
async function sweepOrphans(
  deps: Deps,
  retentionCutoffMs: number,
  onFailure: (count: number) => void,
): Promise<number> {
  let removed = 0;
  let cursor: string | null = null;
  for (let page = 0; page < ORPHAN_MAX_PAGES; page++) {
    let listed;
    try {
      listed = await deps.blob.listObjects("reports/", ORPHAN_LIST_LIMIT, cursor);
    } catch {
      onFailure(1);
      break;
    }
    for (const item of listed.items) {
      const derived = parseDerivedPathname(item.pathname);
      if (derived === null) continue;
      if (item.uploadedAtMs > retentionCutoffMs) continue;
      const row = await deps.store.getById(derived.reportId);
      if (row !== null) continue;
      try {
        await deps.blob.deletePathname(item.pathname);
        removed += 1;
      } catch {
        onFailure(1);
      }
    }
    cursor = listed.cursor;
    if (cursor === null) break;
  }
  return removed;
}
