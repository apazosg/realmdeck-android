# Estado y pendientes (roadmap)

## Prioridad vigente —2026-10-21

**C127 instalada: Login y entrada al juego confirmados por el usuario**, sin
glitches gráficos observados en ese ensayo. Cliente70450 ARM64/enUS en USB;
publicación C117 completada, CASC corregido y compatibilidad grupo8 resuelta.
No equivale todavía a acreditar una sesión30–60min ni un producto one-click.

1. C128 ya compilada/auditada,32 tests JVM correctos: búsqueda/aplicación antes
   de Enter. Instalar solo cuando el usuario cierre su partida; prueba
   de APPLIED antes del primer Login pendiente.
2. Medir estabilidad prolongada, FPS, audio, temperatura/memoria y controles.
3. Resolver estabilidad del USB y cierre/recuperación Wine/PulseAudio/zombis.
4. Validar importación física de otra instalación y acabado de producto. Singlepass
   y pantalla de carga siguen como trabajos separados, no implementados aquí.

[Estado completo](launcher-app-status.md), [handoff](downloader-handoff.md),
[plan C128 aprobado](../.agents/planning/early-group8-apply-plan.md).

## Historial de prioridades anteriores (no confundir con el corte actual)

### Última entrega privada code79 — HISTÓRICA (ya no es el corte actual)

Ajustes simplificado (Almacenamiento, Cuenta y
Resolución), avanzados plegados, mejor contraste y navegación sin diálogos apilados.
Cuenta indica inicio manual; edición/Guardar bloqueados mientras autologin no esté
disponible, sin modificar ni borrar credenciales automáticamente. Root/A: del cliente actual
verificados; ida/vuelta interno→extraíble→interno y contenedor persistido probados
con fixtures, validación de enlaces anidados corregida. No USB público montado
aún: cambio físico/arranque USB sin acreditar, ni instalador completo automático.
El selector de resolución adaptativa permanece instalado.
Wine/ventana/CVars coordinados y medidos; las franjas restantes siguen pendientes,
no se declara fullscreen resuelto. Ajustes de autologin protegido, todavía sin
envío automático; falta gate de formulario/campo y validación física de Keystore.
La bienvenida compacta conserva detección GPU
y configuración prevista. G610/xaga marcado como render histórico bajo su perfil concreto;
otros dispositivos muestran compatibilidad no comprobada. Sin cambio de driver
ni prueba nueva de gameplay. Preset preparado y runtime horizontal comprobados;
IME/mando/frame y autologin seguro pendientes. [Estado y pendientes](launcher-app-status.md).

[Plan aprobado](../.agents/planning/arm64-launcher-app-polish-plan.md) y
[estado de producto](launcher-app-status.md). Comenzar con bienvenida, launcher
vertical/juego horizontal, fullscreen sin deformación, teclado real y preset
gamepad/30FPS/calidad1, sin overlay táctil y conservando mando físico.

Después: gestión del cliente y builds, elección de almacenamiento con gate USB,
actualización incremental sin duplicación completa normal y autologin opcional
protegido. No mover instalación ni cambiar permisos de almacenamiento por defecto.

Login aceptado provisionalmente por decisión del usuario para avanzar; mundo y
gameplay siguen sin validar. No se reabre el diagnóstico por defecto. Logging D4
ya restaurado; los checkpoints y gates diagnósticos siguientes son historia.

## Historial de prioridades y gates diagnósticos

**Avance D4:** TCP/TLS del cliente conForever1119/8081 demostrados y
cuenta de test aceptada en REST, sin reino/mundo todavía. Investigar únicamente
respuesta/semántica auth/SRP/BGS siguiente; no repetir transporte ya observado.
El checkpoint de reconexión/restauración se resolvió a16:49:17UTC; mantener
instrumentosOFF. [Checkpoint](arm64-forever-connection-progress.md#bloqueo-físico-y-checkpoint).

**D2, último resultado:** [observación de sockets](arm64-wine-tcp-observation.md)
inconcluyente. Attach al WoW real permitido sin root, pero tardío y cap2MiB
agotado por E/S genérica en6s. No TCP/TLS/auth acreditados ni error de servidor
identificado. Se detuvo sin repetir; cualquier seguimiento requiere aprobación
y debe reducirse a conexión/estado de sockets, no aumentar volumen ni reparar
capturadores. WINEDEBUGrestaurado, memoriaOFF, sin nuevo Login/APK/Btrfs.

## Prioridad histórica: diagnóstico directo, no capturador

Capturador, claves en memoria y análisis estático detenidos. El
[diagnóstico D1](arm64-wine-auth-diagnosis.md) cerró en17min35s con ambos
instrumentosOFF. DNS/TCP Android funcionan; no se acreditó TLS del cliente contra
Forever, respuesta WoW o auth. Ya había aserciones repetidas antes del Login.
Sin corrección funcional mínima justificada ni motivo demostrado para desactivar
certificados. La revisión D1-A de la aserción ya terminó: origen/proceso exacto e
impacto no atribuibles con los logs existentes; no seguir esa investigación.
Siguiente propuesta única, no ejecutada: observar la conexión TCP del propio PID
WoW con una traza estándar disponible, máximo60s/3min totales, sin Login/APK nuevo.
Sin herramienta o permisos existentes, declarar la limitación sin instalar ni
escalar. Los gates de captura posteriores son únicamente historial suspendido.

> Vista de conjunto de lo que queda, por fase, y de qué se puede avanzar **sin**
> dispositivo frente a qué lo necesita. Las bitácoras de cada fase están en `docs/`;
> los planes de tarea activos, en `.agents/planning/`.
>
> Punto de entrada actual: [estado ARM64 + Forever](arm64-forever-status.md) e
> [índice temático](README.md). Esta página conserva pasos históricos útiles, pero
> no los presenta como el diagnóstico actual.

## Resumen por fase

| Fase | Estado | Nota |
|---|---|---|
| 0 — Baseline | ✅ | Contrato, decisiones y arquitectura (`docs/architecture.md`) |
| 1 — Descarga del cliente | ✅ caso70450 completado | Cliente en USB abierto por WoW tras corregir16 índices; escritor nuevo probado con metadata completa, sin repetir descarga completa de validación |
| 2 — Cliente ARM64 en Android | ✅ arranque/acceso al juego en ensayo real | Ruta funcional C127 sobre Mali, distinta de code33/Box64; estabilidad prolongada pendiente |
| 3 — Forever | ✅ caso70450 ARM64 resuelto | Bloque y aplicación32B revalidados; Login/mundo positivos comunicados por el usuario |
| 4 — Gameplay | 🚧 primera prueba positiva | Sin glitches observados; faltan30–60min, medidas, audio y controles |
| 5 — Launcher | 🚧 experimental, no one-click | C127 funcional; C128 lista/auditada, pendiente de instalación y prueba física; `android/` es esqueleto histórico, no app activa |
| 6 — Producto | 🚧 pendiente de consolidación | Cierre, recuperación/USB, importación física y matriz de compatibilidad no cerrados |

## Último ensayo host-only completado

La inspección estática del alias ARM64 `0x4cb63b8 → 0x4a5ff20` está completada.
Clasificó el alias DIR64 y su destino file-backed como datos posteriores al slot,
sin identificar el objeto, un consumidor, un enlace al grupo 8 ni un objetivo
directo demostrado de parche. El método estático del alias queda agotado dentro
del alcance y **no debe repetirse**. Detalles y evidencia autenticada en el
[estado central](arm64-forever-status.md#a1--alias-de-confianza-arm64-2026-10-14-completado) y la
[bitácora técnica](arm64-certstore-patch-exploration.md).

## Alcance de captura aprobado y controlado

La captura limitada de fragmentos de código runtime es solo una propuesta en
[`arm64-runtime-code-fragments-plan.md`](../.agents/planning/arm64-runtime-code-fragments-plan.md).
El usuario autorizó la ampliación a32KiB por snapshot/64KiB total. La candidata
fue implementada, revisada y auditada en host, y los controles físicos sin Login
ya se ejecutaron; el resultado actual y sus límites están debajo y en C2–C6 del estado
central. No representa autenticación resuelta ni habilita lectura arbitraria o
escritura.

## Estado operativo actual: C7–C9 final de esta ronda

C4–C6 se conservan como historia. C7–C9 añadieron commit atómico, normalización
de paths anónimos y pinning de la base PE. C7 leyó payload bajo las guardas pero
falló el commit binario; C8 y C9 terminaron por guards/cap. El resultado actual
sigue siendo **0 snapshots de código retenidos**. No se ampliaron caps, caller,
payload RX ni lectura de heap; tampoco se demostró desencriptado, CertStore,
TLS, autenticación ni ausencia de código.

La secuencia completa, con preguntas, parámetros, resultados, límites y
descartes, está en [C2](arm64-forever-status.md#c2--controles-físicos-de-preparación-ui-y-captura-2026-10-14),
[C3](arm64-forever-status.md#c3--estado-final-de-la-ronda-captura-sin-snapshot-2026-10-14) y
[C4–C6](arm64-forever-status.md#c4--retoma-con-candidata-corregida),
[C7–C9](arm64-forever-status.md#c7--lectura-válida-commit-atómico-fallido-2026-10-14).
C7–C9 incluye `7a52c580a814T201325`, `926cd03c62T202906` y
`b176855b69T20261014T205045`. Los handshakes TLS host están separados en
[TLS diagnostics](arm64-forever-tls-diagnostics.md).

Las rondas host fueron independientes: C7 `26`, C8 `27` y C9 `30` pruebas
dirigidas, cada una con `65 filtered`; no se suman ni representan una suite
completa. No se retuvo binario completo ni se habilitó Login.

### Gate siguiente — no es otro ensayo físico todavía

No se planifican más controles físicos hasta cerrar el gate `source30` y el nuevo
build aprobado. El próximo control, si se autoriza, usará la baseline privada
`75d` con solo cambios JNI/marker y confirmará hash estable del file descriptor,
base PE pinned, correspondencia inequívoca con el map privado y cuerpo RX
before/after dentro de las ventanas. No se reutiliza automáticamente `c8c01`, su
sidecar ni otros artefactos, no se aumenta el cap y no se habilita
caller/exportación genéricos.

La compatibilidad ARM64 + Forever continúa sin autenticación demostrada: T1/T2
solo probaron TLS estricto del endpoint desde Linux host; no hay Login actual,
rechazo de clave del servidor, escritura de memoria, parche del PE ni modificación
de Btrfs/assets runtime originales. La variante aislada con TLS desactivado sigue
sin implementar bypass.

## Pasos históricos de dispositivo (no son el estado actual)

Los siguientes puntos documentan gates de rondas anteriores y se conservan para
no perder contexto. No reactivan Login, ADB, Wine ni una descarga, y no sustituyen
el gate host-only pendiente de C7–C9.

1. **`DynCodeProbe`** en el móvil — ¿el runtime ejecuta código generado en memoria?
   Discrimina si el fallo es del runtime o del cliente.
2. **Experimento E1** — `FEX_SMC_CHECKS=mtrack`, `FEX_HIDEHYPERVISORBIT=1`,
   `FEX_X87REDUCEDPRECISION=0` (una variable por corrida).
3. **`SlotProbe`** — leer el slot del loader y de dónde sale la dirección inválida.
4. **Ensayo Ludashi v4.1** (FEX 2601, distinto de FrontMage 2508) tras comprobar
   colisión del paquete `com.ludashi.benchmark`.
5. **Cliente con datos suficientes en el móvil** — gate histórico no resuelto; no
   se afirma aquí un tamaño o estado de descarga actual.

## Trabajo host-only adicional, condicionado

1. **[Fase 5] Detección de GPU + matriz de drivers (D-L7)** en la UI, sin
   confundirlo con compatibilidad Forever.
2. **[Fase 5] Escaneo del patcher en segundo plano con reintentos** (~17 s medidos).
3. **[Fase 5] Contrato generado + autotest automatizado: completado** en emulador
   x86_64; uso y evidencia en `docs/android-emulator.md` y `docs/fase-5-launcher.md`.
4. **[Fase 5] Provisioning del contenedor sin UI desde la app.**
5. **[Fase 5] Descargador del esqueleto con memoria acotada** + verificación + reanudación.
6. **[Fase 5] Logs persistentes y supervisión** (proceso vivo ≠ frames presentados).
7. **[Fase 1] `verify` de los datos CASC de `Data/data`** (laguna conocida frente a
   `US-001-FR-005`).

## En segundo plano, sin cambiar la conclusión ARM64

- **Instalación completa del cliente** en hermes: una posibilidad histórica para
  validar Fase 3 contra un `CertStore` real, no un resultado actual ni una orden
  de descarga.

## Deuda y decisiones abiertas

| Tema | Estado |
|---|---|
| `verify` no cubre CASC `Data/data` | Laguna conocida (`US-001-FR-005`) |
| Visibilidad de `wowforever` | Sigue **privado**; decidir |
| Licencias de terceros (Wine LGPL, Mesa, Box64, DXVK, forks MIT/GPL) | Revisar antes de **distribuir**; hoy es proyecto interno |
| Instrumentación dejada en el móvil | `version.dll` proxy, override en `user.reg`, sección `[WowB.exe]` en `config.box64rc`: limpiar |
| `targetSdk 28` | No publicable en Google Play ⇒ distribución por APK directo (asumido) |

## Riesgos vivos

1. **La compatibilidad completa ARM64 + Forever no está demostrada**: hay PoC
   gráfica, pero no autenticación/mundo.
2. **PowerVR sin Turnip** — mitigado por D-L7: no invalida el producto; hay que probar
   también Adreno.
3. **Espacio en el dispositivo** — hipótesis histórica D-L2 (streaming) **no probada**
   como instalación jugable ARM64, más tamaño real menor de lo estimado. C112 exige
   catálogo completo validado antes de activar.
