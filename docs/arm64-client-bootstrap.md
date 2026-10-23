# ARM64 client bootstrap catalog

This is the host-side result for the approved one-action launcher flow.  The
generator is `scripts/arm64-prototype/client/catalog.py`; it reads the pinned
NGDP objects from the immutable cascette cache, never reads or writes the live
`client/` tree, and never executes the PE.

## Fixed input

| Field | Value |
|---|---|
| Product / build | `wow_classic_beta` / `1.60.1.70009` |
| Build config | `05215079e3905ef5922ae0b03ffefb73` |
| CDN config | `9b3c456dbb837d133a026d380c7c13e9` |
| Install tags | `Windows` + `arm64` + `enUS` |
| Executable | `WowB-ARM64.exe`, PE `0xaa64` |
| CDN path | `tpr/wow` on the official US CDN |

The install manifest has 219 entries, of which the exact ARM64/enUS tag query
selects 35 loose files: one ARM64 executable and 34 runtime dependencies.  A
priority-0 selection or an executable-only download is not used as a
playability claim.

## Catalog schema

The JSON root is `wowforever.arm64-client-catalog`, schema version `1`:

* `provenance` pins product, tags, build/version, both config hashes, CDN path,
  and the official host.
* `executable` and `loose_files` contain safe relative paths, decoded manifest
  MD5/size (`content_key`, `decoded_size`), encoding keys, encoded size, and
  either a direct HTTPS object URL or an archive HTTPS URL plus byte range.
  Validation modes are explicit: range/object size, BLTE decode, decoded size,
  and decoded MD5/content key.  Optional host PE evidence records the SHA-256;
  the artifact is never copied into the repository.
* `required_configs` describes `Data/config/<hash[:2]>/<hash[2:4]>/<hash>` for
  build and CDN configs.
* `casc_bootstrap` lists the required root/VFS, encoding, install, download and
  size objects plus every data CDN archive index.  Its `materialization.recipe`
  is the small portable CASC V7 recipe: 480-byte segment header, 30-byte local
  record header, 18-byte nine-byte-ekey index entries, 1 GiB data segments, and
  sorted bucket indexes.  `local_records` supplies each ekey, raw encoded
  length, local record length and bucket; Android streams verified BLTE bytes
  into fresh `data.NNN` files and writes those records without a TACT engine.
* `build_info` contains the exact installer-compatible PSV fields and the
  serialized SHA-256 for `.build.info`; materialization uses cascette's
  `create_build_info`/`BuildInfoParser`, not JSON.
* `build_metadata.host_only_objects` retains metadata provenance and SHA-256s.
  The `patch` content key is explicitly optional delta provenance: the clean
  installer catches unavailable patch-manifest errors and continues; it is not
  downloaded or treated as a required bootstrap object.
* `bounds` reports transfer and workspace estimates; `completeness` separates
  finite catalog completeness from the explicitly unproven playable install.

## Current cached evidence

The real dry-run produced the following finite set (all source objects were
resolved from the existing cascette cache). The code-27 fix also uses one verified
3.9 MB standalone object stored externally, without modifying the cascette cache:

| Quantity | Result |
|---|---:|
| Selected loose files | 35 |
| Selected loose decoded bytes | 297,225,800 |
| Selected loose encoded bytes | 168,022,215 |
| CDN archive indexes | 1,402 (1,401 archives + `file-index`) |
| CDN index bytes | 125,555,056 |
| Required runtime CASC objects | 832 |
| Required runtime encoded / decoded bytes | 454,437,598 / 560,614,688 |
| Planned initial transfer | 748,157,712 bytes (~713.5 MiB) |
| Catalog maximum transfer | 1 GiB |
| Workspace encoded / expanded estimate | 880,531,985 / 986,709,075 bytes |

The generated JSON and optional skeleton belong outside git, for example:

```bash
ARTIFACT=/home/adrian/wowforever-artifacts/arm64-prototype/client-bootstrap
/home/adrian/.local/bin/uv run python \
  scripts/arm64-prototype/client/catalog.py \
  --output "$ARTIFACT/catalog.json" \
  --official-executable \
  /home/adrian/wowforever-artifacts/references/official-arm64-beta/WowB-ARM64.exe \
   --verified-overlay "$ARTIFACT/cdn-verified"
```

The previously validated external skeleton is:
`/home/adrian/wowforever-artifacts/arm64-prototype/client-bootstrap/casc-skeleton-final`.
It contains `.build.info`, configs, CDN index files, and required runtime CASC
objects through cascette's LocalStorage V7 writer.  It contains no full archive
payloads and no loose install files.  The UI must show the 748,157,712-byte
transfer and workspace estimate and obtain informed approval before downloading;
the original skeleton investigation itself used only cached objects.  The generated local
store is a verified structural input for a runtime trial, not proof that the
native client can render or log in. The Android consumer now implements bounded
range downloading, streaming BLTE, atomic verification/resume and CASC writes;
the actual full provisioning and policy for game cache misses remain phone gates.
Encrypted `E` BLTE requires a TACT key store and is
not silently accepted by this catalog's validation mode.

## Verification boundary

Validated on the host: pinned build/config consistency; manifest tag filtering;
safe paths and duplicate rejection; PE machine identity when the supplied
artifact is checked; ekey lookup; direct/range URL construction and SHA-256
metadata; CDN index parser compatibility; installer-compatible `.build.info`
round-trip; all 16 generated local V7 indexes; and all 832 required ekey/record
size mappings.

Not validated: complete game-data acquisition, the native client's demand set,
streaming behavior after a cache miss, render, login, or Forever authentication.
The catalog reports `status: finite-bootstrap`, `launch_ready: true` for the
catalog/provisioning stage, and `playable_installation: unproven`; it does not
download the full game.  If the Android consumer cannot implement bounded BLTE
decoding plus local CASC index/data writes, the concrete blocker is the missing
mobile CASC materializer—not something to hide by expanding a priority-0 list.

## Encoded/decoded size incident and full-reader gate (2026-10-01)

The code-25 phone run rejected object `bc0fe26fca9629c46c33c478077ecd4d` with
`BLTE decoded size mismatch: 154 != 181`, before Wine spawn. The VFS buildconfig
size is the encoded length (181), not the decoded length (154). Its decoded MD5
is the expected content key `001edd43c5b3baa939ebbff41d750e1a`. The generator now
derives every required runtime decoded size by actual BLTE decoding and checks
MD5, encoding metadata, raw lengths/hashes and official CDN source bindings.
This corrected 828 entries; transfer and local encoded record sizes are unchanged.

Corrected catalog SHA-256:
`061d6cd103b984670ba476660109f4a5a79462da676daddf0d959bf9bbf3a7c0`.
The finite schema deliberately rejects multiple EKeys or unverified mixed sources;
the pinned set has one EKey/source per loose/runtime record. Incomplete loose or
index sources block readiness rather than understating the transfer.

`scripts/arm64-prototype/client/runtime_audit.py` generates an external cache-only
manifest tied to the exact catalog SHA. All **832/832** sources have verified raw
SHA/size, decoded MD5/size and supported BLTE flags/modes: flag `0x0f`, N/Z only.
The APK's streaming `ForeverBlte` reader independently verifies all those same
objects in the opt-in JVM test `ForeverBlteCatalogTest`; both tests passed without
skips, including reproduction of the original mismatch and rejection of a wrong
content key. Optional delta `patch-index` provenance is not a required clean-install
record. No cached object or manifest path is packaged in the APK.

The Python regressions pass (13 tests); build and final APK/source audit pass for
`0.2.2-automated` / code 26. Installed runtime and verified cache are preserved.
This is metadata/reader compatibility evidence on the host, not execution of WoW
or proof of a playable installation. See [automated-arm64-launcher.md](automated-arm64-launcher.md).

## Zero-offset CDN file index and source gate (code 27, 2026-10-01)

The code-26 phone run received 404 for the standalone loose object
`e092b002f52c0827ce4b65d534d7316d` (`Utils/discord_partner_sdk.dll`). The file-index
`6589b9d0516fd85ed44ff57302a4cefe` is not a payload archive. Its footer has
`offset_bytes=0` and 20-byte EKey/encoded-size records; archive indices have
four-byte offsets. The generator parses these separately instead of allowing the
upstream parser to read the next EKey as an offset. Unproven six-byte group indices
are rejected. Index records now retain explicit `index_role`/`offset_bytes`.

The actual standalone URL returned HTTP 200 and decoded to 8,654,776 bytes with
MD5 `d6c3f8e62bc38d8db9cb0cf1f3dee719`; raw SHA-256 is
`a6fe23b7e54091551c823c90cd5d432cf6211202daa03cd4990ed93eb516f9a0`.
It enters the launch-ready catalog only as a verified `direct` source; an uncached
`direct-object` remains explicitly blocked until verified. All 35 routes returned
exact 64-byte 206 probes, with total body transfer 3,934,256 bytes including the
one full object. No full CDN archive, client PE, or game was executed.

`cdn_source_audit.py` binds URL/EKey/archive, status, headers, ranges, raw SHA and
decoded identity, counts received bytes and fails closed. Its existing report can
be rebound/validated offline without repeating network access. Final catalog SHA:
`3cee581d681a5439c138955edd3c39ff2c799689b3889a47cd41c6762e6ff1ce`.
Code 27 has 22 passing Python tests and 9 JVM provisioning/CDN tests, including the
exact object with the APK downloader and unchanged cache reuse. Runtime metadata
payloads and all transfer/workspace bounds remain unchanged. See the launcher
document for the audited APK; device Wine/render/login gates are still pending.
