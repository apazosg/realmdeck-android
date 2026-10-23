// Frontera de persistencia. La reserva de cuota ES la transacción:
// ningún URL de subida se emite antes de que reserve() confirme.
import { blobPathname } from "./blob.js";
export type ReportState = "pending" | "complete";

export interface AttachmentRow {
  name: string;
  bytes: number;
  sha256: string;
  contentType: string;
  blobPathname: string;
  verified: boolean;
}

export interface ReportRow {
  id: string;
  idempotencyKey: string;
  installationId: string;
  capHash: string;
  snapshotHash: string;
  state: ReportState;
  metadataJson: string;
  manifestJson: string;
  ipHmac: string | null;
  createdAtMs: number;
  expiresAtMs: number;
  completedAtMs: number | null;
  uploadIssues: number;
  completeAttempts: number;
  totalBytes: number;
  redactionCount: number;
}

export interface ReserveInput {
  id: string;
  idempotencyKey: string;
  installationId: string;
  capHash: string;
  snapshotHash: string;
  metadataJson: string;
  manifestJson: string;
  ipHmac: string | null;
  nowMs: number;
  expiresAtMs: number;
  totalBytes: number;
  redactions: number;
  attachments: AttachmentRow[];
}

export type ReserveResult =
  | { outcome: "reserved"; report: ReportRow }
  | { outcome: "replayed"; report: ReportRow }
  | { outcome: "conflict" }
  | { outcome: "quota"; reason: string };

export interface ReserveQuotas {
  perInstallationPerDay: number;
  perIpPerDay: number;
  globalPerDay: number;
  maxReports: number;
  maxReservedBytes: number;
  dayStartMs: number;
}

export interface ListFilters {
  device?: string;
  gpu?: string;
  appVersion?: string;
  result?: string;
  sinceMs?: number;
  untilMs?: number;
}

export type RemoveExpiredResult = "deleted" | "skipped";

/**
 * Gracia fija tras expirar la sesión pendiente antes de borrarla: un PUT
 * aceptado por la CDN justo antes de expirar la URL puede confirmarse
 * después, así que el borrado de pendientes espera una hora extra.
 * Solo afecta al borrado; complete sigue devolviendo 410 tras expirar.
 */
export const PENDING_CLEANUP_GRACE_MS = 3600_000;

export interface Store {
  /** Reserva atómica (cuota + idempotencia + inserción) o replay/conflicto/cuota. */
  reserve(input: ReserveInput, quotas: ReserveQuotas): Promise<ReserveResult>;
  getById(id: string): Promise<ReportRow | null>;
  getAttachments(reportId: string): Promise<AttachmentRow[]>;
  /**
   * Consume UN intento de complete solo si sigue pendiente y sin expirar.
   * Null cuando ya no es completable (el llamante re-lee para 404/410).
   */
  claimCompleteAttempt(reportId: string, nowMs: number): Promise<{ attempts: number } | null>;
  /**
   * Transición guardada pending→complete + verified. Null en carrera
   * perdida/expiración (el llamante mapea a 404/410, nunca 500).
   */
  markComplete(reportId: string, redactionDelta: number, nowMs: number): Promise<ReportRow | null>;
  /** Incrementa el contador de emisiones; devuelve el nuevo valor. */
  incrementUploadIssues(reportId: string): Promise<number>;
  list(limit: number, cursor: string | null, filters: ListFilters): Promise<{ items: ReportRow[]; nextCursor: string | null }>;
  /**
   * Borra un informe SOLO si sigue vencido bajo coordinación: primero los
   * blobs derivados, y la fila solo tras confirmar el borrado. Nunca libera
   * cuota antes. `deleteBlob` solo recibe pathnames derivados de este informe.
   */
  removeExpiredReport(
    reportId: string,
    nowMs: number,
    retentionCutoffMs: number,
    deleteBlob: (pathname: string) => Promise<void>,
  ): Promise<RemoveExpiredResult>;
  findExpired(nowMs: number, retentionCutoffMs: number): Promise<string[]>;
  count(): Promise<{ reports: number; bytes: number }>;
}

export function isExpired(report: ReportRow, nowMs: number, retentionCutoffMs: number): boolean {
  if (report.state === "complete") return (report.completedAtMs ?? nowMs) <= retentionCutoffMs;
  return report.expiresAtMs + PENDING_CLEANUP_GRACE_MS <= nowMs;
}

/** Implementación en memoria con la misma semántica (para tests). */
export class MemoryStore implements Store {
  private reports = new Map<string, ReportRow>();
  private byIdempotency = new Map<string, string>();
  private attachments = new Map<string, AttachmentRow[]>();

  async reserve(input: ReserveInput, quotas: ReserveQuotas): Promise<ReserveResult> {
    const existingId = this.byIdempotency.get(input.idempotencyKey);
    if (existingId !== undefined) {
      const existing = this.reports.get(existingId);
      if (existing === undefined) throw new Error("store corrupted");
      if (existing.capHash !== input.capHash || existing.snapshotHash !== input.snapshotHash) {
        return { outcome: "conflict" };
      }
      return { outcome: "replayed", report: existing };
    }
    const quota = this.checkQuotas(input, quotas);
    if (quota !== null) return { outcome: "quota", reason: quota };
    const report: ReportRow = {
      id: input.id,
      idempotencyKey: input.idempotencyKey,
      installationId: input.installationId,
      capHash: input.capHash,
      snapshotHash: input.snapshotHash,
      state: "pending",
      metadataJson: input.metadataJson,
      manifestJson: input.manifestJson,
      ipHmac: input.ipHmac,
      createdAtMs: input.nowMs,
      expiresAtMs: input.expiresAtMs,
      completedAtMs: null,
      uploadIssues: 0,
      completeAttempts: 0,
      totalBytes: input.totalBytes,
      redactionCount: input.redactions,
    };
    this.reports.set(report.id, report);
    this.byIdempotency.set(input.idempotencyKey, report.id);
    this.attachments.set(report.id, input.attachments.map((a) => ({ ...a })));
    return { outcome: "reserved", report };
  }

  private checkQuotas(input: ReserveInput, quotas: ReserveQuotas): string | null {
    let installation = 0;
    let ip = 0;
    let global = 0;
    let totalBytes = 0;
    for (const report of this.reports.values()) {
      totalBytes += report.totalBytes;
      if (report.createdAtMs < quotas.dayStartMs) continue;
      global += 1;
      if (report.installationId === input.installationId) installation += 1;
      if (input.ipHmac !== null && report.ipHmac === input.ipHmac) ip += 1;
    }
    if (installation >= quotas.perInstallationPerDay) return "installation-daily";
    if (input.ipHmac !== null && ip >= quotas.perIpPerDay) return "ip-daily";
    if (global >= quotas.globalPerDay) return "global-daily";
    if (this.reports.size >= quotas.maxReports) return "max-reports";
    if (totalBytes + input.totalBytes > quotas.maxReservedBytes) return "max-bytes";
    return null;
  }

  async getById(id: string): Promise<ReportRow | null> {
    return this.reports.get(id) ?? null;
  }

  async getAttachments(reportId: string): Promise<AttachmentRow[]> {
    return (this.attachments.get(reportId) ?? []).map((a) => ({ ...a }));
  }

  async claimCompleteAttempt(reportId: string, nowMs: number): Promise<{ attempts: number } | null> {
    const report = this.reports.get(reportId);
    if (report === undefined || report.state !== "pending" || report.expiresAtMs <= nowMs) return null;
    report.completeAttempts += 1;
    return { attempts: report.completeAttempts };
  }

  async markComplete(reportId: string, redactionDelta: number, nowMs: number): Promise<ReportRow | null> {
    const report = this.reports.get(reportId);
    if (report === undefined || report.state !== "pending" || report.expiresAtMs <= nowMs) return null;
    report.state = "complete";
    report.completedAtMs = nowMs;
    report.redactionCount += redactionDelta;
    const rows = this.attachments.get(reportId) ?? [];
    for (const row of rows) row.verified = true;
    return report;
  }

  async incrementUploadIssues(reportId: string): Promise<number> {
    const report = this.reports.get(reportId);
    if (report === undefined) throw new Error("report not found");
    report.uploadIssues += 1;
    return report.uploadIssues;
  }

  async list(limit: number, cursor: string | null, filters: ListFilters): Promise<{ items: ReportRow[]; nextCursor: string | null }> {
    let items = [...this.reports.values()].sort((a, b) => b.createdAtMs - a.createdAtMs || (a.id < b.id ? -1 : 1));
    if (cursor !== null) {
      const decoded = decodeCursor(cursor);
      if (decoded !== null) {
        items = items.filter((r) => r.createdAtMs < decoded.t || (r.createdAtMs === decoded.t && r.id > decoded.id));
      }
    }
    items = items.filter((r) => matchesFilters(r, filters));
    const page = items.slice(0, limit);
    const nextCursor = items.length > limit && page.length > 0
      ? encodeCursor(page[page.length - 1] as ReportRow)
      : null;
    return { items: page, nextCursor };
  }

  async removeExpiredReport(
    reportId: string,
    nowMs: number,
    retentionCutoffMs: number,
    deleteBlob: (pathname: string) => Promise<void>,
  ): Promise<RemoveExpiredResult> {
    const report = this.reports.get(reportId);
    if (report === undefined || !isExpired(report, nowMs, retentionCutoffMs)) return "skipped";
    const rows = this.attachments.get(reportId) ?? [];
    // Solo pathnames derivados del manifiesto de ESTE informe.
    for (const row of rows) {
      if (row.blobPathname !== blobPathname(reportId, row.name)) continue;
      await deleteBlob(row.blobPathname);
    }
    this.reports.delete(reportId);
    this.byIdempotency.delete(report.idempotencyKey);
    this.attachments.delete(reportId);
    return "deleted";
  }

  async findExpired(nowMs: number, retentionCutoffMs: number): Promise<string[]> {
    const out: string[] = [];
    for (const report of this.reports.values()) {
      if (isExpired(report, nowMs, retentionCutoffMs)) out.push(report.id);
    }
    return out;
  }

  async count(): Promise<{ reports: number; bytes: number }> {
    let bytes = 0;
    for (const report of this.reports.values()) bytes += report.totalBytes;
    return { reports: this.reports.size, bytes };
  }
}

export function encodeCursor(report: ReportRow): string {
  return Buffer.from(JSON.stringify({ t: report.createdAtMs, id: report.id }), "utf8").toString("base64url");
}

export function decodeCursor(cursor: string): { t: number; id: string } | null {
  try {
    const parsed: unknown = JSON.parse(Buffer.from(cursor, "base64url").toString("utf8"));
    if (typeof parsed !== "object" || parsed === null) return null;
    const record = parsed as Record<string, unknown>;
    if (typeof record["t"] !== "number" || typeof record["id"] !== "string") return null;
    return { t: record["t"], id: record["id"] };
  } catch {
    return null;
  }
}

/** Filtros sobre el JSON de metadatos (misma semántica en Postgres vía JSONB). */
export function matchesFilters(report: ReportRow, filters: ListFilters): boolean {
  if (filters.sinceMs !== undefined && report.createdAtMs < filters.sinceMs) return false;
  if (filters.untilMs !== undefined && report.createdAtMs > filters.untilMs) return false;
  if (filters.device === undefined && filters.gpu === undefined && filters.appVersion === undefined && filters.result === undefined) {
    return true;
  }
  let metadata: Record<string, unknown>;
  try {
    metadata = JSON.parse(report.metadataJson) as Record<string, unknown>;
  } catch {
    return false;
  }
  const config = (metadata["config"] ?? {}) as Record<string, unknown>;
  const device = (config["device"] ?? {}) as Record<string, unknown>;
  const app = (config["app"] ?? {}) as Record<string, unknown>;
  const attempt = (metadata["attempt"] ?? {}) as Record<string, unknown>;
  if (filters.device !== undefined && device["model"] !== filters.device) return false;
  if (filters.gpu !== undefined && device["gpu"] !== filters.gpu) return false;
  if (filters.appVersion !== undefined && app["versionName"] !== filters.appVersion) return false;
  if (filters.result !== undefined && attempt["result"] !== filters.result) return false;
  return true;
}
