# Architecture and baseline (Phase 0)

For the current RealmDeck Android release and evidence limits, see
[current status](current-status.md). The dated baseline below is preserved for context.

> Status: 2026-09-27. Reproducible baseline on a Linux x86-64 host.
> **Status snapshot (2026-10-21):** C127 reaches Login and game entry with 70450
> ARM64 on xaga/Mali; C128 is further along on the patch, compiled/audited but not installed.
> See [resumption point](downloader-handoff.md) and
> [product status](launcher-app-status.md).
> Winlator 11.2 / POCO M7 Pro 5G PowerVR below are a **historical baseline**, not
> the active device/runtime or a new runtime decision.
> Behavioral source of truth: `.agents/specs/` (SYSTEM.md, US-001.md).
> This document records the components and architecture decisions for Phases 0–1.

## 1. Objective

Run the **WoW Classic Beta 1.60.1** client against **Classic Forever** on Android,
without Battle.net or Windows. Phase 0 establishes the components; Phase 1 obtains
the client from the CDN. The project proceeds only if Phase 2 (starting `WowB.exe`
on Android) gets a go decision.

## 2. Client and NGDP/TACT protocol

| Item | Value |
|---|---|
| NGDP product | `wow_classic_beta` (`wow_forever` does not exist → 404) |
| Active build (2026-09) | `1.60.1.70009` (BuildId `70009`) |
| Build tested on Android (2026-10-21) | `1.60.1.70450`, Windows ARM64/enUS; Login and game entry in C127 |
| Builds accepted by the launcher | `1.60.1.69913`, `1.60.1.69977`, `1.60.1.70009` |
| Versions endpoint | `http://us.patch.battle.net:1119/wow_classic_beta/versions` |
| CDNs endpoint | `http://us.patch.battle.net:1119/wow_classic_beta/cdns` |
| CDN path | `tpr/wow` (shared by the entire `wow` family) |
| CDN config path | `tpr/configs/data` |
| Hosts | `level3.blizzard.com`, `us.cdn.blizzard.com` |
| Windows executable | `WowB.exe` (ARM64: `WowB-arm64.exe`) |
| Default subfolder | `_classic_beta_` |
| Text locale | `enUS` |
| Disk requirement (product) | ~128 GB (all tags) |

### CDN URL layout

- Build/CDN configs → `http://<host>/tpr/wow/config/<a>/<b>/<hash>`
- Data manifests (encoding/install/download/size/vfs-root) → `http://<host>/tpr/wow/data/<a>/<b>/<hash>`
- Product config (JSON) → `http://<host>/tpr/configs/data/<a>/<b>/<hash>`
- Data files (buckets) → `http://<host>/tpr/wow/data/<a>/<b>/<cKey>`

### Hash-pair rule (critical)

Each `<x> = h1 h2` field in the build config contains **two** hashes. For the
selected CDN, **the second** works; the first returns 404. Verified for `encoding`,
`install`, `download`, and `vfs-root`. **Do not assume the first.**

The build config is plain text (not BLTE) at `tpr/wow/config/<a>/<b>/<bcHash>`. The
legacy `root` manifest is not used (404); content is resolved through `vfs-root`
(TVFS) and the `vfs-1..vfs-N` tree.

### Storage mode

The build config does **not** declare `build-file-db` ⇒ **container** mode (CASC
files), not containerless.

## 3. Forever (Classic Forever server)

Reference: `defexnicolas/wow-classic-launcher` (MIT).

- **Portal**: `auth.gpon.com.co` (certificate issued for the hostname, not the IP).
  Login on `:1119`, world on `:8085`.
- **Configuration**: the launcher creates/updates `WTF/BetaSuspendedTest.wtf` with
  `SET portal "auth.gpon.com.co"` (copies `Config.wtf` the first time) and launches
  `WowB.exe -config BetaSuspendedTest.wtf`. **It does not modify `Config.wtf`.**
- **Key patch**: searches the process's private memory (rw, not code or disk) for
  the 12-key Ed25519 public-key store in `NetClient::CertStore` and replaces the
  group 8 key with the server's public key, reapplying it if the client recreates
  the store. Key 1 is the search anchor and is never changed.
- Operational details (constants, entry layout, flags): `.agents/references/classic-forever-patch.md`.
- Do not run with `sudo` (Wine as root → BC_ASSERT).

## 4. Android runtime (candidates, Phase 2)

The following is the historical September baseline, not the active runtime/device.
The later validated path is in [product status](launcher-app-status.md).

Device from that baseline: **POCO M7 Pro 5G** (Dimensity 7025, GPU **PowerVR B-Series
BXM-8-256**, Vulkan 1.4, Android 16). Full inventory and Vulkan capabilities:
`docs/device-poco-m7-pro-5g.md`.

No runtime decision has been made. Candidates to evaluate for a stable combination of:

| Layer | Candidates |
|---|---|
| Container | Winlator or equivalent |
| Wine | build ARM64/ARM64EC |
| x86-64→ARM64 | FEX-Emu, Box64 |
| D3D→Vulkan | DXVK (+ VKD3D if applicable) |
| Vulkan driver | **proprietary PowerVR** (no Turnip: Adreno-only) |

Device-derived constraint: the Vulkan driver is the vendor's (PowerVR), with no
free alternative; the risk is concentrated in DXVK stability and performance on it.

**Selected runtime (historical Phase 2 baseline)**: **official Winlator v11.2** (`com.winlator`,
versionCode 33, minSdk 26) — the lightest APK in its family (150 MB) and the only one
with a driver path for non-Adreno GPUs (*System Driver*/Vortek/Gladio). Installed and
verified to start on the POCO M7 Pro 5G. Initial container configuration: Graphics
Driver = *System Driver* (PowerVR Vulkan), DX Wrapper = DXVK, emulator = Box64.

Testing will use the real `WowB.exe` (not `Wow.exe`), targeting ARM64. No custom
runtime will be written until Phase 2 gets a go decision.

## 5. Licenses and redistribution

| Component | License | Packagable in APK? |
|---|---|---|
| `wow-classic-launcher` | MIT | Yes (with attribution) |
| `cascette-py` | MIT / Apache-2.0 | Yes (with attribution) |
| WoW client / CASC / `WowB.exe` | Blizzard proprietary | **No** — downloaded on the device |
| Wine / FEX / Box64 / DXVK | LGPL/MIT/etc. | Yes, subject to their licenses |
| Android runtime (Winlator) | GPL if applicable | Review when selecting |

The APK should be lightweight: download the runtime and client to the device rather
than packaging them.

## 6. Architecture decisions

| ID | Decision | Reason |
|---|---|---|
| **D-001** | TACT engine = `cascette-py` via a git URL pinned to a commit | Not on PyPI; implements the `agent.exe` flow and correctly resolves `wow_classic_beta` |
| **D-002** | Authoritative product = `wow_classic_beta`; use `cascette-py` from an **owned repository** (`apazosg/cascette-py`, fork with the enum added), dependency via a git URL pinned to a commit | The `cdns` for the `wow` family is identical (`tpr/wow`), but naming the correct product avoids silent debt. The fork is maintained in our own repository; **no contributions are made to third-party repositories** |
| **D-003** | Python + `uv` for the provisioner | `uv` is already installed; no `pip3` on the host |
| **D-004** | Self-contained installation in one directory (`WowB.exe` + `Data/` + metadata at the root) | The launcher starts `WowB.exe` from the game directory |
| **D-005** | Validate Phase 1 on Linux without Android | Decouples provisioning uncertainty from runtime uncertainty |

## 7. Provisioning: `forever-client`

Phase 1 tool (`US-001`). Subcommands: `resolve`, `install`, `verify`, `repair`. It
creates the `WowB.exe` + `Data/` + metadata layout and supports a minimal
installation (`--loose-only`) to unblock Phase 2 with a small download.

## 8. Repository organization and upstream compatibility

Explicit project goal: **be the Android version of the Classic Forever server**,
compatible with `defexnicolas/wow-classic-launcher` **and its future versions**.

Division of repositories (principle: our work in one monorepo; external code we need
to modify in our own repository):

| Repository | Role | Relationship |
|---|---|---|
| `apazosg/realmdeck-android` | Project monorepo: host tooling in `src/forever_client`, the `forever/` contract, diagnostic `android/` project, functional launcher under `scripts/arm64-prototype/app/`, specs and docs | — |
| `apazosg/cascette-py` | Owned repository (fork) of the TACT engine, with `wow_classic_beta` added | Dependency via a git URL pinned to a commit |
| `apazosg/wow-classic-launcher` | Owned repository (read-only mirror) to **follow the launcher contract** | Not compiled or vendored |

**Policy**: do not open PRs or merge into third-party repositories. All work lives
in our repositories; upstream launcher and `cascette-py` are only **read** (and the
`cascette-py` change is maintained in our repository, not sent upstream).

A vendored copy of the launcher would create debt: we do not reuse its *code* (WPF +
Python script); we reproduce its *behavior*. **Following ≠ copying.**

### Future-version compatibility: versioned contract + drift check

The patch contract has **already diverged** between platforms (padding `7F 00 00` on
Windows versus `00 00 00` under Wine/Proton, measured 2026-09-27). Therefore, the
contract is not hardcoded in multiple places: it lives in a versioned module and is
checked against upstream.

- `forever/contract.json` — portal, ports, 12 keys, `TARGET_GROUP`, `ENTRY_SIZE`,
  flags, **platform-specific padding rule**, supported builds, `status.json` URL, and
  the validated upstream commit.
- `scripts/contract-drift-check` — extracts launcher constants at a given commit/tag
  and **fails if they differ** from our contract. A new build, key, or portal change
  will not silently break things.

## 9. Open risks

- **Phase 2** (starting on Android) is the project's real gate; no Phase 1 decision
  should become coupled to it.
- **CDN build change**: the launcher only supports `69913/69977/70009`; a new build
  would break the flow until the launcher supports it.
- **Actual size** of the `Windows`+`x86_64`+`enUS` subset: not yet measured.
