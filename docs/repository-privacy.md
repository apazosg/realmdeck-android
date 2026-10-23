# Repository privacy

The code may be published; physical identifiers, credentials, signing keys, and
private reports may not. Keep artifacts and screenshots outside the repository.
If a local directory inside the checkout is needed, `private/` is ignored.

## Phone selection

`DEVICE_SERIAL` in documents and contracts is a placeholder, not a real phone.
Historical USB runners receive the selection through `WOWFOREVER_ADB_SERIAL`;
do not save its value in scripts or replace the placeholder in versioned
documentation. The variable does not replace test authorization or its model,
package, version, and exclusion checks.

It can be exported in the terminal without writing the value to its history:

```bash
read -r -s -p 'Serial USB: ' WOWFOREVER_ADB_SERIAL
printf '\n'
export WOWFOREVER_ADB_SERIAL
```

A `.env.local` file is also ignored, but is not loaded automatically: explicitly
export the required variables. Generated reports may contain the selected serial;
review and sanitize any excerpts before adding them to public documentation. Do not
publish screenshots of accounts or login screens.

## What `.gitignore` protects

`.env`/`.env.*` (except `.env.example`), local Vercel configuration, signing keys
and keystores, HAR/PCAP captures, and `private/` are excluded. The backend
environment example is kept **without credentials**. Also keep diagnostic data,
administration tokens, binaries, and the client/CASC out of Git.

**This is not a content filter:** it does not protect a serial pasted into Markdown,
a secret in an already versioned file, or data in history. `git add -f` can bypass
the exclusions. Review the specific files and staged diff before each commit; do
not indiscriminately add the entire checkout. Exclusions do not replace a secrets
review before publishing.

## GitHub access

The GitHub access reference is kept only in the local checkout, ignored and
untracked, as is the credential. Never version the PAT value or copy it into
public documentation.

## After history sanitization

Hashes change. Do not merge or push branches based on the old history: they could
reintroduce the removed data. Coordinate other clones and use a fresh clone of the
sanitized remote once its update is confirmed. Earlier backups are private and
must never be published as branches, tags, or releases.

Rewriting local history does not automatically delete copies, forks, GitHub caches,
PR references, or attachments/Actions. Verify the remote and those resources
before making it public. No global deletion or exhaustive scan is promised.

Final check on 2026-10-23: the **140 sanitized commits** were retained as private
backup history; the historical repository was renamed to
`wowforever-private-backup-20261023` (ID `1390913283`) and remains private. It
retains its original `main` and issue #1; neither was deleted or migrated. GitHub
retains an old blob containing the removed identifier in that repository, so its
visibility was not changed.

Historical creation event: `https://github.com/apazosg/wowforever` was created as an
independent public repository (ID `1431381862`, distinct node ID, not a fork). Only
the root commit `21f1132b4119cee725e46588c4ff89408e7a63e5` was published, derived
from `HEAD` `2f4d86713c6443a6223f349bacf4cf7c337b5ede` and tree
`8e30d4c7df016bd2fef35833402a58e637fcd18a`. Earlier history was not published.

### Current public repository

The public repository was renamed on 2026-10-23 to
`https://github.com/apazosg/realmdeck-android`, retaining ID `1431381862`; its
project homepage is `https://realmdeck.fyi`. Authenticated verification confirmed
the same public repository, release tags and asset digests. Local `origin` now
points to `https://github.com/apazosg/realmdeck-android.git`, without a fetch or push.
The historical backup
`wowforever-private-backup-20261023` (ID `1390913283`) **must remain private**:
GitHub retains a private historical blob there.

The local `main` diverges from the public parentless root commit
`21f1132b4119cee725e46588c4ff89408e7a63e5` (tree
`8e30d4c7df016bd2fef35833402a58e637fcd18a`). Never push local history, merge or
rebase the histories, or publish the backup. Public documentation-only commits
must be derived solely from public `main`, not from the local ancestor; do
not include code, source, or release assets. The published root remains an ancestor
and release-tag target; do not describe it as the current `main` after documentation
commits.

The old release-page URL redirects to the renamed repository. The old release API
returns HTTP 301; C142 rejects redirects, so its update check fails silently.
Check releases manually until an APK containing the endpoint correction is
published. See [current status](current-status.md).

### Historical snapshot publication verification

The single push reached the remote; the helper did not mark it verified because it
encountered transient/inconsistent responses from the witness object and stopped.
The push was not repeated. Subsequent read-only reconciliation confirmed that
`main` was the only ref and pointed to the stated SHA; the anonymous API returned
that commit with the exact tree and zero parents; the public Git advertisement
exposed the same ref; and the witness object returned HTTP 404 in both authenticated
and anonymous queries. The final read-only verification passed. No visibility
change or other remote operation took place.

The audit scanned 993 tree entries and 872 blobs: zero matches for the private
identifier or PAT, zero prohibited payload paths, and no local access/PAT guide.
The backup remains private and retains the issue. The reconciliation report is
outside the repository, with private permissions, at
`/tmp/opencode/wowforever-new-repo-20261023/final-readonly-reconciliation.json`.

At that time, local `origin` pointed to `https://github.com/apazosg/wowforever.git`;
`origin/main` was updated by CAS to the public SHA without moving or merging the
local `main` branch. Later local documentation commits are not included in the
snapshot and must not be pushed along with the diverged history. C140 was later
published as prerelease `c140-0.2.116-test`; there was no build or device test. The
user confirmed redistribution rights, without an independent audit. Release
coverage and source limitations are recorded in the
[optimization plan](../.agents/planning/apk-optimization-observability-plan.md).
GitHub documents cache retention and Support-managed GC in
[Removing sensitive data](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
Do not delete the previous repository, publish backups, or merge the old history
with the sanitized history; keep the earlier issue in the renamed private repository.
