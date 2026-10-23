---
id: launcher-on-device-updates-2026-10-17
state: in-progress
date: 2026-10-17
---

## Summary

El usuario aclara: «idealmente desde la propia apk sin tener que tener que generar
una nueva versión de la apk». La preparación host de catálogos con posterior
reconstrucción del launcher no satisface el flujo normal solicitado. Este documento
sustituye esa decisión de distribución, no las garantías de integridad ni los
componentes de descarga, CASC, recuperación y exclusión ya implementados.

Diseño aprobado por el usuario con «si hszlo». Se inicia el port e integración de
admisión dinámica. No se cambia la instalación del móvil ni se habilita activación
por aprobar este plan; siguen los gates de identidad, consentimiento y prueba nativa.

## Content

### Flujo normal propuesto

1. **Comprobar versiones** desde la APK consulta NGDP oficial y distingue versión
   publicada, evidencia ARM64, compatibilidad de servidor y errores de consulta.
2. **Preparar actualización** obtiene build/CDN config, install, encoding,
   download/size e índices CDN necesarios desde los hosts oficiales. Verifica la
   cadena de hashes, tags y tamaños, sin ejecutar herramientas del host.
3. Genera el catálogo SQLite completo **en el almacenamiento privado de Android**,
   con parsers streaming/paginados, colas/buffers y espacio temporal acotados.
   Caché de metadatos verificables persistente para no repetir transferencias
   completas después de una interrupción o error de parsing.
4. Calcula reutilización real frente al destino elegido y muestra versión, red,
   espacio por volumen y estado de compatibilidad. Solo con confirmación comienza
   la descarga de payloads o la modificación del cliente.
5. Descarga/reanuda, verifica, construye índices canónicos y activa mediante el
   journal bajo exclusión duradera con Wine. Conserva originales y archivos ajenos.

### Frontera de confianza

- No aceptar un SQLite/JSON remoto solo porque declara su propio SHA o una versión.
  La admisión se deriva de metadatos oficiales verificados por la APK; el productor
  host es un oráculo de pruebas, no una autoridad remota que Android deba creer.
- Reutilizar HTTPS oficial, hashes de config, reglas TACT EKey/CKey y checksums de
  manifests/índices. No servidores nuevos, catálogos sin firma de terceros, claves
  de publicación nuevas ni importación arbitraria de rutas ejecutables.
- El catálogo70425 empaquetado puede conservarse como fallback/fixture verificado,
  pero no es la única build que el código puede preparar ni obliga a otro APK para
  admitir una nueva versión del protocolo soportado.
- La publicación en Blizzard no demuestra compatibilidad con Classic Forever.
  Si hay una fuente verificable de versión del servidor, consultarla separadamente;
  en su ausencia, mostrar pendiente y pedir confirmación explícita para el destino,
  sin autoactualizar por ser la última build genérica del producto.
- La admisión dinámica no convierte una prueba sintética en aceptación nativa.
  Sigue pendiente cualificar el motor/formato/FS en una instalación aislada. No
  eliminar el gate físico actual para aparentar disponibilidad de actualización.
- Una nueva versión ordinaria no requiere otro APK. Cambios de formatos TACT,
  modos BLTE, arquitectura, runtime o límites no soportados sí pueden exigir una
  actualización del launcher: bloquear con explicación, no prometer compatibilidad
  universal ni degradar verificaciones.

### Componentes reutilizados y trabajo nuevo

- Reusar `ForeverClientUpdateCheck`, transporte HTTPS acotado, catálogo SQLite,
  planner por objetos, CASC canónico/checkpoints, journal y lease/observador.
- Portar a Android los parsers y resolución que hoy solo existen en
  `client/update_manifest.py` y `client/admit_build.py`. No empaquetar Python ni
  depender de un PC, APK generado por versión o servicio privado de catálogos.
- Separar validación de esquema/protocolo de los pins históricos de70009/70425.
  Guardar fingerprints y recibos propios por instalación para futuras actualizaciones
  entre builds gestionadas, sin adoptar archivos desconocidos por nombre.
- Mantener scripts host parametrizables para preparar fixtures, reproducir fallos
  y contrastar resultados; no son un paso obligatorio del usuario final.

### Criterios de aceptación

- Una misma compilación de APK procesa dos builds sintéticas diferentes sin editar
  Kotlin, regenerar assets ni reinstalar el launcher.
- En Android, el manifiesto70425 produce los mismos fingerprints/conjunto de objetos
  y fuentes que el oráculo host ya verificado:1.144.978 objetos y36 archivos loose.
  No confundir esa igualdad de metadatos con haber descargado o ejecutado el juego.
- RAM/colas acotadas y trabajo en disco medido; fallo/reinicio conserva raw de
  metadatos ya verificados y contabiliza transferencias sin descargas repetidas.
- Fuente host no disponible, metadatos corruptos, nuevo formato o compatibilidad
  desconocida dejan un estado accionable, no «Al día» ni actualización automática.
- La comprobación y preparación de metadatos no alteran cliente/Data/WTF; la descarga
  de payloads muestra coste y pide consentimiento, y una transacción interrumpida
  se recupera sin permitir lanzamiento de una build mezclada.
- No distribución de cliente en APK/Git, credenciales, root o permisos all-files.

## Implementation / Action Items

- [x] 1. Aprobar esta sustitución del flujo de admisión y reconciliar US-008.
- [ ] 2. Portar parsers streaming y resolución oficial con fixtures host/Android
  comparables; pruebas negativas de hashes, tags, tamaños, límites y truncación.
- [ ] 3. Integrar generación local del catálogo, caché/reanudación de metadatos y
  planificación dinámica con el motor existente, eliminando restricciones de build
  concretas de sus decisiones de ejecución.
- [ ] 4. UI de comprobar/preparar/consentir/descargar/reparar/recuperar desde la APK;
  probar dos versiones con la misma compilación y sin intervención del host.
- [ ] 5. Review de admisión/recuperación, build y auditoría; cualificación física e
  instalación aislada antes de habilitar cambios en el cliente funcional.

## Open Issues

### Cambio solicitado: eliminar el veto de validación nativa

El usuario pide dejar de frenar el desarrollo con protecciones/tests/bloqueos.
Se elimina el requisito booleano de aceptación nativa como autorización de ejecución
y recuperación. Su estado continúa siendo un dato honesto, no se falsifica como
verificado. Se conservan consentimiento de descarga, hashes, exclusión con Wine,
identidad del destino y recuperación de archivos: no se borran datos ni se inicia
una actualización automáticamente. Cambio en fuentes, aún sin compilar/instalar;
por petición del usuario no se ejecuta otra ronda de tests en esta corrección.

### Validación física87

Primer bloqueo de observación del host resuelto con evidencia: `comm` real era
`ever.arm64probe`, sufijo15 de `com.wowforever.arm64probe`; exe sistema app_process64,
argv0 completo del paquete y UID10330. Regresión exige esas pruebas y rechaza argv0
ajeno/ejecutable nativo desconocido. Se mantiene protección contra Wine huérfano.
Code87 build/auditado/instalado: qualify interno devuelve filesystem privado y
cliente correctos, runtime idle y transferencia pequeña CDN correcta. Originales
PE/WTF intactos. No se ha probado actualización del cliente ni aceptación nativa.

Blizzard ya publica70450 en la consulta física; Forever solo está confirmado por
usuario para70425. La UI actual vincula «Preparar versión publicada» a la última
fila oficial: falta ofrecer explícitamente la admisión conocida70425 sin falsear
el fingerprint de la consulta ni marcar compatibilidad de70450. Este ajuste no
requiere nuevo diseño de confianza: se reutilizan configs con hashes admitidos.

- Diseño aprobado. El APK instalado sigue siendo79.
  El candidato80 anterior pasó66 tests integrados pero no la auditoría de assets;
  no se instaló ni se presenta como descargador terminado.
- Falta confirmar una fuente pública vigente de versión de Classic Forever;70425
  está confirmada por el usuario y por evidencia de tags ARM64 oficial, no por un
  Login propio. La comprobación de servidor no debe requerir cuentas personales.
- La portabilidad Android de parsing TACT completo y el presupuesto temporal de
  metadatos requieren medición, no copiar los números de RAM/tiempo del host.
