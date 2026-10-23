# Fixture de trazado x64

Además de esta fixture Linux, `wine_fixture.py` valida watchpoints en una fixture
PE propia bajo Wine11. `run_wow_trace.py`/`wow_trace.c` son el laboratorio Win32
separado: nunca invocan el entrypoint productivo ni escriben claves.

Modos explícitos del runner:

- `--synthetic`: prueba mecánica con datos públicos del contrato.
- `--real`: debugger sobre el hijo x64 propio; todavía no ha identificado el Store.
- `--passive-real`: mismo arranque sin debug/watchpoints; detector solo lectura.
- `--capture-display`: únicamente pasivo, captura el Xvfb privado a 20/60/180s.
- `--trace-build-config`: pasivo, filtro acotado de metadatos públicos de archivo.
- `--copy-working-archives`: pasivo, nueva copia ordinaria de 93GB más índices
  públicos en una capa privada RW; fuente RO, sin hardlinks. Preparación900s,
  control300s+30cleanup, reserva de espacio y hashes durante la copia. No repetir
  sin un diagnóstico nuevo; los reportes y fuentes anteriores no se sobrescriben.

El cliente real sigue fallando en inicialización CASC. Tests, éxito de la fixture
y scans privados completos no prueban construcción, ausencia global o auth.

`trace_fixture.py` es una prueba **sintética nativa de Linux x86-64**, no un
launcher WoW, detector de memoria ajena ni instrumento validado para Wine.

Compila un hijo propio con una tabla de 12×40 bytes derivada de las claves
públicas de `forever/contract.json`. Antes de inicializarla, arma DR0 para
observar ocho bytes y distingue una escritura conocida de una lectura posterior
mediante las instrucciones exactas de la fixture y el IP posterior al trap.

```bash
/home/adrian/.local/bin/uv run --offline python \
  scripts/diagnostics/reverse-engineering/x64-certstore-trace/trace_fixture.py

/home/adrian/.local/bin/uv run --offline pytest -q tests/test_x64_certstore_trace.py
```

El primer comando crea una carpeta nueva fuera del repo, bajo
`/home/adrian/wowforever-artifacts/work/`, privada `0700`; reportes exclusivos
`0600`, con fsync. `--output-dir` permite una carpeta nueva externa para tests.
No reutilizar ni sobrescribir reportes anteriores.

Presupuestos: 30 segundos de trazado, 64 eventos totales y 16 frames por pila.
Cleanup solo del hijo creado por el programa; sin root, cambios de Yama,
capacidades nuevas, señales por UID/nombre ni Wine/prefijo global.
El binario sintético no usa PIE; esto no afecta al ASLR de otros procesos.

El test compara propiedades de región en dos hijos independientes. Una salida
correcta no identifica la rutina de WoW ni demuestra soporte del debugger en
Wine. El watchpoint observa un subconjunto de ocho bytes; la validación completa
de la tabla la hace la propia fixture.

Estado del cliente real y siguiente gate:
[`docs/x64-certstore-initialization.md`](../../../../docs/x64-certstore-initialization.md).
