---
id: addon-management-2026-10-28
state: in-progress
date: 2026-10-28
---

## Summary

Goal: añadir a la app Android funcional de WoW Forever una gestión de addons
sencilla e intuitiva: ver los instalados, desinstalar o desactivar y añadir otros
buscando en CurseForge. El usuario aprobó continuar el 2026-10-28 y reutilizar
nuestro proyecto Vercel existente para la integración. Esa aprobación del plan
no sustituye las revisiones de US/UC/AC, FR/NFR y TC ni resuelve la autorización
de CurseForge.

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
- Se ha creado [US-010](../specs/US-010.md) en draft con US/UC/AC, pendiente de
  revisión del usuario antes de derivar FR/NFR y TC.
- Aplican `SYSTEM.md` (draft), US-004 (UX, ready), US-007 (almacenamiento, dirty)
  y US-008 (actualizador/exclusión, dirty). No se reinterpretarán sus estados como
  aprobación nueva ni se modificarán sus contratos sin decisión explícita.
- El cliente de referencia es Classic Forever / 1.60.1; “Classic” genérico no
  demuestra compatibilidad. Debe verificarse el etiquetado Forever y la versión
  del archivo, sin prometer funcionamiento en el juego solo por sus metadatos.

### Alcance del plan aprobado; concreción UC/AC pendiente

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

### Integración externa: arquitectura aprobada y límites pendientes

El usuario propuso usar un endpoint en nuestro backend Vercel y confirmó
«perfecto adelante» tras identificar el proyecto ya existente:

- Reutilizar `services/diagnostics/`, Vercel Functions TypeScript sin Next.js,
  documentado en [diagnostics-api.md](../../docs/diagnostics-api.md).
- Flujo **app → rutas propias de addons → API/CDN oficial de CurseForge**.
  La clave queda exclusivamente en configuración secreta del servidor; nunca
  en el APK, parámetros de URL, respuestas, redirecciones al cliente o logs.
- Separar rutas y configuración de addons de las de informes. No reutilizar
  `DIAGNOSTICS_READ_TOKEN`, capabilities de informes ni `CRON_SECRET` para addons;
  no cambiar permisos, datos, retención ni rutas de diagnósticos.
- No crear otro backend, una caché de catálogo ni almacenamiento de ZIP en Blob
  como requisito implícito. La descarga autenticada necesita un transporte
  permitido por CurseForge y verificado con los límites del proyecto Vercel.
- No ofrecer un proxy de URLs arbitrarias: admitir solo proyectos/archivos
  CurseForge identificados y revalidados por el servidor.

La aprobación de arquitectura no autoriza desplegar, ampliar el plan de pago,
aceptar términos ni eludir restricciones del proveedor.

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
No contratar servicios ni aceptar términos en nombre del usuario. Confirmar si el
proyecto dispone de acceso autorizado y si CurseForge permite este modelo de
integración; reutilizar Vercel no elimina esa comprobación. Antes de implementar
el transporte, verificar streaming, límites de tamaño/duración y consumo de
transferencia del proyecto existente ([límites](https://vercel.com/docs/functions/limitations),
[streaming](https://vercel.com/docs/functions/streaming-functions)). La documentación
general no acredita la configuración o cuota restante de nuestro despliegue.
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
- [x] 2. Obtener aprobación del plan y decidir arquitectura: backend Vercel
  existente con rutas de addons separadas, confirmado por el usuario el 2026-10-28.
- [ ] 3. Crear US-010 en draft con US/UC/AC; obtener aprobación antes de derivar
  FR/NFR. Borrador creado; después aprobar FR/NFR y TC conforme al flujo spec-as-source.
- [ ] 4. Confirmar autorización y arquitectura permitida de CurseForge, incluidos
  secretos, descargas y persistencia mínima; no implementar contra supuestos.
- [ ] 5. Implementar inventario y acciones locales en el launcher funcional,
  reutilizando las guardas existentes y cambios mínimos de reconstrucción.
- [ ] 6. Implementar búsqueda e instalación CurseForge admitidas, compatibilidad,
  errores y extracción/publicación segura, con rutas independientes en
  `services/diagnostics/`; pruebas solo del comportamiento tocado y no regresión
  del contrato de diagnósticos.
- [ ] 7. Revisar independientemente archivos no confiables, secretos y concurrencia;
  ejecutar verificaciones focales, build/auditoría y aceptación física posible.
- [ ] 8. Actualizar trazabilidad/evidencias, hacer commits atómicos, auditar el
  resultado completo del goal y archivar este plan como done solo al terminar.

## Open Issues

1. **Bloqueante para derivar FR/NFR:** revisar y aprobar US/UC/AC de US-010.
   El plan y la arquitectura ya están aprobados; la historia concreta propone
   rechazar sobrescrituras y dependencias ausentes, y no agrupar instalaciones
   manuales por conjeturas. Después se mantienen las puertas FR/NFR y TC.
2. **Bloqueante para integración real:** ¿dispone el proyecto de clave/acuerdo
   autorizado de CurseForge? ¿Permite servir búsquedas y descargas mediante este
   backend sin exponer la clave? No enviar claves por chat ni incorporarlas a este
   documento. Falta verificar transporte y cuotas Vercel sin afectar diagnósticos.
3. **Resolver con inspección/prueba antes de implementar:** ruta real de addons,
   clasificación Blizzard/terceros, mecanismo reversible de desactivación,
   agrupación de paquetes, selección Forever/versiones y dependencias obligatorias.
   Si implican decisiones de producto, presentarlas para aprobación en lugar de
   inventar comportamientos o ampliar el alcance.
