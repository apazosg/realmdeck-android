# PanVK kbase context metadata control

This is a bounded **own-kernel-context metadata proof**, not a GPU, driver,
Vulkan, ICD, renderer, or readiness test.  It is a new sibling of the original
`KbaseMetadata` control; the original source and artifact remain byte-for-byte
unchanged.

## Fixed contract

The control accepts only:

```text
KbaseContextMetadata --run-id PVK-<32 lowercase hexadecimal characters>
```

It requires UID `10330`, `/dev/mali0`, character-device identity `10:113`, and
one of the two canonical private package paths ending in that exact run ID:

```text
/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/<run-id>
/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/<run-id>
```

The control does `lstat`, opens a fresh private fd with
`O_RDWR|O_CLOEXEC|O_NOFOLLOW`, and verifies the same character-device identity
with `fstat` before and after the operation.  The only ioctl sequence is:

```text
VERSION_CSF  _IOWR(0x80, 52, {u16 major,u16 minor}) = 0xC0048034
              only when the returned version is exactly 1.18:
SET_FLAGS    _IOW(0x80, 1, u32 create_flags)          = 0x40048001
              create_flags = 0, exactly once
GET_GPUPROPS _IOW(0x80, 3, {u64 buffer,u32 size,u32 flags}) = 0x40108003
              bounded size probe followed by one bounded fill
```

`SET_FLAGS` creates only this control's kernel context.  Closing its fd releases
that context.  There is no allocation or tracking mmap, JIT or EXEC operation,
queue, fence, shader, reset, priority change, ICD load, or other ioctl.  A
version mismatch, negative SET_FLAGS result, or unexpected positive ioctl is a
stop; no later ioctl is attempted.  The cooperative native bound is five
seconds, while the HOST runner uses a ten-second boundary.  A blocked kernel
syscall cannot be promised to be forcibly preempted by this process.

GET_GPUPROPS is limited to 1..16384 bytes.  The owned allocation adds a 16-byte
canary, checked immediately after every fill even when the fill fails or reports
an oversized return.  Corruption is `UNKNOWN` and the potentially corrupted
allocation is deliberately not freed.  Parsing accepts only bounded,
little-endian, tightly packed records with declared widths and unique selected
keys: GPU ID 55 and texture fields 52, 53, 54, and 81.

## Result schema

The line schema is
`wowforever.android-kbase-context-metadata/version1`.  It preserves every
field of the original metadata schema and adds:

```json
"context": {
  "called": true,
  "ioctl": "0x40048001",
  "createFlags": 0,
  "ret": 0,
  "errno": 0
}
```

`flagsInited` is true only after version 1.18 and one successful SET_FLAGS call.
`gpuWorkloadVerified`, `driverExecution`, `deviceVerified`, and
`hardwareVerified` are always false.  `VERSION_MISMATCH` and `FLAGS_BLOCKED`
are controlled failures with native exit 1.  `PROPERTIES_BLOCKED` is also
nonzero.  Unexpected positive returns, identity/clock/deadline/canary failures,
and malformed properties are `UNKNOWN`.  A zero exit proves only this metadata
sequence, never GPU readiness.

## Build and authority

`build_context_probe.sh` uses the existing NDK
`24.0.8215888/aarch64-linux-android29-clang` and `llvm-readelf`, strict
`-Wall -Wextra -Werror`, and no download or device operation.  It writes only:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/kbase-context-metadata
```

The directory contains the <=64 KiB AA64 `ET_DYN` PIE, readelf report, and a
manifest with artifact/source/compiler hashes and byte sizes.  The external
UAPI authority is the pinned
`zenithblue-oss/panvk-kbase-android` commit
`de577411e8f27da9de50aec26a0d8cc43890745e`:

```text
https://github.com/zenithblue-oss/panvk-kbase-android
```

The HOST runner pins the final local source, artifact, and manifest hashes
before upload.  Its only CLI argument is an existing external evidence parent.
It uses the fixed serial `DEVICE_SERIAL`, existing loader preflight helpers,
ADB shell-v2 plus exit-37 sentinel, package version 41, `run-as` UID 10330,
exclusive private run paths, exact device hash/mode checks, and stdin closed.
It captures and fsyncs `capture.json` before starting the held HOST adb child.
Cleanup is bounded to that handle and the exact package's force-stop only for
unknown, timeout, or remaining-owned-process conditions; a valid native
`FLAGS_BLOCKED` result is not force-stopped.
