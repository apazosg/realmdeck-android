# Construcción del CertStore x64: trazador probado, cliente bloqueado en CASC

**Vía auxiliar suspendida el 2026-10-14.** El usuario confirma que Btrfs es un
proyecto separado y que el objetivo es hacer funcionar el cliente ARM64 con
Classic Forever, sin limitar de antemano el método a un parche. La exportación
propuesta de la base protegida no se ejecutó y queda descartada. Este documento
conserva los controles x64 como historial, no como el siguiente paso del proyecto.

Estado del 2026-10-14: **instrumentación sintética Linux/Wine validada y cliente
x64 ejecutado en laboratorio aislado; construcción del almacén no observada**.
Los controles reales se detienen antes del arranque normal por errores CASC.
No se ha hecho Login, aplicado Forever ni cambiado verificaciones criptográficas.
No hay modificaciones ARM64 ni escrituras en las fuentes originales.

Plan aprobado:
[x64-certstore-initialization-trace-plan.md](../.agents/planning/x64-certstore-initialization-trace-plan.md).

## 1. Resultado por pregunta

| Pregunta | Cliente x64 real |
|---|---|
| Momento en que aparece la tabla de doce claves | No observado: los arranques reales no superan la inicialización CASC. |
| Instrucciones o funciones que la inicializan | Pendiente. Las instrucciones capturadas pertenecen exclusivamente a una fixture propia. |
| Copia estática, reconstrucción o componente externo | No determinado; la fixture no demuestra el comportamiento del cliente. |
| Participación del slot `.rdata` `0x48f6870` | No demostrada. El hallazgo anterior `g9h0 || g10h1` no identifica el origen del grupo 8. |
| Características para localizar el equivalente ARM64 | Pendientes hasta demostrar constructor/consumidor del almacén x64 real. No trasladar RVAs. |

El detector funcional x64 valida un bloque completo de 12 entradas de 40 bytes,
con las claves, IDs, flags y padding del contrato. Esa validación sigue siendo
el criterio de identificación; una watchpoint sobre un buffer de forma parecida
no basta. No se ejecutó el supervisor productivo: su modo diagnóstico no elimina
la posible escritura automática del grupo 8.

## 2. Fuente de cliente y bloqueo de datos

Ejecutable de referencia:

- `client/WowB.exe`, PE32+ x86-64, 102916816 bytes.
- SHA-256 `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.

Inspección limitada a documentación, identidad de ejecutable y metadatos de
directorios; no se leyeron cuentas, HOME, registros personales, WTF privados ni
servicios de producción.

| Fuente | Observación | Decisión |
|---|---|---|
| `/home/adrian/dev/wowforever/client` | Árbol ext4 escribible, aproximadamente 17,1 GB observados. No es una instalación completa verificada. | No usar como reproducción del cliente funcional. |
| `/opt/wowserver/client-base` | Árbol histórico ext4, aproximadamente 93,7 GB observados; ejecutable con el hash de referencia. | No tratarlo como base productiva consistente. |
| `/opt/wowserver/shared-runtime/btrfs/initial-70009/mnt/base` | La documentación describe un lower con 120 archives y 1663 archivos. La inspección de componentes se detiene en `shared-runtime`, `root:root 0700`, con permiso denegado. | No acceder mediante privilegios, contenedores de producción ni cambios de permisos. |

`wowserver/docs/SEGMENT-HEADERS.md:36–58` documenta que el árbol histórico
`client-base` tenía 93 archives y 16 referencias KMT ausentes. Es evidencia
histórica, no una nueva comprobación byte a byte de todo el árbol.
`wowserver/docs/BTRFS-PRODUCTION.md:184–188` advierte que esa base conservada no
está garantizada como equivalente a la versión productiva actual.
`wowserver/docs/SHARED-RUNTIME-PLAN.md:308–331` describe el lower de 120 archives;
esos datos tampoco se rehashearon ni montaron en esta investigación.

La imagen `wowserver/forever:auth-candidate` existe con ID
`sha256:9d553802d94756f06968f12ebfe31a4a71060a8ee73267353a4879984260ba06`.
Su entrypoint normal es `/opt/wowserver/start-forever.sh`, usuario configurado
`root`, y conduce al supervisor que parchea. No se utilizó ese entrypoint:
los controles arrancan un comando de laboratorio, UID1000, red deshabilitada,
capacidades retiradas, prefijo y Xvfb propios. No se ejecutan servicios productivos
ni el supervisor que escribe la clave. La vista del cliente usa dependencias
verificadas, datos de trabajo privados y la fuente montada solo lectura.

El bloqueo concreto es disponer de esa fuente utilizable en el laboratorio.
No es un resultado negativo sobre la inicialización del CertStore.

### Revalidación local y posibilidad de prepararla nosotros

El 2026-10-13 se ejecutó una auditoría read-only actual de
`/opt/wowserver/client-base`, mediante `_source_report`, `audit_client` y
`analyze_idx` del tool de `wowserver`:

- `.build.info` coincide con el contrato de `1.60.1.70009`.
- Archives presentes: **0–92**, 93 consecutivos.
- KMT: **16 tablas válidas**.
- Headers: **1488/1488 válidos**, sin malformados ni colisiones observadas.
- Referencias cubiertas: **1472/1488**; las 16 restantes pertenecen únicamente al
  archive **18 decimal** y están presentes como headers, pero no en los índices.
- Estado: `needs-review`; `payload_bytes_read=0` y
  `payload_integrity_verified=false`.

Por tanto, podemos preparar nosotros una copia técnica de laboratorio y evaluar
la reparación determinista de esas referencias en la **copia**, usando el
mecanismo existente `kmt_repair --segment-headers`. No implica descargar de nuevo
todo el cliente ni acceder al lower productivo root0700. Hay unos 1,3 TB libres.

**La copia técnica ya está creada** en
`wowforever-artifacts/work/x64-certstore-data-20261013T213448Z-211565/fixture-root/players/player1/client`.
Se repararon solamente las 16 referencias KMT en esa copia: 1488/1488 headers
válidos/cubiertos. Se completaron y verificaron los 42 archivos públicos del
manifiesto, incluidas las dependencias DLL; el primer copy había omitido 41 y
no se trató como utilizable hasta completar ese check.
Los reportes `lab-data-report.json` y `manifest-completion-report.json` se
conservan por separado, sin retaggear el primero. SHA respectivos:
`61e98c21e5e4c35899881d2dd4812a348295cbeb3473ab2bd29a850c30293dea`
y `810cb5bda4921e0709a3d5ea037c90a1bd5b8044396c9921c9b4fe6a2c2d2ba6`.
Cubrir headers/índices/42 archivos no demuestra la integridad de todos los
payloads ni que el cliente acepte los índices de grupo. No se copiaron WTF,
cuentas, HOME ni configuración personal; no se modificó el origen.

## 3. Prueba real de la instrumentación, no del cliente

Herramienta nueva:
`scripts/diagnostics/reverse-engineering/x64-certstore-trace/trace_fixture.py`.

Ejecuta únicamente un programa C sintético propio en Linux x86-64. Usa
`PTRACE_TRACEME` del hijo y un watchpoint hardware de lectura/escritura de ocho
bytes, colocado **antes** de su función de inicialización. No necesita GDB,
Wine, root, capacidades nuevas ni cambios de Yama. No admite un PID de un
proceso existente como argumento.

La tabla sintética contiene los doce IDs, claves públicas, flags y padding del
contrato; el propio hijo comprueba los 480 bytes. No se imprimen claves, valores
de tabla ni contenido de pila. Se registran solo instrucciones de la fixture,
offsets de módulo, identidad, pila acotada y propiedades de la región.

La fixture se compila deliberadamente sin PIE para el test controlado; **no se
desactiva ASLR del sistema ni se propone hacerlo para WoW**. Su almacenamiento
es estático, no una prueba del allocator real del cliente.

Evidencia externa inmutable:

`/home/adrian/wowforever-artifacts/work/x64-certstore-trace-20261013T210508Z-34816/trace-report.json`

| Observación sintética | Resultado |
|---|---|
| Escritura inicial | `movq` de ocho bytes; offset de módulo `0x1205`. |
| IP después del trap | Offset `0x120c`, distinto de la instrucción escritora. |
| Lectura posterior | `movq` de ocho bytes; offset de módulo `0x124b`. |
| IP después de esa lectura | Offset `0x1252`. |
| Región observada | Privada, `rw-p`, 4096 bytes, respaldada por el binario de la fixture. |
| Estabilidad dentro de la ejecución | Mismos permisos, tamaño, respaldo y offset antes de inicializar y en la lectura posterior. |
| Pila | Cuatro frames observados, límite máximo 16; sin volcado de contenido. |
| Eventos | Dos accesos clasificados y cuatro accesos no clasificados; total 6, límite 64. |
| Validación final | IDs, claves, flags y padding completos correctos; salida del hijo 0. |

Los accesos no clasificados no se atribuyen a un constructor ni a `memcpy`.
La watchpoint cubre solo ocho bytes: el resto de la tabla lo comprueba el hijo
después. Esto valida el mecanismo de observación, no una traza completa de todas
las escrituras ni una rutina del cliente WoW.

Test ejecutado:

```bash
/home/adrian/.local/bin/uv run --offline pytest -q tests/test_x64_certstore_trace.py
```

Resultado: **1 test positivo**, con dos hijos independientes. Compara propiedades
de región entre esas ejecuciones, no igualdad de direcciones. Hubo además una
ejecución sintética manual que generó el reporte anterior. No se repite ninguna
de estas evidencias como si fuese un arranque Wine o un almacén real.

## 4. Instrumentación Wine y controles reales

`wine_fixture.py` validó WineDbg en Wine11 con una fixture PE propia: escritor
RVA `0x1499`, lector `0x14e9`, pilas y región `PAGE_WRITECOPY`. La petición typed
de ocho bytes se reportó como watchpoint efectiva de cuatro, por lo que no se
afirma cobertura completa de la tabla.

`wow_trace.c` es un debugger/detector separado: crea únicamente su hijo fijo,
valida dos lecturas estables de los 480 bytes, conserva identidad/handles,
consulta regiones reales, registra módulos/RVAs y usa watchpoints hardware.
La fixture bajo ese tracer observó un intervalo 0→1 almacenes, lectura/escritura/
lectura con seis frames, región privada RW y exit0. Esta evidencia sigue siendo
**sintética**, no el constructor de WoW. No usa `WriteProcessMemory` para claves
ni el entrypoint productivo. `--passive-real` omite completamente el puerto de
debug, los contextos y las watchpoints; sus scans no son prueba de constructor.

Los controles con debugger sufrieron excepciones y no alcanzaron gráficos.
El pasivo tampoco arrancó normalmente: eso no permite atribuir el fallo solo al
debugger. Cada corrección posterior fue guiada por el diálogo/log real:

| Observación | Corrección en la vista privada, no en el cliente |
|---|---|
| Selector de idioma | `textLocale/audioLocale=enUS` de la instalación seleccionada. |
| No active install info entries | Añadir `.build.info` RO con hash fijado. |
| Config buscado en `..\\Data` | Unificar los aliases `game/Data` y `/lab/Data`. |
| No se podía crear `shmem.lock` | Copias privadas escribibles de los 16 journals. |
| Error5 al abrir `data.000` | Copias ordinarias de los 93 archivos; nunca hardlinks ni writes en origen. |
| Error5 al escribir el grupo CDN | Copias privadas de `Data/indices` para coordinación normal. |

Últimos controles, bajo `wowforever-artifacts/work/`:

| Directorio | Resultado reproducible |
|---|---|
| `x64-certstore-trace-20261014T105900Z-1741374` | 180 scans completos/0 bloques; aún Error5 al escribir el grupo. |
| `x64-certstore-trace-20261014T112348Z-1867495` | Bloqueado antes del cliente por límites de copia CDN intercambiados; corregidos a 128MiB/file y 2GiB total. |
| `x64-certstore-trace-20261014T112637Z-1881800` | 125 scans completos/0 bloques; ya sin Error5, pero rechazo del grupo `93483a247c83d099458c1a92a7d4742f` y hosts exhaustos. |

Los dos controles reales finales conservaron tres capturas 20/60/180s del
Xvfb privado con el diálogo de startup CASC, no una pantalla de Login sana.
Duraron 312828/312750ms, objetivo terminado por timeout con código124,
output no truncado, cleanup del CID sin error y Xvfb propio terminado.
No se armaron watchpoints ni se observaron escritores/lectores del almacén.
El cero se limita al scan privado RW en ese estado pre-graphics.

La copia de trabajo contiene 93 archives (93021230203 bytes), 16 journals
(24903680 bytes) y en el último control 318043668 bytes de CDN indexes.
Los archivos copiados se verificaron byte a byte por SHA durante la preparación;
tras el cliente se registran cambios de metadatos de la copia, deliberadamente
mutable. En los originales se verifican metadatos de archives/CDN indexes y SHA
de los 16 journals, ejecutable/42 dependencias/`.build.info`; no afirmar un hash
completo post-run de 93GB ni validación Blizzard de todos los payloads.

## 5. Índice de grupo: qué se ha verificado

El grupo que el cliente rechaza mide 31241988 bytes, SHA-256
`6c3f087ff68f19cb055b562e493b4aa5510ddad9640ccf395500b703a2e948ba`.
La configuración pública lo declara como `patch-archive-group`, con 76 archivos
patch de entrada. El parser existente acepta su formato/round-trip y sus hashes
internos de páginas/TOC/footer, pero eso **no valida la identidad externa del grupo
contra el build ni su aceptación por este cliente**.

Un rebuild aislado con los 76 inputs presentes y válidos produjo exactamente
los mismos 31241988 bytes/1190482 entradas/SHA, diferencias0. Artefacto:
`wowforever-artifacts/work/x64-patch-group-research-9p2q6838/`.
No se sustituyó ningún grupo del cliente ni se hizo otro control con bytes
idénticos. Se descarta como explicación demostrada un skip actual de inputs o
un orden de hashes distinto: ambas listas ya están ordenadas y el builder usa
el orden de configuración. La relación del vector de tamaños y su significado
posicional siguen sin establecerse; un HEAD público devolvió 53588 bytes para
el primer patch index, igual al archivo local, no a su supuesto tamaño posicional.

[WoWDev/TACT](https://wowdev.wiki/TACT#archive-group) documenta que estos grupos
se ensamblan en el cliente y no están en CDN; el 404 del grupo en el host oficial
no prueba corrupción. Los footer hashes truncados no son el hash externo de
32 hex. No se obtuvo una derivación normativa de ese hash ni se cambiaron checks
del cliente para aceptar nuestro archivo.

Limitación de auditoría: el primer worker de formatos superó el límite de I/O
asignado de 16MiB (mínimo249935904 bytes lógicos); chunks de1MiB no eran un cap
total. No hubo writes en la fuente. El experimento posterior de rebuild sí
registró 180792743 bytes bajo su presupuesto explícito de256MiB; RSS no medido.

Tests del runner: último round completo 31 positivos y una aserción histórica
de alias obsoleta; la aserción se corrigió a la vista privada real y su filtro
pasó (1 positivo/31 excluidos). Los tests sintéticos Wine anteriores se conservan
por ronda; no se declara la suite completa del repo ni evidencia de auth.

## 6. Qué sigue y qué técnica escoger

Antes de continuar el trazado necesitamos un baseline que supere CASC. La copia
actual y sus reparaciones acotadas no lo han demostrado. No se harán más
repeticiones de 300s con el mismo error ni cambios a ciegas de los índices o de
la verificación. La comparación con metadatos de una base funcional coherente
es más discriminante que otra prueba de ausencia del almacén.

Propuesta histórica descartada por la separación de proyectos:
[exportación acotada de referencias CASC](../.agents/planning/x64-casc-reference-export-plan.md).
No se ejecutó ni se solicitará continuar el acceso a esa ruta como parte del
trabajo ARM64. Los siguientes párrafos describen la técnica x64 histórica,
no una autorización vigente para nuevas pruebas.

Con esa fuente:

1. Verificar el debugger en el runtime Wine elegido y crear la sesión aislada.
2. Ejecutar el detector read-only desde el arranque y acotar la primera aparición
   del bloque realmente validado; registrar región y asignación observables.
3. Observar escritores y lectores. Si la watchpoint llega después de construir
   el bloque, no llamar a esos hits “inicialización”: dirigir otra ejecución al
   origen de asignación/copia demostrado, sin reutilizar una dirección de heap.
4. Relacionar fuentes y consumidores con módulos/RVAs y pilas, diferenciando
   una copia genérica de su constructor.

**Todavía no se puede escoger con evidencia entre parche estático, parche
dinámico o interceptación.** El resultado actual favorece continuar con la
investigación dinámica aprobada una vez resuelto el bloqueo de datos. Si la
tabla no aparece antes de Login, se documentará ese límite; no se introducirán
credenciales ni acciones de autenticación en esta fase.
