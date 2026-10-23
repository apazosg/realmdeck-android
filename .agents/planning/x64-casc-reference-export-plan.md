---
id: x64-casc-reference-export-2026-10-14
state: cancelled
date: 2026-10-14
---

## Summary

**Descartado el 2026-10-14 por aclaración del usuario:** Btrfs pertenece a un
proyecto separado y esta exportación queda fuera del alcance. El objetivo de
wowforever es hacer funcionar el cliente ARM64 con Classic Forever, sin dar por
decidido si requiere parche en memoria, archivo u otro método. Esta exportación no se ejecutó ni se
autorizó; no continuar sus acciones. Se conserva el borrador como historial,
no como propuesta pendiente de aprobación.

Aclaración posterior: se permite consultar código de parcheo, configuración de
Wine y referencias del proyecto Btrfs en solo lectura, sin modificar nada de él.
Esto no reactiva la exportación ni autoriza ejecutar sus scripts/servicios.

El trazador read-only está validado en fixtures Linux y Wine, pero el laboratorio
WoW x64 sigue sin superar CASC. El grupo patch local es internamente consistente
y un rebuild con los 76 inputs produce bytes idénticos; el cliente lo rechaza.
Otra repetición con esos mismos datos no aporta evidencia de construcción del
CertStore. Se propone comparar **solo metadatos públicos** con la base completa
documentada, sin copiar cuentas ni usar sesiones existentes.

La fuente está detrás de un padre `root:root 0700`. Esta propuesta requiere
autorización explícita de lectura/exportación administrativa; no se ejecuta por
la autorización previa de copiar `client-base`. No se ejecutará WoW o el debugger
como root y no se cambiarán permisos ni servicios productivos.

## Content

### Alcance mínimo

- Fuente candidata fija:
  `/opt/wowserver/shared-runtime/btrfs/initial-70009/mnt/base`.
  Confirmar primero que es la base técnica consistente, no un upper vivo de un
  jugador. Si no puede verificarse, parar sin recorrer otras rutas privadas.
- Confirmar `.build.info`, build70009 y los hashes de configuración fijados;
  verificar mediante lectura el hash del ejecutable x64 de referencia
  `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`, sin ejecutarlo.
- Exportar únicamente `.build.info`, los configs públicos seleccionados por ella
  y **los dos índices de grupo** declarados por el CDN config. Los nombres tienen
  que ser 32 hex y provenir de ese config; el grupo patch conocido es
  `93483a247c83d099458c1a92a7d4742f.index`.
- No exportar todo `Data`, archives, HOME, WTF, logs, Errors, credenciales, tokens,
  perfiles, control de sesiones o archivos por glob. No leer memoria de procesos.
- Fuente montada solo lectura en un helper de exportación de datos o copiada por
  el administrador. Entrada fija, sin entrypoint productivo, red, capacidades de
  depuración, cambios Btrfs, chmod/chown de origen ni parada de procesos.
  No habilitar un mecanismo genérico de ejecución o lectura privilegiada.
- Destino nuevo privado fuera del repo, archivos CREATE_NEW0600, lectura por FD
  sin symlinks, bytes/metadata/hash antes-después, límites 256MiB por índice y
  768MiB total de datos exportados. Salidas creadas por el usuario del laboratorio;
  no sobrescribir las evidencias ni las copias anteriores.

### Resultado y continuación

- Comparar estos datos con la copia actual: build/config y hashes de ambos
  grupos. La identidad interna del footer no prueba aceptación por el cliente.
- Si coinciden, registrar que el problema no se distingue con esta comparación;
  no repetir otra ejecución ni modificar checks o hashes esperados.
- Si difieren con el mismo build/config, investigar origen/consistencia y probar
  los metadatos verificados solo en **una nueva copia de trabajo**, manteniendo
  todos los originales y los expected hashes. No rehacer payloads a ciegas.
- Una prueba pasiva adicional solo procede si hay una diferencia causal concreta.
  Mantener detector read-only, sin Login, red bloqueada, UID1000, prefijo/display
  propios, 300s+30cleanup, cliente no modificado. Confirmar arranque normal antes
  de interpretar cero stores; no inferir autenticación o constructor por GUI.
- Este plan no autoriza exportar el cliente completo, montar producción en el
  contenedor de juego, modificar claves, usar el supervisor parcheador ni
  implementar cambios ARM64. El plan de trazado principal permanece abierto.

## Implementation / Action Items (cancelados; no ejecutar)

- [ ] 1. Autorizar la exportación acotada de metadatos de esta base protegida.
- [ ] 2. Verificar origen consistente y preparar helper/operación administrativa
  cerrada, sin acceso a material personal ni acciones sobre producción.
- [ ] 3. Exportar y autenticar únicamente los archivos públicos permitidos.
- [ ] 4. Comparar datos y recomendar un único control discriminante, o documentar
  que no hay diferencia suficiente para justificar otra ejecución.
- [ ] 5. Documentar resultados; no dar por identificado CertStore ni resuelto el
  parche o Login hasta observar evidencia real.

## Open Issues

- La base completa está documentada pero no se ha inspeccionado por falta de
  acceso. Su papel, build y estado consistente se verificarán, no se asumirán.
- No existe una fórmula normativa validada del hash externo del grupo en las
  fuentes consultadas; no cambiar ese hash para aceptar el blob local.
- La exportación puede no resolver el problema. Si la coincidencia es total,
  habrá que contrastar runtime/vista con la configuración funcional, sin leer
  secretos ni iniciar los entrypoints productivos.
- La aprobación modifica solo la frontera de **lectura de datos públicos**; no
  concede root al cliente/debugger ni acceso general al árbol de producción.
