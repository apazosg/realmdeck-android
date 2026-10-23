# Fase 2 en Android: `WowB.exe` sobre Winlator — estado y evidencia

> Última actualización: 2026-09-27. Dispositivo: POCO M7 Pro 5G (Dimensity 7025,
> PowerVR BXM-8-256, Vulkan 1.4 del vendor, Android 16). Runtime: Winlator oficial 11.2.

## Resumen en una frase

La pila gráfica **funciona** (lo prueba `TestD3D.exe`), `WowB.exe` **arranca de verdad**,
pero se **suicida a los ~6,8 s con `exit_code=1` sin escribir absolutamente nada** —
y eso ocurre *antes* de lo más temprano que se observa en Wine nativo (`Logs/cpu.log`).

## Lo que sí funciona (verificado)

| Pieza | Evidencia |
|---|---|
| Contenedor provisionado sin UI | `.container` escrito vía `run-as`; `ContainerManager` lo lista |
| Escritorio Wine | Capturas con `wfm.exe`, C:/D:/X:/Z: y barra de tareas |
| XServer + GL del vendor | Ventana Windows renderizada en la pantalla del móvil |
| **D3D sobre PowerVR** | `Z:\opt\apps\TestD3D.exe` (probador de Winlator) **funciona bien** |
| `WowB.exe` ejecuta | Proceso propio, `ntdll.dll` cargado, RSS hasta ~500 MB |
| Acceso directo | `.desktop` en `.../users/xuser/Desktop/`; lanzar = pulsación larga en ▶ |

Conclusión: **la incógnita gráfica de la Fase 2 está respondida que sí.** El fallo de
`WowB.exe` no es de GPU.

## El fallo, con números

Sonda propia `C:\wow\diag\WoWDiag.exe` (MinGW x86-64) que lanza el juego con cwd correcto,
stdout/stderr redirigidos a un fichero y `WaitForSingleObject`:

```
=== launch OK ===   child_pid=232
=== result ===      exit_code=1 (0x00000001) elapsed_ms=6762
```

- Reproducible: **6762 / 6843 / 6739 / 6368 / 6552 / 6620 / 6629 ms**, siempre `exit_code=1`.
- **Cero bytes** en stdout/stderr del juego.
- **Nunca crea** `Logs/`, `WTF/`, `Errors/`, `Cache/`, `CrashDumps/`. El directorio del juego
  conserva sus mtimes originales.
- Sin tombstone del juego en logcat (sin crash nativo).

CPUID que expone Box64 0.4.4 en este entorno:

```
vendor: GenuineIntel   family=6 model=70 stepping=1
branding: Box64 v0.4.4 on Unknown CPU @2.50 GHz
features: cmpxchg16b sse sse2 sse3 ssse3 sse41 sse42 avx avx2 aesni clmul
          pclmulqdq sha rdrand fma bmi1 bmi2 movbe osxsave adx lm
xcr0: 0x7   avx_usable=1   avx2_usable=1   lahf64=0   numProcs=8
RtlGetVersion: 10.0.19045
```

## Descartado por experimento (no es la causa)

| Hipótesis | Prueba | Resultado |
|---|---|---|
| Dynarec / JIT | `[WowB.exe] BOX64_DYNAREC=0` en `config.box64rc` | idéntico (6843 ms, exit 1) |
| Preset Box64 agresivo | `box64Preset` PERFORMANCE → COMPATIBILITY | idéntico |
| Servicios de arranque | `startupSelection` 1 (Essential) → 2 (Aggressive) | idéntico (6739 ms) |
| Wrapper OpenGL estorbando | `graphicsDriver` `vortek,gladio` → `vortek` | irrelevante: muere pre-gráfico |
| Sin permiso de escritura | La sonda escribe `diag.txt` en el mismo prefijo | el prefijo es escribible |
| DLLs ausentes | 750 DLLs en el Wine del contenedor; presentes `d3d12/dxgi/d3d11/d3d10core/d3d9/vcruntime140/msvcp140/ucrtbase/bcrypt/wintrust` | no falta ninguna crítica |
| Falta de AVX/AVX2 | CPUID de la sonda | Box64 las expone como usables |

## Contraste decisivo: Wine nativo x86-64 (hermes)

El **mismo** `WowB.exe`, en Wine 10.0 x86-64 nativo (sin Box64), `WINEPREFIX=~/wine-lab/prefix`:

- **No muere**: corre indefinidamente (matado por `timeout` a 120 s).
- Inicializa red **MsQuic** (`status=0x0`), enumera CPUs.
- **Crea `Logs/` (11 ficheros, incluido `cpu.log`) y `WTF/` en menos de 1 s.**
- En 60 s no carga ninguna DLL gráfica (fase pre-gráfica).

⇒ El binario no tiene ningún defecto de arranque. **El fallo es específico del entorno
Winlator (Wine custom del rootfs + Box64), y ocurre antes de lo más temprano que el nativo
llega a registrar.**

## El problema de fondo: no hay observabilidad

En Winlator, la salida de Wine/Box64 es **inalcanzable**:

- La app no redirige stdout/stderr del contenedor (verificado en `XServerDisplayActivity`).
- Los hijos de Wine heredan los fds del `wineserver`, no del lanzador ⇒ setear `WINEDEBUG`
  y hacer `dup2` de los fds Unix desde un wrapper **no produce traza**.
- Nada de Wine/Box64 aparece en logcat.

Sin traza, seguir probando variables es *tirar dados*: es la razón para cambiar de estrategia.

## Hipótesis restantes (sin traza, todas plausibles)

1. **Wine alternativo** (10.10-WoW64 vs el del rootfs) y **Box64 0.3.x vs 0.4.4**: descartaría
   regresión del runtime (cf. issues upstream #981/#1063 de Vortek).
2. **Probe de CPUID/features**: el cliente actúa muy pronto sobre CPUID; un shim que finja
   un CPU más conservador es un experimento barato **y ya instrumentable en nativo**.
3. **Imports diferidos** (`MsQuic`/Aftermath/`WowB_loader.dll`) resueltos de forma distinta.
4. **Excepción temprana absorbida** por el handler de Blizzard
   (`WerRegisterRuntimeExceptionModule`) que mata el proceso sin log.

## Próximos pasos, con coste y valor

| # | Acción | Coste | Valor |
|---|---|---|---|
| 1 | **Reproducir el fallo en el laboratorio nativo** bajando Box64 al escenario Linux | bajo | **alto**: convierte el problema en depurable con strace/WINEDEBUG |
| 2 | Probar **FEX-Emu** en lugar de Box64 en el contenedor | medio | medio-alto: FEX cubre AVX/AVX2 mucho mejor |
| 3 | Envolver el arranque con una DLL de logging temprano (hook de `ExitProcess`/`WerRegister`) | medio | alto: **da la traza que hoy no existe** en el móvil |
| 4 | Probar Wine 10.10-WoW64 (`.wcp` externo) y Box64 0.3.x | medio | medio: descarta regresión del runtime embebido |
| 5 | Instrumentar Winlator (build debug / fork mínimo) | alto | alto pero caro; última bala |

## Cómo reproducir (host → dispositivo)

```bash
ADB=~/.local/opt/platform-tools/adb; D="-s MZAYW4SCFEJBTCLV"
# Desplegar el cliente (snapshot loose-only)
$ADB $D shell "run-as com.winlator sh -c 'cd files/rootfs/home/xuser-0/.wine/drive_c && mkdir -p wow && tar xf - -C wow'" \
  < /home/adrian/wowforever-artifacts/wow_client_lite.tar
# Lanzar el acceso directo (pulsación larga en el botón ▶ de la tarjeta)
$ADB $D shell am start -n com.winlator/.MainActivity
$ADB $D shell input tap 79 188        # menú lateral
$ADB $D shell input tap 394 439       # Shortcuts
$ADB $D shell input swipe 207 699 207 699 200
# Leer el diagnóstico de la sonda
$ADB $D shell "run-as com.winlator cat files/rootfs/home/xuser-0/.wine/drive_c/wow/diag/diag.txt"
```
