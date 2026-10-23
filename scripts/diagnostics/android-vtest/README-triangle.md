# Bounded vtest off-screen triangle gate

`VtestTriangle` is a native protocol-0 client for the already qualified
Android-default `virglrenderer` 1.3.0 vtest server. It is an off-screen gate,
not a renderer, WSI, presentation path, WineD3D integration, or gameplay
runtime. It links only libc and does not use EGL/GLES or `libvirglrenderer`.

The client is fixed to the established `VTEST_UID`/run directory, `vtest.sock`,
and the host-captured `server.identity`. It performs the existing renderer/caps
handshake, creates exactly:

* resource 1: `PIPE_TEXTURE_2D`, `B8G8R8X8_UNORM`, 64x64,
  `PIPE_BIND_RENDER_TARGET`;
* resource 2: `PIPE_BUFFER`, `R8_UNORM`, 96 bytes,
  `PIPE_BIND_VERTEX_BUFFER`.

It emits the literal three vertices and TGSI shader text from the official
`tests/test_virgl_cmd.c` render-simple test. The command stream creates the
surface, framebuffer, viewport, vertex elements/buffer, shaders, blend, DSA,
and rasterizer objects; clears green; reads the clear baseline; draws one
triangle from three vertices; sends `VCMD_RESOURCE_BUSY_WAIT` with `VCMD_BUSY_WAIT_FLAG_WAIT`; and
reads exactly 16,384 bytes. The acceptance gate ignores the X byte of
B8G8R8X8, requires the pre-draw green baseline, at least 128 changed RGB
pixels and 128 green pixels, non-uniform RGB, non-zero FNV-1a, and a green
border. Clear-only, all-zero, truncated, and X-only changes cannot pass.

Object/resource cleanup, server identity revalidation, `SHUT_WR`, and exact
EOF are required for a pass. Failures emit `UNKNOWN`; only the complete gate
emits native status `TRIANGLE_OFFSCREEN`. The native JSON keeps the caps opaque
(`protocolCaps`, `capBytes`, `capNonzeroBytes`, `capsFNVhash`) and reports
`width`, `height`, `readbackBytes`, `clearVerified`, pixel counts,
`readbackFNVhash`, `unrefSent`, stages, and the fixed server binding. It never
prints pixel data or secrets and has a 4 KiB stdout bound. Presentation, D3D,
WoW, hardware-rendering, and the historical broad flags remain false.

## Host build and tests

The build is offline and does not execute or install the Android artifact:

```bash
scripts/diagnostics/android-vtest/build_triangle_client.sh
uv run pytest tests/test_android_vtest_triangle.py -q
```

The artifact is written outside the repository:
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-triangle/`.
The build manifest records source, common handshake, builder/compiler hashes,
the pinned candidate manifest, official source tar, authoritative reference
file hashes, actual `llvm-readelf` output, ELF type/interpreter/DT_NEEDED,
mode, size, and binary SHA-256. The artifact target is AArch64 ET_DYN with
`/system/bin/linker64`, exactly one `DT_NEEDED` entry (`libc.so`), mode 0755,
and a 64 KiB size bound.

Authoritative inputs are the verified candidate manifest
`b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28` and the
official source tar SHA-256
`56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`.
