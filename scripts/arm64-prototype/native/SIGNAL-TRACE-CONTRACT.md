# Diagnóstico SIGINT — contrato interno 1

Plan aprobado: `.agents/planning/android-host-sigint-plan.md`. No cambia el
patcher, grupos/sesiones, Wine, driver ni la política de reinicio tras spawn.

**Estado del candidato code 29:** en Android, `armSignalTrace` responde
`UNSUPPORTED/ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED` antes de locks, `sigaction`,
archivos o publicación de contexto. No se ha demostrado propiedad exclusiva de
la disposición ni compatibilidad libsigchain: el handler descrito abajo solo se
ejercita en hijos sintéticos Linux. Su formato/decoder están comprobados, pero no
son prueba de captura de emisor en el teléfono. No se ignora ni cambia SIGINT.

## API

En `com.wowforever.launcher.ForeverPatchBridge`, usando la misma biblioteca JNI:

```kotlin
@JvmStatic external fun armSignalTrace(
    outputPath: String, runId: String, bootId: String,
    expectedPid: Int, expectedStartTicks: Long,
): String
@JvmStatic external fun signalTraceStatus(): String
```

Resultado JSON schema 1: `status` = `ARMED`, `UNSUPPORTED` o `ERROR`; `reason`,
`pid`, `startTicks`, `runId`, `bootId`, `path` y `previousDisposition` cuando
estén comprobados. Status consulta también si la disposición instalada sigue
siendo la nuestra; no reinstala ni señala nada. Fallos no son evidencia de emisor.

- El caller crea/valida el directorio privado y pasa el archivo exclusivo
  `<runId>-sigint.bin` bajo `files/forever/runs/`. Native verifica inputs,
  identidad propia, boot y no-follow/regularidad/propietario de su destino.
- Se arma justo antes del exec gestionado, después del journal durable de intento.
  Un fallo/UNSUPPORTED se registra sin fingir cobertura; no se ignora ninguna señal.
- Solo se instala sobre `SIG_DFL` con flags compatibles. `SIG_IGN` y handlers
  personalizados se dejan intactos con UNSUPPORTED, conservando su semántica.
- Una sola vinculación por proceso, idem para el mismo RUN/destino; otra identidad
  queda UNSUPPORTED. FD CLOEXEC retenido hasta muerte del host (sin API disarm/close).
  Reintentos previos al exec no reciben falsamente cobertura de otro RUN.
- Handler: stack fijo, operaciones async-signal-safe, sin JNI/alloc/locks/formato
  JSON. Como máximo cuatro eventos. Tras la captura restaura SIG_DFL y entrega
  SIGINT al mismo hilo para mantener terminación **por señal 2**, no `_exit(130)`.
  No señales a grupos/hijos/terceros. Forks no escriben evidencia atribuida al host.

## Formato binario little-endian

Todo byte reservado es cero. Escritura del header antes de instalar el handler,
sin reemplazar archivos existentes. Lectura máximo 512 bytes (256 + 4 × 64).

Header 256 bytes:

| Offset | Campo |
|---|---|
| 0 | magic ASCII `WFSIG001` (8) |
| 8 / 12 | u32 version=1 / headerBytes=256 |
| 16 / 20 | i32 hostPid / u32 hostUid |
| 24 | u64 hostStartTicks |
| 32 / 40 | u64 armedRealtimeMs / armedMonotonicNanos |
| 48 / 52 | i32 hostPgid / i32 hostSid (metadata, no containment) |
| 56 / 60 | u32 runIdBytes / bootIdBytes |
| 64 | ASCII runId, máximo 100, campo 128 bytes con padding cero |
| 192 | ASCII bootId UUID de 36 bytes |
| 228 | 28 bytes reservados cero |

Evento 64 bytes:

| Offset | Campo |
|---|---|
| 0 | magic ASCII `WFSIGEV1` (8) |
| 8 / 12 | u32 version=1 / eventBytes=64 |
| 16 / 20 | i32 signal / i32 si_code |
| 24 / 28 | i32 receivingPid / i32 receivingTid |
| 32 / 36 | i32 senderPid / u32 senderUid |
| 40 / 48 | u64 realtimeMs / monotonicNanos |
| 56 / 60 | u32 flags / reservado cero |

Flags: 1=siginfo presente; 2=emisor válido para SI_USER/SI_QUEUE/SI_TKILL y PID>0;
4=error de reloj (tiempos no atribuibles). Otros flags se rechazan. Kernel/origen
desconocido no convierte los campos de la unión en una identidad válida.

## Importación y evidencia

El lector Kotlin puro valida header/tamaños/padding/ASCII/flags, host PID/ticks,
RUN y boot, tiempos posteriores al armado y anteriores a lectura; rechaza
registros viejos, truncados o sobredimensionados. Solo el evento completo válido
se atribuye a su host. Si falta la señal, se informa `NOT_CAPTURED`/`INVALID`,
no se reconstruye el emisor a partir de ApplicationExitInfo. PGID/SID compartidos
no prueban que la señal fuera enviada al grupo. No modifica quiescencia/cancelación.

Exportar archivo binario bounded + JSON decodificado dentro del ZIP del RUN
seleccionado, también al recuperar una corrida interrumpida. Inicio y cola de
stdout/stderr viven en snapshots privados distintos, límite constante y flush
periódico mientras drena; no dependen del callback final ni del presupuesto JSONL.
