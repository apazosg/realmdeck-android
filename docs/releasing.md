# Releasing RealmDeck Android

Use this guide with the [release-notes template](release-notes-template.md). Public
release titles, descriptions, and current product guidance are written in English;
dated historical reports keep their original language and context.

## Keep release identifiers consistent

Before publishing, verify that the app version code, version name, tag, APK asset,
release title, and release-body header all describe the same build. The APK asset
name is the release tag plus `.apk`; the tag's release label need not equal the
app's full version name. For C142:

- App version code: `142`
- App version name: `0.2.118-apk-update-notice`
- Tag: `c142-0.2.118-test`
- APK asset: `c142-0.2.118-test.apk`

For a prerelease, set GitHub's prerelease flag and make the body's **first line**
exactly `C<versionCode> prerelease — version code <versionCode>`; for C142, that is
`C142 prerelease — version code 142`. For a stable release, use the `release`
marker instead and do not mark it as a prerelease. The version code in the tag, APK
metadata, header, and release metadata must agree; do not rely on release dates or
version-name sorting.

## Write the release description

Use concise, user-friendly English: a brief summary, a prominent direct APK link
and checksum link, **What's new**, **Installation**, **Before you try**, and
**Details**. Be explicit about manual installation, experimental status, and any
untested behavior. Keep audited technical material, source limitations, and license
notices in the technical attachments and link them from **Details**; the friendly
release body is not a copy of a technical report.

For C142, the release title is **RealmDeck Android 0.2.118 · Preview**. The attached
`README.txt` is a historical technical report, not the release description. The
published C142 APK, source archive, `README.txt`, `THIRD_PARTY_NOTICES`, and
`SHA256SUMS` are frozen assets: do not replace or rewrite them to polish the prose.
If release prose needs improvement, change the release description without
misrepresenting those immutable attachments.

Review and pin the friendly notes separately from technical attachments. Historical
one-shot publishers may pin the original body to the technical README; do not rerun
them to edit an existing release. Verify the final title and body after any edit,
as well as the unchanged asset names, sizes, digests, tags, and release channel.

## Publish and verify

- Confirm the prerelease flag, title, first-line header, tag, version code, and APK
  filename are consistent.
- Attach the APK and its checksum manifest; include the source/technical assets and
  notices that were actually audited for that build. Do not imply that a partial
  source archive is complete Corresponding Source or a self-contained build.
- Verify the public release page and download links, and check the published APK
  size and SHA-256 against the release receipt before announcing it.
- Keep the technical status page clear about what was built, tested, and not tested;
  a build audit is not a physical-device test.

## Automated test-bench builds

[`.github/workflows/release.yml`](../.github/workflows/release.yml) builds and
publishes the in-repository test-bench app (`android/`, package
`com.wowforever.launcher`) when a `vX.Y.Z` tag is pushed. It can also be run from
the Actions tab with an explicit version.

- Version name: `X.Y.Z` from the tag, without the `v`.
- Version code: `major * 10000 + minor * 100 + patch`, so `v0.0.1` is code `1`;
  each component must be `0`–`99`. The code must keep increasing across releases.
- Assets: `<tag>.apk` and `SHA256SUMS`, signed with the CI key.
- Release title: `RealmDeck test bench <version>`. Builds are published as
  prereleases by default, including tag pushes, so the product release keeps the
  repository's "Latest" marker. A manual run with the prerelease flag off
  publishes a stable release; with publishing off it only produces a CI artifact.

Tag the commit that contains the workflow: a tag on an older commit has no
workflow to run. This workflow builds the test bench only. The functional launcher
APK is still built with the local recipe from artifacts outside the repository and
is not produced here.

### CI signing secrets

Every published APK must share one signing key, or later versions cannot be
installed over earlier ones. The keystore stays outside the repository and is
registered once as repository secrets:

```bash
gh secret set RELEASE_KEYSTORE_BASE64 < <(base64 -w0 private/release-signing/realmdeck-release.p12)
gh secret set RELEASE_KEYSTORE_PASSWORD < private/release-signing/.storepass
gh secret set RELEASE_KEY_ALIAS --body realmdeck
gh secret set RELEASE_KEY_PASSWORD < private/release-signing/.storepass
```

The PKCS12 key uses the same password for the store and the key. Back up the
keystore: GitHub secrets cannot be read back, and losing the key permanently ends
in-place upgrades for installed builds.

## Automated functional builds (self-hosted)

[`.github/workflows/release-functional.yml`](../.github/workflows/release-functional.yml)
builds the functional launcher (`com.wowforever.arm64probe`) on the self-hosted
runner labeled `realmdeck-build`, because the recipe reads artifacts outside the
repository.

- Trigger: push a `c<code>-<version>-test` tag, where `<version>` is
  `CANDIDATE_VERSION`'s name without a trailing `-test`. The workflow fails when
  the tag and the committed version disagree, so the tag identifies exactly the
  source that produced the APK.
- Dry run: append `-dryrun` to the tag. It builds and audits but publishes
  nothing; the assets stay as CI artifacts.
- Gate: `audit_functional_ui_apk.py` must return
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`, and the workflow re-hashes the APK after
  the audit to prove it did not change.
- Assets: `<tag>.apk`, `SHA256SUMS`, `README.txt`, `THIRD_PARTY_NOTICES`,
  `audit.json`.
- The JVM behavior-test groups are not automated by this workflow yet; they remain
  a manual pre-release gate.

Runner prerequisites: JDK 17 at
`wowforever-artifacts/integrated-runtime/toolchains/jdk-17.0.20.1+1`, the Android
SDK at `/home/adrian/android-sdk` with build-tools 36.0.0, a warm Gradle cache
(the build runs `--offline`), `gh` in `~/.local/bin`, and the canonical
`THIRD_PARTY_NOTICES` at
`wowforever-artifacts/release-assets/THIRD_PARTY_NOTICES`.

Security: the runner belongs to a public repository, so the workflow listens only
to tag pushes and manual dispatches. Do not add `pull_request` triggers to a
self-hosted runner.

## Update-check limitation

The C142 APK still queries the former `apazosg/wowforever` release API and rejects
redirects. The old API now returns HTTP 301, so its in-app check fails silently; it does
not download or install APKs. C140 has no checker. Do not present in-app checking as
a reliable future-update path until a later APK containing a verified fix is
published. Users should install releases manually in the meantime.
