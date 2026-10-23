# Fixed DRI core-version observer

This sibling control measures only the little-endian `uint32_t` at
`screen + 64`, once before and once after the original nine-argument
`driCreateContextAttribs` call. It accepts only API 3 with exactly two unique
attribute pairs `{0,3}` and `{1,3}` (either order), and forwards all arguments
unchanged exactly once. A scope/provider/layout failure is control-invalid;
it is never converted into a Mesa result.

The provider gate is the private Gallium file at
`guest/lib/libgallium-26.2.4.so`, size `17656040`, SHA-256
`910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5`.
The observer validates the canonical loaded owner, ELF64 little-endian
`ET_DYN`/AArch64, the dynamic symbol, and the API3/maximum-check instructions
through PT_LOAD VA-to-file mapping before reading guest memory. It never reads
another screen field, dereferences `screen`, uses `/proc` memory, ptrace,
handlers, retries, or a fallback reader.

## Native event

The observer exports only:

```c
void *driCreateContextAttribs(void *, int, const void *, void *, unsigned,
    const uint32_t *, unsigned *, void *, bool);
```

It writes at most four bounded `WFDRI_VERSION` events and 4096 bytes to
stderr, schema `wowforever.android-dri-version-observer/version1`. Alongside
the retained return-observer fields, each record has `layoutVerified`,
`scopeMatches`, `requestedVersion`, `maximumBefore`, `maximumAfter`,
`beforeReadBytes`, `afterReadBytes`, `beforeReadErrno`, `afterReadErrno`,
`measurementValid`, and `checkInference`. `requestedVersion` is the provider's
decimal 3.3 value (`33`); unread maximum values are `null`.
`process_vm_readv` is used only with `getpid()`, one local iovec, one remote
iovec, four bytes, and flags zero. Any error/partial/unsupported read is
`UNKNOWN` and cannot change the original return or errno.

Inference is deliberately derived from the verified static check, not from an
executed PC: `EARLY_CHECK_WOULD_REJECT`,
`NOT_EXPLAINED_BY_EARLY_CHECK`, `NO_BAD_VERSION`, or `UNKNOWN`.

## Fixed build

```text
bash scripts/diagnostics/android-vtest/build_dri_version_observer.sh
```

The recipe uses only the existing NDK 24/API 29 toolchain and existing local
`GuestEglContext.c` plus the existing local-logs patch, then applies only the
new preload patch. It refuses ambient compiler flags, network input, unknown
installs, and changes to the return-observer control. Output is exclusively:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-version-limit/
```

The child is `GuestEglDriVersion`; the only preload is
`guest/observer/libdri_version_observer.so`. The manifest pins source,
result-source, patch, provider, toolchain, ELF size/hash, imports and exports.
Both ELFs are bounded to 64 KiB; the child has only the system linker
interpreter and both binaries need only `libc.so` and `libdl.so`. No phone,
driver, ICD, renderer, context, or maximum result is claimed by this build.
