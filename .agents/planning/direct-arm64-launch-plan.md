---
id: direct-arm64-launch-2026-10-02
state: in-progress
date: 2026-10-02
---

## Summary

El usuario aporta `jaredgei/wow-forever-android`. La revisión encuentra una
diferencia concreta y reutilizable: su ruta Windows ARM64 **no envuelve el juego
en `winhandler.exe`**, mientras que nuestro code 28 sí lo hace. Usa los mismos
WCP Proton y DXVK, comprobados por SHA-256, por lo que podemos ensayar ese cambio
sin sustituir el runtime ni importar Turnip, Battle.net o su instalador manual.

Se propone que la próxima APK combine el arranque directo del **PE AA64 ya
verificado** con los logs inicio/cola y metadata seguros que están en desarrollo.
Cambiar la ruta de lanzamiento afecta al contrato interno y contradice el límite
anterior de no evitar el helper sin evidencia: requiere esta aprobación específica.
No se afirma que el helper sea el emisor de SIGINT ni la causa única del negro.

## Content

### Referencia fijada y comparación

- Repo: https://github.com/jaredgei/wow-forever-android
- Main inspeccionado: `2619b9f7be80d5549be96e8e04135228651b57f1`,
  2026-10-01 16:31:33 UTC. Release `v1.2.0` apunta a
  `9aa445606562bd54547590a04e0d91e3b0445428`; no se descargó/ejecutó su APK.
- `README.md` informa mundo jugable en AYN Thor/Adreno 740; esto es evidencia
  del autor, no reproducción en nuestro Poco PowerVR.
- `tools/components.sha256` coincide exactamente con nuestros archivos:

```text
Proton: 137807f4145b1b440075f83b2b1b3501d19c4eb618784b1a28418652cae3db03
DXVK:   f660b5c6422b3f93cec65664f309718942e58581b5b96fa2902d55d63ce1db8a
```

- En `app/.../ui/screen/xserver/XServerScreen.kt`, `getWineStartCommand`, el fork
  devuelve `args` sin helper si el nombre contiene ARM64; para Windows absolutos
  también resuelve el cwd a partir de la unidad. Nuestro código fijado a GameNative
  `205cbf8...` devuelve siempre `winhandler.exe $args` en la ruta custom usada.
- **No copiar el test por nombre**: nuestra selección se limita al contenedor/RUN
  gestionado y al PE con cabecera `0xaa64` validada, path/cwd/hash esperados.
- El fork sigue usando Wine, explorer/desktop, Bionic y las piezas X11/SHM/input.
  Arranque directo no significa portar WoW a Android ni eliminar Wine/FEX auxiliares.
- El fork configura Turnip/Adreno y pide copiar `.build.info` + `Data/` (~67–80 GB)
  desde Battle.net/PC. Escribe `Config.wtf` con portal `test` e integra credenciales
  Battle.net. **No implementa** nuestro portal/grupo 8 ni la provisión autónoma.
  No se importan esas partes. `System` no está rechazado para toda GPU no-Adreno,
  pero no hay prueba de render PowerVR ni equivale a su preset Turnip.

### Alcance propuesto

Solo para el juego gestionado/verificado:

```text
Antes: wine explorer /desktop=shell,<size> winhandler.exe "A:\WowB-ARM64.exe" -config BetaSuspendedTest.wtf -d3d11
Después: wine explorer /desktop=shell,<size> "A:\WowB-ARM64.exe" -config BetaSuspendedTest.wtf -d3d11
```

1. Mantener el loader Bionic, prefijo, drive A, cwd canonical, gráficos y preloads.
   Quitar únicamente la dependencia del helper Windows en el launch de ese PE.
2. Rechazar rutas, identidad PE o contexto gestionado inválidos; no generalizar el
   cambio a otros clientes x64/x86, modo escritorio o testGraphics. Conservar el
   comportamiento upstream para ellos, no activar la ruta por nombre ARM64.
3. Persistir el comando efectivo y la selección `verified-aa64-direct` en el RUN;
   distinguir Wine iniciado, WoW validado, ventana/render/login. El patcher sigue
   buscando el proceso propio por sus pruebas, no por el helper ni por una etiqueta.
4. Mantener los avances seguros aprobados de logs inicio/cola/grupos/recuperación.
   La captura global SIGINT nativa en Android sigue **UNSUPPORTED**, sin tocar
   disposiciones, hasta tener un mecanismo compatible/exclusivo. No suprimir señales.
5. Entregar una sola actualización con el mismo package/firma, sin borrar datos.
   Mantener code 28, los ZIPs de evidencia y los componentes/cache existentes.

### No cambia

- Contrato Forever canónico, `BetaSuspendedTest.wtf`, flags y escritura del grupo 8.
  No se modifica `Config.wtf`, no se importan credenciales Battle.net.
- Wine/Proton y DXVK actuales, Vulkan del sistema en PowerVR, no Turnip/driver nuevo.
- Catálogo/MD5/CASC inicial; no descarga completa de decenas de GB ni copia desde PC.
- No `setsid`, nuevas sesiones/grupos, servicio foreground o containment inventado.
  PGID/SID son metadata. Sigue el reinicio Android antes de repetir tras spawn.
- No poda del fork completo ni retirada de controles/servicios Java WinHandler:
  la prueba cambia la invocación Windows, no reimplementa input/IPC.

### Verificación y aceptación

- Tests con PE AA64 válido frente a x64/x86/MZ inválido/nombre ARM64 engañoso.
- Cwd y unidad/ruta esperadas con espacios; path distinto con el mismo basename,
  traversal, symlinks fuera de la instalación, RUN ajeno y flags incorrectos rechazados.
- Afirmar en la fuente y el comando generado que falta `winhandler.exe` **solo**
  en la rama gestionada; flags/proceso supervisor/guarded memory patch intactos.
- Tests afectados de journal/colas/recuperación/spawn único, metadata corrupta y
  terminal inmutable; revisión independiente de las pruebas de propiedad/proceso.
- Receta fresca/idempotente, Gradle offline single-worker, auditoría del APK real
  con versión nueva, firma/ABI/input hashes y fuentes/receta para SFTP.
- Un intento en foreground tras reiniciar Android: obtener PID/imagen del juego o
  evidencia final del fallo. No declarar causalidad del helper ni render/login por
  compilar o aparecer controles. Si falla de nuevo, conservar el final de la traza.

## Implementation / Action Items

- [x] 1. Fijar fuente y comparar hashes/runtime/ruta de arranque y límites de auth/GPU.
- [x] 2. Aprobar el experimento de arranque AA64 directo para la siguiente APK.
- [x] 3. Implementar selección por PE/contexto verificado y adaptar receta/contrato interno.
- [x] 4. Completar los avances seguros de logs/metadata y sus regresiones.
- [x] 5. Revisar seguridad, compilar, auditar y entregar el único candidato actualizado.
- [ ] 6. Recoger resultado en PowerVR y decidir por evidencia, manteniendo rollback.

## Open Issues

- **Aprobado:** «ok. adelante. tengamos en cuenta que el suyo funciona en otros
  dispositivos. así que aprovechemos el conocimiento todo lo que podamos».
  Se contrasta también el resto del fork, sin importar ajustes Adreno/Battle.net
  o cambios de runtime que no correspondan a esta prueba controlada.
- El fork informa éxito en Adreno, no nuestro dispositivo/servidor privado/bootstrap.
- Origen de SIGINT del host code 28 sigue desconocido; bypass no prueba su causalidad.
- **Resultado real code 30:** `forever-RUN-1790948680875-8382457650114.zip` confirma
  selección PE/hash de la ruta directa y Wine/explorer sin helper Windows.
  El host PID 31865 vuelve a terminar por SIGINT 11,830 s después del spawn,
  con identidad de RUN/package/PID comprobada. Snapshot stderr 55.168 bytes sin
  truncación hasta 677 ms antes del cierre; wineboot exit 0 y Explorer creado,
  sin creación WoW validada ni fatal gráfico explicativo. El bypass no bastó.
  No se generó nueva APK ni se modificó runtime; evidencia y siguiente límite en
  `docs/run-code30-sigint.md`. Emisor, grupos y render permanecen no demostrados.
- Input, render, CASC suficiente y CertStore ARM64 siguen pendientes de prueba real.
- **Incidente code 29 / corrección code 30:** al seleccionar el comando, el asset
  de 2.501.689 bytes excedía un cap independiente de 2 MiB. Reproducido en JVM con
  el asset real antes del fix. Lector compartido cap 8 MiB para provisionador y
  gate, validación de metadata íntegra; cinco regresiones + siete de ruta + cinco
  de provisión pasan sin skips. Receta fresca/idempotente 59 inputs, build offline
  y audit real (incluyendo tamaño <= cap del reader) PASS. Candidato
  `0.2.6-automated` / code 30, 362.010.209 bytes, SHA
  `e7e0d4b746f5b1c3af38d284215aa711ec2290fe1d4149df9d3b12a20eba30f5`.
  Descargas, WCPs, hashes de cliente, JNI y policy de reboot tras spawn no cambian.
- **Candidato code 29 / 0.2.5:** build offline correcto, 58 casos JVM distintos
  comprobados en rondas (45 iniciales + 13 finales sin skips), receta fresca e
  idempotente de 57 inputs, review sin P0/P1 pendientes y audit real PASS.
  APK 362.007.621 bytes, SHA
  `f2f79a524875ff640793b628801c500786af0c0687ce8904b2022bc65b99c901`.
  Publicado en `delivery/wowforever-automated-0.2.5-code29-debug.apk` sin retirar
  el build ni releases anteriores; audit, instrucciones, recetas/fuentes propias
  (84 archivos + audit) y SHA256SUMS acompañantes. Los cuatro hashes y la
  integridad de las 85 entradas del ZIP se han verificado después de copiar.
  El plan sigue abierto hasta el ensayo PowerVR: ni la compilación ni el bypass
  prueban quién envió SIGINT o que el juego haya renderizado.
