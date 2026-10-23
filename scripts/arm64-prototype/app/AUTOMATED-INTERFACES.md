# Automated launcher integration interfaces

Scope approved in `.agents/planning/automated-arm64-launcher-plan.md` and US-003.
These are internal implementation interfaces, not a new public client contract.

## Client provisioning (package `app.gamenative`)

`ForeverClientProvisioner` owns download, verification and CASC materialization.

```kotlin
object ForeverClientProvisioner {
    data class Plan(val downloadBytes: Long, val workspaceBytes: Long)
    fun plan(context: android.content.Context): Plan
    suspend fun ensure(
        context: android.content.Context,
        installDir: java.io.File,
        cancelled: () -> Boolean,
        progress: (String) -> Unit,
    ): java.io.File // returns verified WowB-ARM64.exe or throws; never false success
}
```

- Asset: `forever/client-catalog.json`, generated outside the repo by
  `scripts/arm64-prototype/client/catalog.py` (schema v1).
- Canonical contract asset: `forever/contract.json`, copied from
  `forever/contract.json`, not independently edited.
- Shared expected installation directory: a dedicated app-managed custom-game
  directory, one client across runs (not another folder/download each time).
- Files owned by provisioner builder: `ForeverClientProvisioner.kt`,
  `ForeverBlte.kt`, `ForeverCasc.kt` and corresponding tests. The app overlay
  applicator copies these files; it is owned by the launcher integration builder.
- `ensure` runs off UI; callbacks do not assume main thread. Cancellation throws,
  with identity/generation checked again before the app performs any launch.
- Structural provisioning success is not render/login, nor complete game data.
- Transfer cap: 1 GiB catalog; current 748,157,712 bytes. Host catalog generation
  is cache-only. UI must show transfer, working space and runtime's separate
  network/storage requirements before user confirmation.

## Native patcher (package `com.wowforever.launcher`)

Use `scripts/arm64-prototype/native/ForeverPatchBridge.kt`, JNI library
`forever_patcher_jni`; exact JSON API and limits in the adjacent README.
Discovery requires the absolute verified client executable path. Application
first requests read-only `inspectOwnedGame(..., apply=false)`; only a complete
`VALIDATED` response permits applying and maintaining the same process identity.
`INCOMPLETE`, `REJECTED` and `ERROR` are not permission to patch or a no-store proof.
Both discovery and passes run off UI and are bound to the current run generation.
No native callback owns lifecycle or kills processes.

## Run supervision

State journal precedes expensive work and every spawn. Suggested stage names:
`PREFLIGHT`, `RUNTIME`, `CLIENT`, `CONFIGURATION`, `LAUNCHING`, `RUNNING`,
`STOPPING`; terminal states `FAILED`, `CANCELLED`, `INTERRUPTED`, `EXITED`.
Only the active generation may launch or change its state. Runtime/process,
render and Forever readiness are separate fields. Missing game logs still leave
the app's own journal, spawn exception/exit and historical exit evidence.

The app integration owns orchestration, GameNative hooks, UI, persistence,
bounded process stdout/stderr and SAF frozen-run export. No generic catalog UI
or manual probe sequence in the main path; GameNative remains the internal
runtime implementation. Preserve existing testbench and old report data.
