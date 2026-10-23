---
id: launcher-display-fit-2026-10-16
state: in-progress
date: 2026-10-16
---

## Summary

Transcripción de la decisión aprobada por el usuario («correcto. hagámoslo»):
medir el área útil horizontal y coordinar resolución de escritorio Wine y WoW,
sin stretch, con resolución interna moderada en lugar de imponer la nativa.
El usuario añade selector en Ajustes aunque haya un default. Implementación
reversible sobre code72; no cambia Wine, driver, DXVK, datos ni autenticación.

## Content

### Contrato aprobado

- Launcher vertical hasta abrir WoW; juego horizontal. Medir el viewport real
  después de aplicar orientación/immersive y antes de crear XServer/arrancar Wine,
  no usar dimensiones portrait intercambiadas como si fueran una medición final.
- Descontar recortes/insets que afecten al contenido. No confundir márgenes de
  seguridad Android con letterboxing evitable de una superficie16:9.
- Selector persistido en Ajustes: Automática/recomendada por defecto (objetivo
  altura720), Rendimiento (540), Calidad (900), Nativa. Nunca exceder resolución
  útil real; opciones reducidas conservan su proporción, no presets16:9 fijos.
- Por ejemplo2460×1080 útil permite1640×720. Redondear dimensiones válidas con
  error de aspecto mínimo y escalado proporcional existente; no activar stretch.
- Medición/resolución congeladas por arranque; no modificar contenedor/config
  mientras WoW corre. Cambios de ajuste se aplican en la siguiente preparación/
  apertura. Si no se mide área válida en tiempo acotado, error explícito, no
  inventar resolución ni lanzar desde viewport portrait.
- Misma resolución interna en desktop/root XServer y CVar de WoW del fichero
  derivado del launcher. Conservar los originales y claves ajenas; idempotencia,
  eliminación de duplicados y CRLF. No tocar `Config.wtf` ni el WTF de entrada.
- Mantener límite30FPS, gamepad y nivel visible1, input físico y botón IME.
  Conservar código de sesión y cancelación; evitar doble arranque por recomposición
  o cambios de orientación. No mostrar medición prevista como resultado efectivo.
- Autologin sigue sin sender habilitado. La nueva geometría invalida las
  referencias visuales antiguas; registrar tamaño/offset usados para recalibración.

### Fuera de alcance

No selector de drivers, nueva runtime, soporte GPU por nombre, custom resoluciones
arbitrarias, cambios Btrfs/wowserver, descargas de cliente, Login ni escritura de
credenciales. No activar captura de memoria o parche para medir presentación.

## Implementation / Action Items

- [x] 1. Registrar decisión del usuario y aceptación de resolución/aspecto/selector.
- [x] 2. Implementar política pura y preferencias del selector; fixtures panorámico,
  16:9, insets/cutout, pantallas pequeñas, datos inválidos y límites de resolución.
- [x] 3. Medir tras rotar y aplicar plan una sola vez antes del arranque: Wine y
  configuración derivada de WoW coordinados. Error/cancelación no arranca tarde.
- [x] 4. Tests de política, merge y entrada idempotente; build aislado/auditoría con
  payloads nativos/runtime conservados, sin duplicar checks ya exitosos no afectados.
- [ ] 5. Instalar candidato73 y verificar valores desktop/CVars, captura completa
  del cliente y ausencia/reducción de franjas sin deformación. No enviar Login.
- [x] 6. Registrar evidencia y límites: IME/touch/mando, área recortada legítima,
  render, rendimiento y nuevas coordenadas de formulario sin afirmar auth.

## Open Issues

- Verificar cómo el renderer existente aplica cutout/insets y tamaño real de view;
  no descontarlos dos veces. Si exige cambio nativo/runtime, parar y explicitarlo.
- Si el cliente conserva barras internas pese a desktop/CVars coordinados, medir
  esa frontera antes de atribuirla a otra cosa o activar fill/stretch.
- Resolución nativa consume más GPU; advertencia visible, no promesa30FPS.
- Captura completa autorizada con cuenta de pruebas, sin escribir credenciales ni
  quitar `FLAG_SECURE` al editor del APK. Artefactos y capturas fuera de Git.
- **Pendiente: las franjas no están eliminadas.** La medición code74 acredita
  desktop XServer y ventana de WoW1564×716 sobre surface2360×1080; el frame aún
  muestra márgenes internos. No atribuirlo a desktop16:9 ni declarar éxito por
  haber escrito las CVars. Falta distinguir los píxeles/source drawable del
  compositor frente a letterboxing del cliente antes de cambiar esa frontera.
- Nativa2360×1080 cambió el encuadre/tamaño de UI, pero tampoco llenó todo el área
  útil. Se restauró Automática al terminar. No repetir resoluciones o CVars a
  ciegas; no activar stretch ni modificar runtime/driver para esconder el margen.

### Implementación y verificación

- Nuevo `ForeverFunctionalDisplayFit.kt` y test; selector en Ajustes, planeación
  tras orientación/immersive, dos mediciones estables y timeout5s. Insets físicos
  por intersección view/window con padding absoluto, sin duplicar cutout ni RTL.
- CVars de resolución se escriben solo con plan final en WTF derivado; el mismo
  plan configura container.screenSize. gxWindow no se adivinó (la referencia wiki
  lo documenta retirado, sin prueba de vigencia para este cliente).
- Review cerró cuatro defectos de cancelación, pausa e insets. Coordinador de
  proceso/token y lease impide relanzar mientras termina una escritura cancelada,
  incluida recreación de Activity. No onPause arranca tarde ni cambia un guest vivo.
- Host:29 passed,1 deselected de tests afectados. Primer Kotlinbuild73 detectó
  import LaunchedEffect ausente y conversión de Insets incorrecta; corregido en
  reconstrucción final nueva, sin ocultar fallo del candidato anterior.
- JVM73:10 tests DisplayFit y7 ServerProfiles,0 errores/fallos. Build offline
  SUCCESSFUL7m18s. No se repitieron tests de secretos/GPU no afectados.
- Code73 instalado/auditado, SHA-256
  `e8275856973c48a3e3b8de89e54af0bbf3b59f57fcb5181a534a73b9c4dc1334`.
  Workspace externo `display-fit-code73-final` en el directorio de artefactos.
- Code74 añade observación de dimensiones reales al mapear ventanas, sin títulos,
  nombres de cuenta, contenido de campos ni lectura de memoria del proceso del
  juego. No altera Wine/DXVK/PanVK ni activa fill/stretch. Última versión instalada:
  `0.2.50-functional-display-observation`, code74, UID10330.
- APK74 SHA-256 `00fe59c1f33aeadd6ed9ad9ba83664d1b115f28ae534cf3c832d3e48a6d77e50`,
  419030452 bytes. Firma conservada,2140 inputs de fuente intactos y174 payloads
  iguales a code64 salvo marker; JNI/runtime no cambiaron.
- Área medida:2460×1080 total, recorte físico izquierdo100, útil2360×1080.
  Auto eligió1564×716 por redondeo par/aspecto cercano a altura720. Desktop real,
  ventana de WoW y CVars coinciden en1564×716; view Android2360×1080.
- Control Nativa:2360×1080 en las tres CVars; preset/servidor conservados. No es
  promesa de rendimiento ni prueba de que desaparezcan barras. Automática
  restaurada en preferencias, aplicable al próximo arranque. El último WTF derivado
  conserva Nativa hasta que se aplique el nuevo plan, por diseño; no hay guest vivo.
- Ambas sesiones cerradas mediante paquete propio, runtime no observado después
  del cierre y launcher reabierto. PE/WTF originales con hashes anteriores intactos.
  Sin credenciales, Login, teclado/copy, autologin, memoria patch o captura.

### Artefactos para retomar

- `/home/adrian/wowforever-artifacts/arm64-prototype/display-fit-code73-final/`:
  recibo, `gradle-display-build.log`, auditoría, selector y captura completa.
- `/home/adrian/wowforever-artifacts/arm64-prototype/display-observe-code74-reviewed/`:
  recibo/auditoría, `device-display-verification-code74.json`, captura Auto,
  `device-display-verification-code74-native.json`, captura Nativa y
  `native-control-restoration.json` (`autoRestored=true`).
- Los dos JSON74 reutilizan un arnés73 autenticado; su campo superior `version=73`
  es una etiqueta heredada del arnés, no la app probada. `package.versionCode=74`,
  el nombre0.2.50, el guard de paquete y la auditoría fijan la identidad real.
- Esas capturas autorizadas muestran formulario vacío sin máscaras y overlay
  pequeño de teclado. No confirman IME/input/mando o gameplay. Autologin continúa
  sin envío y sus referencias antiguas no se reutilizan tras este cambio.
