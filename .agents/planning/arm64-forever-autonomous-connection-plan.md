---
id: arm64-forever-autonomous-connection-2026-10-15
state: in-progress
date: 2026-10-15
---

## Summary

Usuario autoriza explícitamente investigación, pruebas y correcciones reversibles
autónomas, incluyendo usar su cuenta de test, sin aprobación por cada ensayo.
Orden inmediato aprobado: corregir attach tardío y exceso de E/S de D2 y ejecutar
directamente observación temprana de sockets. El objetivo final sigue siendo
autenticación y entrada al juego ARM64 nativo, no completar diagnósticos.

La primera acción es una corrección metodológica acotada ya decidida por el
usuario, no otra propuesta pendiente de aceptación. Este documento registra el
alcance de ejecución. No reabre la hipótesis cerrada de escaneo anterior al Login.

## Content

- Preparar strace existente antes del lanzamiento; identificar WoW mediante
  candidatos Wine+comm, UID4, startTicks y un marcador propio de entorno. No
  esperar75s con el basename que falló. Medir latencia real de attach.
- Primera traza solo socket/connect/getsockopt/getpeername/shutdown/close y
  duplicación de descriptores; sin read/write ni buffers de envío/recepción.
  Fuente de verdad: proceso real, socket TCP, endpoint y resultado de conexión.
- Sin subir automáticamente cap2MiB ni rehacer capturador. Si una limitación
  impide observar, identificarla y corregir el procedimiento existente una vez;
  después de dos intentos sin evidencia nueva, reevaluar/cambiar de hipótesis.
- Login con cuenta de test autorizado si es necesario para provocar la conexión.
  No reutilizar cuentas reales. No escribir contraseña en el repo: usar un
  fichero efímero0600 en artefactos privados, consumido/eliminado por el helper.
- Registrar solo evidencia breve: hipótesis, cambio, resultado, pendiente,
  timestamps y rutas/hash relevantes. Restaurar configuración experimental y
  preservar binarios/clientes originales. No sobrescribir trabajo ajeno.
- No confundir TCP Android, otra instancia Wine o metadatos de cipher con TLS
  del cliente hacia Forever. Errores/estados desconocidos se mantienen como tales.
- Consultar antes de cambios destructivos, sistema/particiones/Btrfs, publicación,
  seguridad permanente, credenciales reales o cuentas no de prueba. Btrfs queda
  solo lectura como referencia: no ejecutar ni modificar su infraestructura.

## Implementation / Action Items

- [x] 1. Ejecutar observación temprana de sockets con ambos instrumentos de
  memoriaOFF y restauración de configuración prevista desde el comienzo.
- [ ] 2. Identificar la primera frontera de fallo; contrastar hipótesis más probable
  con un control distinto, usando Login de test si es necesario.
- [ ] 3. Aplicar corrección reversible justificada y validar nueva ejecución.
- [ ] 4. Confirmar autenticación/entrada al mundo, o bloqueo técnico específico que
  requiera intervención del usuario; mantener bitácora durante la ejecución.

## Open Issues

- **Actualización posterior:** logging D4 restaurado a16:49:17UTC e instrumentosOFF;
  un Login visual a17:02:22UTC sin mundo acreditado. El checkpoint USB siguiente es
  histórico y ya resuelto. Investigación pausada por prioridad de producto según
  `arm64-launcher-app-polish-plan.md`; no declarar objetivo de autenticación cumplido.
- **Checkpoint D4:** transporte del propio WoW a1119/8081, handshakes TLS y
  tráfico cifrado bidireccional demostrados. Control REST del host valida cuenta
  test (`DONE`/ticket presente), no se retuvo ticket. Todavía sin reino/mundo ni
  código de error de aplicación aislado. Bitácora breve en
  `docs/arm64-forever-connection-progress.md`.
- **Intervención necesaria:** serialDEVICE_SERIAL ausente de ADB y USB a
  06:27:45/06:28:40UTC. No cambios críticos intentados para recuperar conexión.
  Al reconectar, primera acción restaurar WINEDEBUG/GNUTLS_DEBUG_LEVEL/marcador
  temporal del contenedor desde backup privado D4; no cleanup realizado todavía.
  Capturador/escánerOFF, ejecutable/runtime/Btrfs intactos, sin política de cipher
  o bypass TLS. Después continuar la hipótesis de semántica auth/SRP/BGS.

- D2 demostró attach posible, pero llegó tarde y saturó el cap con E/S genérica.
  D3 corrigió llegada/ruido y descubrió delegación de connect a wineserver,
  atribuible por inode compartido conWoW; D4 identificó DNS/SNI y auth transporte.
- La versión de strace4.25 y los permisos se comprobarán de nuevo si cambió el
  entorno. No asumir que herramientas/servicios nuevos estén instalados.
- No hay almacén CertStore ARM64 validado. No repetir búsquedas ya negativas ni
  elegir Ed25519 por defecto; investigar red/Wine/TLS/protocolo según evidencias.
