-- Esquema Postgres (Neon) del backend de diagnóstico.
-- Cuotas y reservas se resuelven en UNA transacción READ COMMITTED con
-- advisory lock adquirido antes de leer (ver src/store-postgres.ts);
-- este esquema solo declara tablas e índices.

CREATE TABLE IF NOT EXISTS reports (
  id UUID PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  installation_id UUID NOT NULL,
  cap_hash TEXT NOT NULL,
  snapshot_hash TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending', 'complete')),
  metadata_json TEXT NOT NULL,
  manifest_json TEXT NOT NULL,
  ip_hmac TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  upload_issues INT NOT NULL DEFAULT 0,
  complete_attempts INT NOT NULL DEFAULT 0,
  total_bytes BIGINT NOT NULL DEFAULT 0,
  redaction_count INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_reports_installation_created
  ON reports (installation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_reports_ip_created
  ON reports (ip_hmac, created_at);
CREATE INDEX IF NOT EXISTS idx_reports_created
  ON reports (created_at);
CREATE INDEX IF NOT EXISTS idx_reports_expires
  ON reports (expires_at, state);

CREATE TABLE IF NOT EXISTS report_attachments (
  report_id UUID NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  bytes BIGINT NOT NULL,
  sha256 TEXT NOT NULL,
  content_type TEXT NOT NULL,
  blob_pathname TEXT NOT NULL,
  verified BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (report_id, name)
);
