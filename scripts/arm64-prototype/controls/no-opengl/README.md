# Scoped no-OpenGL diagnostic asset

`no_opengl_shim.c` is the source for the bounded diagnostic `libGL.so.1`.
It deliberately exports only `wow_diagnostic_no_opengl`; it does **not** define
`glGetString`, GLX functions, Vulkan functions, or a replacement renderer.

The source is the original MIT-licensed Thor diagnostic source, referenced at
commit `4d332477b489863c7d5da686403ac92acb1ebad1`, `src/no-opengl-shim.c`.
The resulting AArch64 ELF is data-only and is intended only for the managed
WoW D3D11/DXVK launch path.  It is not proof that Wine's loader will select the
asset, and it is not a new Vulkan driver or a global ImageFS replacement.

`build.sh` uses the installed Android NDK clang and writes the compiled library
and its small manifest outside the repository; bounded `readelf` reports are
retained beside that external artifact for audit evidence.  The Android overlay consumes
the parent-integrated assets at:

```text
forever/controls/no-opengl/libGL.so.1
forever/controls/no-opengl/manifest.json
```

The manifest is schema 1 and records SHA-256, byte size, ELF machine 183, and
the exact SONAME `libGL.so.1`.  The overlay verifies those values before an
app-private, atomic materialization; no global library is overwritten.
