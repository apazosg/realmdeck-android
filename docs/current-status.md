# Current status

**Evidence snapshot: 2026-10-23.** RealmDeck Android is an experimental Android
ARM64 launcher project, not a general release. The current public preview is
[C142, RealmDeck Android 0.2.118 · Preview](https://github.com/apazosg/realmdeck-android/releases/tag/c142-0.2.118-test)
(version code 142; version name `0.2.118-apk-update-notice`).

## Device evidence

The latest positive device report is for C141: the user reported a successful first
login on one device. This is not evidence for C142. **C142 has not been installed or
visually tested on a physical device.** No measured performance or sustained-session
result is available, and this evidence does not establish broad device support,
audio, or controller support. See the [historical evidence archive](launcher-app-status.md)
for dated test context.

## C142 build and source limits

The C142 canonical build audit passed: 2,223 source files and 178 payloads were
audited; six JVM tests against the same implementation and 29 reconstruction tests
passed. This is build/test evidence, not a device test. The APK is a ModernDebug
build with the existing debug signing and remains experimental.

The release's `c142-0.2.118-source.tar.gz` is the source archive associated with
this APK, but it is **partial**: it is not complete Corresponding Source for every
binary and is not a self-contained build environment. GitHub's automatically
generated source archives for the tag represent the older public repository
snapshot, not the source set for this APK. See the [release notes](releases/c142-0.2.118.md)
for all attached assets.

## Manual updates

Install the APK manually from the [C142 release page](https://github.com/apazosg/realmdeck-android/releases/tag/c142-0.2.118-test).
Do not rely on the in-app notice as an update mechanism: C140 has no update checker,
and the checker shipped in C142 still targets the former `apazosg/wowforever` API
and rejects redirects. The former API now returns HTTP 301, so C142's check fails
silently. A later APK is needed to deliver the checker fix; until then,
check the release page and install updates manually. The notice does not download
or install APKs.

For the user-facing summary, APK hash, and installation guidance, see the
[C142 release notes](releases/c142-0.2.118.md). For project setup and boundaries,
see the [README](../README.md).
