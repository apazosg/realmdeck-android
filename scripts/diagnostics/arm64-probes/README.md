# Windows ARM64 diagnostic probes

These are three small, standalone Windows PE/AA64 probes for the official
WowB-ARM64/Wine 11 custom path. They contain no Blizzard code, do not launch or
inspect the game, and never access another process:

| executable | control | successful process exit |
| --- | --- | ---: |
| `HelloArm64.exe` | Native architecture and pointer-width log | `42` |
| `ApiArm64.exe` | Strict `LoadLibraryExW` path results, `GetProcAddress`, and a real `InitializeCriticalSectionEx` call | `0` |
| `DynCodeArm64.exe` | Generated AArch64 ABI-safe code, private RW→RX execution, and a shared section with RW/RX aliases updated from return `17` to `29` | `0` |

Every successful invocation requires an absolute Windows log path:

```text
HelloArm64.exe --log "C:\wow\diag\hello-arm64.txt" [--run-id RUN_20260929]
```

The Windows CRT supplies quote-safe `argv` parsing, so spaces in the path are
safe when quoted by the caller. `--run-id` is optional, but if supplied it is a
strict non-empty token (`A-Z`, `a-z`, `0-9`, `_`, `-`, `.`); its value prefixes
every log line. Missing/relative/duplicate arguments return `2` without
creating a log. Logs use `fflush` plus `_commit` after each line. Each
mandatory operation has `STAGE-BEGIN` and `STAGE-END ... returncode=N` markers.

The dynamic probe deliberately does **not** include a copied-syscall test. It
records `STAGE-NOT-INCLUDED name=syscall-copied` instead: arbitrary copied bytes
or offset-fragile syscall stubs would not be a normative ARM64 source test and
must not be reported as coverage.

## Reproducible cross-build

The host build uses the official fixed llvm-mingw release, not the existing
x86-64 MinGW toolchain:

```text
URL=https://github.com/mstorsjo/llvm-mingw/releases/download/20260922/llvm-mingw-20260922-ucrt-ubuntu-22.04-x86_64.tar.xz
SIZE=83924028
SHA256=bb7bb7654b33d5aa8712acb837c963b2e0c56352560c76105270a3268c665c21
```

The archive is below 200 MB. Verify the SHA-256 before extracting it. With
`TOOLCHAIN` set to the extracted directory, the exact compile recipe is:

```bash
TARGET=aarch64-w64-windows-gnu
COMMON=(--target="$TARGET" --sysroot="$TOOLCHAIN" -fuse-ld=lld \
  -std=c11 -O2 -Wall -Wextra -Werror -mwindows \
  -D_WIN32_WINNT=0x0602)
"$TOOLCHAIN/bin/clang" "${COMMON[@]}" -o HelloArm64.exe HelloArm64.c
"$TOOLCHAIN/bin/clang" "${COMMON[@]}" -o ApiArm64.exe ApiArm64.c
"$TOOLCHAIN/bin/clang" "${COMMON[@]}" -o DynCodeArm64.exe DynCodeArm64.c
```

`llvm-readobj --file-headers` must report `Machine: IMAGE_FILE_MACHINE_ARM64`
(`0xaa64`) for all three files. Inspect imports with
`llvm-readobj --coff-imports`; no x86-64 object, library, Wine, DXVK, client,
or runtime is part of these artifacts. The host is x86-64 and cannot execute
these PE files; successful compilation and PE header/import inspection are the
only host-side verification. Runtime results must come from an AA64 Windows/Wine
environment and must retain the durable logs.
