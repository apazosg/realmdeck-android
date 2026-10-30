---
id: usb-resume-ux-release-2026-10-30
state: draft
date: 2026-10-30
---

## Summary

El usuario quiere recuperar y continuar en el Xaga los datos parciales que venían
de otro móvil, mejorar la claridad y sencillez del flujo, reducir el tiempo y,
después, publicar la versión en GitHub. El USB ya está conectado al Xaga y no debe
trasladarse. Este plan complementa el plan de portabilidad ya aprobado; no sustituye
sus consentimientos específicos ni su objetivo de revisión de hasta 900 s.

## Content

- C168 ya mostró en Xaga una revisión confirmable de 822.658 objetos reutilizables,
  322.320 objetos CASC pendientes, hasta 36,9 GiB de descarga y hasta 39,4 GiB de
  espacio adicional (hasta 2,0 GiB en la app y 37,4 GiB en el USB). Se desconoce la
  duración total de la revisión. No se ha confirmado la adopción ni la descarga.
- Priorizar aprovechar ese plan, no perderlo innecesariamente instalando otra APK
  o repitiendo la revisión. Comprobar que sigue vigente y solicitar el consentimiento
  específico de adopción con los costes reales. La adopción crea estado propio y
  puede actualizar el marcador; no inicia payload. El plan posterior de descarga
  requiere su confirmación separada. Ante cambios o fallos, no inventar el resultado
  ni relajar las guardas.
- Revisar el recorrido de almacenamiento y de gestión del cliente para que el
  usuario distinga una descarga parcial, la verificación, el resultado, la
  recuperación y la descarga pendiente. Simplificar textos, navegación y acciones
  confusas sin ocultar costes ni eliminar los consentimientos vigentes. No presentar
  un cliente parcial como instalado o listo para jugar.
  Actualmente el destino se selecciona en Almacenamiento y la revisión se inicia
  aparte en Cliente y actualizaciones; las acciones de revisión y de recuperación
  interrumpida aparecen juntas. Facilitar el acceso y hacer visible el destino
  concreto antes de revisar o recuperar, sin iniciar operaciones automáticamente.
- Mejorar el tiempo de revisión hasta el resumen confirmable; el objetivo solicitado
  es 900 s en la fixture física documentada, no una garantía universal. En C167 el
  catálogo seguía preparándose a los 890,6 s; en C168 no se observó el tiempo total.
  Elegir una optimización acotada basada en evidencia y medir fases y tiempo total.
  Si ya se recuperó la fixture real, no dañar ni revertir su progreso para repetir
  la prueba: usar una fixture segura y registrar la diferencia de condiciones.
- Mantener las verificaciones EKey, BLTE, CKey y tamaños, los límites de recursos,
  identidad, exclusión, Wi-Fi, cancelación y durabilidad. No copiar estado privado
  del móvil anterior ni reinterpretar el marcador como prueba de integridad.
  Respetar US-007, US-008 y US-009; si se propone modificar criterios aprobados,
  documentar y aprobar primero el cambio de comportamiento y su impacto.
- Publicar después como **prerelease experimental**, siguiendo la práctica de
  `docs/releasing.md`, sin afirmar un rendimiento o compatibilidad no demostrados.
  Derivar la integración pública del `main` público, nunca publicar sin auditoría
  la historia local o privada. Comprobar identidad, versión,
  firma, checksum, privacidad, notices y licencias de los componentes y adjuntos;
  mantener cliente, datos CASC, claves y evidencias privadas fuera de la publicación.
  Publicar solo el APK y materiales cuya redistribución esté autorizada y auditada.

## Implementation / Action Items

- [ ] 1. Acordar este plan y el momento de la adopción real; confirmar que el
  resumen C168 en Xaga sigue siendo el mismo y que hay espacio.
- [ ] 2. Con consentimiento específico, recuperar el parcial mediante la app, sin
  iniciar payload; verificar el nuevo estado. Mostrar el plan actualizado y pedir
  la confirmación separada antes de comenzar o reanudar su descarga.
- [ ] 3. Analizar el recorrido real y el perfil de costes; realizar los cambios
  mínimos de interfaz y rendimiento dentro de los criterios aprobados, con pruebas
  focalizadas de mensajes, navegación y seguridad de la recuperación.
- [ ] 4. Compilar y auditar la candidata exacta, pedir revisión independiente de
  las partes sensibles y probar de manera proporcionada. No interrumpir una descarga
  activa para instalar una APK; actualizar sin desinstalar ni borrar datos.
- [ ] 5. Medir en condiciones documentadas y reflejar si el objetivo de 900 s queda
  demostrado, no demostrado o incumplido; no alterar el parcial para la medición.
- [ ] 6. Preparar la integración pública y la prerelease GitHub, con notas en inglés,
  assets auditados y sumas; publicar cuando pasen las barreras de privacidad,
  redistribución y verificación y comprobar metadatos y hashes publicados.

## Open Issues

- La optimización concreta y su ganancia se decidirán con el perfil y las pruebas;
  no se promete que el límite de 900 s se logre antes de medirlo.
- La revisión actual está en memoria: instalar otra APK antes de adoptar puede
  perder el resumen y obligar a repetir trabajo. La adopción con su consentimiento
  específico evita descartarlo innecesariamente; no demuestra por sí sola que la
  posterior descarga o publicación del cliente haya terminado.
- La publicación depende de que el acceso GitHub, el árbol público y los derechos
  de redistribución de la candidata pasen las comprobaciones. No usar un proyecto
  Android de diagnóstico como si fuera la app funcional.
