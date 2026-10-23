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

## Update-check limitation

The C142 APK still queries the former `apazosg/wowforever` release API and rejects
redirects. The old API now returns HTTP 301, so its in-app check fails silently; it does
not download or install APKs. C140 has no checker. Do not present in-app checking as
a reliable future-update path until a later APK containing a verified fix is
published. Users should install releases manually in the meantime.
