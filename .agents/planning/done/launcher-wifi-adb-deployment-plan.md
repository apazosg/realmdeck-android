---
id: launcher-wifi-adb-deployment-2026-10-21
state: done
date: 2026-10-21
---

## Summary

**Completado:** C129 instalada por Wi-Fi ADB en xaga/22041216G, Android 14,
`192.168.1.130:40645`, con `install -r: Success`. VersionCode 129 y
`0.2.105-reuse-preparation` confirmados; SHA256 del APK instalado idéntico al
auditado: `95e2b4fef825598ee119b7bd59dda6837a7841606b49287205c8c7860e6fd76b`.
No se abrió la app ni Wine/WoW. Evidencia externa y recibo en
`/home/adrian/wowforever-artifacts/arm64-prototype/client129-wifi-adb-ime-fixed-20261021/`.
Los apartados siguientes conservan el contexto y bloqueos históricos.

El usuario solicita compilar y desplegar por ADB Wi-Fi la nueva versión del
launcher ARM64 funcional. La candidata identificada en la receta actual es C129,
`com.wowforever.arm64probe`, versionCode `129`, versionName
`0.2.105-reuse-preparation`. C129 está compilada y auditada externamente, pero
figura como no instalada; se hará una compilación/auditoría nueva desde el estado
actual del árbol de trabajo antes de instalar. El candidato está en el HEAD local
`1bd8fcc`; no se cambiará ni se creará ningún commit de código. Se preservarán las
ediciones/planes locales que quedan fuera del candidato.

El teléfono ya está conectado por ADB inalámbrico como
`192.168.1.130:40645`; verificado `22041216G`/`xaga`, Android 14. C129 sigue siendo
la candidata; el paquete instalado es C127 (`0.2.103-guarded-group8-apply`). La app,
WoW y Wine no están ejecutándose y la pantalla en primer plano es Ajustes.

## Alcance y salvaguardas

- Usar la app funcional `com.wowforever.arm64probe`, no la app diagnóstica
  `com.wowforever.launcher`.
- Mantener identidad/firma/versionado de C129 según la receta; no inventar C130 ni
  cambiar fuentes o versión durante esta operación.
- Dejar fuentes, APK, logs y recibos fuera del repositorio, bajo
  `/home/adrian/wowforever-artifacts/`.
- Instalar con actualización conservando datos (`adb install -r`) únicamente tras
  confirmar el dispositivo Wi-Fi correcto y que la app/WoW no están activos. Si hay
  un proceso vivo, versión instalada superior o firma incompatible, detenerse y
  consultar; no forzar cierre, desinstalar, borrar datos, reiniciar ni reparar.
- Desplegar significa instalar y verificar la versión; no abrir la app ni iniciar
  Wine/WoW/Login.
- Si se instala correctamente, añadir una nota factual breve a
  `docs/launcher-app-status.md` con versión, hash, dispositivo y alcance de lo
  verificado; no editar historial ajeno ni afirmar pruebas de ejecución del juego.
- Guardar APK, logs, recibo y evidencia operativa fuera del repositorio.

## Implementación / acciones

- [x] 1. Recibir aprobación del plan. La disponibilidad de ADB Wi-Fi se mantiene
  como requisito previo para la instalación (paso 4); la compilación puede avanzar.
- [x] 2. Comprobar la herramienta ADB, SDK/JDK fijados y rutas de fuentes externas;
  el reconstructor validará sus pines antes de generar la candidata. La fuente de
  código está en el commit local `1bd8fcc` (la receta/versionado actual sigue C129).
  No alterar el árbol local.
- [x] 3. Reconstruir C129 fuera del repo; receipt creado con el commit fuente
  `34a9556f9d25db30347cdd0cbd7c7b6f4226d1cb` y entradas autenticadas.
- [x] 3.1 Ejecutar Kotlin focal para almacenamiento, UX, resolución, perfiles,
  CASC y actualización del cliente; las gates host exploratorias registraron
  `59 passed, 6 failed`. El usuario autoriza dejar esas seis fallas anotadas, no
  reparar pruebas fuera del alcance y continuar con las gates focales. La tarea
  Gradle falló antes de ejecutar pruebas al compilar producción; revisar el Open
  Issue antes de reanudar.
- [x] 3.2 Compilar `assembleModernDebug` offline con el JDK/SDK fijados.
- [x] 3.3 Auditar paquete, versión, ABI, firma/procedencia y ausencia de payloads
  del cliente. No instalar si falla una gate Kotlin focal o la auditoría.
- [x] 4.1 ADB Wi-Fi verificó `192.168.1.130:40645`, modelo `22041216G`/xaga,
  Android 14, C127 instalado y sin procesos launcher/WoW/Wine. Preflight externo en
  `verified/device-preflight.txt`.
- [x] 4.2 Justo antes de instalar, volver a comprobar procesos/actividad y confirmar
  que el paquete sigue en versión inferior o igual a C129; el gestor de paquetes
  verificará firma compatible al actualizar.
- [x] 5. Instalar el APK auditado con `adb -s <serial-wifi> install -r`, sin
  `uninstall`, `-d`, limpieza de datos ni lanzamiento automático.
- [x] 6. Confirmar por ADB paquete/versionCode/versionName instalados y registrar
  hash del APK, dispositivo, comandos y resultado en un recibo externo.
- [x] 7. Mover este plan a `.agents/planning/done/` y marcarlo `done` tras verificar
  el despliegue; si ADB sigue inaccesible, dejarlo abierto y reportar el bloqueo.

## Open Issues

- Bloqueos de compilación/auditoría resueltos: import `layout.ime` y admisión
  exclusiva del descriptor `ForeverFunctionalKeyboardKeys`, con regresión negativa
  de nombres vecinos y revisión independiente sin bloqueantes. No se amplían prefijos.
- Resultado final: build 6m22s; 241 tests JVM, 0 fallos/errores, 1 omitido;
  8 pruebas host del auditor correctas; `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`.
  Las seis fallas host exploratorias quedan registradas y aceptadas por el usuario;
  no se declara suite global verde. Prueba de interfaz/teclado y juego no ejecutada.

- El usuario autoriza resolver el bloqueo de compilación. Se amplía el alcance
  únicamente para añadir el import `androidx.compose.foundation.layout.ime` que
  falta en la Activity, sin cambiar lógica ni C129. Regenerar el receipt desde la
  fuente corregida, repetir compilación/pruebas focales y auditar antes de instalar.
- La compilación falla en
  `scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt:1201`:
  `WindowInsets.ime` produce `Unresolved reference 'ime'`. El código ya implementa
  la fila de teclado/IME especificada por US-004; parece faltar el import
  `androidx.compose.foundation.layout.ime`. La corrección mínima requiere modificar
  fuente del repo, prohibido por el alcance aprobado hasta recibir autorización.
  No se ejecutaron las pruebas Kotlin, no se generó APK ni se instaló nada.
- La reconstrucción externa de C129 terminó correctamente, pero el lote host
  exploratorio registró `59 passed, 6 failed`: un test de texto de cuenta que el
  plan de reutilización ya declara excluido, y cinco tests de la receta que fallan
  al usar fixtures de manifiesto sin el ancla
  `FOREGROUND_SERVICE_DATA_SYNC` requerida por el transform actual. El reconstructor
  sí validó las entradas reales. Por aprobación del usuario, no se arreglan esas
  fallas host fuera de alcance; continuar con las pruebas Kotlin focales y audit APK.
