# Fixed private DRI return observer

This control is a separate native build for observing the original Mesa DRI
context return. It does not change the EGL request, renderer, capabilities, or
error handling. The child is the pinned `GuestEglContext.c` control after the
existing three logging variables and one additional exact environment value:

```text
LD_PRELOAD=<RUN>/guest/observer/libdri_return_observer.so
```

The canonical observer patch changes only the loader environment allowlist and
comparison. It is applied after `guest-egl-local-logs.patch` with fuzz zero.
Unknown, missing, duplicate, or differently valued variables remain rejected.

The observer exports only the nine-argument public ABI:

```c
void *driCreateContextAttribs(void *screen, int api, const void *config,
    void *shared, unsigned num_attribs, const uint32_t *attribs,
    unsigned *error, void *data, bool thread_safe);
```

At callback time it accepts only UID 10330 and the canonical vtest RUN
directory. It resolves only the already loaded
`<cwd>/guest/lib/libgallium-26.2.4.so` with `RTLD_NOLOAD|RTLD_NOW|RTLD_LOCAL`,
checks the no-follow file identity and canonical `dladdr` owner, then forwards
all nine arguments exactly once. A provider, owner, scope, or recursion failure
exits the child with 125; it never fabricates a NULL result or error.

The bounded stderr record is prefixed `WFDRI_RETURN` and contains only the RUN,
PID/UID, sequence, API, `numAttribs`, `threadSafe`, at most sixteen copied
integer pairs, provider/forwarding/result/error presence, error value, and
incoming/returned errno. It emits at most four records and 4096 bytes. Logging
failure cannot change the forwarded return or errno. There are no constructors,
GPU calls, signals, ptrace, ioctls, memory patches, GL/EGL imports, or renderer
claims.

## Reproducible build

From the repository root:

```text
bash scripts/diagnostics/android-vtest/build_dri_observer.sh
```

The recipe verifies the pinned context/loader source and logging-patch hashes,
copies fresh sources into an external controls scratch directory, applies both
patches with fuzz zero and no reject/original files, and compiles the child and
observer independently with NDK 24/API 29. It refuses ambient C/C++/linker
flags and uses no network or downloaded input.

Output is exclusively under:

```text
/home/adrian/wowforever-artifacts/arm64-prototype/controls/dri-return-observer/
```

The manifest records each ELF size/SHA, ET_DYN/PT_INTERP, imports/exports,
original and result source hashes, both patch hashes, recipe/readme/toolchain
hashes, and the fixed nine-argument provider contract. The artifact is a build
control only: it has not been executed on a phone and does not establish an
EGL context, graphics, presentation, D3D, or WoW result.
