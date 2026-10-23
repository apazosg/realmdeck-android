#!/usr/bin/env python3
"""Build the isolated autologin-provider instrumentation APK without Gradle.

This helper intentionally packages only the already compiled instrumentation
classes.  The production APK and its class jar are inputs, not outputs, and no
device, ADB, network, client data, or real credentials are involved.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


REPO = Path(__file__).resolve().parents[3]
EXTERNAL_ROOT = Path(
    "/home/adrian/wowforever-artifacts/arm64-prototype/autologin-settings-code72-framework"
)
SOURCE_ROOT = EXTERNAL_ROOT / "source" / "GameNative-205cbf8ddc06f855a1b85ba57c78c4be9a6930aa"
MAIN_CLASSES_JAR = SOURCE_ROOT / "app/build/intermediates/compile_app_classes_jar/modernDebug/bundleModernDebugClassesToCompileJar/classes.jar"
MAIN_APK = SOURCE_ROOT / "app/build/outputs/apk/modern/debug/app-modern-debug.apk"
ANDROID_JAR = Path("/home/adrian/android-sdk/platforms/android-36/android.jar")
BUILD_TOOLS = Path("/home/adrian/android-sdk/build-tools/36.0.0")
JAVA = Path(
    "/home/adrian/wowforever-artifacts/integrated-runtime/toolchains/"
    "jdk-17.0.20.1+1/bin/java"
)
DEBUG_KEYSTORE = Path.home() / ".android/debug.keystore"

INSTRUMENTATION_SOURCE = REPO / (
    "scripts/arm64-prototype/app/instrumentation/"
    "ForeverFunctionalAutologinProviderInstrumentation.kt"
)
INSTRUMENTATION_MANIFEST = REPO / "scripts/arm64-prototype/app/instrumentation/AndroidManifest.xml"
DEFAULT_OUTPUT = EXTERNAL_ROOT / "provider-check-build"

TEST_PACKAGE = "com.wowforever.arm64probe.providercheck"
TARGET_PACKAGE = "com.wowforever.arm64probe"
RUNNER = "app.gamenative.ForeverFunctionalAutologinProviderInstrumentation"
CERTIFICATE_SHA256 = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"

EXPECTED_SOURCE_SHA256 = "cdb77e1d6885023e1f4f0b79674b282fe2e8bd6fa5ec2abeabf93468fe487af1"
EXPECTED_MANIFEST_SHA256 = "f1c9e8c2b0614b6c251240c7e00c9d69429f8b3795958492be913ca88a33ac7d"
EXPECTED_MAIN_CLASSES_SHA256 = "48e0c2b3151710dfa5819f53e5d24856ff07a5b29da6846d33ddf8b36da43627"
EXPECTED_MAIN_APK_SHA256 = "d4e05e205ea3f9614bed71b507b0e70880bdcd2c275c0a0604b244611462dfe2"

ANDROID_NS = "http://schemas.android.com/apk/res/android"
MAX_CAPTURE_BYTES = 16 * 1024


class BuildError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def require_file(path: Path, label: str) -> None:
    require(path.is_file(), f"missing {label}: {path}")


def cached_jar(relative: str) -> Path:
    path = Path.home() / ".gradle/caches/modules-2/files-2.1" / relative
    require_file(path, f"cached compiler dependency {relative}")
    return path


def compiler_jars() -> list[Path]:
    # These are the already cached Kotlin 2.1.21 compiler runtime inputs.  Keep
    # the versions explicit: silently selecting another compiler defeats the
    # purpose of this Gradle-independent check build.
    return [
        cached_jar(
            "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21/"
            "70a69b95905e33806fbee6c6c9c48e2b2b2b4da4/"
            "kotlin-compiler-embeddable-2.1.21.jar"
        ),
        cached_jar(
            "org.jetbrains.kotlin/kotlin-stdlib/2.1.21/"
            "97a0975aa19d925e109537af60eb46902920015c/"
            "kotlin-stdlib-2.1.21.jar"
        ),
        cached_jar(
            "org.jetbrains.kotlin/kotlin-script-runtime/2.1.21/"
            "e274d560d10526099330411c45961f04d85e77b9/"
            "kotlin-script-runtime-2.1.21.jar"
        ),
        cached_jar(
            "org.jetbrains.kotlin/kotlin-reflect/1.6.10/"
            "1cbe9c92c12a94eea200d23c2bbaedaf3daf5132/"
            "kotlin-reflect-1.6.10.jar"
        ),
        cached_jar(
            "org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21/"
            "2376f98b59395adad949ca19811283fafb1b2e31/"
            "kotlin-daemon-embeddable-2.1.21.jar"
        ),
        cached_jar(
            "org.jetbrains.intellij.deps/trove4j/1.0.20200330/"
            "3afb14d5f9ceb459d724e907a21145e8ff394f02/"
            "trove4j-1.0.20200330.jar"
        ),
        cached_jar(
            "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/"
            "ac1dc37a30a93150b704022f8d895ee1bd3a36b3/"
            "kotlinx-coroutines-core-jvm-1.8.0.jar"
        ),
        cached_jar(
            "org.jetbrains/annotations/13.0/"
            "919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
        ),
    ]


def compiler_target_jars() -> tuple[Path, Path, Path]:
    stdlib = cached_jar(
        "org.jetbrains.kotlin/kotlin-stdlib/2.1.21/"
        "97a0975aa19d925e109537af60eb46902920015c/"
        "kotlin-stdlib-2.1.21.jar"
    )
    annotations = cached_jar(
        "org.jetbrains/annotations/13.0/"
        "919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"
    )
    return stdlib, annotations, ANDROID_JAR


def record_file(path: Path) -> dict[str, Any]:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)}


def command_text(argv: list[str]) -> str:
    return shlex.join(str(item) for item in argv)


class CommandLog:
    def __init__(self, output: Path) -> None:
        self.output = output
        self.commands: list[dict[str, Any]] = []

    def run(self, argv: list[Path | str], *, cwd: Path | None = None, compiler: bool = False) -> str:
        normalized = [str(item) for item in argv]
        item: dict[str, Any] = {"command": command_text(normalized)}
        if cwd is not None:
            item["cwd"] = str(cwd)
        try:
            completed = subprocess.run(
                normalized,
                cwd=cwd,
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env={**os.environ, "JAVA_HOME": str(JAVA.parent.parent)},
            )
        except subprocess.CalledProcessError as error:
            item["returncode"] = error.returncode
            item["result"] = "FAIL"
            item["stderr_tail"] = error.stderr[-MAX_CAPTURE_BYTES:]
            self.commands.append(item)
            self._write()
            if compiler:
                (self.output / "compiler-failure.txt").write_text(
                    "Compiler failed once; no automatic retry was attempted.\n\n"
                    + error.stderr[-MAX_CAPTURE_BYTES:],
                    encoding="utf-8",
                )
            raise BuildError(f"command failed ({error.returncode}): {command_text(normalized)}") from error
        item["returncode"] = 0
        item["result"] = "PASS"
        self.commands.append(item)
        self._write()
        return completed.stdout

    def _write(self) -> None:
        (self.output / "commands.json").write_text(
            json.dumps(self.commands, indent=2) + "\n", encoding="utf-8"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--source", type=Path, default=INSTRUMENTATION_SOURCE)
    parser.add_argument("--manifest", type=Path, default=INSTRUMENTATION_MANIFEST)
    parser.add_argument("--main-classes-jar", type=Path, default=MAIN_CLASSES_JAR)
    parser.add_argument("--main-apk", type=Path, default=MAIN_APK)
    return parser.parse_args()


def validate_inputs(args: argparse.Namespace, output: Path) -> tuple[list[Path], dict[str, Any]]:
    require(EXTERNAL_ROOT.is_dir(), f"external artifact root is absent: {EXTERNAL_ROOT}")
    require(not output.is_relative_to(REPO), "output must remain outside the repository")
    require(output.is_relative_to(EXTERNAL_ROOT), "output must be under the external framework directory")
    for path, label in (
        (args.source, "instrumentation source"),
        (args.manifest, "instrumentation manifest"),
        (args.main_classes_jar, "main compile jar"),
        (args.main_apk, "main APK"),
        (ANDROID_JAR, "Android 36 android.jar"),
        (JAVA, "JDK 17 java"),
        (DEBUG_KEYSTORE, "public Android debug keystore"),
    ):
        require_file(path, label)
    require(sha256(args.source) == EXPECTED_SOURCE_SHA256, "instrumentation source hash changed")
    require(sha256(args.manifest) == EXPECTED_MANIFEST_SHA256, "instrumentation manifest hash changed")
    require(sha256(args.main_classes_jar) == EXPECTED_MAIN_CLASSES_SHA256, "main compile jar changed")
    require(sha256(args.main_apk) == EXPECTED_MAIN_APK_SHA256, "main APK changed")
    with zipfile.ZipFile(args.main_classes_jar) as archive:
        names = set(archive.namelist())
    for class_name in (
        "app/gamenative/ForeverFunctionalAutologinCredentials.class",
        "app/gamenative/ForeverFunctionalAutologinStore.class",
        "app/gamenative/ForeverFunctionalAutologinAndroidKeystoreBackend.class",
    ):
        require(class_name in names, f"main compile jar lacks {class_name}")
    jars = compiler_jars()
    return jars, {
        "source": record_file(args.source),
        "manifest": record_file(args.manifest),
        "mainCompileJar": record_file(args.main_classes_jar),
        "mainApk": record_file(args.main_apk),
        "androidJar": record_file(ANDROID_JAR),
        "debugKeystore": {
            "path": str(DEBUG_KEYSTORE),
            "bytes": DEBUG_KEYSTORE.stat().st_size,
            "purpose": "standard public Android debug signing key; no test credentials",
        },
        "compilerJars": [record_file(path) for path in jars],
    }


def prepare_output(output: Path) -> None:
    if output.exists():
        marker = output / ".synthetic-provider-check-output"
        require(marker.is_file(), f"refusing to replace non-helper output directory: {output}")
        shutil.rmtree(output)
    output.mkdir(parents=True)
    (output / ".synthetic-provider-check-output").write_text(
        "Synthetic instrumentation artifact only; safe to replace by this helper.\n",
        encoding="utf-8",
    )


def generated_manifest(source_manifest: Path) -> bytes:
    root = ElementTree.fromstring(source_manifest.read_bytes())
    require(root.tag == "manifest", "instrumentation manifest root is not manifest")
    root.set("package", TEST_PACKAGE)
    instrumentations = list(root.findall("instrumentation"))
    require(len(instrumentations) == 1, "instrumentation manifest must contain one runner")
    instrumentation = instrumentations[0]
    require(instrumentation.get(f"{{{ANDROID_NS}}}name") == RUNNER, "unexpected instrumentation runner")
    require(instrumentation.get(f"{{{ANDROID_NS}}}targetPackage") == TARGET_PACKAGE, "unexpected target package")
    require(len(list(root)) == 1, "provider check manifest has unexpected components")
    require(not list(root.findall("uses-permission")), "provider check manifest requests permissions")
    require(not list(root.findall("application")), "provider check manifest has an application")
    return ElementTree.tostring(root, encoding="utf-8", xml_declaration=True)


def validate_main_apk(args: argparse.Namespace, log: CommandLog) -> dict[str, str]:
    badging = log.run([BUILD_TOOLS / "aapt2", "dump", "badging", args.main_apk])
    require(f"package: name='{TARGET_PACKAGE}'" in badging, "main APK target package is not stable")
    signature = log.run([BUILD_TOOLS / "apksigner", "verify", "--verbose", "--print-certs", args.main_apk])
    require(
        f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}" in signature,
        "main APK certificate differs from the expected debug certificate",
    )
    return {"package": TARGET_PACKAGE, "certificateSha256": CERTIFICATE_SHA256}


def build(args: argparse.Namespace) -> Path:
    output = args.output_dir.resolve()
    prepare_output(output)
    log = CommandLog(output)
    jars, input_receipt = validate_inputs(args, output)
    main_identity = validate_main_apk(args, log)

    work = output / "work"
    work.mkdir()
    manifest = output / "AndroidManifest.xml"
    manifest.write_bytes(generated_manifest(args.manifest))
    unsigned = work / "provider-check-unsigned.apk"
    dex_dir = work / "dex"
    dex_dir.mkdir()
    test_classes = work / "test-classes.jar"
    compiler_classpath = os.pathsep.join(str(path) for path in jars)
    target_stdlib, target_annotations, target_android = compiler_target_jars()
    target_classpath = os.pathsep.join(
        str(path)
        for path in (args.main_classes_jar, target_stdlib, target_annotations, target_android)
    )

    compile_argv: list[Path | str] = [
        JAVA,
        "-Xmx1536m",
        "-XX:ActiveProcessorCount=2",
        "-cp",
        compiler_classpath,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-classpath",
        target_classpath,
        "-Xfriend-paths=" + str(args.main_classes_jar),
        "-d",
        test_classes,
        args.source,
    ]
    log.run(compile_argv, compiler=True)
    require(test_classes.is_file(), "Kotlin compiler did not produce test classes jar")

    d8_argv: list[Path | str] = [
        BUILD_TOOLS / "d8",
        "--min-api",
        "29",
        "--lib",
        target_android,
        "--classpath",
        args.main_classes_jar,
        "--classpath",
        target_stdlib,
        "--classpath",
        target_annotations,
        "--output",
        dex_dir,
        test_classes,
    ]
    log.run(d8_argv)
    dex_files = sorted(dex_dir.glob("classes*.dex"))
    require([path.name for path in dex_files] == ["classes.dex"], "D8 emitted unexpected dex members")

    log.run(
        [
            BUILD_TOOLS / "aapt2",
            "link",
            "-I",
            target_android,
            "--manifest",
            manifest,
            "--min-sdk-version",
            "29",
            "--target-sdk-version",
            "36",
            "-o",
            unsigned,
        ]
    )
    with zipfile.ZipFile(unsigned, "a") as archive, dex_files[0].open("rb") as dex:
        require("classes.dex" not in archive.namelist(), "unsigned APK already contains classes.dex")
        archive.writestr("classes.dex", dex.read(), compress_type=zipfile.ZIP_DEFLATED)

    aligned = work / "provider-check-aligned.apk"
    log.run([BUILD_TOOLS / "zipalign", "-f", "-p", "16", unsigned, aligned])
    artifact = output / "provider-check.apk"
    log.run(
        [
            BUILD_TOOLS / "apksigner",
            "sign",
            "--ks",
            DEBUG_KEYSTORE,
            "--ks-key-alias",
            "androiddebugkey",
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--v1-signing-enabled",
            "false",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "false",
            "--v4-signing-enabled",
            "false",
            "--out",
            artifact,
            aligned,
        ]
    )
    verification = log.run(
        [BUILD_TOOLS / "apksigner", "verify", "--verbose", "--print-certs", artifact]
    )
    require(f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_SHA256}" in verification, "test APK certificate mismatch")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in verification, "test APK lacks v2 signature")

    badging = log.run([BUILD_TOOLS / "aapt2", "dump", "badging", artifact])
    require(f"package: name='{TEST_PACKAGE}'" in badging, "test APK package mismatch")
    require("minSdkVersion:'29'" in badging and "targetSdkVersion:'36'" in badging, "test APK SDK mismatch")
    xmltree = log.run(
        [BUILD_TOOLS / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", artifact]
    )
    require(RUNNER in xmltree and TARGET_PACKAGE in xmltree, "test APK runner/target manifest mismatch")
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "test APK has duplicate ZIP members")
        require("classes.dex" in names and not any(name.startswith("lib/") for name in names), "test APK has JNI/ABI content")
        require(not any(name.startswith("assets/") for name in names), "test APK has assets")
        require(archive.read("classes.dex") == dex_files[0].read_bytes(), "classes.dex changed during packaging")

    receipt = {
        "schema": "wowforever.synthetic-autologin-provider-check.v1",
        "artifactKind": "synthetic instrumentation test APK, not a customer app",
        "customerApp": False,
        "offline": True,
        "deviceExecution": "NOT RUN",
        "network": "NOT USED",
        "actualCredentials": "NOT USED; instrumentation creates only synthetic provider-validation values",
        "productionVault": "NOT MODIFIED; provider-validation alias is separate",
        "package": TEST_PACKAGE,
        "targetPackage": TARGET_PACKAGE,
        "runner": RUNNER,
        "minSdk": 29,
        "targetSdk": 36,
        "mainIdentity": main_identity,
        "inputs": input_receipt,
        "toolchain": {
            "java": record_file(JAVA),
            "javaVersion": "17.0.20.1+1",
            "androidJar": record_file(ANDROID_JAR),
            "buildTools": "36.0.0",
            "kotlinCompiler": "2.1.21",
            "compilerHeap": "1536m",
            "activeProcessors": 2,
        },
        "checks": {
            "mainApkHashPinned": True,
            "mainCompileJarHashPinned": True,
            "manifestFromRepository": True,
            "testClassesOnlyToDex": True,
            "noJniOrAbiMembers": True,
            "certificateSha256": CERTIFICATE_SHA256,
        },
        "artifact": record_file(artifact),
        "commands": log.commands,
        "plannedDeviceInvocationsNotRun": [
            f"adb shell am instrument -w -e phase write {TEST_PACKAGE}/{RUNNER}",
            f"adb shell am instrument -w -e phase restore-and-forget {TEST_PACKAGE}/{RUNNER}",
            f"adb shell am instrument -w -e phase cleanup {TEST_PACKAGE}/{RUNNER}",
        ],
    }
    (output / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    shutil.rmtree(work)
    (output / "provider-check.apk.sha256").write_text(
        f"{sha256(artifact)}  {artifact.name}\n", encoding="utf-8"
    )
    return artifact


def main() -> int:
    args = parse_args()
    try:
        artifact = build(args)
    except (BuildError, OSError, ElementTree.ParseError) as error:
        print(f"provider-check build failed: {error}", file=sys.stderr)
        return 1
    print(f"provider-check APK: {artifact}")
    print(f"sha256: {sha256(artifact)}")
    print("offline Gradle-free build PASS; no ADB/device/instrumentation execution performed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
