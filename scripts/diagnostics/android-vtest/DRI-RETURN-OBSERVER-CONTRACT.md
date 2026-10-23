# Fixed private DRI return observation

Approved plan: `.agents/planning/done/dri-return-observer-plan.md`. This is a native
qualification child only, never the Android host, server, Wine or an APK hook.

## ABI and authority

Only export `driCreateContextAttribs` with this public Mesa 26.2.4 ABI:

```c
void *driCreateContextAttribs(void *screen, int api, const void *config,
                            void *shared, unsigned num_attribs,
                            const uint32_t *attribs, unsigned *error,
                            void *data, bool thread_safe);
```

The exact private Gallium ELF exports this function at VA `0xaa856c`. Its AArch64
prologue reserves 112 stack bytes and loads the ninth boolean at `[x29,#64]`
with `x29=sp+48`, i.e. the caller's original SP. Attributes are indexed as pairs
and `*error` is accessed through x6. The actual Mesa EGL ELF has a
`R_AARCH64_JUMP_SLOT` relocation for this symbol. This corroborates the public
nine-argument declaration; it is not permission to read internal screen structs.

Provider is ONLY `<cwd>/guest/lib/libgallium-26.2.4.so`, already validated by the
fixed guest manifest/tree and device hash before execution. Resolve it with
`RTLD_NOLOAD|RTLD_NOW|RTLD_LOCAL`; verify canonical `dladdr` owner and reject self,
wrong provider or recursion. Never load another driver or forward to an arbitrary
symbol/path. Failure exits the diagnostic child explicitly as invalid (125), not
an invented DRI/context failure. Preserve the original result and errno after
one verified forwarding call. No borrowed object is modified.

Scope: UID10330, RUN `VTEST-[0-9a-f]{32}`, canonical cwd
`/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/<RUN>`
(equivalent canonical `/data/data` accepted). Exact child basename
`GuestEglDriObservation`, arguments `--run-id <RUN>`, observer path
`<cwd>/guest/observer/libdri_return_observer.so`. No new environment variable:
LD_PRELOAD is the single additional value, exact fixed path, in the child only.

## Output and safety

Use bounded stderr lines prefixed `WFDRI_RETURN ` with schema/version1, RUN,
PID/UID, call sequence, api, numAttribs, threadSafe, copied integer pairs <=16,
providerVerified, forwarded, resultPresent, errorPresent/error, incoming errno
and returned errno. No addresses, config/screen contents, cap bits or game data.
Maximum four complete records / 4096 bytes. Oversized attributes are represented
as unobserved rather than dereferenced beyond the bound; forwarding stays exact.
Serialization/write failure cannot alter forwarding errno/result. Concurrent
calls are bounded and no recursion is forwarded as a valid measurement.

NDK24 Android29 shared AA64 ET_DYN, <=64KiB, libc/libdl only, no GL/EGL imports,
no constructors which create a display/context, no signals/ptrace/ioctls or ELF
memory patching. Host synthetic provider tests prove all nine args, return/error,
errno, bounds, missing provider, recursion and race behavior without GPU execution.

The new native child is copied from the pinned GuestEglLocalLogs sources with
only the exact LD_PRELOAD environment check added; keep the single 3.3 core
request, known logs, guest/server payloads and ownership unchanged. It never
reports context support merely because an observer record is present.

HOST requires exact original source/probe/observer/manifest pins, private ELF
identity and device hashes, RUN/server PID/startticks binding, all normal pipe,
exit and cleanup gates. A valid observed forwarded call may classify
`DRI_RETURN_OBSERVED`, while actual context status remains separate. No record,
invalid provider, observer exit125, truncated/extra records or failed forwarding
is invalid/TRACE_NOT_OBSERVED, not a GPU result. Presentation/D3D/WoW remain false.
