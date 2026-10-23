# FrontMage/WoWBox — revisión de referencia

Revisión textual/API del 2026-10-02. No se descargó, instaló ni ejecutó su APK,
runtime, Battle.net o cliente. No se modificó el launcher propio.

## Identidad y alcance

- Repo: <https://github.com/FrontMage/WoWBox>.
- Revisión fijada: `fe2e92070a83a67708f899659f19337ec61f3caf`, commit inicial
  `2026-10-02T02:52:01Z`.
- Publicación `v0.1.0`: **prerelease/Preview**, `2026-10-02T03:00:13Z`.
- Identidad declarada: `com.winlator.llm`, versión 0.1.0 / code 17, ARM64,
  Android 8.0+. Es el mismo package nominal que FrontMage usado históricamente;
  no asumir que se puede actualizar encima: firma/datos no se verificaron aquí.
- APK anunciado: `WoWBox-v0.1.0-arm64.apk`, 588.337.776 bytes;
  SHA publicado por GitHub
  `41a2cac736f086b28bfef96c0efe9b5a0e3387cbad183cc64e86ddaef8a15ae2`.
  Es metadata de release, no una auditoría local de esos bytes.

El README define un **Winlator preconfigurado para Battle.net y WoW**. La ruta de
usuario es instalar Battle.net, autenticarse, descargar/actualizar el juego y
lanzarlo desde Battle.net. No es la misma especialización de cliente Beta AA64
directo que `jaredgei/wow-forever-android`.

## Diferencias confirmadas con nuestro experimento

Fuente fijada:
[README](https://github.com/FrontMage/WoWBox/blob/fe2e92070a83a67708f899659f19337ec61f3caf/README.md)
y
[box_spec.json](https://github.com/FrontMage/WoWBox/blob/fe2e92070a83a67708f899659f19337ec61f3caf/app/src/main/assets/box_spec.json).

| Aspecto | WoWBox público | Nuestro candidato code 30 |
|---|---|---|
| Entrada | `defaultTargetId: bnet`; instalación y juego mediante Battle.net | `WowB-ARM64.exe` verificado, sin helper Windows en la ruta gestionada |
| Cliente | Versión elegida/gestionada por Battle.net; sin target Beta directo en el spec | Beta 1.60.1.70009, catálogo propio fijado |
| Wine | `proton-11.0-1-arm64ec-wowbox-openfonts-9ac94fff` | `proton-11.0-90624-arm64ec`, WCP comunitario verificado |
| FEX | Paquete 2508 `99d69a-native-mock-jemalloc`, `FEX_BNET_FORCE_X86=1` | Auxiliares FEX/HODLL; PE principal AA64 directo |
| DX | DXVK 2.7.1 AMD64 + vkd3d; candidato ARM64EC opcional | DXVK 2.4.1 plain AA64, D3D11 |
| GPU | Turnip/AdrenoTools, candidatos Qualcomm | Wrapper/Vulkan del sistema para el Poco PowerVR |
| Provisión | Instalador Battle.net descargado por usuario; este instala y actualiza el cliente | CDN sin Battle.net; bootstrap finito, suficiencia aún no probada |
| Auth privada | No se identifica portal Forever ni parche grupo 8 | Contrato propio, BetaSuspendedTest.wtf y patcher condicionado a identidad |

El README dice expresamente que **esta versión no incluye el nuevo parche
copied-syscall para la beta WoW ARM64 pura**. Tampoco anuncia aceptación del juego
en un nuevo dispositivo para esta configuración pública. Release/README enumeran
build/firma/recursos/tests de host; no convertir eso en gameplay verificado.

La configuración pública está orientada a Adreno, reconoce problemas pendientes
de RP5/D3D12 y no aporta prueba de render PowerVR. Esto no demuestra que PowerVR
sea imposible; sí impide importar Turnip o afirmar equivalencia con nuestro caso.

## Conocimiento reutilizable, sin importar otro runtime

1. **Recursos por capas con identidad y checksum.**
   `app/src/main/java/com/winlator/box/BoxInstaller.java`:
   `installWineRuntimeUpdate`, `installLocked`, `installLayer`,
   `verifyAssetChecksum`. Se verifica el payload y se protege el prefijo
   persistente durante updates. Es un patrón operativo útil; no justifica copiar
   sus archivos/preloads/versiones ni reemplazar nuestro catálogo ahora.
2. **Revalidación de PID/TID antes de operar.**
   `app/src/main/java/com/winlator/core/WineSessionProcessController.java`:
   `scanCandidateProcesses`, `isStillSameLiveThread`, `ProcReader.readUid`,
   `readThreadGroupId`, `readStartTime`. Ideas aplicables a gestión de sesiones y
   futura eliminación del reinicio, pero su alcance de procesos Wine del mismo UID
   es más amplio que nuestra propiedad por RUN/raíz/startTicks. No copiar sus kills
   globales del UID ni tomar la ausencia de candidatos como containment probado.

`GuestProgramLauncherComponent.stopAndDrain`, `waitForWineProcesses` y
`requestWineserverShutdownLocked` usan shutdown de wineserver y espera acotada,
seguida de SIGKILL mediante ese controlador. No identifican el emisor del SIGINT
que mató nuestro host Android.

`app/src/main/cpp/winlator/sigquit_exec.c` desbloquea **SIGQUIT**, luego `execvp`.
No es un handler de SIGINT ni un arreglo de la señal 2. No trasladar esa modificación
de señales sin una necesidad observada y verificada.

## Provenance y licencia

El spec publica identidades de las capas, entre ellas Wine
`9ac94fff85c5156e0179e1e11508b1cbb569d4f974bca8838df319327c6f291d`
y FEX `5f1dbc8db81adcbcc25e1e4119b8bb16d4abbeb8d37b06a95249764e21edeae3`.
**No son nuestros WCPs**; un nombre Wine 11/ARM64EC no establece intercambiabilidad.
Los binarios y la firma de la release no se inspeccionaron.

La app conserva MIT de Winlator. Wine/FEX/Mesa/DXVK/PRoot/etc. mantienen licencias
propias; `THIRD_PARTY_NOTICES.md` y el asset de fuentes describen lo controlado y
los gaps de provenance de dependencias heredadas. No confundir MIT de la app con
licencia única de todos los componentes.

## Decisión de esta revisión

**No sustituir nuestro runtime ni producir otra APK por este enlace.** La referencia
aporta patrones de instalación y lifecycle, pero no una diferencia demostrada que
explique nuestro SIGINT o pruebe Beta AA64/private Forever/PowerVR.

El siguiente control mínimo ya propuesto en
`.agents/planning/runtime-without-jni-control-plan.md` sigue **draft, pendiente de
aprobación**: mismo arranque/runtime/datos, sin cargar ni llamar el JNI propio.
No se ha ejecutado ni se atribuye el problema al patcher. El mero envío del enlace
WoWBox no autoriza ese control, un cambio de autenticación o la importación de
otra distribución. Se conservan code 30 y ambos ZIPs de señal como baseline.
