# Registro de pruebas y hallazgos — runtime Android (Fase 2)

> Bitácora acumulativa. Corte actual:2026-10-21; el baseline de septiembre se
> conserva separado debajo.
> Objetivo: dejar registrado **qué se probó, con qué, qué salió y qué falta** para
> poder retomar sin depender de la memoria de la sesión.

## Resultado posterior — ARM64 real y entrada al juego (C127)

- Cliente `WowB-ARM64.exe`1.60.1.70450/enUS en USB, app funcional
  `com.wowforever.arm64probe`. Dispositivo xaga/22041216G, Android14,
  Mali-G610MC6; no es el POCO M7/PowerVR de la matriz histórica.
- Proton11.0-90624 ARM64EC + DXVK2.4.1-wow-aarch64-test/PanVK/noGL, D3D11.
  `gx.log` acredita creación Dx11 correcta; después el usuario confirma Login y
  entrada al juego Classic Forever, sin glitches gráficos observados.
- Para llegar ahí se corrigió el formato de índices CASC y la clave pública del
  grupo8 con un escritor acotado en el hijo propio, sin root, DLL externa,
  parche del EXE ni bypass TLS. No se cambió driver para lograr ese Login.
- **Límite:** no hay FPS/audio/controles ni duración30–60min medidos. Resultado
  positivo del ensayo, no certificación de todas las GPU/builds ni del producto.
- C128 adelanta el parche: revisión cerrada,32 tests JVM, compilación y auditoría
  correctos; aún sin instalar ni prueba física. No interrumpir la partida actual.

Evidencia y versiones: [producto](launcher-app-status.md),
[compatibilidad](arm64-forever-connection-progress.md).

## Historial de septiembre — objetivo y alcance

Conseguir que el cliente `WowB.exe` (1.60.1.70009, x86-64) arranque de forma estable
en el móvil y sirva de base a un APK que automatice todo. Las pruebas de
compatibilidad se hacen **en el dispositivo físico**; el PC solo prepara y analiza.

## Estados usados

| Estado | Significado |
|---|---|
| **PASA** | Resultado observado y reproducible |
| **FALLA** | Fallo reproducible con evidencia (exit code / excepción) |
| **BLOQUEADO** | No ejecutado por falta de conexión, UI o tiempo; se puede retomar |
| **NO EJECUTADO** | Previsto, sin intentar |
| **REFERENCIA** | Analizado solo por documentación/código, sin ejecutar |

## Dispositivo del baseline de septiembre

POCO M7 Pro 5G (`2409FPCC4G`, `citrine`) · Android 16 (SDK 36) · HyperOS
`OS3.0.302.0.WOQMIXM` · arm64-v8a · MediaTek Dimensity 7025 · GPU **PowerVR
B-Series BXM-8-256** · Vulkan **1.4** del vendor · ~7,3 GiB RAM · **sin root** ·
129 GB libres. Detalle: `docs/device-poco-m7-pro-5g.md`.

## Entornos probados

| ID | App | Traductor | Wine | Gráficos | Notas |
|---|---|---|---|---|---|
| **A** | Winlator oficial 11.2 (`com.winlator`) | Box64 0.4.4 | Wine custom del rootfs | Vortek + DXVK 1.10.3 | Contenedor provisionado por ADB (`run-as`) |
| **B** | FrontMage (`com.winlator.llm` 7.1/16) | **FEX 2508** (`HODLL=libwow64fex.dll`) | **proton-10-arm64ec** | VirGL 23.1.9 + WineD3D | Prefijo `Container-1`, imagefs 21 |
| **C** | Ludashi v4.1 (`com.ludashi.benchmark`) | FEX 2601 / Box64 0.4.2 | Proton 9 ARM64EC | System + wrapper + Zink | APK verificado, **no instalado** |
| **L** | Wine 10.0 nativo en hermes | ninguno (x86-64) | Wine 10.0 + DXVK 2.7.1 | RADV (iGPU) | Laboratorio de control |

## Matriz de pruebas

| # | Prueba | Entorno | Estado | Resultado |
|---|---|---|---|---|
| 1 | Escritorio Wine + XServer | A | **PASA** | `wfm.exe`, unidades C:/D:/X:/Z: |
| 2 | `TestD3D.exe` (probador D3D de Winlator) | A | **PASA** | D3D sobre PowerVR funciona |
| 3 | Control `Hello.exe` (stdout/stderr/escritura) | A, B | **PASA** | `HELLO-EXIT=0`; la redirección y la escritura funcionan |
| 4 | `WowB.exe` | A | **FALLA** | `exit=1` a los ~6,8 s, **sin escribir nada** (ni `Logs/` ni `WTF/`) |
| 5 | `WowB.exe` | B | **FALLA** | `exit=-1073741819` = `0xC0000005` (violación de acceso de **ejecución**) |
| 6 | `WowB.exe` | L | **PASA (parcial)** | Corre indefinidamente; crea `Logs/`+`WTF/` en <1 s. Fase pre-gráfica |
| 7 | Descartar causas en A (dynarec, presets, servicios, gladio, permisos, DLLs, AVX) | A | **PASA** | Ninguna cambia el resultado |
| 8 | `ApiProbe.exe` (control de APIs) | B | **PASA** | `LoadLibraryExW` del API-set + `InitializeCriticalSectionEx` OK |
| 9 | `SlotProbe.exe` (leer slot del loader sin escribir) | B | **BLOQUEADO** | Desplegado en `C:\wow\diag\`; no ejecutado (sin conexión) |
| 9b | `DynCodeProbe.exe` (ejecutar código generado en memoria) | B | **NO EJECUTADO** | Fuente lista en `scripts/diagnostics/probes/`; discrimina fallo de runtime de fallo del juego |
| 10 | APK Ludashi v4.1: identidad y componentes | host | **PASA** | Ver tabla siguiente |
| 11 | Instalar y probar Ludashi | C | **NO EJECUTADO** | Espera verificación de colisión de paquete |
| 12 | Pocket Realm / wow-mobile | host | **REFERENCIA** | No sirven para nuestro cliente (ver abajo) |

## Hallazgos

### H1 — La pila gráfica funciona; el fallo de WoW es previo a los gráficos
`TestD3D` y el escritorio Wine funcionan en el entorno A. `WowB.exe` muere antes de
crear sus logs, así que **no es un problema de GPU** y no se debe seguir probando
drivers/presets.

### H2 — El binario del juego está bien
El mismo `WowB.exe`, en Wine nativo x86-64 (entorno L), corre y crea `Logs/`+`WTF/`
en menos de un segundo. El problema es del **entorno**, no del cliente.

### H3 — En FrontMage/FEX el fallo cambia de forma
Pasa de un `exit=1` "silencioso" a una **violación de acceso al ejecutar**, con
retorno en `WowB_loader.dll + 0x203E6AB`:

- Sitio de llamada: RVA `0x203E6A5`, bytes `ff 15 8d 20 07 00` = `call [rip+0x7208d]`.
- Slot llamado: RVA `0x20B0738`.
- Destino: dentro de una vista **anónima ejecutable** (`0x1a20000..0x1b1ffff`), con
  una vista gemela RW al lado. **Corrección**: no es "dirección no mapeada" como se
  dijo antes.

### H4 — La rutina del loader hace una carga de API-set
Análisis estático del loader: la función en `0x203E620` carga la cadena UTF-16
`api-ms-win-core-synch-l1-2-0` y busca `InitializeCriticalSectionEx`. La llamada del
fallo encaja con `LoadLibraryExW(RCX=cadena, RDX=0, R8=0x800 = SEARCH_SYSTEM32)`, con
reintento a `flags=0` si falla. **No está confirmado dinámicamente**; la atribución
previa a `GetFileVersionInfoA` se descartó por el flujo de datos.

### H5 — En FrontMage esas APIs funcionan fuera del juego
`ApiProbe` cargó el API-set con `flags=0x800` y con `0`, resolvió a `kernelbase.dll`,
obtuvo direcciones ejecutables de `LoadLibraryExW` e `InitializeCriticalSectionEx` y
creó/destruyó la sección crítica. **Se descarta que esas APIs falten o fallen siempre.**
No demuestra que el loader las resuelva igual.

### H6 — No hay antiemulación demostrada
Se midió `LAHF/SAHF` como soportado (el valor `ECX=0x121` tiene el bit 0 activo; una
sonda anterior leyó el bit equivocado). El único delta real de CPUID es cosmético:
Box64/BFEX se anuncian como `GenuineIntel` con marca que incluye el nombre del
traductor. **No hay base para empezar falsificando la CPU.**

### H7 — Sin instrumentación no hay diagnóstico
La salida de Wine/Box64 no es alcanzable en Winlator; en FrontMage sí, porque escribe
`wine.log` persistente. Por eso el entorno B es el que ha permitido avanzar.

### H8 — Faltan variables de FEX que otros forks sí ponen
Nuestro entorno de FrontMage define `FEX_TSOENABLED`, `FEX_X87REDUCEDPRECISION=1`,
`FEX_MULTIBLOCK`… pero **no** define `FEX_SMC_CHECKS=mtrack` ni
`FEX_HIDEHYPERVISORBIT=1`, que los forks con más rodaje (CMOD) sí establecen por
defecto. `FEX_SMC_CHECKS` gobierna la detección de **código automodificable**, y
nuestro fallo es una ejecución dentro de una región que antes era escribible:
encaja. Es una hipótesis con base documental, **no** una causa demostrada, y da
lugar al experimento **E1** (ver el plan). También hay un delta de precisión x87
(`1` aquí, `0` en CMOD).

### H9 — Hay instrumentación lista para el siguiente intento
`DynCodeProbe.exe` (nuevo, en `scripts/diagnostics/probes/`) comprueba si el
runtime ejecuta código x86-64 generado en memoria —constante, argumentos, salto,
llamada a una API real y ejecución tras liberar/reasignar—. Si falla, tenemos un
caso reproducible pequeño **sin depender del juego**, que es lo que hoy nos falta
para saber si el problema es del runtime o del cliente.

## Candidatos evaluados (host, sin instalar)

| Candidato | Identidad verificada | Veredicto |
|---|---|---|
| **Ludashi v4.1** | `com.ludashi.benchmark`, 551.444.161 B, sha256 `c7c570…e24de64`, firma v3 | **Candidato a ensayo**: FEX 2601 + Proton 9 ARM64EC (distinto de B). Sin VirGL en su fuente; ruta System+wrapper+Zink |
| Winlator-CMOD | `com.winlator.cmod` | **Archivado**; prioridad baja |
| Pocket Realm | GPL-3.0 | **Solo referencia** de launcher/supervisión. Exige WoW 1.12.1 build 5875, PE32 i386 |
| wow-mobile | — | **Solo referencia** de automatización. WoW 3.3.5a build 12340 |

Aprendizaje transferible de Pocket Realm: supervisión de proceso, captura persistente
de logs, distinción entre "proceso vivo" y "frames presentados", y lanzamiento sin
exponer el escritorio de Wine.

## Comandos reproducibles

```bash
ADB=~/.local/opt/platform-tools/adb
# ADB por WiFi/Tailscale (emparejado previamente); el puerto cambia al reactivar
$ADB connect <ip>:<puerto>
$ADB -s <ip>:<puerto> devices -l

# Runner de captura (no instala, no hace clic, no mata procesos)
~/.local/bin/uv run python scripts/diagnostics/android_diagnostics.py \
  --serial <serial> --package com.winlator.llm --output-dir /home/adrian/wowforever-artifacts/android-diagnostics

# Recoger resultado de una sonda desplegada
$ADB -s <serial> shell "run-as com.winlator.llm cat files/imagefs/home/xuser-1/.wine/drive_c/wow/diag/api-probe.txt"

# Pruebas offline del repo
~/.local/bin/uv run pytest tests/ -q     # 8 passed
```

## Pendiente (retomar con el móvil)

1. **T9** — ejecutar `SlotProbe.exe` en FrontMage y leer el slot real
   (`C:\wow\diag\slot-probe.txt`). Sonda ya desplegada (sha256 `2c50d94c…`).
2. **T11** — comprobar que `com.ludashi.benchmark` no colisiona, instalar el APK y
   repetir la batería corta (Hello → ApiProbe → WowB) en prefijo nuevo.
3. **T2** — si T9 apunta a la ruta de código dinámico, control mínimo x64 que genere
   código propio, lo haga RW→RX y lo ejecute.
4. **T4** — al superar el arranque: separar falta de datos CASC de fallo gráfico.

## Artefactos

- `/home/adrian/wowforever-artifacts/frontmage-ab/` — APK FrontMage, fuentes, sondas
  (`api-probe/`, `slot-probe/`), logs de sesión y `session-20260927/`.
- `/home/adrian/wowforever-artifacts/runtime-candidates/ludashi-v4.1/` — APK, metadata,
  hashes y reporte.
- `/home/adrian/wowforever-artifacts/android-diagnostics/` — salidas del runner.
- `/home/adrian/wowforever-artifacts/wow_client_lite.tar` — snapshot limpio (1,1 GB).
- `/home/adrian/wowforever-artifacts/android-runtime-ab-20260927-step1/` — baseline A.

## Notas para retomar

- **Nunca** tarar `client/` del repo en vivo (la instalación completa quedó incompleta
  por OOM de cascette); usar el snapshot.
- Los artefactos grandes **nunca** en `/tmp` (es un tmpfs de 15 GB y ya nos mordió).
- `run-as com.winlator[.llm]` funciona (apps *debuggable*): permite leer/escribir sus
  ficheros, **pero no ejecutar Wine** (SELinux).
- Al cambiar de entorno, no mezclar Wine/puentes entre forks.
