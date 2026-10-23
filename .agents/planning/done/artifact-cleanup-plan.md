---
id: artifact-cleanup-2026-10-01
state: done
date: 2026-10-01
---

## Summary

El usuario pregunta «podemos limpiar los artefactos antiguos?». Se propone una
primera pasada conservadora sobre **cuatro APKs concretos**, no una eliminación
recursiva de prototipos, fuentes o cachés. El usuario aprueba la lista el
2026-10-01 con «si haz la limpieza h a ver si podemos hacer un apk que avance
más». La limpieza no amplía el alcance del siguiente ensayo de runtime.

Después del inventario, el usuario dejó un informe de la corrida en `delivery/` y
describió controles táctiles con pantalla negra, seguidos de otra pantalla tras
minimizar/restaurar. Ese informe queda expresamente protegido. Confirma spawn de
Wine en code 27, no ejecución/render de WoW ni parche aplicado; las nuevas
investigaciones de runtime pueden necesitar los componentes originales.

## Content

### Lista exacta propuesta para eliminación

Raíz de los cuatro ficheros: `/home/adrian/wowforever-artifacts/delivery/`.

| Fichero | Bytes | SHA-256 esperado | Motivo |
|---|---:|---|---|
| `wowforever-automated-0.2.0-code24-debug.apk` | 489.521.421 | `dec5df5c73f646cd064052c6d42fb53993f33ab9aba72521423854bb912ef805` | Candidato sustituido; fallo de validación de Proton corregido posteriormente. |
| `wowforever-automated-0.2.1-code25-debug.apk` | 361.821.873 | `c071c07f1053da848dfa70a9e575909d603975f4744f303a4c5cb5677db3ee1b` | Candidato sustituido; catálogo con tamaños BLTE incorrectos. |
| `wowforever-automated-0.2.2-code26-debug.apk` | 361.854.069 | `b13cda7e527d0e60f58801ee7e615c7e55200487199c0ade959a20006af20ac9` | Candidato sustituido; resolución CDN que provocaba el 404. |
| `wowforever-arm64-native-probe-35e9911b.apk` | 365.944.497 | `35e9911b0713bc6f6ca750aef16216e453cffdb50cfeef50f49c4382faaa256f` | Copia duplicada: se conserva el APK byte-idéntico en `arm64-prototype/delivery/app-modern-debug.apk`. |

Total de tamaños: **1.579.141.860 bytes**, aproximadamente **1,47 GiB**. Se
verificaron tamaños y enlaces con `stat`; la igualdad del APK de sondas con su
copia conservada se comprobó mediante SHA-256. Es una estimación por tamaños,
no una garantía del incremento exacto de espacio libre del filesystem.

### Conservación obligatoria

- Entrega actual **0.2.3/code 27**, incluidos APK, instrucciones, audit JSON,
  `source-recipe.zip` y `SHA256SUMS-wowforever-automated-0.2.3.txt`.
  APK esperado: 362.784.252 bytes, SHA
  `5e4b0b7d52caa2ec4357b26959ec778443495115c5fe426913905e3823a0f00a`.
- **Todos los ZIP de corridas/logs**, incluidos
  `/home/adrian/wowforever-artifacts/delivery/forever-RUN-1790883604709-786341652235022.zip`
  y cualquier informe nuevo que aparezca durante la limpieza. Nunca seleccionar
  ZIPs por antigüedad o con un patrón global.
- Audits, instrucciones, checksums y ZIPs de fuentes/recetas históricos de
  0.2.0–0.2.2. Se mantienen como registros históricos, no como entregas completas
  con APK disponible. No alterar sus hashes ni presentar sus checksum manifests
  como verificables íntegramente después de retirar el binario.
- `arm64-prototype/delivery/app-modern-debug.apk`, `CANDIDATE-REVIEW.txt`,
  `SHA256SUMS.txt` y el ZIP histórico de candidato de sondas. Su eliminación no
  pertenece a esta primera pasada.
- Fuentes GameNative modificadas, archivo original fijado, APK actual del build,
  JNI, perfiles, sondas, catálogo, payload externo verificado y evidencias CDN.
- `integrated-runtime/toolchains/jdk-17.0.20.1+1` y demás toolchains; SDK, NDK,
  Gradle, Rust, cachés cascette y entorno del emulador, sin excepción.
- `references/`, descargas originales Winlator/FrontMage, experimentos Box64/FEX,
  informes del emulador/patcher y logs de pruebas. No borrar sus carpetas completas.
- Cliente, CASC, snapshots, tar del cliente, procesos de instalación activos,
  contenido del dispositivo y archivos del repositorio ajenos a este plan.

### Riesgos y límites

Eliminar los tres APKs automatizados históricos retira la posibilidad de
reinstalar inmediatamente esos **bytes exactos**. Se conservan sus fuentes y
audits, pero no se promete una reconstrucción byte a byte. El APK de sondas sí
mantiene una copia exacta como rollback.

Esta pasada no compacta todos los artefactos del proyecto ni migra carpetas.
Una limpieza posterior de builds o runtimes necesita un inventario de
dependencias propio: por ejemplo, el directorio del prototipo Box64 también
contiene el JDK que utiliza actualmente GameNative.

## Implementation / Action Items

- [x] 1. Inventariar candidatos y comprobar que la entrega vigente está identificada.
- [x] 2. Excluir fuentes/toolchains, cachés, cliente y el informe nuevo del usuario.
- [x] 3. Obtener aprobación explícita de esta lista de cuatro ficheros.
- [x] 4. Preflight inmediato antes de borrar, sin compilar ni descargar:
  - [x] Verificar los cuatro artefactos enumerados en el checksum manifest de
    0.2.3 y que su APK sigue siendo code 27/tamaño/hash esperados.
  - [x] Verificar que la copia de sondas conservada mantiene el SHA esperado.
  - [x] Validar los cuatro candidatos con `lstat`, rutas canónicas, tamaños y
    SHA-256: ficheros regulares, sin symlinks y dentro de la raíz prevista.
  - [x] Si cualquier fichero cambió, falta o no coincide, parar y no ampliar la
    lista. Registrar el inventario previo y la lista aprobada fuera del repo,
    incluyendo que los APKs históricos quedarán retirados.
- [x] 5. Eliminar únicamente los cuatro ficheros aprobados, sin globs, recursión
  ni borrado de directorios. No detener ningún proceso.
- [x] 6. Verificar que los candidatos han desaparecido, la entrega vigente sigue
  íntegra y el ZIP de la corrida no ha cambiado; registrar el resultado de la
  limpieza y comunicar el espacio recuperado/estimado.
- [x] 7. Actualizar las referencias que afirmen conservación de APKs históricos,
  distinguiendo el binario retirado de su audit/receta conservados. Marcar este
  plan `done` y moverlo a `done/` solo después de verificar la limpieza.

## Open Issues

- Ejecutados y verificados los cuatro borrados aprobados: 1.579.141.860 bytes
  lógicos retirados; incremento observado de espacio libre 1.579.155.456 bytes.
  Ese delta también puede incluir actividad concurrente del filesystem. Registro
  previo, eventos de borrado y comprobación final en
  `/home/adrian/wowforever-artifacts/delivery/artifact-cleanup-2026-10-01.jsonl`.
  Los cuatro artefactos actuales, su checksum manifest, el ZIP de la corrida y el
  rollback de sondas conservan hash/tamaño/inode/mtime. Solo desaparecieron los
  cuatro nombres aprobados; no se borraron directorios ni se detuvieron procesos.
- No se amplía esta operación para resolver la pantalla negra. El informe nuevo
  queda conservado y su análisis inicial no demuestra un crash gráfico ni del
  proceso WoW: `gamePid=null`, `DISCOVERY_NOT_FOUND`, sin logs de juego; Wine
  sí produce stdout/stderr. La redirección a `app.gamenative` es una pista por
  comprobar, no una causa confirmada.
- Mientras la entrega 0.2.3 se investiga en teléfono, no se eliminan sus fuentes,
  componentes runtime ni evidencias, aunque haya copias o parezcan antiguas.
