# Development workspace

Use **one active checkout of the public repository** for source work. The current
local checkout is named `realmdeck-performance`; the name does not identify a
second independent fork. It now has its own `.git` directory and does not depend
on the retired internal checkout.

## What belongs where

- **Repository:** source, tests, `AGENTS.md`, `.agents/specs/`, skills, references,
  plans and public documentation. Keep new work on branches based on public
  `main`; preserve local commits when updating from the remote.
- **Private build support:** upstream archives, admitted catalog/native inputs,
  signing material and retained release artifacts. Keep these outside the public
  repository. The reconstruction recipe still names their existing external
  paths; changing those paths is a separate recipe migration, not a cleanup.
- **Generated builds:** use an explicitly selected external test/build tree,
  rather than another copy of the maintained source checkout for every test.
  Freeze receipt-bound release builds and retain their APKs, hashes and evidence.
- **Historical recovery:** one local archive/bundle, not another active source
  checkout. Do not merge unrelated internal history into the public repository.

The functional launcher is reconstructed from `scripts/arm64-prototype/app/`.
The root `android/` project is diagnostic, not the functional launcher project.
Do not bypass admitted input hashes or copy signing keys into Git when a build
input is missing. Keep the applicable source/notice obligations and artifact
privacy checks described in [releasing](releasing.md).

The local consolidation preserved all versioned files and local public commits.
The old host client was explicitly removed; that does not remove or reset the
installation on the phone. No new APK was produced by workspace consolidation.
