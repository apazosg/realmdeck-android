# CurseForge addon API routes

These isolated Vercel Functions expose explicit CurseForge queries only:

- `GET /api/addons/search?query=<text>&index=<offset>` (20 results/page)
- `GET /api/addons/file?projectId=<id>` (latest accepted file descriptor)
- `GET /api/addons/download?projectId=<id>&fileId=<id>` (ZIP stream)

Set `CURSEFORGE_API_KEY` only in the server environment. Without it all routes
fail closed with `503 service-not-configured`. The key is sent to
`api.curseforge.com` and, for file downloads only, to an exact allowlist of
`edge.forgecdn.net` and `media.forgecdn.net` after validating the HTTPS URL
returned by the official API. URL credentials, ports, fragments, other hosts,
and redirects are rejected; redirects are never followed. CurseForge announced
that CDN download authentication is required for `edge.forgecdn.net` beginning
July 16 ([announcement](https://blog.curseforge.com/introducing-api-key-authentication-for-curseforge-file-downloads/)).
Actual authorized provider access has not been tested, so successful CDN
authentication remains unverified. Responses are no-store.
The routes use the official game-version-types endpoint and require one exact
`Forever` type plus file metadata containing exact `1.60.1` and `Forever`
labels. If that type is absent or ambiguous, compatibility is unverified and
the service refuses to claim support. This is intentionally fail-closed, not a
promise that CurseForge currently publishes such a type.

Only projects that explicitly report author distribution permission and files
reported available, stable-release (not beta/alpha), non-alternate,
exact-version compatible, bounded, and with a SHA-1 descriptor are offered.
A malformed file record is dropped instead of failing its whole project; a file
page where no entry belongs to the requested project, or where no entry is
schema-valid at all, is rejected with `502 upstream-invalid-response`. An omitted
`dependencies` field means no dependencies; a present but malformed one
invalidates that file.
Downloads are limited to 64 MiB and stream
without whole-file buffering. The SHA-1 is returned in the file descriptor for
the client to verify; this backend does not claim a successful integrity check
before the client performs it. Unknown or revoked project/file metadata blocks
download.

The backend enforces bounded query values, upstream JSON size, timeout, and a
four-request in-process concurrency limit that remains held while a route reads
its response body/streams the download. JSON routes use a 12 s deadline; the
download route uses a 45 s deadline because it must also stream up to 64 MiB.
`vercel.json` sets `maxDuration` to 30 s for `search`/`file` and 60 s for
`download` so neither deadline is cut short by the platform default; a deployment
must keep its platform limit above those values. These limits are not distributed abuse protection:
deployments must add a platform firewall/rate-limit gate for these public,
unauthenticated routes. No in-memory counter is represented as a global quota.
The live Vercel streaming behavior, authorized CurseForge account access,
provider terms, and deployment rate-limit gate have not been verified. No real
API key, deployment, paid service, or live provider request is part of this
implementation or its tests.
