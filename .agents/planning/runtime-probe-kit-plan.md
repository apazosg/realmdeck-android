---
id: runtime-probe-kit-2026-09-28
state: draft
date: 2026-09-28
---

## Summary

Siguiente ampliación propuesta del APK para diagnosticar un runtime Windows
existente sin ADB. Pendiente de aprobación del alcance y confirmación del runtime.
El usuario aportó resultado ARM64 API 36 PASS de la batería Android
`run-d514316b253c46b4bf0f9f17d2b07bd1`: hijo PATCHED, reescaneo idempotente,
533/533 regiones legibles, duración total ~39,6 s. Es evidencia aportada por el
usuario, no una ejecución local ni prueba de Wine/WoW.

## Content

Puente mínimo: APK exporta kit por SAF; usuario lo ejecuta dentro de un runtime
instalado desde una carpeta compartida; APK importa resultados por SAF y exporta
informe consolidado. No automatizar intents no demostrados ni empaquetar runtimes.

La configuración documentada de Winlator/FrontMage mapea Download a D:, pero
debe confirmarse en el contenedor del usuario. XServerDisplayActivity no está
exportada en la fuente FrontMage inspeccionada: no asumir control entre apps.

Primera iteración: DynCodeProbe y ApiProbe; SlotProbe contra WowB queda fuera
hasta conocer ubicación y build del cliente. No lanzar ni modificar el juego.
Los binarios se generan en build/artefactos externos; no se versionan ejecutables.
Runtime/version se registran como declarados por el usuario, no autodetectados.

## Implementation / Action Items

- [ ] Confirmar runtime instalado y acceso del contenedor a Download/D:.
- [ ] Diseñar formato acotado de kit y resultado con ID de ejecución, versión,
  hashes de sondas, resultados por caso, códigos de salida y logs.
- [ ] Añadir correlación de ejecución a las sondas y runner Windows para no
  importar logs antiguos de C:\wow\diag como actuales. Conservar evidencia
  parcial si una sonda termina por excepción; no interpretar ausencia como PASS.
- [ ] Generar y exportar kit desde APK mediante SAF, con instrucciones y runner
  manual. Aislar directorios por ejecución y no sobrescribir archivos del juego.
- [ ] Importar informe mediante SAF con límites de tamaño y parsing estricto;
  no extraer rutas arbitrarias ni ejecutar contenido importado. Los hashes
  permiten comprobar consistencia, no autentican el runtime que produjo el log.
- [ ] Mostrar resultados diferenciados de la batería nativa Android, con
  procedencia y pruebas incompletas explícitas; exportar informe consolidado.
- [ ] Validar runner/sondas en Wine host, importación/exportación en emulador y
  fixtures negativos (ID ajeno, incompletos, duplicados, tamaños excesivos).
- [ ] Compilar APK, verificar ABIs/firma, entregar por SFTP con hash e instrucciones.
  Ejecución Windows ARM64 pendiente del usuario, no del emulador x86_64.

## Open Issues

- ¿Está instalado FrontMage, Winlator oficial u otro? ¿Qué versión muestra?
- Confirmar carpeta compartida visible desde el contenedor; no exigir al usuario
  package ID/container ID si no los conoce. Registrar datos desconocidos como tales.
- Aprobar flujo con un paso manual de ejecución dentro del runtime: aún no es
  un botón que controla otro APK. Si se exige automatización completa, hay que
  investigar integración distinta antes de implementar.
