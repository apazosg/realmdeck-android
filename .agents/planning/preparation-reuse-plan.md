---
id: preparation-reuse-2026-10-21
state: in-progress
date: 2026-10-21
---

## Summary

Petición del usuario: «con cada arranque de la app de WoW forever hay que volver
a darle a comprobar y preparar WoW...lo que lleva varios minutos. no debería de
ser así al menos no tan lento».

Objetivo: reutilizar la instalación y el entorno ya preparados, sin exigir una
preparación manual prolongada en cada apertura. Abrir la app no debe arrancar
Wine ni WoW; la acción explícita Jugar debe resolver las comprobaciones necesarias.
No confundir este trabajo con reducir las pasadas de publicación del descargador.

**Aprobado por el usuario:** «hazlo. debemos reutilizar la preparación y reducir
el recorrido para que se sienta más rápida. además creo que convendría que el
usuario pudiese revisar si hay actualizaciones manualmente y no siempre que se
abre. es posible que no quiera actualizarlo todavía».
Posteriormente añade: «recuerda no sobrecargar la interfaz con texto excesivo».

Se incorpora su indicación expresa: comprobación remota solo mediante una acción
manual, separada de instalar, y posibilidad de Jugar con el cliente instalado sin
consultar ni adoptar el último build. US-008 ya exige comprobación manual; el
`detect()` automático del diálogo la contradice y se corrige como defecto.

## Content

### Contexto y límites

- App funcional: `com.wowforever.arm64probe`, fuentes en
  `scripts/arm64-prototype/app/overlay/` y `updater-gate/`; `android/app` es otra ruta.
- US-004 aceptaba provisionalmente la preparación explícita. Esta petición cambia
  esa experiencia; no autoriza retirar comprobaciones de integridad o consentimiento.
- La identidad durable del destino es UUID/ruta; dev/inode/montaje se congelan
  durante cada operación, no se usan como identidad persistente entre aperturas.
- Hay cambios previos sin commit y una candidata C128 preparada. Preservarlos;
  no instalar ni detener procesos mientras el usuario esté jugando.

### Diagnóstico estático contrastado

- `ForeverFunctionalPocActivity.kt:569-598`: `prepared`, comprobación y consentimiento
  nacen de nuevo con `remember` en cada composición nueva. No se recupera una
  preparación anterior al abrir de nuevo el proceso.
- `ForeverFunctionalPoc.kt:134-295`: `prepare()` vuelve a resolver el cliente,
  asegurar perfiles/contenedor, revalidar almacenamiento, escribir configuración
  derivada, verificar/materializar controles y asegurar ImageFS. Los componentes
  sí tienen reutilización parcial; repetir la cadena no demuestra una reinstalación
  ni una descarga nueva de ImageFS.
- `ForeverFunctionalClientStorage.kt:1036-1065,1669-1734`: cada `revalidate()` llama
  a `validateStorage()`, que incluye el recorrido NOFOLLOW del árbol del cliente.
  La cadena lo invoca varias veces; son metadatos, no una lectura de todo CASC.
  Este es un candidato de coste en USB/FUSE, no la causa temporal ya medida.
- También se verifican contenido del ejecutable y PanVK durante preparación.
  No hay tiempos ni volumen de lecturas medidos en esta sesión que permitan
  adjudicar los minutos a esos hashes o a los recorridos.
- El objeto `Prepared` contiene estado de sesión, por lo que persistirlo tal cual
  no es una solución. Tampoco se acepta de antemano un nuevo receipt con una
  copia de su SHA: un digest junto al JSON no es firma ni prueba de que el contenido
  referenciado siga siendo válido. Priorizar mecanismos y garantías existentes.

### Enfoque mínimo propuesto

1. Separar la preparación reutilizable de los datos de cada lanzamiento. No guardar
   y restaurar un objeto de sesión como si siguieran válidos su lease, nonce,
   identidades congeladas o proceso anterior.
2. Reutilizar recursos del entorno ya verificados con los mecanismos existentes;
   evitar extracciones, copias y verificaciones duplicadas dentro de una misma
   operación. No introducir un framework de caché ni una marca «listo» que sustituya
   pruebas de integridad. Cualquier reducción de garantías requiere otra decisión.
3. Integrar la preparación necesaria en Jugar, con progreso por etapas reales,
   conservando las puertas de lanzamiento y el consentimiento actual. La primera
   instalación o un cambio real pueden seguir necesitando preparación larga.
4. Medir las etapas para distinguir comprobación del cliente, preparación del
   runtime y arranque de Wine. No atribuir los minutos a una etapa sin medirla ni
   prometer un tiempo final sin prueba física.
5. Separar inspección local de búsqueda remota en el gestor: abrirlo solo muestra
   el estado instalado. «Buscar actualizaciones» consulta novedades; Actualizar y
   confirmar descarga siguen siendo acciones distintas. Jugar no depende de que la
   versión instalada sea la última ni de que la consulta de red funcione. Texto
   de interfaz breve; diagnóstico y detalles solo a demanda.

### Contrato de implementación aprobado

- Reutilización apoyada en los perfiles, ImageFS, controles y contenedor ya
  persistidos; reconstrucción fresca del estado efímero de cada lanzamiento.
  No añadir receipts autofirmados, caché criptográfica basada en tamaño/fecha ni
  persistir `Prepared` para evitar verificar contenido.
- Mantener verificación fuerte del ejecutable en cada nueva preparación y de los
  controles; reducir recorridos redundantes, no sustituir hashes por metadatos.
- Mantener un recorrido completo NOFOLLOW al resolver el cliente y otro en la
  puerta final de lanzamiento. En los pasos intermedios de la misma operación,
  revalidar selección, montaje, raíz/base, rutas críticas y archivos congelados
  sin volver a recorrer todo el subárbol. La API general de revalidación mantiene
  su comportamiento fuerte para otros consumidores.
- Omitir la escritura de configuración derivada cuando sus bytes no cambian.
  Reutilizar el contenedor sin resetearlo: se conserva su `saveData()` app-privado
  al finalizar la configuración, evitando introducir un dirty-check de sus setters.
  El nonce fresco de `beforeLaunch` sí debe cambiar cuando el parche está activo;
  nunca se reutiliza para omitir esa escritura. El contenedor reutilizado no
  autoriza lanzar sin preparación fresca.
- No arrancar juego ni hacer preparación pesada por abrir la app. Jugar inicia el
  flujo local y conserva el consentimiento separado antes del lanzamiento.

### Criterios de aceptación

- Tras cerrar y volver a abrir la app con la misma instalación válida, no es
  necesario pulsar antes «Comprobar y preparar WoW» para acceder al flujo de Jugar.
- La ruta habitual reutiliza recursos válidos y no repite trabajo pesado sin una
  razón comprobada. Se registran duración por etapa y operaciones omitidas/repetidas.
- Primer uso, actualización del entorno y recursos ausentes/alterados mantienen
  verificación y errores explícitos; no hay descargas grandes inesperadas.
- Cambio/desconexión de destino, operación pendiente o guest no confirmado como
  cerrado no permiten saltarse exclusión de E/S ni revalidación de lanzamiento.
- No se recorre íntegramente CASC para una apertura normal, no se toca el cliente
  para probar rendimiento y no se cambian drivers, autenticación ni credenciales.
- Abrir la app o el gestor no consulta versiones remotas. Una búsqueda manual no
  instala ni condiciona Jugar; hay acción de juego con la instalación existente
  incluso si hay una versión más nueva o la consulta falla.

## Implementation / Action Items

- [x] 1. Diagnosticar estáticamente las llamadas reales y el estado que se pierde al
  recrear la app; contrastar las conclusiones con código. Medición física pendiente.
- [x] 2. Obtener aprobación del alcance antes de modificar implementación o specs.
  Aprobación y ampliación expresa a actualizaciones manuales transcritas arriba.
- [x] 3. Aplicar la corrección mínima en el flujo existente y actualizar únicamente
  los requisitos afectados, sin mezclar downloader, pantalla de carga o parche.
- [x] 4. Añadir unas pocas regresiones focales: reapertura con recursos válidos,
  invalidación por cambios/ausencias y conservación de guardas de lanzamiento.
  Reutilizar infraestructura existente, sin nuevas dependencias ni arneses generales.
- [x] 5. Revisar independientemente cualquier cambio de reutilización/integridad o
  concurrencia; ejecutar pruebas focales, compilar y auditar la APK fuera del repo.
- [ ] 6. Solo con el juego cerrado y autorización para instalar: conservar datos y
  comprobar dos aperturas en frío, midiendo preparación y lanzamiento por separado.
  Documentar lo medido y cualquier validación física pendiente.

## Open Issues

- Implementado: Jugar solicita preparación fuerte; la presencia local no declara
  integridad, y el consentimiento sigue separado. Búsqueda remota solo manual,
  independiente de Jugar con la instalación admitida. Revalidación intermedia sin
  árbol completo, resolución/puerta final completas; comparación derivada por bytes
  acotada y escritura NOFOLLOW. Duraciones en evento `prepare_stages` existente.
- Inspección inicial contrastada en código: solo nodos fijos/volúmenes/StatFs, no
  recorrido del árbol. No se ha medido todavía su coste físico.
- Pruebas de política del gestor: 5 correctas. UI/reconstrucción: 40 correctas y
  una exclusión por discrepancia de texto de cuenta ajena a esta tarea. Las suites
  completas ejecutadas por builders NO están verdes; sin logs completos de todos
  los fallos, no se presenta una comparación basal global ni se atribuyen todos
  inequívocamente a fallos previos. Consolidación C129:127 tests JVM correctos
  (76 almacenamiento,33 UX,10 resolución,8 perfiles), compilación5m33s y auditoría
  `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`. Artefactos y SHA en
  `docs/launcher-app-status.md`; no se instaló ni se tocó el dispositivo.
- Revisión independiente cerrada sin bloqueantes para Activity/UX, actualizaciones
  manuales, equivalencia de las guardas intermedias y puerta final bajo lease.
  Se corrigió el helper derivado para comparar bytes acotados y propagar errores
  de lectura; escritura NOFOLLOW en la apertura, sin fallback a escritura seguidora.
  Persisten las ventanas locales de cambio del directorio padre del escritor
  preexistente; no se afirma protección nueva por descriptores de directorio.
- Implementación aprobada. La instalación y prueba física aún requieren coordinar
  el cierre del juego; no hay autorización para interrumpirlo.
- No hay todavía medición por etapas que explique los minutos de la sesión del
  usuario. El diagnóstico estático debe distinguir hechos de posibles costes.
- Si reutilizar el entorno exige cambiar la política de verificación entre
  ejecuciones, concretar las garantías e invalidación y pedir aprobación antes de
  sustituir hashes por metadatos. No aceptar tamaño/fecha como prueba criptográfica.
- La instalación/prueba física debe coordinarse para no interrumpir una partida;
  no reiniciar Android, borrar reservas, reparar ni volver a descargar el cliente.
