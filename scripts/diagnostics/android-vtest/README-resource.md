# Bounded vtest resource probe

`VtestResource` is a native, graphics-free protocol probe for the fixed
Android-default vtest server. It is restricted to UID `10330`, the established
per-run working directory, `vtest.sock`, and the verified `server.identity`.
It sends only protocol 0 `CREATE_RENDERER`, `GET_CAPS`, `RESOURCE_CREATE`,
`TRANSFER_PUT`, `TRANSFER_GET`, and `RESOURCE_UNREF`; it never negotiates a
version, creates shared memory or file descriptors, draws, presents, starts
Wine, or claims GPU/graphics support.

The resource is exactly one handle-1 `PIPE_BUFFER`, `R8_UNORM`, width 4,
height/depth/array-size 1, bind 0. The fixed payload is `a1b2c3d4`; success
requires an exact four-byte readback, resource unref, peer identity
revalidation, half-close, and verified EOF.

Build without executing the Android artifact:

```bash
scripts/diagnostics/android-vtest/build_resource_client.sh
```

The build writes only to the external artifact directory
`/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-resource/`.
Its manifest pins the new source, the included handshake source, this build
recipe, compiler, ELF properties, mode, size, and SHA-256 values. The official
wire/source reference is independently verified at these exact paths:

* `/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-default-v2/source/virglrenderer-virglrenderer-1.3.0/vtest/vtest_protocol.h`
* `/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-default-v2/source/virglrenderer-virglrenderer-1.3.0/vtest/vtest_renderer.c`
* `/home/adrian/wowforever-artifacts/arm64-prototype/controls/vtest-android-default-v2/source/virglrenderer-virglrenderer-1.3.0/src/vrend/vrend_renderer.c`

The client binds its wire encoders to the protocol-0 definitions in those
files; it does not copy or build the renderer sources.

This result is classified as `VTEST_RESOURCE_ROUNDTRIP` by the host parent. It
is not a draw, triangle, present, Wine, or graphics qualification.

The native event uses `RESOURCE_ROUNDTRIP`; the host qualification has a separate
namespace. Both processes must exit normally, caps and the exact readback must be
validated, and the server instance must match the HOST-captured PID/start time.
Revalidation occurs after caps, before unref, and after unref. The native client
has a 15-second total deadline and the resource runner defaults to a 20-second
host deadline, with no option above that bound.

```bash
uv run python scripts/diagnostics/android-vtest/run_resource_usb.py \
  --output /home/adrian/wowforever-artifacts/physical-trials/xaga-20261002 \
  --seconds 20
```

The command is fixed to the qualified Android-default source server and the
registered `VtestResource`; no arbitrary executable, profile or device selector
is accepted. It does not change the installed APK, ImageFS or Wine configuration.
