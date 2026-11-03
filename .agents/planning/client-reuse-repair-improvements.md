---
id: client-reuse-repair-2026-11-02
state: in-progress
date: 2026-11-02
---

## Origen

El build de ensayo del 2026-11-02/03 activó dos atajos para medir la reanudación del
prefijo local en USB: `trustForeignHeadersWithoutBody = true` fijado en
`ForeverClientUpdater` (adopción por cabecera sin probar cuerpos) y la revalidación
del binding de ruta/atributos/segmento cada 256 objetos
(`CANDIDATE_BINDING_INTERVAL`, `ForeverCasc.kt:365`). El usuario fijó la dirección:
**reutilizar el contenido es interesante, pero no debe haber nada puesto a mano**, y
quiere poder instalar y probar el build en otros dispositivos mientras se resuelve.

La reutilización ya está especificada en US-009-FR-011 (cada candidato se prueba por
EKey, tamaño, BLTE, checksums y, sin frames `E`, CKey y tamaño decodificado). El
atajo no reutilizaba: se saltaba esa prueba. Hoy no existe una reparación barata para
ese fallo (ver más abajo), por lo que no puede quedarse como comportamiento de
producto.

## Principio

Ningún comportamiento activado por un literal escondido, y toda reutilización debe
tener una prueba verificable o una reparación efectiva. Si no hay prueba barata, se
mide antes de debilitar la especificación.

## Trabajo pendiente, por orden

### P0.1 — Quitar el atajo y medir la verificación completa

- Eliminar `trustForeignHeadersWithoutBody` del parámetro de `ForeverCasc.writeTarget`
  y del llamador, junto con su rama de diagnóstico; restaurar FR-003/FR-011
  (prueba por objeto) sin dejar ninguna ruta alternativa.
- Medir la verificación completa con las optimizaciones ya presentes: índice en
  memoria y `DiskIndexTable` pre-dimensionada/mapeada, readahead por segmento y
  binding amortizado.
- Referencia: 16,48 GB tardaron ~68 min antes de esas optimizaciones, pero el USB da
  ~42 MB/s (48 GB ≈ 19 min). Si el coste baja a un rango aceptable, se cierra sin
  tocar la spec.

### P0.2 — Reparación explícita del cliente CASC

- US-008-UC-03 (verificar/reparar objetos) sigue sin implementación: `repairLoose`
  solo cubre ficheros sueltos y `forever-client repair` no verifica objetos bajo
  `Data/`.
- Añadir una acción Verificar/Reparar que re-pruebe el contenido CASC y re-descargue
  lo que falle, sin depender de reaplicar una actualización.
- Resolver el límite documentado de reaplicar una instalación idéntica (atasco del
  journal de `ForeverClientUpdateTransaction`,
  `docs/launcher-app-status.md:1753-1757`).

### P1.1 — Reutilización barata y verificable (requiere aprobación de spec)

- Diseñar un recibo durable por objeto escrito al sellar el segmento (hash de
  contenido + identidad de segmento + versión), de forma que la reanudación verifique
  la cadena de recibos en vez de releer decenas de GB.
- Solo es válido si el segmento sellado conserva su identidad y nada externo lo
  modificó; el límite debe quedar explícito en FR-003/FR-011.
- No implementar sin aprobación del cambio de especificación.

### P1.2 — Decidir el binding cada 256 objetos

- Aprobar en la spec la ventana (y la detección tardía de una sustitución) o volver a
  comprobar en cada objeto. Es una relajación real frente a la letra actual.

### P1.3 — Desbloquear la procedencia de release

- `REVIEWED_LIGHT_STORAGE_PROVENANCE_SHA256` conserva la carga congelada anterior:
  la reconstrucción estricta falla cerrado. Revisar esa carga y actualizar el pin, o
  documentar por qué no se actualiza.

### P2.1 — Alinear el smoke de host

- `scripts/diagnostics/android-process/run_functional_launcher_ux.py` todavía espera
  la etiqueta de preparado, la casilla y el botón «Lanzar WoW»; no debe usarse tal
  cual contra el flujo de una sola acción.

### P2.2 — Quitar cifras narrativas del código

- El comentario «48 GiB local prefix» y la estimación del plan de USB son aproximados
  y no medidos. Medir el prefijo (`du -sh`) y sustituir la cifra, o eliminarla del
  código.

### P2.3 — Medición de rendimiento

- El objetivo de 3,2 MB/s y la decisión de merge/publicación siguen abiertos; no hay
  throughput de red medido para este build.

## Build de ensayo publicado

El build que se publica ahora mismo lleva el atajo activo a propósito, para poder
probarlo en otros dispositivos. No es un release estable ni debe promocionarse como
tal: puede aceptar contenido local sin probarlo y, si algo falla, la reparación
disponible es cara. Los detalles del tag y del APK se registran en
`docs/launcher-app-status.md` y en las notas del release.

## Criterios de salida

- Ningún comportamiento queda gobernado por un literal fijo.
- El código y US-008-FR-003 / US-009-FR-011 coinciden, o la diferencia está aprobada.
- Verificar/Reparar CASC probado en dispositivo con un objeto corrupto inducido.
- Sin fallos nuevos en la suite; cifras de tamaño y velocidad medidas, no estimadas.
