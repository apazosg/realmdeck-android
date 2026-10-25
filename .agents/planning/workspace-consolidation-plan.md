---
id: workspace-consolidation-2026-10-25
state: in-progress
date: 2026-10-25
---

## Summary

The user asked to simplify the local project layout and clean up accumulated work
directories. A read-only inventory found one public development worktree and one
legacy internal worktree, but they share a single Git common directory currently
located in the internal checkout. The internal tree also contains the 16-GiB
client installation. Blindly deleting or moving that checkout would damage either
the live Git worktree arrangement or user data.

The user clarified that `realmdeck-performance` contains the latest tested work
and wants it unified with the public repository. C146 is published from this
public branch; its ordinary launcher entry and upgrade-progress preservation were
verified. The ten-minute throughput trial was on C145, however, and must not be
presented as a C146 performance result.

The user confirmed that the public checkout has the latest tested code and
approved making it the canonical active checkout, then explicitly requested
unification. `realmdeck-performance` now has an independent `.git` directory.
The old checkout has been retired into a private recovery archive; it is no
longer a second active repository. The user subsequently authorized
deleting the legacy 16-GiB client and clarified that retaining the old internal
history is not a requirement. Preserve signing material and the inputs actually
needed by the current build; do not keep duplicate working trees merely as a
maintenance precaution. Do not sweep large directories by age or name.

## Inventory and constraints

| Area | Observed size/state | Initial disposition |
|---|---:|---|
| Public performance checkout | Independent `.git`; current public source and three pre-existing local planning commits preserved | Only active checkout |
| Legacy internal checkout | Retired into the private recovery area; `.git` renamed as archival metadata, not an active checkout | One recovery bundle/archive; no internal history imported into the public clone |
| Legacy client installation | Previously 16 GiB | Explicitly authorized removal completed after no-game/process/path checks; phone installation untouched |
| Legacy Rust `target` | 937 MiB ignored build output | Potential cleanup candidate only after dependency/process checks and explicit approval |
| Legacy `.venv`, Android build/cache and pytest caches | At least 46 MiB plus generated build outputs | Recreate only when needed; list exact paths before any cleanup |
| External artifact root | 348 GiB; includes 234 GiB `arm64-prototype`, 89 GiB `work`, runtime/toolchains, client snapshots, release evidence and Git-recovery backup | Preserve pending item-by-item classification |
| Recent source/build trees | Public port 26 GiB, integrated trials 24 GiB, earlier physical build 6.9 GiB; multiple candidate source/build copies | Preserve receipts/release assets; classify each tree by source hash and process references before proposing removal |
| External `work/` | 89 GiB across 141 immediate directories: ~35 GiB c-series test/build work, ~44 GiB x64 investigations, remainder unclassified/trust/Wine | Do not delete by name; active Gradle daemons reference c106 work directories |
| 62 `app/build` trees under `arm64-prototype/*/source/GameNative-…` | 105.08 GiB total: `intermediates` 77.48 GiB, `tmp` 2.90 GiB, `.transforms` 1.06 GiB, `kspCaches` 1.04 GiB, `kotlin` 0.87 GiB; `outputs` 21.67 GiB | Proposed removal limited to 296 generated subdirectories (83.36 GiB); preserve all `outputs` (48 trees contain APKs), reports, test results, receipts and source/assets |
| Internal Rust/Python/Android generated data | `target` 937 MiB; `.venv` 46 MiB; `.pytest_cache` 2.8 MiB; Android Gradle cache 408 KiB/build 17 MiB; diagnostics `node_modules` 39 MiB and `dist` 288 KiB | Candidate only after checking required local environments/processes; preserve the private tree and all tracked files |

Initial repository facts: public HEAD was a descendant of public `origin/main`;
the internal `main` history was unrelated. The public worktree's `.git` file
pointed into the internal checkout's `.git/worktrees` directory. This dependency
has now been removed without changing source files or public commits. The
internal tree contained ignored `client/`,
`target/`, `.venv/`, caches, logs and private data. A running local service and
long-lived Gradle daemons were observed; do not stop them or remove their
directories without establishing ownership and quiescence.
An untracked resume-plan document was observed during the initial inventory.
Later checks show a clean internal checkout and a separate public planning
commit; this task did not move, stage or delete that document.

Before independence, the public checkout contained three local commits beyond
public `main`, including concurrent planning work. All three were preserved,
including HEAD `561adcb`. No commits were pushed or dropped by the consolidation.

The current application reconstruction recipe has hard-coded dependencies in
the external artifact root, including the upstream archive, recipe bundle,
catalog, native inputs and retained APK/provenance. Preserve those paths unless
the recipe is deliberately migrated and verified in a separate change.

## Proposed phases

1. [x] **Top-level read-only map:** record repository sizes, worktree links,
   artifact-root categories, hard-coded reconstruction dependencies and known
   active process references. No client/CASC contents or private file contents
   were inspected.
2. [x] **Canonical roles:** use `realmdeck-performance` as the only active code
   location. Keep the internal checkout/history and client in place as a legacy
   archive because of the shared Git common directory and retained data. Keep
   external pinned inputs and release/recovery evidence at their existing paths.
3. [x] **Detailed candidate map:** inspected metadata only for the 62 `app/build`
   trees: generated-child paths/sizes, APK-bearing outputs, source trees and
   sibling receipts. No client payloads were read or hashed.
4. [x] **Cleanup candidates:** produced an exact 296-path manifest totaling
   83.36 GiB. Candidates are only `intermediates`, `tmp`, `.transforms`,
   `kspCaches` and `kotlin` beneath those 62 Gradle build roots. The manifest is
   `workspace-cleanup-20261025/generated-build-cleanup-candidates.json` outside
   Git. It excludes `outputs`, reports, tests, generated source, client data,
   credentials, pinned inputs and release/provenance evidence.
5. [x] **Approved generated-output cleanup:** the user approved the exact
   296-directory list. Quarantine completed with all candidate sizes verified;
   sources, APK outputs, reports and test-result metadata matched before/after.
   Four focused reconstruction/admission tests passed. The same exact quarantine
   was removed, retaining its manifest and completion receipt outside Git.
6. **Required final Git layout:** the user wants one independent public-based
   repository, not two maintained checkouts. Prepare a standalone public clone
   in a temporary location, preserve the current local public planning commits
   and worktree files, validate public ancestry/refs/status, then review an atomic
   cutover of the canonical checkout's Git metadata. Do not remove the old common
   Git directory until the canonical checkout no longer points at it. Old
   internal history may be stored in one local archive rather than another
   active checkout; do not import that unrelated history into the public repo.
7. **Minimal build support:** enumerate the current reconstruction recipe's
   external input closure and signing identity, verify their existing content
   hashes and identify duplicates. Select one private support location for each
   required input, not one copy per candidate. Update hard-coded paths only in
   a reviewed recipe change, then prove reconstruction and signing still work
   before removing superseded copies. Keep keys and non-source payloads outside
   the public repository. Do not re-publish private inputs or rewrite historical
   receipt hashes to make a migration pass.

## Acceptance criteria

- One clearly identified active checkout and a written map of retained archives
  and pinned inputs.
- No credentials, current release evidence, required build inputs, public work
  or live process data is removed. The explicitly authorized legacy client
  deletion is separate from the generated-cache cleanup.
- Any approved cleanup has an exact-path manifest, recovery path and successful
  post-cleanup Git/reconstruction validation.
- The active public repository is independent of the legacy checkout and
  contains current source, specs, agent documentation and local public work.
- Required signing/build material has one documented private canonical location;
  unrelated internal history is not merged into the public repository.

## Implementation / Action Items

- [x] Complete the top-level read-only map of repositories, worktrees, artifacts
  and pinned dependencies.
- [x] Confirm the canonical active checkout is `realmdeck-performance`; preserve
  internal history and client data separately.
- [x] Inventory the 62 candidate `app/build` trees by generated subdirectory and
  identify APK-bearing `outputs` that must be retained.
- [x] Produce the exact candidate manifest; process-quiescence must be rechecked
  immediately before cleanup.
- [x] Confirm that one independent public-based repository is the desired final
  layout; keeping two active repositories is not the objective.
- [x] Independently review and execute the standalone Git-metadata cutover.
- [x] Map and authenticate the current pinned input files and directories;
  preserve signing material. Input relocation/deduplication remains separate.
- [x] Remove the explicitly authorized legacy client after path/quiescence
  validation, without touching the installed phone client.
- [x] Retire the old active checkout into one private archive and verified bundle.
- [x] Present exact cleanup candidates and obtain approval to clean them.
- [x] Quarantine only approved regenerable artifacts, then verify before deletion.
- [x] Remove the verified 296-directory quarantine: 89,502,289,506 bytes
  (83.356 GiB). Preserve all excluded source/output/evidence directories.
- [x] Revalidate both Git worktree states, unchanged source/output metadata,
  four focused reconstruction/admission tests and `git fsck --full` (exit 0).

## Generated-output cleanup verification

The external completion receipt reports `purged_verified`, 296 removed
directories and 89,502,289,506 bytes. The exact quarantine no longer exists.
Sources, APK outputs, reports and tests retained their recorded inode/size/mtime
fingerprints; both checkout HEADs and statuses were unchanged by the operation.
No client installation, signing material, pinned input, receipt, release asset
or Git metadata was in the removal list.

The cleanup script initially rejected legitimate nested cache directories and
stopped before deletion. A subsequent read-only validation was interrupted;
the owned CPU-heavy validator was later stopped before its purge stage and
changed to validate candidate roots once rather than comparing every nested
entry with all 296 roots. Final preflight and deletion then completed normally.
No broad parent directory deletion or additional cleanup scope was introduced.

Evidence remains outside Git in the dated cleanup directory: candidate manifest,
quarantine journal, scripts, purge log and post-cleanup fsck output. Four selected
host reconstruction/admission tests passed (36 deselected); no full build or
whole-suite result is claimed. Standalone Git migration, input deduplication and
the separately authorized legacy-client deletion were not completed by that
first step; their later status is recorded below.

## Standalone public repository verification

The user explicitly requested unification. A new single-branch clone transferred
the public branch through Git's object transport, with no hardlink/alternate
dependency on the internal object store. It retained public `main` and all three
local public commits. The internal HEAD commit is absent from the new object
database. A complete local legacy bundle was created and verified separately.

Independent review found no blocking cutover issue; the final script also checks
public branch refs and rejects the internal commit before the swap. The old
`.git` pointer was saved, the new standalone `.git` installed, and 888 versioned
file hashes, HEAD and clean status verified. `git fsck --full` exits 0. Effective
author identity was retained without copying credentials or private branch refs.

After confirming no host game processes or descriptors/current directories under
the old checkout, its explicitly authorized client was removed. The remaining
legacy checkout was moved into private recovery storage and its `.git` renamed
as archival metadata. The original working-directory path no longer exists.
Ignored private files were preserved without opening their contents.

The canonical reconstruction input authentication succeeds from the independent
checkout. Ten pinned external files match their exact SHA-256 pins; admitted
probe/update directories also pass the recipe's existing checks. Those files
remain in their current private artifact locations: no historical hashes were
rewritten and no new APK/release/phone mutation occurred. Moving or deduplicating
those input files still requires a separately validated recipe change.

The external `git-independence` receipts record the before/after file hashes,
standalone cutover, verified input map, client removal and legacy retirement.
See [workspace guidance](../../docs/development-workspace.md) for routine use.

## Open Issues

- Which external historical trial/build directories, if any, may be discarded?
  Several contain multi-GiB trees and their names alone do not establish that
  they are reproducible or unneeded.
- The completed first cleanup was only the 296 generated Gradle subdirectories
  listed in the external manifest (83.36 GiB). Their sibling APK outputs, reports,
  tests, receipts, source trees/assets and every client snapshot are excluded.
  The user approved this exact cleanup; quarantine, verification and permanent
  removal are complete. No other historical build or artifact was deleted.
- The final layout is one independent public-based checkout. The technical
  Git cutover is verified and the old checkout is retired. Input relocation
  and further historical-artifact deduplication are not completed here.
- The public branch retains local planning commits not yet in public `main`;
  this cleanup does not authorize pushing or merging them.
- The cause of previous host reboots and empty Git/cache artifacts remains
  unestablished; cleanup must not be described as fixing it.
