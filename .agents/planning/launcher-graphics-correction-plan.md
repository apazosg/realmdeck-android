---
id: launcher-graphics-correction-2026-10-22
state: in-progress
date: 2026-10-22
---

## Summary

El usuario comunica problemas con el selector, franjas negras y el preset inicial.
El 2026-10-22 concreta el comportamiento esperado:

> el selector de la apk debería ser de resolución con el objetivo de que el usuario
> pueda extender la pantalla en base al formato de pantalla de su móvil y eliminar
> barras negras. La calidad debería ser por defecto la más baja y 30fps. si después
> el usuario la cambia dentro del juego, que guarde los cambios. Podríamos tener un
> botón de volver s defaults en la apk

El usuario aprobó la revisión y el botón de restauración exclusivamente gráfica
el 2026-10-22: «si, hazlo». Se ejecuta el alcance aprobado; la instalación y prueba
física siguen sujetas a disponibilidad y autorización de dispositivo.
Posteriormente autoriza «compila y despliega la en el móvil conectado por adb wifi»:
compilar, auditar e instalar sin borrar datos; no autoriza abrir el juego.

## Content

### Hechos y límites del diagnóstico

- Árbol inicialmente limpio en `main`.
- US-004 está `ready`: AC-02/FR-002 definen un selector de resolución con Automática
  (objetivo 720), Rendimiento (540), Calidad (900) y Nativa, manteniendo la proporción
  útil. No definen un selector independiente de calidad gráfica.
- El selector actual en `ForeverFunctionalPocActivity.kt` muestra esos nombres sin
  explicar sus dimensiones objetivo. «Calidad» cambia resolución, no el nivel de
  detalle del juego; únicamente el botón de radio responde al toque, no toda la fila.
- AC-03/FR-003 ya exigen calidad visible 1 y límite de 30 FPS. El código
  `ForeverFunctionalServerProfiles.applyPocOverrides` escribe `graphicsQuality=0`,
  `graphicsShadowQuality=0` y límites de 30 FPS. Esa escritura no demuestra que el
  cliente aplique el preset ni que desactive realmente las sombras.
- FR-003 afirma que `graphicsQuality=0` corresponde al nivel visible 1. No cambiar
  esa equivalencia ni darla por validada solo por una prueba de transformación de
  texto: contrastar las CVars efectivas y el estado mostrado por el cliente.
- El plan anterior `launcher-display-fit-plan.md` ya registra bordes pendientes:
  en code74 coincidían desktop, ventana y CVars (1564×716) sobre una superficie útil
  de 2360×1080. Nativa tampoco eliminó las franjas. No es una prueba de la versión
  actual, pero impide asumir que basta con repetir la adaptación de resolución.

### Resultado propuesto

1. Distinguir con claridad resolución interna y calidad gráfica. Mostrar objetivos
   de resolución sin presentarlos como medidas efectivas antes de rotar y medir.
   El único selector de la APK será de resolución, con etiquetas de resolución en
   vez de «Calidad»/«Rendimiento», adaptada al formato útil del móvil para eliminar
   franjas evitables. Mantener Automática por defecto y las escalas ya aprobadas,
   salvo que el diagnóstico demuestre que requieren revisión. Hacer seleccionable
   toda la fila y mantener selección persistida/accesible.
2. Inicializar la configuración gráfica con el nivel visible más bajo (1), sombras
   desactivadas y límite de 30 FPS. No prometer 30 FPS sostenidos. Aplicar estos
   valores como defaults iniciales, no como imposición en cada preparación.
3. Conservar entre sesiones los ajustes gráficos que el juego haya guardado,
   incluidos calidad, sombras y límite de FPS. Reabrir la APK, preparar o cambiar
   resolución no debe volver a imponer el preset. Comprobar la ruta que el juego
   escribe y la que carga el siguiente arranque; no basta con guardar una preferencia
   de Android. Mantener la resolución bajo control del selector de la APK.
4. Propuesta: añadir «Restablecer gráficos» en la APK. Con la sesión cerrada,
   restaurar solo el preset gráfico inicial (nivel 1, sin sombras, límite 30 FPS)
   para el próximo arranque. No restablecer resolución, cuenta, controles, servidor,
   almacenamiento ni datos del cliente. No escribir ajustes de una sesión activa.
5. Localizar las franjas en la frontera Android/superficie X11/compositor/cliente
   y corregir la causa comprobada. Conservar proporción y correspondencia de input;
   no ocultar el problema mediante estiramiento o recorte del contenido.
6. Mantener los ficheros originales, los ajustes ajenos, el runtime/driver y los
   controles de integridad, consentimiento y exclusión existentes.

### Impacto sobre especificaciones

- US-004-UC-02/AC-03 y FR-003 deben distinguir inicialización, conservación de
  ajustes guardados dentro del juego y restauración explícita. La política actual
  de aplicar el preset en cada preparación contradice la persistencia solicitada.
- US-004-AC-02/FR-002 deben aclarar que la APK selecciona resolución/formato, no
  calidad gráfica; conservar proporción e input y eliminar franjas evitables.
- Añadir un criterio de aceptación para restaurar únicamente gráficos y derivar
  de él el requisito y la prueba correspondientes tras su aprobación.
- Revisar TC-003/004 (preset inicial/idempotencia), TC-009/010/011 (ajuste de
  resolución) y añadir pruebas enfocadas de conservación entre arranques y reset.
  Las comprobaciones del preset no deben seguir exigiendo sobrescribir elecciones
  posteriores del usuario.
- Si la evidencia refuta la equivalencia técnica de FR-003, presentar la corrección
  para aprobación; no perpetuar una expectativa errónea ni editarla silenciosamente.
- No se identifica de momento cambio necesario en otras historias o en SYSTEM.

## Implementation / Action Items

- [x] 1. Registrar la decisión: selector solo de resolución y calidad inicial baja,
  sin sombras, 30 FPS, conservando cambios posteriores guardados desde el juego.
- [x] 1.1 Aprobar este impacto y el alcance propuesto de «Restablecer gráficos».
- [ ] 2. Completar diagnóstico de la configuración realmente cargada y de la
  geometría del render; separar hechos actuales de evidencia histórica.
- [x] 3. Registrar en US-004 el comportamiento aprobado (UC-02, AC-02/03/11) y
  derivar FR-003/015/016 y TC-003/004/020/021, conservando FR-002 como contrato de
  presentación proporcional. El usuario aprueba este alcance con «si, hazlo».
- [x] 4. Implementar el cambio mínimo en el selector de resolución, inicialización
  y conservación de gráficos y restauración explícita; corregir las franjas
  únicamente donde quede demostrada su causa. No reemplazar silenciosamente un
  perfil existente por el fichero base durante preparación o cambio de resolución.
  Implementado: se reutiliza el derivado y el reset solo modifica el preset.
  Revisión independiente final sin bloqueos tras añadir comprobación de runtime
  inactivo antes/después del lease y exclusión bidireccional en la UI. No se cambia
  geometría: la causa de las franjas sigue pendiente del punto 2.
- [x] 5. Ejecutar pruebas enfocadas de política de resolución y merge del preset,
  inicialización, conservación entre sesiones y reset, más las de integración
  afectadas; conservar idempotencia, duplicados y CRLF.
  Usar la reconstrucción canónica y auditoría existentes si se genera un APK.
  C131 reconstruida y compilada: 22 pruebas JVM pasan y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. Las 32 pruebas host de reconstrucción y
  auditoría pasan; quedan seis fallos preexistentes en la selección host ampliada,
  documentados en `docs/launcher-app-status.md` sin afirmar suite global verde.
- [ ] 6. Con autorización y dispositivo disponibles, verificar versión instalada,
  configuración efectiva, opciones visibles y captura completa del juego; comprobar
  imagen/input y restauración tras IME. Cambiar y guardar gráficos desde el juego,
  cerrar normalmente y relanzar para comprobar conservación; probar luego reset.
  Sin prueba física, dejar explícitamente pendientes sombras/calidad efectiva,
  persistencia real del cliente y desaparición de franjas.
  Instalación y versión verificadas: C131 / `0.2.107-graphics-settings` en
  xaga/Android14 por Wi-Fi, `install -r`, UID10330 conservado y hash instalado
  idéntico al auditado. No se abrió la app ni el juego; lo restante sigue pendiente.
- [ ] 7. Registrar resultados y límites en documentación operativa, revisar diff,
  hacer commit atómico de los cambios propios y cerrar/mover este plan a `done/`.
  Código/pruebas comprometidos en `9d62f68`; resultados y límites registrados.
  El plan permanece activo: los puntos 2 y 6 aún requieren prueba del juego.

## Open Issues

- Aprobado: el botón restaura solo gráficos, manteniendo la
  resolución elegida. No existe selector de calidad en la APK ni calidad fija
  impuesta después de que el usuario guarde cambios dentro del juego.
- Conservación implementada: la existencia de un derivado regular y sin symlink
  distingue la reutilización de la primera creación; no hay migración que reinicie
  gráficos al actualizar la APK. Falta comprobar físicamente que el cliente guarda
  sus elecciones en ese mismo derivado.
- La causa actual de las franjas y del preset no aplicado no está confirmada.
- Instalación autorizada en xaga/22041216G mediante ADB Wi-Fi. La inspección previa
  encuentra C130 y procesos Wine activos bajo UID10330. Tras solicitar cierre normal,
  el usuario confirma «Ya está cerrado»: WoW y Wine quedan en estado Z; solo siguen
  activos launcher, logcat y el helper `libpulseaudio.so`. La comprobación fresca
  previa a instalar no encuentra guest vivo ni servicios activos. Instalación C131
  correcta y verificada; no se ha autorizado arrancar el juego ni probar su UI.
- Si corregir las franjas exige modificar el runtime/driver o cambia la política
  de recortes seguros, detener ejecución y presentar el impacto para aprobación.
