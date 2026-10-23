// Persistencia Postgres (Neon) con TLS verificado y reserva atómica.
// La reserva (cuota + idempotencia + inserción) ocurre en UNA transacción con
// advisory lock adquirido ANTES de leer: con READ COMMITTED basta, sin
// reintentos de serialización y sin reentrar al pool desde la transacción.
import { Pool } from "pg";
import {
  type AttachmentRow,
  decodeCursor,
  isExpired,
  type ListFilters,
  matchesFilters,
  PENDING_CLEANUP_GRACE_MS,
  type RemoveExpiredResult,
  type ReportRow,
  type ReserveInput,
  type ReserveQuotas,
  type ReserveResult,
  type Store,
} from "./store.js";
import { blobPathname } from "./blob.js";

/** TLS verificado: nunca rejectUnauthorized:false. Testeado explícitamente. */
export const POOL_SSL = { rejectUnauthorized: true } as const;

export function createPool(databaseUrl: string): Pool {
  return new Pool({ connectionString: databaseUrl, ssl: POOL_SSL, max: 1 });
}

// Llave del advisory lock que serializa reservas (bigint válido).
const RESERVE_LOCK = 810024001;

type Row = Record<string, unknown>;

function toMs(value: unknown): number {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value;
  return Date.parse(value as string);
}

function toRow(record: Row): ReportRow {
  return {
    id: record["id"] as string,
    idempotencyKey: record["idempotency_key"] as string,
    installationId: record["installation_id"] as string,
    capHash: record["cap_hash"] as string,
    snapshotHash: record["snapshot_hash"] as string,
    state: record["state"] as ReportRow["state"],
    metadataJson: record["metadata_json"] as string,
    manifestJson: record["manifest_json"] as string,
    ipHmac: (record["ip_hmac"] as string | null) ?? null,
    createdAtMs: toMs(record["created_at"]),
    expiresAtMs: toMs(record["expires_at"]),
    completedAtMs: record["completed_at"] === null ? null : toMs(record["completed_at"]),
    uploadIssues: record["upload_issues"] as number,
    completeAttempts: Number(record["complete_attempts"] ?? 0),
    totalBytes: Number(record["total_bytes"] as string | number),
    redactionCount: record["redaction_count"] as number,
  };
}

/** Lock por informe: coordina complete frente a cleanup sin estados nuevos. */
function reportLockKey(reportId: string): number {
  let hash = 0;
  for (let i = 0; i < reportId.length; i++) {
    hash = (Math.imul(hash, 31) + reportId.charCodeAt(i)) | 0;
  }
  return hash;
}

interface TxClient {
  query: (text: string, params?: unknown[]) => Promise<{ rows: Row[]; rowCount: number | null }>;
}

export class PostgresStore implements Store {
  constructor(private readonly pool: Pool) {}

  async reserve(input: ReserveInput, quotas: ReserveQuotas): Promise<ReserveResult> {
    const client = await this.pool.connect();
    try {
      // READ COMMITTED + lock antes de leer: las reservas no se solapan.
      await client.query("BEGIN ISOLATION LEVEL READ COMMITTED");
      await client.query("SELECT pg_advisory_xact_lock($1)", [RESERVE_LOCK]);
      const existing = await client.query("SELECT * FROM reports WHERE idempotency_key = $1", [input.idempotencyKey]);
      if (existing.rowCount !== 0) {
        const row = toRow(existing.rows[0] as Row);
        await client.query("COMMIT");
        if (row.capHash !== input.capHash || row.snapshotHash !== input.snapshotHash) {
          return { outcome: "conflict" };
        }
        return { outcome: "replayed", report: row };
      }
      const quota = await this.checkQuotas(client, input, quotas);
      if (quota !== null) {
        await client.query("ROLLBACK");
        return { outcome: "quota", reason: quota };
      }
      await client.query(
        `INSERT INTO reports
          (id, idempotency_key, installation_id, cap_hash, snapshot_hash, state,
           metadata_json, manifest_json, ip_hmac, created_at, expires_at, total_bytes, redaction_count)
         VALUES ($1,$2,$3,$4,$5,'pending',$6,$7,$8,
           to_timestamp($9/1000.0), to_timestamp($10/1000.0), $11, $12)`,
        [input.id, input.idempotencyKey, input.installationId, input.capHash,
         input.snapshotHash, input.metadataJson, input.manifestJson, input.ipHmac,
         input.nowMs, input.expiresAtMs, input.totalBytes, input.redactions],
      );
      for (const attachment of input.attachments) {
        await client.query(
          `INSERT INTO report_attachments
            (report_id, name, bytes, sha256, content_type, blob_pathname, verified)
           VALUES ($1,$2,$3,$4,$5,$6,FALSE)`,
          [input.id, attachment.name, attachment.bytes, attachment.sha256,
           attachment.contentType, attachment.blobPathname],
        );
      }
      // Relectura con el MISMO cliente antes de liberar: nunca reentra al pool.
      const reread = await client.query("SELECT * FROM reports WHERE id = $1", [input.id]);
      await client.query("COMMIT");
      if (reread.rowCount === 0) throw new Error("reserve committed but row missing");
      return { outcome: "reserved", report: toRow(reread.rows[0] as Row) };
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch { /* ya abortada */ }
      throw error;
    } finally {
      client.release();
    }
  }

  private async checkQuotas(client: TxClient, input: ReserveInput, quotas: ReserveQuotas): Promise<string | null> {
    const dayStart = new Date(quotas.dayStartMs).toISOString();
    const count = async (text: string, params: unknown[]): Promise<number> => {
      const result = await client.query(text, params);
      return Number(result.rows[0]?.["n"] ?? 0);
    };
    if (await count(
      "SELECT COUNT(*) AS n FROM reports WHERE installation_id = $1 AND created_at >= $2",
      [input.installationId, dayStart],
    ) >= quotas.perInstallationPerDay) return "installation-daily";
    if (input.ipHmac !== null && await count(
      "SELECT COUNT(*) AS n FROM reports WHERE ip_hmac = $1 AND created_at >= $2",
      [input.ipHmac, dayStart],
    ) >= quotas.perIpPerDay) return "ip-daily";
    if (await count("SELECT COUNT(*) AS n FROM reports WHERE created_at >= $1", [dayStart]) >= quotas.globalPerDay) {
      return "global-daily";
    }
    const totals = await client.query("SELECT COUNT(*) AS n, COALESCE(SUM(total_bytes),0) AS b FROM reports");
    if (Number(totals.rows[0]?.["n"] ?? 0) >= quotas.maxReports) return "max-reports";
    if (Number(totals.rows[0]?.["b"] ?? 0) + input.totalBytes > quotas.maxReservedBytes) return "max-bytes";
    return null;
  }

  async getById(id: string): Promise<ReportRow | null> {
    const result = await this.pool.query("SELECT * FROM reports WHERE id = $1", [id]);
    if (result.rowCount === 0) return null;
    return toRow(result.rows[0] as Row);
  }

  async getAttachments(reportId: string): Promise<AttachmentRow[]> {
    const result = await this.pool.query(
      "SELECT name, bytes, sha256, content_type, blob_pathname, verified FROM report_attachments WHERE report_id = $1",
      [reportId],
    );
    return result.rows.map((r) => {
      const record = r as Row;
      return {
        name: record["name"] as string,
        bytes: Number(record["bytes"] as string | number),
        sha256: record["sha256"] as string,
        contentType: record["content_type"] as string,
        blobPathname: record["blob_pathname"] as string,
        verified: record["verified"] as boolean,
      };
    });
  }

  async claimCompleteAttempt(reportId: string, nowMs: number): Promise<{ attempts: number } | null> {
    // UPDATE atómico con guarda: concurrentes obtienen números distintos.
    const result = await this.pool.query(
      `UPDATE reports SET complete_attempts = complete_attempts + 1
        WHERE id = $1 AND state = 'pending' AND expires_at > to_timestamp($2/1000.0)
        RETURNING complete_attempts`,
      [reportId, nowMs],
    );
    if (result.rowCount === 0) return null;
    return { attempts: (result.rows[0] as Row)["complete_attempts"] as number };
  }

  async markComplete(reportId: string, redactionDelta: number, nowMs: number): Promise<ReportRow | null> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN ISOLATION LEVEL READ COMMITTED");
      await client.query("SELECT pg_advisory_xact_lock($1)", [reportLockKey(reportId)]);
      const current = await client.query("SELECT * FROM reports WHERE id = $1 FOR UPDATE", [reportId]);
      if (current.rowCount === 0) {
        await client.query("ROLLBACK");
        return null;
      }
      const row = toRow(current.rows[0] as Row);
      if (row.state !== "pending" || row.expiresAtMs <= nowMs) {
        await client.query("ROLLBACK");
        return null;
      }
      await client.query(
        `UPDATE reports SET state='complete', completed_at=to_timestamp($2/1000.0),
          redaction_count = redaction_count + $3 WHERE id=$1`,
        [reportId, nowMs, redactionDelta],
      );
      await client.query("UPDATE report_attachments SET verified=TRUE WHERE report_id=$1", [reportId]);
      const reread = await client.query("SELECT * FROM reports WHERE id = $1", [reportId]);
      await client.query("COMMIT");
      if (reread.rowCount === 0) return null;
      return toRow(reread.rows[0] as Row);
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch { /* ya abortada */ }
      throw error;
    } finally {
      client.release();
    }
  }

  async incrementUploadIssues(reportId: string): Promise<number> {
    const result = await this.pool.query(
      "UPDATE reports SET upload_issues = upload_issues + 1 WHERE id=$1 RETURNING upload_issues",
      [reportId],
    );
    if (result.rowCount === 0) throw new Error("report not found");
    return (result.rows[0] as Row)["upload_issues"] as number;
  }

  async list(limit: number, cursor: string | null, filters: ListFilters): Promise<{ items: ReportRow[]; nextCursor: string | null }> {
    const conditions: string[] = [];
    const params: unknown[] = [];
    const add = (condition: string, value: unknown): string => {
      params.push(value);
      return condition.replace("?", `$${params.length}`);
    };
    if (filters.sinceMs !== undefined) conditions.push(add("created_at >= to_timestamp(?/1000.0)", filters.sinceMs));
    if (filters.untilMs !== undefined) conditions.push(add("created_at <= to_timestamp(?/1000.0)", filters.untilMs));
    if (filters.device !== undefined) {
      conditions.push(add("(metadata_json::jsonb->'config'->'device'->>'model') = ?", filters.device));
    }
    if (filters.gpu !== undefined) {
      conditions.push(add("(metadata_json::jsonb->'config'->'device'->>'gpu') = ?", filters.gpu));
    }
    if (filters.appVersion !== undefined) {
      conditions.push(add("(metadata_json::jsonb->'config'->'app'->>'versionName') = ?", filters.appVersion));
    }
    if (filters.result !== undefined) {
      conditions.push(add("(metadata_json::jsonb->'attempt'->>'result') = ?", filters.result));
    }
    if (cursor !== null) {
      const decoded = decodeCursor(cursor);
      if (decoded === null) throw new Error("invalid cursor");
      const instant = new Date(decoded.t).toISOString();
      params.push(instant, instant, decoded.id);
      const a = params.length - 2;
      const b = params.length - 1;
      const c = params.length;
      conditions.push(`(created_at < $${a} OR (created_at = $${b} AND id::text > $${c}))`);
    }
    const where = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
    params.push(limit + 1);
    const result = await this.pool.query(
      `SELECT * FROM reports ${where} ORDER BY created_at DESC, id ASC LIMIT $${params.length}`,
      params,
    );
    let rows = result.rows.map((r) => toRow(r as Row));
    // Aplica en JS los filtros que ya cubre SQL (coherencia con MemoryStore).
    rows = rows.filter((r) => matchesFilters(r, filters));
    const page = rows.slice(0, limit);
    const last = page[page.length - 1];
    const { encodeCursor } = await import("./store.js");
    const nextCursor = rows.length > limit && last !== undefined ? encodeCursor(last) : null;
    return { items: page, nextCursor };
  }

  async removeExpiredReport(
    reportId: string,
    nowMs: number,
    retentionCutoffMs: number,
    deleteBlob: (pathname: string) => Promise<void>,
  ): Promise<RemoveExpiredResult> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN ISOLATION LEVEL READ COMMITTED");
      await client.query("SELECT pg_advisory_xact_lock($1)", [reportLockKey(reportId)]);
      const current = await client.query("SELECT * FROM reports WHERE id = $1 FOR UPDATE", [reportId]);
      if (current.rowCount === 0) {
        await client.query("ROLLBACK");
        return "skipped";
      }
      const row = toRow(current.rows[0] as Row);
      if (!isExpired(row, nowMs, retentionCutoffMs)) {
        // Un complete concurrente lo salvó: no tocar nada, no liberar cuota.
        await client.query("ROLLBACK");
        return "skipped";
      }
      const attachments = await client.query(
        "SELECT name, blob_pathname FROM report_attachments WHERE report_id = $1", [reportId],
      );
      for (const entry of attachments.rows) {
        const record = entry as Row;
        const pathname = record["blob_pathname"] as string;
        // Solo pathnames derivados de ESTE informe, nunca URLs remotas.
        if (pathname !== blobPathname(reportId, record["name"] as string)) continue;
        await deleteBlob(pathname);
      }
      await client.query("DELETE FROM reports WHERE id = $1", [reportId]);
      await client.query("COMMIT");
      return "deleted";
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch { /* ya abortada */ }
      throw error;
    } finally {
      client.release();
    }
  }

  async findExpired(nowMs: number, retentionCutoffMs: number): Promise<string[]> {
    const pendingCutoffMs = nowMs - PENDING_CLEANUP_GRACE_MS;
    const result = await this.pool.query(
      `SELECT id FROM reports WHERE
        (state = 'complete' AND completed_at <= to_timestamp($1/1000.0))
        OR (state = 'pending' AND expires_at <= to_timestamp($2/1000.0))`,
      [retentionCutoffMs, pendingCutoffMs],
    );
    return result.rows.map((r) => (r as Row)["id"] as string);
  }

  async count(): Promise<{ reports: number; bytes: number }> {
    const result = await this.pool.query("SELECT COUNT(*) AS n, COALESCE(SUM(total_bytes),0) AS b FROM reports");
    const row = result.rows[0] as Row;
    return { reports: Number(row["n"] ?? 0), bytes: Number(row["b"] ?? 0) };
  }
}
