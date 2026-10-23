---
id: wine-interrupted-run-disposition-2026-10-11
state: done
date: 2026-10-11
---

## Summary

El segundo control SERVER_ISOLATION60 publicó un binding RUN/WS. Un defecto host
al leer el receipt interrumpió la captura y originó force-stop propio. El RUN
permanece `ACTIVE/PREFLIGHT`; no hay cierre nativo probado. El defecto del
collector está corregido, pero repetir el control exige cambiar admisión.

Propuesta: **abandonar exclusivamente ese intento tras un nuevo boot**, conservar
sus bytes/estado inciertos y consumir una autorización append-only una sola vez
para un RUN nuevo. No es recuperación prelaunch: ausencia de marcadores/archivos
no prueba que no se crearan hijos. No se convierte en PASS, ECHILD o quiescencia.

El usuario autorizó continuar con «adelante», tras aclarar que las protecciones
internas y sus correcciones son responsabilidad del agente. No se solicitará
permiso para cada corrección menor o repetición diagnosticada posterior.
La implementación gráfica del plan padre sigue pendiente del GO server/client.

## Content

### Autoridad exacta que se congelará antes de implementar

| Campo | Único valor admitido |
|---|---|
| Package/device | com.wowforever.arm64probe / UID10330 / xaga22041216G Xiaomi/API34 / serial DEVICE_SERIAL |
| APK60 | 418266965 / SHA76db60beacfb6d62a0f55b16c4d78b06e9b4351fd9a28de5695849b340800c24 |
| RUN | RUN-1791718684192-65783495695 |
| WS | WS-024cc10c5e18649fad0c6567191aa18b |
| Nonce | 44fa3305f5c3db9fa0d8cd6541978ba8 |
| Último boot observado | 06254205-1db4-4aaa-8f4e-7123db10f851 |
| Current/RUN JSON | ACTIVE/PREFLIGHT,575 bytes/SHA745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2 |
| Binding | BOUND,207 bytes/SHA1c7d568d4ec506bb7a0a2233fa12ec1b453bf64143d5835f9e2bdf083b28bd22 |
| Resultado host | UNKNOWN,232 bytes/SHA681b5154d73d292032249b25ad1e3235e4f34341c886771aba1ec6310d4a9bf9 |
| Seal original | ca8b128255f98c99639615c5e0faef65ddbed613c8f577750785149553a42003 |
| Seal suplemental | 4e6b7bcc0bf586f7f7fd36e7eb5efd4c338ba531562aafc141855ec5be9f9af2 |
| Post-trial state | 9724/SHAfd3b50ed666843f23040aadc3b4b87e4c792494c7d0690e9912b471a867ebc05 |

Raíz externa de evidencia:
`/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/wine60-server-isolation-b5c368d6845f414cbbbe56db5745a744/`.
Closeout completo:
`read-only-closeout-e4e8730ff3264482ab07db3cce79dbc3/`.
Pin adicional de source/provenance original y mapa completo de33 paths se extrae
y autentica desde estos seals antes de escribir el contrato JSON consumible.
No inventar raw source o eventos que no están en la evidencia original.

Las ausencias capturadas (marcadores, resultado, receipt, logs, WS/prefix roots)
deben seguir representadas como `MISSING`, no ficheros vacíos. Se verifican
nofollow y hashes antes/después; cualquier cambio/resultado nuevo bloquea esta
excepción y exige diagnóstico. No se usan esas ausencias como prueba de no-fork.

### Frontera y transacción propuestas

1. Mantener el bloqueo actual hasta aprobar, revisar e incorporar este contrato
   a un candidato nuevo. No llamar `recoverActiveRun()` ni editar estado por ADB.
2. Permitir preparación install/reboot únicamente desde el APK60 exacto y el
   estado/binding anteriores, bajo el lock cooperativo del dispositivo. Otros
   ACTIVE/UNKNOWN siguen bloqueados. Sin Activity/START ni Wine antes de nuevo
   boot verificado y relectura de todo el historial/protectedfiles.
3. Nuevo boot distinto del último y del observado antes de reboot, propiedad de
   package/cert/UID y candidato auditado. Es una frontera de vida de procesos,
   **no** prueba de cómo se cerró/reap el RUN antiguo. No barridos PID/UID/nombre.
4. Bajo el lock JVM + OS del Store: revalidar exacto old current, RUN/binding,
   boot y contrato autenticado mediante los hashes fijados. Archivar current byte-exacto
   mediante CREATE_NEW/0600/fsync/dirsync; el RUN JSON/binding originales quedan
   intactos. Añadir receipt `ABANDONED_ON_NEW_BOOT`, con resultado antiguo incierto,
   `closureObserved=false`, `qualification=false`, `echild=false`, old/new boot,
   hashes y razón host-capture-interruption. No escribir `wine-result` positivo.
5. Reserva de consumo única ligada al nuevo RUN/WS/nonce antes de avanzar
   current. Commit final después de binding y estado nuevos durables. Cualquier
   set parcial, crash, nonce cambiado, doble consumo o fallo fsync bloquea el
   Store; no retomar automáticamente una reserva ni generar otra autorización.
6. Solo el pointer `current.json` avanza al RUN nuevo, después de preservar el
   anterior. Los archivos del RUN viejo mantienen su `ACTIVE/PREFLIGHT`; el
   receipt distingue abandono histórico de un cierre nativo certificado.
   No generic reset, nuevo namespace, borrado o reetiquetado del pasado.
7. Collector/helper verifican archivo/receipts/binding de primera admisión y
   bytes estables en admisiones posteriores positivas; no rebase del historial.
   Un nuevo UNKNOWN/ACTIVE incompleto no hereda esta excepción.

### Implementación acotada

- Candidato61/`0.2.37-automated` si disponible, mismo package/cert/ABI/min29/target36.
  Solo contrato fijo de disposición, Store/admisión, pruebas y consumidores
  recipe/audit/helper/collector. Conservar60 y toda evidencia antes de build.
- ELF Wine/publisher, plantilla, Proton/DXVK/PanVK/noGL/PE/JNI/cliente sin cambios.
  Perfil SERVER_ISOLATION con server y cliente inocuo; no GUI/WoW/login/parche.
- Añadir diagnóstico host fijo con timestamps/seq/fase y ruta propia aprobada
  antes de error/captura/cleanup. No imprimir paths arbitrarios, UI ajena,
  cuentas o excepciones con contenido no aprobado. No retransmitir START.
- Evitar una nueva interrupción durante la espera120s del prefijo; cualquier
  fallo de captura conserva current/authority parcial y queda UNKNOWN, nunca
  éxito o permiso de recuperación derivado del propio collector.

### Criterios de aceptación y validación

- Negativos JVM/host: wrong RUN/WS/nonce/APK/boot/hash/path, symlink/leaf dangling,
  changed current/marker/new result, old nonce reuse, doble consumo concurrente,
  crash en todos los boundaries, fallo de file/dir fsync y callback tardío.
- El caso positivo autoriza **solo** el primer RUN nuevo y preserva bytes de
  old RUN/history; no declara quiescencia, ECHILD o cualificación del abandonado.
- Review de estado/ownership antes del dispositivo. Fresh/idempotent source,
  JDK17 offline1worker/CPU2/Xmx4GiB/assets-rerun-only, APK audit/retención y
  comparación assets/libs contra60, con additions deliberadas/provenance.
- Nuevo boot/own UID/hash/history, un control normal. GO real solo tras
  publisher/receipt, READY/prctl, server y namespace propio, cliente exit0,
  adopción/reaping, RUN_DONE/ECHILD, supervisor exit0, testigo y commit/rootgate
  durables. Luego cancelaciones de server/client si el primer gate lo permite.
- Deadlines existentes: preparación app120s, native45s+10cleanup, host240s+20
  reserva. Repeticiones diagnosticadas no tienen techo global, pero un nuevo
  estado incierto vuelve a bloquear; no expansión automática de excepciones.
- Conexión gráfica managed del plan padre solo después del GO server. PE
  anónimo/visual/login permanecen gates separados; ningún cambio de analizador.

## Implementation / Action Items

- [x] 1. Abandono exacto por nuevo boot autorizado por «adelante», sin prueba retrospectiva de cierre.
- [x] 2. Contrato autenticado `b65e2a66668e7c137259b68e832f587edc41e9f03ed4d5ee8976d19f8d4e92ad`: los33 paths históricos reales y4 precondiciones prospectivas separadas; diagnóstico host acotado.
- [x] 3. Transacción única, lectura FD/nofollow de autoridad y relectura de current antes del avance;35 tests JVM dirigidos pasan en una ronda final, incluidos crash, doble consumo, symlinks, cambios de autoridad y overflow. Python84 tests previos y fuente fresh/idempotente64 inputs verificados.
- [x] 4. Review sin P1/P2 pendiente, fresh/idempotente64 inputs, build y auditoría de APK61:418308948bytes/SHA99a256bd8f5de5854c609490b83799cb0da6c38d184330283745d0bca5cf006d. Retenido0600/fsync;171→172 assets/libs,170 idénticos, solo provenance cambia y se añade el contrato. ELF/runtime/cliente anteriores intactos.
- [x] 5. APK61 instalado/verificado y nuevo boot bc331988; admisión única consumida, historial60 intacto. El nuevo RUN falló en guard filesDir antes del receipt, Wine UNKNOWN; no GO server/client ni nuevos controles tras ese estado.
- [x] 6. Evidencia original y closeout58 paths estables preservados. Resultado NO-GO documentado en docs/wine-interrupted-run-disposition-results.md; plan padre abierto.

## Open Issues

- Validación previa:35 JVM dirigidos, Python84 y fresh64 inputs; build/audit/retención61 pasan. La prueba física posterior no cualifica Wine: RUN-1791738410167-533692127338 FAILED/UNKNOWN, sin receipt/READY acreditado. La corrección posterior del alias filesDir pasa9 JVM de prefijo pero no está ensamblada/instalada. La evidencia no fija los dos paths Java del fallo; no inventar esa observación. Collector protegido stat/SHA/restat pasa14 casos host y preparación idempotente instalada pasa2 casos.

- No puede determinarse el instante de creación nativa respecto a force-stop:
  faltan timestamps y closure. La propuesta conserva esa incertidumbre.
- No hay capacidad Wine/SELinux/app-domain acreditada bajo60 todavía. Si el
  nuevo intento falla, no se atribuye automáticamente al driver ni al prefijo.
- Esta excepción difiere de la antigua disposición PRELAUNCH57: aquí no se
  afirma que la creación de procesos no se alcanzara.
- El plan padre sigue abierto y bloqueado. Esta operación no autoriza Bionic
  genérico, sweeps, Wine memory read/ptrace ni rearmar el EOF59 UNKNOWN.
- Tras un fallo transaccional o nuevo UNKNOWN será necesaria evaluación nueva;
  no generalizar este ticket exacto a recuperación automática de producto.
