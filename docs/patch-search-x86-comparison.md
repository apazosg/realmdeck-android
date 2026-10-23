# Búsqueda del parche: WoWserver x86-64 frente a Android C136

Investigación de fuentes, 2026-10-23. Solo lectura: sin ejecutar binarios,
compilar, iniciar juegos, acceder a memoria/procesos vivos ni modificar WoWserver.
No se verificó la correspondencia del servicio actualmente desplegado con estas
fuentes. El explorador delegado no pudo arrancar; inspección directa, no revisión
independiente. No hay implementación ni medición nueva de rendimiento.

## Ruta que usa WoWserver

Raíz de fuentes: `/home/adrian/dev/wowserver/`.

- `image/start-desktop-session.sh:127` inicia `forever-supervisor`, no el CLI
  sencillo `forever-patch ensure`. `Dockerfile:1-7` compila ese supervisor.
- `vendor/forever-patcher/src/bin/forever-supervisor.rs:1438-1478` descubre el PE
  adoptado con identidad estable, UID y prefijo propios. No espera Enter ni una
  señal de ventana para comenzar a buscar una vez identificado el proceso.
- `:1496-1546` ejecuta `scan_stores_with_budget` con presupuesto5s, pausa2s
  entre resultados y exige estado Complete y exactamente un almacén. Cada
  llamada crea una pasada nueva; no conserva cursor entre estas llamadas.
  Tras identificar el juego no aplica el límite90s de descubrimiento a la espera
  del almacén: puede seguir esperando mientras el proceso siga siendo válido.
- `:1159-1183` vuelve a escanear con memoria fijada; exige un candidato, pausa
  los hilos solo para revalidar identidad, región y bloque480B antes de escribir
  únicamente la clave32B. No es un hook de autenticación.
- `:1589-1627` mantiene una pista de dirección solo después de validación y
  aplicación: lectura480B con validación completa/identidad/mapas cada2s; si falla
  descarta la pista y vuelve al escáner. Esto beneficia mantenimiento, no el
  primer descubrimiento. No reutiliza esa dirección entre procesos.

## Diferencias de búsqueda demostradas en el código

| Aspecto | WoWserver | Android C136 |
| --- | --- | --- |
| Regiones | Privadas anónimas/pseudo, `rw-p`; excluye ejecutables y rutas de fichero | Prioriza privadas anónimas RW/RWX; conserva fallback de todas las legibles |
| Pasada | Nueva en cada búsqueda; espera2s después | Cursor reanudable entre sondeos; pausa1s después |
| Error de lectura de chunk | Abandona esa región | Avanza4KiB e intenta seguir; conserva cobertura/gaps |
| Buscar ancla | Solo posiciones alineadas; prefiltros1B/4B antes de comparar32B | Recorre todas las posiciones, comprueba alineamiento y compara32B |
| Espera del almacén | Sin plazo de espera tras identidad válida | Máximo180s desde arranque observado, acotado también por total900s |

Ambos contratos declaran alineamiento4B,12 entradas de40B, ancla grupo1 y destino
grupo8. No se han emitido claves ni direcciones en esta investigación.

Referencias de WoWserver:

- `vendor/forever-patcher/src/memory.rs:172-214`: filtro de regiones.
- `vendor/forever-patcher/src/patch.rs:178-220`: errores, posición alineada y
  prefiltros. `:124-162`: estado local nuevo en cada llamada.
- `docs/FOREVER-SCAN.md:16-31`: documenta que se corrigió ese antiguo bucle lento.
- `docs/PATCH-WATCH-COST.md:41-49`: registra coste observado aproximado1,5s por
  pasada anónimaRW de unos2GiB; evidencia de ese host, no benchmark ARM64.

Referencias Android:

- `crates/forever-patcher-jni/src/functional_full_scan.rs:375-436`: bucle actual
  por byte; `:470-505`: tratamiento de lecturas fallidas.
- El mismo fichero conserva estado de pasada/cursor y prioriza regiones sin
  eliminar fallback. Presupuestos y validación se mantienen en
  `functional_session.rs`.

## Qué explica y qué no

La versión x86-64 tiene una optimización del buscador que no se trasladó al full
scan Android. Con alineamiento4 visita una de cada cuatro posiciones, pero eso
**no promete acelerar cuatro veces el parcheo**: influyen E/S, mapas cambiantes,
creación del bloque, fallos de lectura y pausas. No se midió aquí su contribución.

El cursor Android puede posponer la revisita de una zona prioritaria mientras
recorre el fallback. Los registros C136 positivos hallaron el candidato al
empezar una nueva pasada, a84215ms y83529ms de búsqueda. Es consistente con esa
demora, no prueba que el bloque existiera durante las lecturas previas.

El código x86-64 no exige un Login fallido ni demuestra que siempre parchea antes
del primer envío. Incluso contempla esperar a que Login cree el almacén; ese
comentario no es evidencia de cuándo ocurre realmente. La experiencia positiva
del usuario en x86-64 sigue siendo la referencia, no una prueba de equivalencia
de tiempos/creación de bloques entre clientes y arquitecturas.

## Cambio aprobado e implementado posteriormente — C137

El usuario aprueba «vale, hagamoslo». Se porta únicamente el bucle alineado y los
prefiltros.106 pruebas nativas focales correctas, build ARM64 y APK auditados;
no instalada ni probada físicamente. Revisión independiente no disponible. La
microprueba del matcher en host mostró mejora, no mide tiempo hasta Login.
Resultados y límites en [estado del launcher](launcher-app-status.md).

Un informe manual posterior corresponde a la C138 instalada, cuya receta
conserva el JNI C137: vuelve a aplicar tras un primer Login fallido, según el
usuario. El registro comunica APPLIED32 a67575ms de búsqueda, al iniciar una
pasada nueva. No hay hito preciso de Login ni control sin Login; la optimización
no resuelve por sí sola la dependencia aparente. No convertir la correlación
en requisito de un fallo previo ni atribuirla a cambios de claves.

La propuesta original de validación fue:

Primero portar exclusivamente el bucle alineado con prefiltros, conservando
solape, deduplicación, cursores, límites, cancelación y todas las validaciones.
Verificar equivalencia con casos de ancla alineada/no alineada y fronteras ya
existentes, y comparar coste sintético antes de atribuirle mejora de Login.

Revisitas prioritarias más frecuentes serían otro experimento separado. No
copiar a ciegas el abandono de regiones ante error, reducir cobertura, eliminar
el plazo Android, añadir cachés entre procesos ni adoptar el mecanismo de pausa
x86-64. Los controles sin Login se suspenden posteriormente por su resultado
repetido y falta de telemetría correlacionada, como se registra a continuación.

## Reverificación oficial/x86-64 frente a C140 —2026-10-23

El usuario solicita volver a comprobar las referencias y mejorar ARM64, no seguir
repitiendo A/B. Según su resumen, todos los intentos sin Login agotaron la búsqueda
sin aplicación y todos los que tuvieron un intento de Login terminaron aplicando;
la mayoría permitieron entrar. B2 C140 es una excepción de autenticación: segundo
envío con contraseña manual, desconexión e indicador todavía aplicado. Ese patrón
es evidencia observacional importante, no una medida de creación del store.

### Fuente oficial comprobada de nuevo

Descarga de solo lectura de
`https://cdn.gpon.com.co/linux/classic-forever-linux.py`: HTTP200,
Last-Modified `Fri, 02 Oct 2026 05:27:41 GMT`, SHA256
`7b773fb16928ac998532271bdeee7c835d452e095caad813e8c994612a5e07c5`.
Coincide con la referencia ya documentada por WoWserver. Es la referencia Linux,
no una verificación del binario Windows actual. La API pública del repositorio
GitHub indicado por el contrato devuelve404; no permite verificar ese otro código.

- `heap_regions:215–227`: limita a memoria privada RW sin fichero, excluye stack y
  algunos pseudo-mapeos. **No copiar ese filtro literalmente:** ARM64 permite RWX
  y otros pseudo-mapeos mediante su predicado ya validado.
- `scan:237–258`, `find_stores:292–298`: pasadas nuevas sobre esas regiones,
  chunks8MiB y `bytes.find`. La alineación/prefiltros ya se portaron a C137; no
  proponer esa misma optimización otra vez.
- `watch:396–455`: escanea antes de encontrar el bloque, espera0,1s entre búsquedas
  iniciales y no impone el plazo180s. Tras encontrarlo conserva la dirección y
  revalida el bloque; si se libera vuelve a buscar. No intercepta Login ni crea
  el store mediante un hook de autenticación.
- `main:523` dice «Haz login y entra al reino». `ready:374–376` dice expresamente:
  **«Si el primer intento de entrar al reino falló, vuelve a entrar SIN cerrar el
  juego.»** El primer fallo no es algo que esa referencia prometa evitar.
- `patch:352–370` comprueba clave anterior, escritura32B, relectura y bloque. Sus
  reglas de padding históricas son más estrechas que la corrección ARM64 aprobada;
  no retroceder a ellas ni copiar sus mecanismos de permisos/root.

### Supervisor x86-64 comprobado de nuevo

Las fuentes locales siguen mostrando: `forever-supervisor.rs:1519–1546` hace
pasadas nuevas de5s y espera2s, sin plazo de espera del store tras identificar el
juego; `memory.rs:172–214` excluye lo no elegible para escritura. Su comentario
«initiate login and create the store» no es una medición ni un hook.
`forever-supervisor.rs:1589–1605` usa una pista validada, solo lectura si ya está
parcheada, y vuelve al escáner al invalidarse. No se ejecuta el supervisor ni se
verifica que el servicio desplegado corresponda a estas fuentes.

Hashes de las fuentes inspeccionadas, en `vendor/forever-patcher/src/`:

| Fuente | SHA256 |
| --- | --- |
| `bin/forever-supervisor.rs` | `4c879e8302797c28dcbe3cf61722d6ebc06a045c3af998cd4b2b6bf54ab5e181` |
| `memory.rs` | `6d22cffc10f5f78c4b9f8b64739a0e7133d6625ba5136dfe53834858b40a5092` |
| `patch.rs` | `adcbd0af2a201ebef05a290cbabe95b2e616b20ac37c86830dc4b2fcea9528ba` |

### Diferencia útil pendiente, no otra búsqueda de claves

ARM64 C140 conserva C137: `functional_full_scan.rs:235–281,602,844` visita primero
regiones elegibles, pero luego consume el fallback de **todas las legibles** antes
de volver a las primeras. Cambios/creaciones detrás del cursor esperan la siguiente
pasada. El predicado del escritor en `functional_session.rs:1988–1998` ya impide
aplicar en ese fallback. Por tanto recorrerlo puede demorar una nueva búsqueda
útil sin aportar otro lugar donde autorizar el parche. No se cuantificó ese ahorro
en C140; los registros históricos no identifican con certeza cada error por grupo.

La propuesta mínima fue limitar el full scan funcional no-capture a las regiones
que **ya** permite el escritor, conservando RW/RWX y el predicado actual, y medir
la latencia real con contadores tipados por intento. Esto cambia expresamente
US-002-AC-010/FR-011 (antes exigían fallback), por lo que se pidió aprobación, no
una edición silenciosa de la spec. El usuario aprueba «si, hagamos eso»; C141 lo
implementa con resumen por intento, revisión independiente, pruebas y APK auditada,
instalada con UID/hash verificados, todavía sin medir el ensayo de Login:
ver el [estado](launcher-app-status.md).
Las rutas de captura y CLI quedan iguales.

Los tres métodos conservan una dirección una vez aplicada; una segunda copia
puede escapar mientras la antigua siga válida. Es una hipótesis pendiente para B2,
no su causa demostrada ni algo que copiar del oficial resuelva. No se mezcla una
reescritura de mantenimiento con el primer cambio de descubrimiento.

La reverificación de referencias no ejecutó tests/binarios de parcheo, ADB, juego,
memoria viva ni input; las pruebas/build posteriores de C141 se documentan aparte.
Los dos workers de exploración fallaron antes de investigar por variante no
disponible; estas conclusiones son inspección directa, no revisión independiente.
Alcance y aprobación en el [plan](../.agents/planning/patch-discovery-parity-plan.md).
