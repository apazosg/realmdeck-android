# Recuperación de un parcial USB en otro móvil — propuesta

Estado: **diseño para revisión, no implementado ni autorizado para mutar datos**.
Los UC/AC de [US-009](../.agents/specs/US-009.md) están aprobados; FR-009–013 y
NFR-004 son borradores. Este documento concreta la opción recomendada y sus
puertas de seguridad; no declara recuperados los datos del teléfono personal.
Operación y evidencia en [el plan](../.agents/planning/usb-cross-phone-plan.md) y
[el informe USB](usb-cross-phone-results.md).

## Punto de partida y decisión propuesta

- La selección normal del USB ya está corregida, sin permiso amplio. Hay 81
  `Data/data/data.*` bajo un staging, no una instalación publicada. El checkpoint
  de 822.658 objetos pertenece al móvil original y no es entrada de esta recuperación.
- Cliente completo: conservar la revisión/reutilización existente; runtime,
  admisión y Play se validan en el teléfono nuevo por separado.
- Parcial: verificar en su ubicación actual y crear estado local nuevo. **No copiar
  decenas de GB a otro staging, importar el checkpoint privado ni fingir que existe
  una instalación completa.** El marcado antiguo es indicio de estructura, no
  autoridad sobre la integridad de los objetos.
- Separar tres decisiones: revisión local sin adopción; petición explícita de
  verificación con el consentimiento de metadatos aplicable; confirmación del
  plan real antes de cambiar propiedad/cliente o transferir payload.

Modelo de concurrencia admitido: **USB en un único host y una instancia/usuario de
la app con la lease real de operación**; traslado secuencial tras pausa y expulsión
segura. No es un disco compartido entre hosts/usuarios ni un protocolo distribuido.
La sustitución atómica del marcador no es compare-and-swap y no se presentará como
exclusión entre escritores arbitrarios. Un segundo intento local no obtiene la
lease; cambios del origen/marcador observados durante admisión bloquean la operación.
No se admite silenciosamente una topología de acceso simultáneo.

## Hechos de código que condicionan el diseño

Inspección de las fuentes vigentes, no prueba de recuperación física:

- `ForeverClientUpdater.resumeGuardState` y `readStageReceipt` rechazan staging
  extranjero o recibos que no coinciden; no deben convertirse en un “permitir todo”.
- `ForeverCasc` usa cabecera de segmento de **480 bytes** y cabeceras de objeto de
  **30 bytes**. Estas incluyen EKey completo invertido, tamaño total, flags y dos
  checksums ligados a la posición; el catálogo aporta CKey y tamaños esperados.
  Los 16 registros iniciales del segmento no son payload descargado.
- `verifyOwnedSegmentHeader` deriva la cabecera esperada de la **ruta absoluta de
  `Data/data`**. Renombrar el staging y entregar sin más los mismos segmentos al
  writer invalida esa identidad: no es una solución transparente.
- `loadResumableCheckpoint` exige archivos/offsets coherentes con los frames y puede
  truncar colas o eliminar un segmento final no checkpointado. **No se llama sobre
  datos extranjeros antes de su admisión ni con un checkpoint vacío inventado.**
- `validateResumableIncrementalPaths` exige fuente, salida y checkpoint disjuntos.
  Pasar el mismo directorio como fuente y salida tampoco resuelve la adopción.
- El marcador actual de transacción es determinista por stage ID e identidad.
  Reconstituir solo un recibo con ese marcador dejaría válido el recibo antiguo:
  no cumpliría la protección contra sobrescritura desde el primer móvil.

## 1. Descubrimiento sin efectos sobre el parcial

Usar la raíz seleccionada por US-007 y su validación normal; no rutas arbitrarias,
SAF convertido a ruta, root ni permisos amplios. Enumerar únicamente la estructura
admitida con límites y no-follow. Distinguir publicado, parcial, vacío y estado
ambiguo/incompatible. No escanear cuerpos ni acceder a red al abrir la app o elegir
destino; no anunciar un número reutilizable a partir del tamaño o del marcador.

No adoptar automáticamente varios stages, journals desconocidos, enlaces o archivos
ajenos. Mostrar el motivo y preservar todo; no elegir el “más nuevo” por nombre o fecha.

## 2. Verificación explícita contra el catálogo oficial

Tras la petición de verificar/continuar y el consentimiento de metadatos existente:

1. Adquirir la exclusión real de operación para el destino; exigir quiescencia y
   conservar las guardas de cambios de selección/montaje. No usar una observación
   ficticia ni otra lease para eludir una operación pendiente incompatible.
   Preparar el catálogo candidato con la cadena oficial existente, sin reemplazar
   a escondidas un catálogo activo fijado por otra operación pendiente. Si no puede
   admitirse ese target en el flujo nuevo, detenerse antes de mutar el USB.
2. Fijar el target admitido y su catálogo íntegro. Recorrer segmentos/registros
   secuencialmente: IDs 0–1023, archivo ≤1 GiB, aritmética de tamaños/offsets sin
   overflow, flags y checksums válidos, EOF exacto para los registros admitidos.
   Reutilizar las comprobaciones CASC/BLTE existentes en vez de otro decodificador.
3. Consultar el catálogo por lotes acotados y verificar EKey/BLTE/CKey/tamaños de
   cada objeto contado. Mantener resultados en estado privado limitado/disco,
   sin copiar el checkpoint del original ni retener el catálogo completo en RAM.
4. Mostrar fase, bytes/objetos realmente comprobados y bloqueos; no velocidad de
   descarga o ETA de red para este trabajo. Cancelar conserva el origen sin adoptar.
5. Elaborar el plan de reutilización, delta y pico de espacio, incluyendo el estado
   privado anterior y los temporales. Nada de payload ni cambios de propiedad aquí.

Primera admisión propuesta: staging generado por este updater, cuya estructura,
cabeceras, posiciones y objetos puedan representarse sin pérdida en el checkpoint
existente y en el target admitido. La revisión no convierte un registro desconocido,
un hueco, una cola rota o un objeto no autenticado en un frame válido. Si esa
representación no es segura, preservar el origen y explicar el bloqueo; no truncar,
compactar ni descargar todo como fallback. La reparación de un layout no representable
necesita un protocolo propio antes de mutarlo, no una excepción silenciosa.

Antes de admitir el checkpoint reconstruido se exige:

- Nombres canónicos `data.000` hasta el último ID, secuencia admitida sin segmentos
  desconocidos, archivos regulares sin enlaces y cabeceras de segmento completas y
  compatibles con su ruta. Los registros sintéticos iniciales no cuentan como objetos.
- Desde offset 480, cada cabecera completa de 30 bytes y su cuerpo deben caber
  exactamente en el archivo. Validar flags, EKey completo, tamaño total, ambos
  checksums y `30 + encodedSize` contra el catálogo; no buscar otra magia BLTE para
  saltar sobre un registro inválido ni inventar huecos.
- Autenticación completa de todos los cuerpos retenidos, membresía/tamaños del
  target, sin duplicados o conflictos de índice no representables. Los frames se
  construyen en el orden físico y satisfacen las invariantes del lector resumible.
- El último offset admitido coincide con EOF en cada segmento; ninguna cola
  desconocida queda fuera del estado preparado. Un archivo final solo de cabecera,
  índices parciales o cualquier caso no representado se bloquea conservándolo hasta
  disponer de una política específica, no se entrega al loader para que lo borre.

El checkpoint candidato se valida por inspección **de solo lectura**, sin llamar
al cargador que trunca/limpia colas. El inventario vincula cada archivo completo y
el estado generado. Las comprobaciones de identidad/tamaño y el marcador se repiten
antes de publicar; no se confunde `stat` con prueba de bytes frente a un escritor
concurrente fuera del modelo de exclusión admitido.

## 3. Cambio de propiedad sin mover los segmentos

**Opción recomendada, pendiente de aprobación:** mantener stage ID/ruta y cuerpos
CASC; introducir una generación fresca de propiedad en un marcador versionado.
El recibo privado ya contiene `markerHash`, y la identidad del checkpoint incluye
ese digest: enlazar el estado nuevo a los nuevos bytes sin copiar el estado antiguo.

- Conservar lectura/creación del marcador legado para operaciones legítimas ya
  existentes. Una nueva generación solo nace en el flujo de adopción admitido.
- El formato nuevo debe ser estricto, acotado y ligado a stage ID/identidad/generación;
  la presencia del marcador jamás sustituye la autenticación de objetos.
- Con recibo propio: validar formato/identidad y digest exacto registrado. Sin él,
  seguir siendo staging extranjero salvo la petición explícita de recuperación.
- El C160 antiguo espera los bytes del marcador legado; tras cambiarlo, su recibo
  obsoleto debe bloquear antes de reset/truncado/append. Ese efecto se debe probar,
  no asumir por ser distinto un UUID. También se debe cubrir el estado posterior a
  la publicación, cuando desaparece el staging. Las versiones incompatibles indican
  actualizar, no reiniciar o borrar los datos.
- No renombrar `Data/data` para resolver la propiedad. Verificar la compatibilidad
  de las cabeceras con su ubicación actual. Un cambio de ruta que exija reescribir
  cabeceras no está autorizado por esta propuesta mínima; se conserva y se explica.

## 4. Intent durable e interrupciones

Antes de cualquier mutación confirmada, revalidar selección, montaje, catálogo,
estado de origen y plan. Usar **un intent privado específico**, no un framework de
migración. Sus campos estrictos y acotados vinculan: operación y generación UUID,
target/catálogo y plan consentido, identidad/ruta raíz, stage ID, hashes de marcador
anterior/nuevo, digest del inventario verificado, digest/identidad del checkpoint
nuevo, y ubicaciones privadas anterior/preparada/reservada. Las rutas se derivan de
raíces conocidas y UUID; no se abren rutas arbitrarias proporcionadas por el intent.
Inventario/frames grandes permanecen en archivos privados limitados y vinculados
por digest, no en un JSON o una colección en RAM sin límite.

Llamar `C` al workspace privado canónico, `P` al preparado nuevo y `S` a la reserva
del workspace anterior. Los tres están en almacenamiento privado permitido y en un
filesystem que admita el rename atómico exigido. `S` no copia datos del otro móvil.

1. Construir `P` a partir de la verificación. No está en la ruta de Resume normal.
   Tras confirmar el plan, forzar los segmentos autenticados antes de forzar el
   checkpoint/owner/recibo nuevo y los directorios de `P`. Si falla esta fase,
   el marcador USB sigue antiguo y no hay progreso nuevo declarado durable.
2. Escribir/forzar el intent mediante temporal propio y publicación atómica,
   conservando los hashes exactos del estado anterior y nuevo. Su presencia bloquea
   **todas** las entradas normales que podrían escribir/resetear staging, no solo Resume;
   la lease queda pendiente mientras necesite recuperación. Sin este intent durable
   no se cambia propiedad ni se sustituye el workspace canónico.
3. Si existe `C`, exigir que sea el estado privado propio identificado en el intent;
   reservarlo mediante `C → S` atómico y forzar el padre. Si es desconocido o cambió,
   bloquear, no editar su recibo inmutable ni eliminarlo. Si se esperaba ausente,
   su aparición inesperada también bloquea.
4. Revalidar origen y marcador bajo la misma exclusión. Publicar los nuevos bytes
   del marcador por temporal UUID propio, force del temporal, reemplazo atómico y
   force del directorio. Sin fallback no atómico. Un marcador distinto del antiguo
   o del nuevo exactos del intent bloquea; no se sobreescribe con “el nuestro”.
5. Publicar `P → C` atómicamente y forzar su padre. Si hace falta cambiar la admisión
   activa del catálogo, el intent debe vincular sus recibos anterior/nuevo y completar
   esa publicación mediante la cadena oficial, antes de habilitar operaciones normales;
   no desactivar la guarda de catálogo fijado ni dejarla como efecto implícito de Resume.
   Los artefactos siguen en el namespace validado por el resolver existente, no en
   una ruta arbitraria tomada del intent.
6. Comprobar coherencia de marcador, recibo, identidad/checksum del checkpoint y
   catálogo por las comprobaciones de lectura aplicables. No ejecutar append,
   truncado o limpieza para “ver si funciona”. Con todo nuevo coherente, limpiar
   de manera durable el `S` propio conocido antes de retirar el intent, evitando
   que quede como otra actualización pendiente. Nunca se limpian datos USB ajenos.
   Retirar/forzar el intent solo al terminar esas comprobaciones y la limpieza;
   después puede ofrecerse el flujo ordinario.

La verificación masiva y las fuerzas de los segmentos no forman parte de una fase
atómica larga que impida cancelar durante decenas de GB. Tras cambiar el marcador,
la finalización protegida es de metadatos: completar hacia delante y pausar antes
del payload, nunca restaurar un marcador viejo que reautorice estado obsoleto.

| Corte observado al recuperar | Acción permitida |
|---|---|
| `P` preparado, sin intent publicado | No adoptar. Origen intacto; Resume no usa `P`. |
| Intent durable, marcador antiguo, `C` reservado o todavía anterior | Verificar todos los bindings; completar la transición consentida o cancelar restaurando solo el estado privado exacto, sin cambiar el origen. |
| Marcador nuevo, sin `C` nuevo | Mantener entradas normales bloqueadas; publicar únicamente el `P` exacto vinculado por el intent. No volver al marcador/recibo antiguos. |
| Marcador y `C` nuevos, admisión activa aún pendiente | Finalizar solo la admisión oficial vinculada por el intent; no transferir payload ni iniciar el writer. |
| Todo nuevo coherente, intent aún presente | Validar, completar la limpieza privada propia y retirar el intent de manera durable, sin otra generación ni otra adopción. |
| Marcador, workspace, temporal o inventario inesperado | Bloquear conservando todo; no escoger por fecha ni borrar para forzar recuperación. |
| USB ausente | Conservar el intent y esperar el mismo destino; sin fallback interno. |

Los temporales se pueden retirar/recrear solo si el intent demuestra su nombre y
contenido esperado (incluido un prefijo parcial propio); no se limpia un temporal
extraño. Otro teléfono sin ese intent vuelve a revisión explícita; nunca confía en
el marcador solo. Los cortes en rename/force y la sustitución de `C` deben cubrirse
con pruebas focales antes de considerar implementable esta secuencia.

## 5. Continuación y publicación

Con el estado nuevo admitido, usar la descarga real y las comprobaciones ordinarias:
catálogo completo, índices canónicos, objetos faltantes, fuerzas de datos/checkpoint,
retención raw, cotas, Wi-Fi y exclusión Wine. No rebajar checks para acelerar la
recuperación ni contar bytes reutilizados como red. La activación sigue exigiendo
el target completo; ningún contador heredado publica `.build.info`.

El consentimiento del móvil original no se hereda. El de adopción no se convierte
por sí solo en un Resume vigente: el plan ordinario debe revalidarse tras la
transición. Si cambian su revisión, costes o condiciones, se muestran y confirman
de nuevo antes del payload conforme a US-008; no se fuerza un consentimiento viejo.

## Puertas pendientes antes de escribir código o tocar el USB

- Aprobar FR/NFR y este diseño, especialmente la generación de propiedad y la
  admisión inicial sin cambio de ruta; no basta la aprobación de los UC/AC.
- Derivar después casos focales de recuperación, datos corruptos/colas truncadas,
  estado obsoleto y cancelación; sin matrices redundantes ni nuevo harness genérico.
- Revisar las transiciones anteriores y su integración con journals previos y
  admisión de catálogo antes de implementar; ante ambigüedad, bloquear conservando
  datos, no introducir una ruta de borrado.
- Revisión independiente sensible, pruebas locales, postimages explícitas y
  reconstrucción/auditoría de la APK exacta antes de acordar otro reemplazo.
- Prueba física en el móvil actual con plan/costes/consentimiento nuevos, pausa y
  comprobación del progreso preservado; portabilidad completa y parcial son pruebas
  distintas, y ninguna equivale a compatibilidad del runtime o Play.

Revisión preliminar del diseño: snapshot suministrado, sin acceso del revisor al
repositorio ni ejecución. Señaló exclusión entre takeovers, publicación privada
durable y representabilidad del checkpoint. Este borrador explicita el modelo de
host único, la secuencia/recuperación y las comprobaciones. El revisor confirmó
resueltas esas omisiones de diseño bajo ese modelo, manteniendo como obligaciones
de prueba todas las rutas de mutación/recuperación, cortes y rechazo legado.
No equivale a revisión de código, prueba de fallos o aceptación física.
