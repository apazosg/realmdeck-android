# Experimental runtime-probe notice

This is a private evaluation artifact, not a public redistribution. It is
based on [`marcocastignoli/wow-mobile`](https://github.com/marcocastignoli/wow-mobile)
at commit `0c8068f617737d9c0fb6246f37b54f85b620b09d`. The upstream root project
is licensed under LGPL-2.1; retain and consult its `LICENSE` file.

The repository's runtime-prototype patches and overlays change the application
identifier, launcher activity, report storage, probe-mode lifecycle, and build
provenance. Canonical probe sources are copied from this repository at build
time and are not a WoW client.

Third-party inventory (incomplete and for private evaluation only): Android SDK
and build tools, NDK, CMake, Gradle, JDK, Wine/Box64/rootfs assets, and their
respective upstream licenses and notices. This inventory is not a clearance to
redistribute any dependency or generated APK.
