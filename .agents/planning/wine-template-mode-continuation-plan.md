---
id: wine-template-mode-continuation-2026-10-11
state: draft
date: 2026-10-11
---

## Summary

APK62 llegó a extraer el prefijo y rechazó un directorio que el manifest firma
como0777. Fuente Kotlin/C corregida, host5 y JVM11 tests pasan; aún no hay nuevo
ELF/APK. El RUN62 FAILED/UNKNOWN conserva un stage parcial. Propuesta63/0.2.39
si disponible: corregir publisher/pins y continuar solo esta preparación concreta,
conservando la evidencia y el stage sin atribuirles cierre nativo limpio.

## Content

Autoridad a congelar antes de implementación de admisión:
RUN-1791752157102-84597710465, WS-5df69c2d17d326d0d9e07042119b9988,
nonceab9c93fab980150ce5000027efe9bf1e, boot0b550857-e3ee-4625-9ee8-875a70b85608,
APK418331853/d1431898c0cd7030419f82a7fc0e819c713d833ae0e98b978a4726827225d6ff,
current47822a424550974a7fdef333d5607695549f011edc724d0fc4d54c029ad5061a,
binding54da738718c441133863183a8673555e826d72da6d65963a5e8c332366a4fe3a,
resultc1983b2157cc8ff7494669a5d34a82df2f39baed34e4c7421dcf1137603896f3,
originalseal c8626fcdf8ba4c953e53fb68f78d7aa0433d976e5b594e3e9f6b28ae79a874f7.
Cerrar el mapa completo de captures/statuses/supplemental antes de código; los
directorios EXISTENTES del stage no se pueden declarar MISSING como en61.

- Mantener raíces privadas700 y permitir solo modos exactos de plantilla firmada
  debajo del stage privado. ZIP/manifest/runtime/DLLs no se alteran. Rebuild solo
  publisher con recipe local NDK24/API29/16KiB; nuevo pin y manifest, no falso
  reetiquetado del ELF retenido62. Supervisor Wine y fixtures intactos.
- Exacta reserva/archivo/receipt/consumo append-only en el mismo ForeverRunStore,
  bajo locks JVM/OS y FD/nofollow. Viejo RUN/binding/result y stage parcial intactos.
  Receipt histórico UNKNOWN/no qualification/no ECHILD, no recovery genérico.
- Candidato63 independiente auditado, mismo package/cert/UID/device; nuevo boot
  distinto del intento62 y del before real. Current avanza solo tras commit;
  partial/crash/doble consumo/mutación/otro UNKNOWN bloquean, no tercer namespace.
- No reutilizar prefijo/stage62 ni borrar el stage. Nuevo RUN/WS/nonce y árbol
  privado NUEVO. Missing intent/logs o snapshot vacío no prueban ausencia de hijos.
  Vincular prelaunch al APK/source error exactos antes de admitir la excepción.
- Añadir diagnóstico de etapa de preparación y marcador durable antes de todo
  submit de proceso; no inventar retrospectivamente eventos62. Las fases con
  intención de proceso incierta nunca adquieren recuperación general.
- Tests mode0777 exacto/umask/root700, autoridad/crash/consumo/historial/latewrites,
  revisión independiente, JVM dirigido, overlay fresh/idempotente, build JDK17
  offline CPU2/Xmx4GiB/1worker y audit/retención/baseline62. Deltas binarios exactos:
  publisher nuevo y su manifest; todo otro runtime/PE/client byte-identical.
- Un control normal SERVER_ISOLATION tras reboot; prefix/receipt/READY, servidor
  namespace+cliente exit0, reaping/ECHILD/supervisor exit0, testigo y commit reales
  necesarios para GO. Cancelaciones solo después de gate positivo. Budgets
  prefix120/native45+10/host240+20, no retransmisión START ni rearm UNKNOWN.
- Sin GUI/WoW/login/auth/memoria/ptrace/root/global UID/name kill; sin publicación
  ni commits. No cambios al historial60/61 o fixtures56/57/59.

## Implementation / Action Items

- [ ] 1. Aprobar alcance y congelar autoridad completa62 autenticada.
- [ ] 2. Rebuild publisher/pins y admisión exacta63 con stage62 preservado.
- [ ] 3. Tests y revisión de límites de plantilla/admisión/diagnóstico.
- [ ] 4. Fresh/build/audit/retención63 y comparación explícita contra62.
- [ ] 5. Nuevo boot/control SERVER_ISOLATION, sin retry tras UNKNOWN.
- [ ] 6. Sellar resultado y decidir siguiente gate hacia gráficos/WoW.

## Open Issues

- Draft: no autorización aún para modificar admisión o generar/instalar63.
  Las correcciones locales de guard ya verificadas no son ejecución de este plan.
- El cierre62 confirma stage700 y directorio777 propietario10330; no prueba
  kernel containment ni Wine. Las fuentes nuevas no describen retroactivamente62.
- La plantilla tiene regulares0777 también: el fchmod sobre FD debe conservar
  modos firmados sin relajar las raíces ni confiar en umask.
