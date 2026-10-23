---
id: diagnostic-apk-delivery-2026-09-28
state: done
date: 2026-09-28
---

## Summary

Preparar una entrega local, instalable sin ADB, del banco de pruebas diagnóstico
Android existente. El alcance queda limitado a `android/`, la documentación de
`docs/android-emulator.md` y `docs/fase-5-launcher.md`, y este plan. No se
incorporan Winlator, Wine, WoW ni runtime de juego.

El estado observado ya contiene la batería JNI/contrato, ExecProbe y el hijo
synthetic del patcher, persistencia y exportación SAF, pero la entrega debe
cerrar la verificación de empaquetado y uso autónomo. C2 se conserva como
`UNSUPPORTED` cuando `nativeLibraryDir` no contiene una `.so`; solo se investigará
una corrección pequeña de empaquetado (`extractNativeLibs`/`useLegacyPackaging`)
sin bajar seguridad ni cambiar `targetSdk=28`. Si eso exige una decisión de
arquitectura, se detiene la ejecución y se informa, sin ampliar el alcance.

## Content

### Límites y propiedad

- Propiedad exclusiva: `android/`, `docs/android-emulator.md`,
  `docs/fase-5-launcher.md` y este plan.
- No revertir ni modificar cambios concurrentes fuera de esos límites.
- No comprometer, publicar, subir a red ni iniciar servidor. El APK final y las
  instrucciones se guardan fuera del repositorio en
  `/home/adrian/wowforever-artifacts/delivery/`, tras verificar el directorio
  padre con `ls`.
- No detener descargas, procesos ni el emulador durante las verificaciones.

### Identificación de la entrega

- Usar la identidad diagnóstica existente, con `versionName`/`versionCode`
  apropiados para esta entrega, sin inventar una provenance exacta de commits
  si el árbol está sucio.
- Incluir esa identidad inequívocamente en el APK y en el JSON/instrucciones.
- Entregar explícitamente ruta absoluta, SHA-256, tamaño, firma debug,
  installable debug y permisos declarados.

### Verificaciones previstas

1. Reproducir/inspeccionar el estado actual y diagnosticar C2; aplicar únicamente
   el ajuste pequeño de empaquetado si la evidencia lo justifica. Mantener C2
   honesto como `UNSUPPORTED` si no hay `.so` y no tocar `targetSdk=28`.
2. Asegurar que el APK incluye `arm64-v8a` y `x86_64`, sin runtime externo ni
   cliente, y que la ruta de carga JNI funciona con las bibliotecas empaquetadas.
3. Confirmar UI autónoma: ejecutar la batería desde la Activity, exportar JSON
   mediante SAF sin ADB y mostrar límites (no escribe el juego; no promete crash
   dump).
4. Confirmar persistencia al relanzar tras cierre: resultados completos se
   conservan y una corrida interrumpida se marca `TIMEOUT` incierto al reabrir;
   no afirmar cancelación nativa ni captura de crash dump.
5. Ejecutar tests de lógica afectados, `assembleDebug` offline con
   `--max-workers=1` y comprobaciones de APK (ABI, firma debug, SHA-256).
6. Probar la batería y la exportación SAF en el emulador existente, guardando la
   evidencia fuera del repo. Registrar claramente que el emulador solo prueba
   `x86_64`/SwiftShader; `arm64-v8a` se verifica por empaquetado, no ejecución.
7. Copiar el APK final con nombre claro de versión a `delivery/` y escribir junto
   a él instrucciones breves: instalar sin ADB, ejecutar batería, exportar JSON,
   enviar JSON, reiniciar si se cierra y límites conocidos.
8. Completar este checklist y mover el plan a `.agents/planning/done/` con
   `state: done` solo cuando todos los puntos estén verificados; si aparece una
   decisión arquitectónica o bloqueo, dejarlo en `## Open Issues` y parar.

## Implementation / Action Items

- [x] 1. Releer este plan aprobado y comprobar que los cambios existentes no se
  pisan.
  - [x] 1.1 Verificar `/home/adrian/wowforever-artifacts` y crear/usar solo
    `delivery/` fuera del repositorio.
- [x] 2. Inspeccionar el APK/build actual y el diagnóstico C2.
  - [x] 2.1 Determinar si `nativeLibraryDir` sin `.so` es solo extracción/
    empaquetado y si una corrección mínima es segura.
  - [x] 2.2 No requirió arquitectura nueva: `useLegacyPackaging=true` resolvió
    la extracción y C2 quedó medible.
- [x] 3. Implementar solo los ajustes necesarios dentro de los límites.
  - [x] 3.1 Mantener JNI/ExecProbe/hijo synthetic y `targetSdk=28`.
  - [x] 3.2 Mantener persistencia, SAF y estado `UNSUPPORTED` honesto para C2
    cuando falta la `.so`; C2 medido acepta rechazo o execve confirmado por señal.
  - [x] 3.3 Añadir/ajustar identificación de build diagnóstica sin provenance
    falsa.
- [x] 4. Ejecutar verificación lógica y de empaquetado offline.
  - [x] 4.1 Tests de lógica afectados: `DiagnosticLogicSelfTest: PASS`.
  - [x] 4.2 `./gradlew --offline --max-workers=1 :app:assembleDebug`:
    `BUILD SUCCESSFUL`.
  - [x] 4.3 Confirmar ABIs, firma debug, permisos, ausencia de runtime/WoW,
    SHA-256 y tamaño.
- [x] 5. Ejecutar batería UI y exportación SAF en el emulador sin detenerlo.
  - [x] 5.1 Batería PASS en `emulator-5554`, run
    `run-21c42243826c40218eed0c9b5fdad2dd`; el ajuste final de mapping fue
    semánticamente equivalente y no se repitió la batería.
  - [x] 5.2 SAF guardó y se verificó el JSON exportado fuera del repo.
  - [x] 5.3 El APK final se instaló y relanzó; la corrida persistida reapareció
    como PASS sin rerun.
- [x] 6. Preparar entrega local e instrucciones y actualizar las dos bitácoras.
  - [x] 6.1 Copiar APK versionado a `/home/adrian/wowforever-artifacts/delivery/`.
  - [x] 6.2 Crear instrucciones breves junto al APK.
  - [x] 6.3 Registrar comandos, resultados exactos, límites y bloqueos.
- [x] 7. Cerrar el plan solo con evidencia completa; devolver ruta absoluta,
  hash, tamaño, permisos, firma, estado final de batería y riesgos residuales.

## Open Issues

- C2 quedó confirmado en el APK final: `useLegacyPackaging=true` extrae la `.so`
  y `execve` devuelve ejecución permitida seguida de señal 11. Eso no se presenta
  como crash de la aplicación.
- No hay ejecución ARM64 disponible en este entorno: la ABI ARM64 se comprobará
  en el APK, no en runtime.
- La aprobación explícita del usuario fue concedida antes de ejecutar este plan.
