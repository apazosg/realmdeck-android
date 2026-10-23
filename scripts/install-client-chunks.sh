#!/usr/bin/env bash
# Instala el cliente completo en lotes acotados, reanudando entre lotes.
#
# Por qué por lotes: el paso "Installing N files to local CASC" retiene en memoria
# estructuras proporcionales al trabajo pendiente. Una pasada con el manifiesto
# completo pica ~15 GB de RSS; en un host con poca memoria disponible el
# OOM-killer lo mata a mitad. El estado de instalación se guarda de forma
# reanudable, así que ejecutar en lotes acota el pico y avanza igual.
#
# Uso:
#   scripts/install-client-chunks.sh <dir-cliente> [tamano-lote] [max-lotes]
#
# Variables de entorno reconocidas:
#   CASCETTE_MAX_CONCURRENCY / CASCETTE_MAX_PER_HOST  concurrencia de descarga
#   UV_BIN                                            ruta al binario `uv`
set -uo pipefail

DIR="${1:?uso: install-client-chunks.sh <dir-cliente> [tamano-lote] [max-lotes]}"
CHUNK="${2:-50000}"
MAX_RUNS="${3:-200}"
UV_BIN="${UV_BIN:-$HOME/.local/bin/uv}"
export CASCETTE_MAX_CONCURRENCY="${CASCETTE_MAX_CONCURRENCY:-6}"
export CASCETTE_MAX_PER_HOST="${CASCETTE_MAX_PER_HOST:-2}"

cd "$(dirname "$0")/.." || exit 1

for run in $(seq 1 "$MAX_RUNS"); do
    echo "=== run $run $(date -Is) lote=$CHUNK ==="
    out=$("$UV_BIN" run forever-client install "$DIR" --max-files "$CHUNK" 2>&1)
    printf '%s\n' "$out" | tail -25

    pending=$(printf '%s\n' "$out" | sed -n 's/.*Installing \([0-9][0-9]*\) files.*/\1/p' | tail -1)
    installed=$(printf '%s\n' "$out" | sed -n 's/.*CASC files installed[^0-9]*\([0-9,]*\).*/\1/p' | tr -d , | tail -1)

    echo "=== fin run $run: pendientes=${pending:-?} instalados=${installed:-?} ==="

    if [ "${pending:-}" = "0" ]; then
        echo "=== COMPLETO: no queda nada pendiente ==="
        break
    fi
    if [ "${installed:-}" = "0" ]; then
        echo "=== SIN PROGRESO: paro para no dar vueltas ==="
        break
    fi
done
