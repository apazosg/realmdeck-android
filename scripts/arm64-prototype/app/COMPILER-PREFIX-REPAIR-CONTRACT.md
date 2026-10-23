# Reparación puntual del compilador del prefijo — contrato

Plan aprobado y cerrado: `.agents/planning/done/wine-aa64-compiler-prefix-plan.md`.
Escritor nuevo solo helper/test, parent integra hook, flags, versión/build/ADB.
No driver/DXVK/Wine/otro DLL/registro/client/key/renderer/override nuevo.

## API interna fijada

```kotlin
package app.gamenative
object ForeverCompilerPrefixRepair {
    internal fun repair(context: Context, container: Container, env: EnvVars,
        runId: String, compiler: ForeverBuiltinShaderCompiler.Selection): Receipt
    internal fun verifyBeforeSpawn(context: Context, runId: String,
        winePrefix: String): Unit
    internal fun rollback(context: Context, runId: String): Receipt
}
```

Receipt tiene strings prefixPath, targetPath, backupPath, transactionPath,
originalSha256, installedSha256 y Boolean reused. No Intent público para rollback
o rutas. repair es called ONLY después de `ForeverPanvkWine.validateLaunchScope`
y del verify builtin existentes, con nuevo flag congelado `COMPILER_PREFIX_REPAIR`.
False/nonmanaged/nonprobe mantiene byte exacto el recorrido anterior.

Prefab Wine autorizado: canonical `container.rootDir/.wine` debe ser EXACTAMENTE
`filesDir/imagefs_shared/home/xuser-<container.id>/.wine`. La home del ImageFS y
home/xuser pueden ser symlinks legítimos únicamente a ese shared-home/container;
`env.WINEPREFIX` debe resolver exactamente al prefix seleccionado. Id no admite
separadores/traversal. Los leaf .wine/drive_c/windows/system32 y compiler no son
symlinks ni root ajeno. Único target `drive_c/windows/system32/d3dcompiler_47.dll`.

Source fijo `imagefs_shared/proton/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/
d3dcompiler_47.dll`: 1.114.112 bytes SHA
`c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add`/AA64/PE32+.
Target original autorizado 4.453.888 bytes SHA
`06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52`/x64/PE32+.
Nunca aceptar manifest self-hash como autoridad de pins ni sobrescribir contenido
distinto. Ya AA64 exacto se revalida sin otro reemplazo.

## Transacción

Backup/receipt propios nuevos dentro de `files/forever/controls/compiler-prefix/
<RUN>` con CREATE_NEW/NOFOLLOW, stream64KiB/identity/pin/owner/PE/fsync antes de
commit; conservar datos originales y metadata (mode/uid/filekey/mtime/hash).
Preparar AA64 temporal exclusivo MISMOsystem32 y fsync/hash/PE. Revalidar original
identidad+hash inmediatamente antes del atomic move; atomic rename requerido,
sin fallback noatómico ni hardlinks (denegados Android). Solo se reemplaza el
original conocido; conservar backup/temporales propios ante IO/move/cancel.
Journal primero intento con rutas/pins, después installed receipt únicamente
postverify del target. Nuevo receipt `transaction.json` <=32KiB con schema1,
RUN/container/prefix/known hashes/estado. Lectura inválida jamás autoriza nuevoexec.

Comprobar active/current RUN en shared store y todavía `launchAttempted=false`;
IO pesado fuera monitor, revalidación+commit corto bajo store monitor. Cancelación
antes del commit no lo hace; cancelación posterior nunca arranca Wine (observer
existente uniqueRUN/claimedtoken/cancel/argv). `verifyBeforeSpawn` solo verifica
receipt/RUN/current/prefix destino exactos + SHA/PE/identity actual, no escribe,
antes de `markLaunchAttempt`. Errores veto/durable reason; no excepción crea root.

Rollback solo receipt/backuppin originales y target AA64 conocido: verificar
quiescencia según política propia (sin intento Wine o boot-boundary validado y sin
handles propios). No force-stop como sustituto de prueba. Temporary+atomic restore
requiere verificar destino/re-read backup; contenido desconocido = STOP. Normal
helper no llama rollback automático tras spawn. No borrar original/backup/journal
o recibos, ni reparar más componentes. Test seam interna puede pins/IO sintéticos,
no expuesta en producción; fixtures no ejecutan PE.
