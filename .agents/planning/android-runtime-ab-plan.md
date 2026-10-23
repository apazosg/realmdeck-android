---
id: android-runtime-ab-2026-09-27
state: in-progress
date: 2026-09-27
---

## Summary

Desbloquear WowB.exe 1.60.1.70009 en el móvil y seleccionar una combinación
reproducible para el futuro APK transparente. Hermes solo prepara artefactos;
las pruebas de compatibilidad se ejecutan en Android. Sin cambios en upstream,
sin commits/push ni distribución de binarios del juego.

## Content

### Próxima ronda: pruebas para desbloquear decisiones (aprobada)

Usuario aprueba la ronda y la evaluación de varios forks: «vamos allá adelante».

Esta ronda continúa la misma operación. No exige resolver todo el runtime para
ser útil: cada prueba debe producir una decisión o un bloqueo reproducible.
Hermes compila/analiza; la compatibilidad se prueba en el POCO. No construir
todavía el APK final. Un cambio técnico verificable o un reporte reciente concreto
de WoW justifican evaluar otro fork, con las precauciones indicadas abajo.

Hechos actualizados: ApiProbe pasó en FrontMage; las APIs básicas de carga y
sincronización funcionan en un ejecutable de control. SlotProbe está desplegado
pero sin resultado recogido. El destino del fallo anterior sí cae en una vista
anónima RX (con otra vista RW); no está demostrada su procedencia ni quién escribe
el slot. No repetir la afirmación antigua de «dirección no mapeada».

| Prueba | Pregunta y preparación | Resultado que decide el siguiente paso |
|---|---|---|
| T0 — ejecución y evidencias | Runner ADB para app/serial explícitos, ID de corrida, hashes/config, logs nuevos, exit code y duración; lanzamiento manual único documentado si automatización falla | No interpretar logs antiguos ni confundir actividad Android viva con juego vivo. Si UI bloquea dos veces, pedir un toque concreto, no otro ciclo de navegación |
| T1 — SlotProbe en FrontMage | Leer slot del loader y VirtualQueryEx/bytes del destino sin escribir memoria; conservar misma combinación y snapshot | Si se obtiene lectura real, contrastar con fallo y mapas. Si permisos/enumeración impiden observar, registrar límite; no reconstruir el mismo diagnóstico indefinidamente |
| T2 — control de código dinámico, condicionado | Solo si T1 indica problema en esa ruta: sonda mínima x64 que genera código propio sencillo, RW→RX, FlushInstructionCache y llamada con retorno conocido; comparar con función estática | Un fallo fuera del juego permite un repro pequeño del runtime. Que pase no descarta problemas de puentes ARM64EC ni valida el loader |
| T3 — Ludashi como combinación alternativa | Verificar APK concreto, hash/firma/ID y componentes efectivos; instalar separado solo si no colisiona. Prefijo nuevo, mismo cliente, Hello/ApiProbe y después WowB; ruta gráfica disponible verificada, no Turnip en PowerVR | Si supera el arranque temprano, conservar receta y repetir una vez. Si falla, comparar excepción, módulo y fase, no solo código de salida. Si no permite ruta gráfica/arranque viable, descartar ese APK sin mezclar bibliotecas a ciegas |
| T4 — datos y render, condicionada | Solo al superar arranque temprano: distinguir datos CASC ausentes de fallo gráfico; comprobar driver/API elegidos y soporte real D3D del cliente | Requerir evidencia visual/logs del login/render estable. Proceso vivo, TestD3D o overlay FPS no cierran Fase 2. No asumir streaming de la instalación loose |

Orden: preparar T0 y verificar artefactos de T3 en paralelo (solo host);
en el móvil un único ejecutor, T1 → T2 si procede → T3 → T4 si procede.
Máximo dos corridas por hipótesis: inicial y confirmación/cambio único justificado.
No cruzar Wine/puentes FEX entre forks en esta ronda. Comparar combinaciones
completas no identifica por sí solo qué componente causó la diferencia.

Entregables propuestos tras aprobación:

- Scripts y fuentes pequeñas reproducibles en `scripts/diagnostics/`, con
  comprobaciones de selección de dispositivo/app y sin credenciales.
- Matriz de resultados en `docs/android-runtime-test-results.md`, con
  «no ejecutado», «bloqueado», «fallo reproducible» o «pasa» por prueba.
- Binarios, snapshots y logs fuera de Git, bajo
  `/home/adrian/wowforever-artifacts/`; nunca artefactos grandes en `/tmp`.
- Backups y restauración de cambios temporales, preservando ambas apps actuales.

Pocket Realm/wow-mobile se usarán como referencias para supervisión, captura
de logs y lanzamiento gestionado, no como prueba de compatibilidad con nuestra
beta. Las licencias no bloquean esta PoC interna; conservar procedencia/avisos y
revisar obligaciones antes de distribuir. Sin PRs a terceros ni commits/push
sin petición explícita.

Puerta de salida de la ronda: receta móvil que avance de fase, o un fallo acotado
con evidencia suficiente para elegir una modificación concreta del runtime.
El contrato Forever y la autenticación se prueban después del login/render de
Fase 2; no confundir arrancar Winlator con ejecutar el cliente y su launcher.

#### Preferencia del usuario: varios forks y reportes recientes

El usuario considera apropiado probar varios forks, especialmente si hay reportes
de ejecución de WoW de los últimos meses. Ludashi no limita el conjunto de
candidatos. No exigir demostrar una diferencia interna del motor como condición
previa si existe un reporte reciente suficientemente concreto que justifique
un ensayo barato y reversible.

Para priorizar, registrar enlace y fecha del reporte, versión/build de WoW,
APK/fork y componentes cuando estén disponibles, dispositivo/GPU, y alcance
observado (arranque/login/mundo). Los campos desconocidos se marcan como tales;
una anécdota puede justificar probar, pero no demostrar compatibilidad.
Priorizar cliente moderno x64 y hardware comparable; pruebas de vanilla/WotLK
32-bit siguen siendo pistas útiles, no equivalencias. Usar la misma batería
corta de controles/cliente en cada candidato, preservar apps/prefijos y
comprobar ID/firma reales antes de instalar. Evitar duplicar APKs idénticos
republicados con otro nombre.

- FrontMage/winlator-llm, commit b1c7b23163bc15cf985b974af906b2d4c28203d6:
  docs/FEX_STATUS.md documenta Wine ARM64EC + puentes FEX, logs persistentes,
  y un arreglo de instrucciones x87 asociado al login de Turtle WoW.
  No demuestra compatibilidad con nuestra beta ni con PowerVR.
- marcocastignoli/wow-mobile, commit 0c8068f617737d9c0fb6246f37b54f85b620b09d:
  objetivo 3.3.5a build 12340; referencia de automatización del producto,
  no evidencia de compatibilidad con WowB.exe.
- Una muestra RIP en el loader no identifica la causa de salida ni demuestra
  antiemulación. Imports criptográficos tampoco lo demuestran.
- Corrección CPUID: leaf 0x80000001 ECX=0x121 tiene bit 0 activo: LAHF/SAHF
  sí está anunciado. La sonda anterior usó erróneamente bit 29.
- TestD3D es una prueba de humo, no el go de Fase 2 ni prueba de que toda la
  ruta gráfica de WoW funcione. Streaming con instalación loose sigue sin validar.

#### Hipótesis nueva (preparada sin dispositivo): variables FEX ausentes

Comparando el entorno real de nuestra corrida de FrontMage con los valores por
defecto documentados por CMOD/FrontMage aparecen **ausencias significativas**:

| Variable | Nuestro entorno observado | CMOD documentado | Relevancia |
|---|---|---|---|
| `FEX_SMC_CHECKS` | **ausente** | `mtrack` | Detección de **código automodificable**. El fallo es una ejecución dentro de una región anónima RX con escritura previa: encaja con SMC mal gestionado |
| `FEX_HIDEHYPERVISORBIT` | **ausente** | `1` | Oculta el bit de hipervisor (detección de VM/emulación) |
| `FEX_DIAG_SMC` / `FEX_DIAG_SIGILL` | ausentes | `0` / `0` | Diagnóstico: se pueden activar temporalmente para ver el motivo |
| `FEX_X87REDUCEDPRECISION` | `1` | `0` | Precisión x87; puede afectar a cálculos del cliente |
| `FEX_SILENTLOG` | ausente | `1` | Ruido de log, no causal |

Esto **no demuestra** la causa, pero es una hipótesis con base documental y un
experimento barato y reversible, sin tocar el juego ni cambiar de runtime.

**Experimento E1 (una sola variable por corrida):**
1. Añadir `FEX_SMC_CHECKS=mtrack` (+ `FEX_DIAG_SMC=1` para diagnóstico) al
   entorno del contenedor y repetir `WowB.exe`.
2. Si cambia el síntoma, añadir `FEX_HIDEHYPERVISORBIT=1`.
3. Después, `FEX_X87REDUCEDPRECISION=0`.
4. Registrar siempre el entorno efectivo (aparece en `ARGV/ENV` de `wine.log`).

Criterio: un cambio de fase (llega a crear `Logs/`/`WTF/`, o excepción distinta)
es avance y se conserva; sin cambio, se revierte y se pasa al siguiente.

## Implementation / Action Items
- [x] 1. Guardar configuración y evidencias actuales; verificar USB y hashes del
  cliente. Retirar únicamente instrumentación propia (proxy version.dll,
  override añadido y flags experimentales), con backup y comparación previa.
- [ ] 2. Obtener una única corrida baseline Box64 limpia con salida, duración,
  logs y versión/hash del runtime registrados; no repetir pruebas ya exitosas.
- [x] 3. Verificar APK FrontMage: procedencia, package ID real, firma, hashes,
  componentes ARM64EC/FEX incluidos y ruta gráfica compatible con PowerVR.
  No usar scripts de instalación con --force: su documentación describe
  desinstalar com.winlator/com.winlator.cmod. No sobrescribir la app existente.
- [x] 4. Instalar en paralelo solo si el APK tiene ID distinto y componentes
  adecuados; crear prefijo limpio con el mismo snapshot del cliente, sin proxy
  ni parches al loader. Ampliación aprobada: usar VirGL para diagnóstico de
  arranque/observabilidad, sin considerarlo validación DXVK/PowerVR ni go Fase 2.
- [x] 5. Ejecutar control y WowB.exe en el móvil, capturando wine.log,
  excepciones, módulos, exit code, tiempos y evidencia visual.
- [x] 6. Comparar combinaciones completas, no atribuir diferencias solo a FEX:
  también cambia Wine. Registrar progreso (Logs/WTF o error accionable),
  login/render estable (go Fase 2), o fallo reproducible. Actualizar documentación
  con hechos e hipótesis separados y pasos automatizables para el APK.

## Open Issues

- **Prueba limitada realizada:** com.winlator.llm instalado en paralelo;
  Container-1 nuevo con proton-10-arm64ec, FEXCore2508 (HODLL confirmado),
  VirGL23.1.9 + WineD3D. Cliente original verificado y sin proxy.
  Hello EXIT=0; WowB EXIT=-1073741819 (0xC0000005) en dos corridas.
  Segunda corrida exclusivamente para observabilidad con
  WINEDEBUG=-all,+seh,+unwind,+loaddll,+process, dejado configurado.
  Log segunda corrida líneas880–890: excepción al EJECUTAR (info[0]=8)
  dirección 0x1A23BC2, módulo desconocido; primer frame posterior
  WowB_loader.dll+0x203E6AB. Esto acota el fallo pero no demuestra causa,
  antiemulación, ni equivalencia con la salida de Box64.
  No se generaron C:/wow/client/Logs ni WTF. Sin login/render demostrado.
  Reporte y logs: /home/adrian/wowforever-artifacts/frontmage-ab/session-20260927/.
  Se mantiene pendiente el cierre total: duración exacta baseline no medida
  y Fase2 no superada. Próximo diagnóstico: origen/destino de la transferencia
  de control y permisos/mapeo de esa dirección, no cambios gráficos a ciegas.
- Usuario aprueba la prueba limitada FrontMage + FEX + VirGL: «si, hagámoslo
  a ver si conseguimos algún avance». Preservar com.winlator y su contenedor.
- **Bloqueo de instalación (verificación completada):** APK FrontMage oficial
  descargado, 791878864 bytes, SHA256
  `01d364cafc77bb919a89668ac20618a108763c4e46ef3a81a104a4dc4bf98b2e`,
  paquete `com.winlator.llm`, firma v2 verificada (certificado debug).
  Release `llm-apk-20260216-144633` actualizada a fuente `4293b75b449d11fdf66da567791c9165fa6df6a0`.
  La ruta Vulkan revisada en XServerDisplayActivity.java:1175–1260 selecciona
  wrapper + Adrenotools/Turnip. No hay selección Vortek respaldada en esa función,
  aunque el APK incluye su asset. Existe alternativa VirGL (:1262–1268),
  pero no equivale a validar DXVK/Vulkan PowerVR. No se instaló la app.
- **Baseline sin instrumentación:** original exe/loader verificados contra snapshot;
  retirados proxy version.dll y override propio con backups. No había sección
  [WowB.exe] en config.box64rc al inspeccionarlo (estado histórico ya cambiado).
  Una corrida: Hello EXIT=0 con stdout/stderr/escritura OK, WowB EXIT=1,
  sin Logs/WTF; duración exacta no capturada (diag.txt era anterior).
  Paso 2 parcialmente completado; no atribuirle los tiempos de corridas antiguas.
  Evidencia: /home/adrian/wowforever-artifacts/android-runtime-ab-20260927-step1/.
- **Decisión antes de continuar:** investigar una combinación FEX con ruta Vulkan
  del sistema explícita, o aprobar FrontMage+VirGL como prueba limitada al arranque
  y observabilidad (no como validación gráfica del producto). No modificar APK
  ni mezclar bibliotecas incompatibles para forzar Vortek sin plan adicional.
- **Deriva de documentación FrontMage:** FEX empaquetado sha256 `0afc4ded...`
  distinto del `2a5f888c...` descrito en FEX_STATUS.md; no asumir que reproduce
  exactamente su prueba Turtle WoW. Artefactos: /home/adrian/wowforever-artifacts/frontmage-ab/.
- Plan aprobado por el usuario: «perfecto hagamoslo».
- La release APK puede no corresponder al commit investigado; verificar contenido.
- FrontMage documenta problemas de swapchain en Adreno: PowerVR no está probado.
- Las licencias del repositorio no sustituyen revisar Wine/FEX y componentes
  redistribuidos antes de empaquetar nuestro producto.
- Cliente completo pendiente (instalación anterior terminó por OOM); una prueba
  parcial no permite prometer login ni streaming. No relanzar descarga masiva aquí.
