# PanVK kbase metadata probe

This is a **kernel metadata observation only**, not a PanVK, Vulkan, or GPU
qualification.  It is intentionally limited to the connected POCO tuple in
 the approved plan: UID `10330`, `/dev/mali0`, character device `10:113`, and
 the two canonical private probe working-directory prefixes.  The directory
 must end in the exact supplied `PVK-<32 lowercase hexadecimal characters>`
 run ID.  The probe itself is the ELF artifact that the parent runner may
 execute; it never initialises a kbase context, sets flags, maps a page,
 allocates GPU memory, reads registers, loads an ICD, executes another ELF or
 payload, submits work, or accesses another process.

## Contract

Invocation is exactly:

```text
KbaseMetadata --run-id PVK-<32 lowercase hexadecimal characters>
```

The process must already be UID 10330 and its canonical CWD must be either
`/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/<run-id>`
or `/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/<run-id>`.
The probe does not accept a device, PID,
command, or ioctl number from the command line.

The probe validates `/dev/mali0` with `lstat`, opens it exactly as
`O_RDWR|O_CLOEXEC|O_NOFOLLOW`, and verifies the character-device identity from
the path, opened fd, and final fd `fstat`: major 10, minor 113, with the same
device/inode.  A wrong lstat character-device identity is `UNKNOWN` with error
stage `lstat_identity`; true negative lstat/open syscalls remain `OPEN_FAILED`
with their negative errno.  It then sends exactly one CSF version ioctl with `{major,minor}
= {0,0}`.  A successful version observation with `major != 1` is recorded and
does not attempt properties.  A successful CSF (`major == 1`) observation sends
the documented `GET_GPUPROPS` size probe once with `buffer=0,size=0,flags=0`,
then one fill call using exactly the bounded returned size.  A property ioctl
 failure is `PROPERTIES_BLOCKED` and preserves the negative errno.  This is a
 valid metadata measurement but fails the gate and exits nonzero; no `SET_FLAGS`
 fallback is attempted even though some kbase ports require it.

The project bound is 1..16384 returned bytes.  The owned allocation is the
returned size plus a 16-byte guard.  The fill return count, guard, strict
tightly-packed records, little-endian values, selected-key uniqueness, and
declared widths are validated.  Unknown keys are skipped only by their
declared width.  Selected metadata is raw GPU ID key 55 and texture keys
52/53/54/81, all width `u32` when present.  Missing key 55 is recorded as
 unknown; it is not classified as hardware unsupported and is never rebuilt
 from other fields.  The guard is checked immediately after the fill ioctl;
 corruption is `UNKNOWN` and nonzero, and the short-lived child deliberately
 does not free a potentially corrupted owned allocation.

The one-line JSON schema is `wowforever.android-kbase-metadata/version1`.
It records run/process identity, bounded time, open identity, immediate
version/property return and errno values, status, cleanup, and optional
`gpuIDhex`/`textureflags`.  It always sets `flagsInited`,
`gpuWorkloadVerified`, `driverExecution`, and `deviceVerified` to `false`.
The broader `hardwareVerified` flag is also always `false`.
`VERSION_OBSERVED` and `PROPERTIES_OBSERVED` exit zero.  `PROPERTIES_BLOCKED`
is valid metadata but a failed gate and exits one; `UNKNOWN`, `OPEN_FAILED`,
and `VERSION_FAILED` also exit nonzero.  JSON `nativeExit` equals that actual
result.  Malformed input, scope, clock, identity, cleanup, or output failures
are `UNKNOWN`.  The line is bounded to 8192 bytes.  A zero native exit means
only that the metadata observation completed; it is not a GPU or driver pass.

The cooperative native deadline is five seconds.  The host runner owns its
ten-second outer deadline; a kernel syscall cannot be forcibly preempted by
this process.

## ABI authority and proof

The source was fixed to
`zenithblue-oss/panvk-kbase-android` commit
`de577411e8f27da9de50aec26a0d8cc43890745e`:

`sourceAuthority`/`sourceHash` for the external UAPI authority is that exact
40-character pinned commit; the manifest separately hashes every local probe
input and the compiler.

- Port source: [`kbase_kmod.c`](https://raw.githubusercontent.com/zenithblue-oss/panvk-kbase-android/de577411e8f27da9de50aec26a0d8cc43890745e/patches/kbase-common/files/src/panfrost/lib/kmod/kbase_kmod.c).
- Common Arm/Linux-syscall-note UAPI: [`kbase_uapi.h`](https://raw.githubusercontent.com/zenithblue-oss/panvk-kbase-android/de577411e8f27da9de50aec26a0d8cc43890745e/patches/kbase-common/files/src/panfrost/lib/kmod/kbase_uapi.h).
- The assembled public ioctl spelling is also preserved in
  [`mali_kbase_ioctl.h`](https://raw.githubusercontent.com/zenithblue-oss/panvk-kbase-android/de577411e8f27da9de50aec26a0d8cc43890745e/patches/kbase-common/files/include/drm-uapi/mali_kbase_ioctl.h).
- Arm public CSF definitions are in [`mali_base_csf_kernel.h`](https://raw.githubusercontent.com/zenithblue-oss/panvk-kbase-android/de577411e8f27da9de50aec26a0d8cc43890745e/patches/kbase-common/files/src/panfrost/lib/kmod/mali_base_csf_kernel.h).
  Its CSF comment/source contract is `arch >= 10: nr=52`; JM is `arch <= 9:
  nr=0`.  This probe intentionally implements only the CSF number and never
  tries the JM frontend.

For this probe the actual NDK build has `_Static_assert` proof of:

```text
VERSION_CSF = _IOWR(0x80, 52, {u16 major,u16 minor}) = 0xC0048034
GET_GPUPROPS = _IOW(0x80, 3, {u64 buffer,u32 size,u32 flags}) = 0x40108003
```

`VERSION_CSF` is sent exactly once with both fields zero.  `GET_GPUPROPS` is
the documented two-phase query: the first return value is the required byte
count, and the second return value is the number of bytes returned into the
owned buffer.  The probe does not invoke `SET_FLAGS` when a port blocks the
metadata query; that result is recorded as UAPI-only `PROPERTIES_BLOCKED`.

The properties stream is a packed little-endian sequence of 4-byte
`(key << 2) | width-code` headers followed immediately by 1/2/4/8-byte
values.  No alternate numbers, layouts, frontend, or declared ioctl is tried.

## Build and artifact

`build_kernel_probe.sh` uses only the existing NDK 24.0.8215888
`aarch64-linux-android29-clang`, strict `-Wall -Wextra -Werror` flags, and the
system linker.  It writes only
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/kbase-metadata`:
the <=64 KiB AA64 `ET_DYN` PIE, `llvm-readelf` report, and an independent
manifest containing artifact/source/compiler SHA-256 and byte sizes, flags,
symbols, `ET_DYN`/AA64/linker64 checks, and the sole `libc.so` dependency.
The build recipe performs no ADB, device, install, root, download, or driver
operation.
