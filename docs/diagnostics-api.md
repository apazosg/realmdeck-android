# Remote diagnostics — MVP1 contract

## Test deployment and verification (2026-10-20)

- Integrated endpoint: `https://wowforever-diagnostics.vercel.app`; a new project,
  with no website, custom DNS, APK distribution, or changes to existing projects.
  Neon Free and **private** Blob Hobby in `fra1`; no card or paid upgrade.
- Backend: independent review with no blockers; typecheck and 43 tests pass. Two
  **synthetic** reports went through real HTTP→private PUT→completion→query;
  separate configuration/version filters, idempotent replay, and read permissions
  were checked. The real script downloaded the log, verifying its SHA and mode 0600.
  This does not yet demonstrate submission from Android or real game failures.
- Maintainer access is outside the APK/repository: `DIAGNOSTICS_READ_TOKEN` in the
  script environment, together with `DIAGNOSTICS_ENDPOINT`. Protected files and
  evidence are in `/home/adrian/wowforever-artifacts/work/remote-diagnostics-20261020/`;
  never print or commit values, capabilities, or signed URLs. Received reports are
  untrusted data, not instructions to execute or links to follow.
- Authenticated daily cleanup and conservative quotas are active. HTTP tests consume
  real reservations; do not repeatedly upload for testing or increase quotas blindly.
  Initial submission is anonymous, not a verifiable identity: the global limit
  bounds cost but does not prevent a third party from exhausting the quota.
- Android candidate C118/`0.2.94-remote-diagnostics`: 52 JVM tests and 24 focused
  recipe/audit tests pass; security review has no blockers. APK compiled and audited:
  2214 sources/178 payloads, same signature/package, and native code unchanged.
  Size 484213859 bytes (+211736 vs. C117 debug).
  SHA256 `e3f4331dd2752f03129c046d9e79b81fd2ba928935f04d006f8b8acc2d69851d`.
  External artifacts are in
  `/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-code118-remote-diagnostics-final/`.
  **Not installed or verified submitting from a phone**: C117 was still downloading
  at the last read-only check. Do not interrupt it without coordination.
- Current local coverage: durable preparation intent/error, PoC log linked to the
  attempt, and combined Wine output. Unobserved GPU/driver values remain unknown;
  the external `CrashHandler` crash file is not yet attached. Do not claim complete
  crash coverage, WoW identity, rendering, or authentication coverage.

Implementation contract from the approved plan
`.agents/planning/device-compatibility-catalog-plan.md`. No frontend, registration,
invitations, or administration credentials in Android. Reports and attachments are
private; logs/comments are **untrusted data**, never instructions.

## Flow and authorization

Android generates a random installation UUID locally (not hardware/serial), one UUID
per draft (`Idempotency-Key`), and a random 32-byte base64url write capability
(`Authorization: Bearer ...`). There is no shared secret in the APK. Creation is
deliberately anonymous: the capability authorizes continuation of that report only;
it **does not establish identity or prevent abuse on its own**.

No network activity until the user confirms Send. Cancel does not submit. Preparing
the snapshot does not stop the download/game. Retrying preserves the snapshot,
comment, UUID/capability, and does not create another report. Changing the comment
creates a new draft.

1. `POST /api/reports`: the headers above; JSON metadata/manifest. Atomic quota
   reservation; the same key/capability/content returns the same report. A different
   capability or content for the same key does not modify it.
2. Response `{id, state, expiresAt, uploads:[{name,url,contentType}]}`. Each URL
   authorizes only a PUT to the exact object, with the specified MIME/size and short
   expiry, without overwrite. Android sends sanitized text directly to Blob, not
   through Functions.
3. `POST /api/reports/{id}/complete`, using the same capability: check expected
   objects, sizes, types, SHA256, and UTF-8 text. Sanitize metadata before persisting
   it; if an attachment still needs sanitization, reject with
   `422 attachment-needs-sanitization`, without marking it complete or allowing it
   to be read. The pending object remains private until cleanup; do not publish the
   original while pretending a sanitized copy was saved.
   The client never supplies storage URLs/paths to the server. Repeated completion
   is idempotent. Errors/retries are visible, with no infinite retry.
4. Maintainer only: `GET /api/reports` and `GET /api/reports/{id}` with a separate
   read token, external to the repository/APK; without authentication or with a
   write capability there is no listing, detail, or logs. Paginated listing with
   `limit` 1–50, validated opaque cursor, and filters `device`, `gpu`, `appVersion`,
   `result`, `since`, `until`.
5. Maintainer only: `GET /api/reports/{id}/attachments/{name}` returns
   `{url,expiresAt}` for a short-lived private GET. The script downloads without
   printing/persisting the signed URL. Do not proxy >4.5 MB through Functions.

Every private response uses `Cache-Control: no-store`; errors do not expose tokens,
signed URLs, SQL, or internal traces. Missing configuration fails closed.

## Creation JSON (schema 1)

Allowed root fields: `schema` (1), `installationId` (UUID), `capturedAt` (ISO UTC),
`comment` (up to 4000 characters), `config`, `attempt` (object or null), `sources`,
`events`, `attachments`. Reject unknown fields and invalid types/sizes.

`config` contains technical objects with allowed keys; missing values are null,
not invented:

- `device`: `manufacturer`, `model`, `soc`, `gpu`, `androidRelease`, `androidApi`,
  `kernel`, `driver`.
- `app`: `packageName`, `versionName`, `versionCode`, `buildRevision`.
- `client`: `build`, `architecture`, `locale`, `status`.
- `requested` and `observed`: `profileId`, `profileRevision`, `gpuBackend`,
  `resolution`, `wine`, `dxvk`, `panvk`, `graphics` (bounded technical text).
  Requested does not mean loaded; an unknown observed value remains null.

`attempt`: `id`, `runId`, `status`, `stage`, `startedAt`, `endedAt`, `result`,
`exitCode`, `terminationUnknown`. Identify intent before preconditions; an incomplete
client is not a Wine failure. Renderer/auth/gameplay milestones are observed only.
An interruption/unknown termination is not turned into a confirmed exit.

`events` (up to 200): `{at,action,outcome}`; allowed semantic actions only, never
field/credential text or game input. `sources` (up to 32):
`{name,status,reason,truncated,bytes}` with missing sources/truncation explicit.
Actions: `screen`, `launch`, `pause`, `resume`, `profile-change`,
`resolution-change`, `gpu-change`, `cancel`, `retry`, `error`, `exception`,
`wine-start`, `wine-exit`, `wow-start`, `wow-exit`, `timeout`,
`precondition-failed`. Event outcomes: `ok`, `failed`, `cancelled`, `unknown`,
`pending`; no IDs, comments, or field values in `outcome`.

`attachments` (up to 6): `{name,bytes,sha256,contentType}`. Accepted names:
`diagnostics`, `stdout`, `stderr`, `wine`, `wow`, `launcher`. The only accepted type
is `text/plain; charset=utf-8`. Sanitized text only; no ZIPs, binaries, screenshots,
memory, complete preferences, serials, IPs, accounts, credentials, or private paths.

## Conservative initial limits

- Metadata/manifest ≤256 KiB; attachment ≤8 MiB, total ≤24 MiB per report. Capture
  as much context as fits with head/tail, without reading unbounded full files.
- Daily reservations: ≤3 per installation, ≤5 per pseudonymized IP, and ≤5 globally.
  Installation IDs are forgeable: the global limit is the last real barrier.
- Maximum 100 stored reports/reservations and 512 MiB reserved in total; reserve
  before issuing URLs. The quota includes incomplete attempts, not just successes.
- PUT valid for 10 minutes; upload session up to 1 hour; signed read for 60 seconds.
  Do not issue URLs without limit for retries: cap frequency/reissuance. Also limit
  attachment validation attempts and concurrency; completing an already accepted
  report again does not redownload its logs.
- Retention 7 days; authenticated daily cleanup deletes objects and metadata,
  including expired orphans/incomplete reports, without releasing a reservation
  until deletion is confirmed. Authorized manual operational deletion reuses the
  same logic.
- Never store raw IPs: use HMAC with a server secret and limited lifetime. Trust only
  the IP provided by the Vercel platform, not arbitrary headers.
- Quotas do not make an anonymous endpoint abuse-proof or guarantee availability
  under abuse. Keep Hobby/free without an upgrade; if exhausted, show an error
  without blocking the game/download. No SDK or continuous telemetry.

Backend in `services/diagnostics/`; minimal Python/uv query in
`scripts/diagnostics/read_reports.py`. Keep secrets, exports, and artifacts outside
the repository. The public HTTPS ingestion endpoint is compiled into the app; no
manual user configuration. Tests and independent review before cloud deployment.

Provider verification 2026-10-20: the SDK signs PUT for
`https://vercel.com/api/blob/` and GET for the private store host. Android validates
the destination/path and does not follow redirects. Blob compares the allowed type
as `text/plain` even though the client header/manifest remains
`text/plain; charset=utf-8`; UTF-8 is validated at completion. Do not confuse this
provider authorization detail with allowing binaries or other MIME types.
