---
id: addon-management-2026-10-28
state: draft
date: 2026-10-28
---

## Summary

Goal: añadir a la app Android funcional de WoW Forever una gestión de addons
sencilla e intuitiva: ver los instalados, desinstalar o desactivar y añadir otros
buscando en CurseForge. Este documento propone el alcance; no aprueba cambios de
comportamiento ni da por resuelta la autorización de CurseForge.

Solo se ha realizado inspección de fuentes y documentación pública. No se ha
modificado la app, consultado la API autenticada, solicitado claves ni operado el
dispositivo. El goal no estará completo por terminar este plan o la gestión local:
la búsqueda e instalación desde CurseForge forman parte del resultado solicitado.

## Content

### Estado observado y límites

- La UI funcional es `scripts/arm64-prototype/app/overlay/ForeverFunctionalPocActivity.kt`
  (Kotlin/Compose), no el proyecto diagnóstico `android/`.
- El almacenamiento seleccionado y sus guardas viven en
  `overlay/ForeverFunctionalClientStorage.kt`; hay exclusión compartida con el
  cliente/runtime y el actualizador. No se ha verificado todavía la ruta efectiva
  de addons en un dispositivo ni cómo se publican carpetas multiparte.
- No existe una historia dedicada a addons en `.agents/specs/US-001.md` a
  `US-009.md`. Se propone `US-010.md`, pendiente de comprobar disponibilidad al crearla.
- Aplican `SYSTEM.md` (draft), US-004 (UX, ready), US-007 (almacenamiento, dirty)
  y US-008 (actualizador/exclusión, dirty). No se reinterpretarán sus estados como
  aprobación nueva ni se modificarán sus contratos sin decisión explícita.
- El cliente de referencia es Classic Forever / 1.60.1; “Classic” genérico no
  demuestra compatibilidad. Debe verificarse el etiquetado Forever y la versión
  del archivo, sin prometer funcionamiento en el juego solo por sus metadatos.

### Propuesta visual y funcional, pendiente de aprobación

1. Entrada visible **Addons** desde la pantalla principal, manteniendo el estilo
   oscuro/dorado existente. Dos vistas: **Instalados** y **Buscar**; sin controles
   técnicos en el recorrido principal.
2. **Instalados**: nombre legible, versión si se conoce y estado; un interruptor
   permite desactivar y volver a activar, y una acción secundaria permite
   desinstalar con confirmación. Estado vacío con acceso directo a Buscar.
3. **Desactivar** es reversible y conserva archivos y configuración. Propuesta:
   alcance global para esa instalación, no perfiles por personaje. El mecanismo
   concreto debe validarse con el cliente antes de especificarlo como requisito.
4. **Desinstalar** retira exclusivamente las carpetas del addon elegido;
   propuesta: conservar SavedVariables y no ofrecer borrado de preferencias.
   No tocar addons integrados de Blizzard ni archivos ajenos. Validar cómo se
   identifican y agrupan addons manuales y paquetes con varias carpetas.
5. **Buscar**: campo de búsqueda, resultados con nombre, breve descripción y
   autor; acción **Instalar** para un archivo admitido de Forever. Mostrar
   claramente instalado, no disponible, sin resultados y errores de conexión o
   autorización. No sustituir silenciosamente por una versión de Retail/Classic.
6. Instalación con progreso y resultado fiel, sin aparentar éxito parcial.
   No sobrescribir carpetas existentes sin una decisión aprobada; detectar
   dependencias obligatorias antes de instalar y definir su tratamiento.
7. Gestión local disponible sin red. Mutaciones solo con cliente cerrado,
   almacenamiento correcto y exclusión compartida adquirida. Las operaciones
   largas no bloquean la UI. Etiquetas accesibles y controles táctiles conforme
   a los patrones existentes.

### Integración externa: hechos y decisión pendiente

Fuentes oficiales consultadas el 2026-10-28:

- [REST API](https://docs.curseforge.com/rest-api): API de proyectos y archivos,
  búsqueda y filtros. Su uso por terceros requiere una clave autorizada.
- [Autenticación del CDN](https://blog.curseforge.com/introducing-api-key-authentication-for-curseforge-file-downloads/):
  anuncio oficial del 2026-06-10; exige clave desde el 16 de julio y documenta
  `x-api-key` y errores 401. No se ha comprobado una descarga real.
- [Términos de terceros](https://support.curseforge.com/en/support/solutions/articles/9000207405-curse-forge-3rd-party-api-terms-and-conditions):
  restricciones sobre compartir la clave, cachear datos, servicios competidores
  y ocultar la identidad mediante proxy. Es necesaria confirmación del modelo
  permitido; no se presupone que un intermediario sea automáticamente admisible.
- [Distribución por terceros](https://support.curseforge.com/en/support/solutions/articles/9000207877-project-distribution-toggle):
  respetar las opciones de distribución de los autores y la disponibilidad real
  de archivos/URLs. No reconstruir URLs para eludir restricciones.
- Ejemplos de proyectos Forever: [Questie Forever](https://www.curseforge.com/wow/addons/questie-forever)
  y [Quartz - Forever](https://www.curseforge.com/wow/addons/quartz-forever).
  Son evidencia de catálogo, no pruebas de compatibilidad en nuestro dispositivo.

No incrustar secretos en APK/repositorio, hacer scraping ni usar claves ajenas.
No añadir backend, contratar servicios ni aceptar términos en nombre del usuario.
Primero confirmar si el proyecto dispone de acceso autorizado y qué arquitectura
permite CurseForge; si requiere infraestructura nueva, someterla a aprobación.
La ausencia de clave no justifica cerrar el goal como completo con un buscador
simulado o un enlace externo en lugar de la integración solicitada.

### Seguridad y validación previstas

- Acotar lectura/escritura a los addons del cliente seleccionado y conservar
  Data, cuenta, configuración y addons ajenos. Reutilizar exclusión/identidad de
  almacenamiento; no crear un segundo sistema de bloqueo incompatible.
- Validar ZIP antes de publicar: rechazar traversal/rutas absolutas, enlaces y
  tamaños/entradas abusivos; no ejecutar contenido descargado durante instalación.
  Usar límites concretos proporcionados al catálogo real y staging acotado.
- Definir publicación y recuperación coherentes también para paquetes con varias
  carpetas; un fallo no puede dejar un addon parcial presentado como instalado.
- Pruebas focales: inventario local, desactivar/reactivar, desinstalación aislada,
  ZIP peligroso o instalación interrumpida, conflicto de carpetas/dependencias,
  rechazo de archivo no compatible, fallo API y exclusión con juego/actualizador.
- Verificar reconstrucción canónica y auditoría de fuentes/payloads existentes al
  generar APK; no rebajar sus comprobaciones para admitir los nuevos componentes.
- Validación física posterior: recorrido visual, selección de almacenamiento y
  addon compatible real. Distinguir pruebas host, visuales y carga dentro del juego;
  no prometer estabilidad general. Evidencia y artefactos fuera del repositorio.

### Fuera de alcance propuesto

Actualizaciones automáticas de addons, sincronización de cuentas, perfiles por
personaje, otros proveedores, borrado de SavedVariables y rediseño general del
launcher. Reactivar sí forma parte de una desactivación reversible.

## Implementation / Action Items

- [x] 1. Crear el goal y revisar specs, puntos de integración y fuentes oficiales.
- [ ] 2. Obtener aprobación del alcance visual/funcional y resolver Open Issues.
- [ ] 3. Crear US-010 en draft con US/UC/AC; obtener aprobación antes de derivar
  FR/NFR. Después aprobar FR/NFR y TC conforme al flujo spec-as-source.
- [ ] 4. Confirmar autorización y arquitectura permitida de CurseForge, incluidos
  secretos, descargas y persistencia mínima; no implementar contra supuestos.
- [ ] 5. Implementar inventario y acciones locales en el launcher funcional,
  reutilizando las guardas existentes y cambios mínimos de reconstrucción.
- [ ] 6. Implementar búsqueda e instalación CurseForge admitidas, compatibilidad,
  errores y extracción/publicación segura; pruebas solo del comportamiento tocado.
- [ ] 7. Revisar independientemente archivos no confiables, secretos y concurrencia;
  ejecutar verificaciones focales, build/auditoría y aceptación física posible.
- [ ] 8. Actualizar trazabilidad/evidencias, hacer commits atómicos, auditar el
  resultado completo del goal y archivar este plan como done solo al terminar.

## Open Issues

1. **Bloqueante para implementar:** aprobación del alcance y posterior historia.
   Son propuestas, no requisitos aprobados, la entrada en Home, el alcance global
   y conservar SavedVariables tras desinstalar.
2. **Bloqueante para integración real:** ¿dispone el proyecto de clave/acuerdo
   autorizado de CurseForge? ¿Qué modelo permite para este launcher Android sin
   exponer la clave? No enviar claves por chat ni incorporarlas a este documento.
3. **Resolver con inspección/prueba antes de cerrar UC/AC:** ruta real de addons,
   clasificación Blizzard/terceros, mecanismo reversible de desactivación,
   agrupación de paquetes, selección Forever/versiones y dependencias obligatorias.
   Si implican decisiones de producto, presentarlas para aprobación en lugar de
   inventar comportamientos o ampliar el alcance.
