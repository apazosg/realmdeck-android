# GameNative ARM64 baseline build gate

This record belongs to the isolated ARM64 variant. It is not an APK artifact and
does not change the existing launcher.

## Source and provenance

The community bundle's `SOURCES.txt` names this exact GameNative revision:

`205cbf8ddc06f855a1b85ba57c78c4be9a6930aa`

The source was downloaded from the commit archive URL, not from a branch or
`HEAD`, and stored outside the repository at:

`/home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative`

The downloaded archive SHA-256 is:

`9151ca5a20168a8901797d7fcc2946706f57532088632527876efa2931bdc361`

The archive is 575 MiB compressed and the extracted source is 666 MiB. No source,
client, WCP, CASC, or APK was added to this repository.

## Static source inspection

The pinned tree declares:

- AGP `8.8.0`;
- Gradle wrapper `8.12.1`;
- compile SDK `36`;
- NDK `27.3.13750724`;
- Java/Kotlin target `17`;
- an `arm64-v8a` modern flavor; and
- native CMake trees for proot, evshim, SysV/Winlator glue, and Vulkan support.

The app already contains the Bionic/Wine integration needed for the later hook:
`ContentsManager`, `ImageFsInstaller`, `WineInfo`,
`BionicProgramLauncherComponent`, and `XServerScreen`. Its custom-game path is
the no-Steam-login route; Steam login is only a deferred-launch requirement for
Steam-sourced games.

## Build checkpoint

The exact pinned wrapper command was run online from the source tree:

```text
GRADLE_OPTS="-Dorg.gradle.instrumentation.agent=false" \
  ~/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle \
  --offline --no-daemon --no-configuration-cache --no-parallel \
  -Dorg.gradle.jvmargs="-Xmx4096m -Dfile.encoding=UTF-8" \
  :app:assembleModernDebug --stacktrace
```

```text
./gradlew :app:assembleModernDebug --max-workers=1
```

The first run downloaded the pinned Gradle `8.12.1` distribution and stopped on
the source tree's missing `local.properties`. A local-only ignored overlay was
then created at the source root with `sdk.dir=/home/adrian/android-sdk`; no
source version or build file was changed.

The default JDK 21 run reached KSP but crashed in the JVM while compiling KSP and
R8 classes. Re-running with the existing external Temurin JDK 17 and constrained
local JVM settings completed successfully:

```text
JAVA_HOME=/home/adrian/wowforever-artifacts/integrated-runtime/toolchains/jdk-17.0.20.1+1 \
./gradlew :app:assembleModernDebug --max-workers=1 --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx4096m -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2"
```

Result: **BUILD SUCCESSFUL** in 6m49s; 68 actionable tasks, 37 executed.

APK output (kept outside the repository):

- `/home/adrian/wowforever-artifacts/arm64-prototype/toolchain/GameNative/app/build/outputs/apk/modern/debug/app-modern-debug.apk`
- Size: `232,966,506` bytes
- SHA-256: `f00382e9edad6a652f2886c0dd86d477beefcf1299be8d5dbd9c8babaf076a22`
- Package: `app.gamenative`, version `1.2.1`
- Native ABI: `arm64-v8a`

The APK audit found no `WowB.exe`, `WowB-ARM64.exe`, CASC, or client markers.
It does contain GameNative's existing `wowbox64` runtime assets; these are not
the WoW client. No production/release signing was used.

The pinned NDK `27.3.13750724` is not installed. The stock build does not invoke
external CMake because its native modules are prebuilt/shipped, so this baseline
APK does not prove a native rebuild. The official NDK archive was inspected at
`663,956,036` bytes and was not downloaded.

The Gradle distribution response was `136,556,486` bytes. New Gradle module
artifacts accounted for `288,256,577` bytes and metadata for `3,168,022` bytes;
the measured wrapper-plus-module download/cache-input total was therefore
`427,981,085` bytes, within the authorized 750-MB envelope. Gradle also created
approximately `1,733,482,479` bytes of local transformed/cache entries during
the build; that is storage consumption, not additional wire download. The
SDK/NDK was not downloaded, and no client/runtime media was downloaded. No APK
was installed, and no device/ADB test was run.

## Safe next step after toolchain authorization

The stock source build gate is now open. Do not modify source versions. The next
bounded step is the diagnostic custom-game entry described in `README.md`, using
the existing `ContentsManager`/Bionic launcher path, followed by a fresh APK
audit to ensure the client remains external.
