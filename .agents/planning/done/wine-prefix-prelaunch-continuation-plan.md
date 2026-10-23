---
id: wine-prefix-prelaunch-continuation-2026-10-11
state: done
date: 2026-10-11
---

## Summary

El abandono histórico60 se consumió correctamente. El nuevo61 falló en
`ForeverWinePrefix.ownFilesRoot` antes del receipt/publisher. Se corrigió la
aceptación bidireccional de los dos alias privados user0 del package;9 JVM
dirigidos pasan, pero el APK instalado sigue siendo61 antiguo.

Continuación propuesta: candidato62/0.2.38 si disponible, corrección de paths
y disposición append-only **solo del prelaunch61 fijado**, en el mismo Store.
No reutilizar el ticket60 ni crear un namespace de escape. No implementar esta
nueva admisión antes de la aprobación del plan.

## Content

Autoridad antes de escribir código: RUN-1791738410167-533692127338,
WS-51c1b011c946c45309229bd9d6fff681, nonce f9f4da016c99beb427b44274e9ab4ef6,
APK418308948/SHA99a256bd8f5de5854c609490b83799cb0da6c38d184330283745d0bca5cf006d,
boot bc331988-3879-4cd4-b4d2-c1b616c5a2a1,
current8625ecf47bf3347107e1475995e85fe3454dcf4f7fed7bad9d8f2b3bba9dd3d2,
Wine result d25aec706b9cf564ae58ff78c43d8567f0a87e2d3f55736b3e01297bb7a04f04.
Completar contrato con TODOS los captures/statuses autenticados por seal
original2831fd327578a6c6d5cd95972fdf7f3c6857113850d2214cbccc7916cc8df10c y
suplemental8bfc88773d7b7be622255cb92d3c54b94692220cafc6439bb9bf24ba768c7d3d.

- Verificar fuente/APK/provenance y error exacto antes de afirmar prelaunch;
  archivos ausentes o supervisorExit=null por sí solos no bastan. Conservar
  incertidumbre y native closure UNKNOWN; no fabricar marker retrospectivo.
- Leer autoridad FD/nofollow y bajo mismo lock Store. Mantener originales61,
  archivo60, reserva/abandono/consumo60 y fixtures56/57/59 byte-exactos.
- Nuevo boot y candidato independiente auditado, mismo package/cert/UID/device.
  Reserva CREATE_NEW ligada a un nuevo RUN/WS/nonce, archivo current61 exacto,
  receipt PRELAUNCH_ABORT histórico sin ECHILD/qualification y consumo único.
  Partial/crash/fallo fsync/reutilización → quarantine, sin recuperación genérica.
- Solo current avanza después del commit. Nuevo UNKNOWN no hereda excepciones;
  no `recoverActiveRun`, borrado, cambio de status pasado o tercer namespace.
- Añadir diagnóstico seguro de ambos paths Java al guard sin guardar rutas
  ajenas arbitrarias; alias exactos package/user0 y metadatos/no-follow intactos.
- No cambiar Wine/publisher ELF, template/runtime/WCP/DXVK/PanVK/noGL/PE/JNI/client.
  No Wine GUI/WoW/auth/memoria/ptrace/root/global kill ni publicación.
- JVM authority/transaction/cancel/crash/consumo/paths, revisión independiente,
  source fresh/idempotente, build offline JDK17/CPU2/Xmx4GiB/1worker con rerun
  assets únicamente, audit/retención y comparación contra61. No falsas suites.
- Primer control nuevo solo SERVER_ISOLATION tras reboot: prefix/receipt,
  READY/prctl, server namespace+cliente exit0, reaping/ECHILD/supervisor exit0,
  testigo y commits durables necesarios para GO. Luego cancelaciones solo si
  gate positivo. Mismos budgets app120/native45+10/host240+20, sin retries inline.

## Implementation / Action Items

- [x] 1. Aprobación recibida («si hazlo»); autoridad congelada en contrato
  14683 bytes/SHA0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27:
  58 paths, 19 PRESENT y 39 MISSING autenticados desde seals.
- [x] 2. Paths/diagnóstico y admisión exacta única implementados en el mismo Store;
  relectura FD de current antes y después del commit, sin rehacer el ticket60.
- [x] 3. Tests dirigidos y revisión: Python 100 casos iniciales positivos y tres
  fixtures de versión corregidos/verificados; JVM 32 positivos iniciales y caso
  de mutación corregido/verificado, más cuatro tests de continuación (tres
  positivos iniciales y fault-loop corregido/verificado). Verificación por rondas,
  no suite completa única. Fresh/idempotente: 64 inputs y equivalencia actual.
- [x] 4. APK62 construido, auditado y retenido: 418331853 bytes,
  SHAd1431898c0cd7030419f82a7fc0e819c713d833ae0e98b978a4726827225d6ff.
  Comparación61→62: 172→173 assets/lib, 171 intactos, solo provenance cambiado y
  contrato de continuación añadido; runtime/ELFs/PE/client intactos.
- [x] 5. APK62 instalado, nuevo boot y un control SERVER_ISOLATION: nuevo RUN
  FAILED/UNKNOWN en extracción de directorio firmado0777. No receipt/publicación
  ni prueba Wine; ninguna repetición después del UNKNOWN.
- [x] 6. NO-GO documentado en docs/wine-prefix-prelaunch-continuation-results.md;
  closeout read-only estable, historial61/60 y stage62 preservados.

## Open Issues

- Aprobado por el usuario el 2026-10-11: «si hazlo». La implementación y las
  pruebas acotadas del plan no necesitan consultas adicionales; toda desviación
  material de su contrato se documentará antes de continuar.

- La pareja supplied/canonical Java del fallo61 no fue capturada. Run-as resuelve
  user0; el test de ambos alias confirma lógica, no reproducción Android real.
- No hay Wine app-domain acreditado. La corrección de paths puede descubrir
  otro gate posterior; no atribuir el siguiente fallo al driver sin evidencia.
- El histórico60 sigue ACTIVE/PREFLIGHT en su RUN original y abandonado solo
  por receipt. El actual61 FAILED conserva terminationUnknown/quiescence=false.
- Este plan modifica admisión, no es permiso para ignorar arbitrary UNKNOWN.
- Resultado62: RUN-1791752157102-84597710465 /
  WS-5df69c2d17d326d0d9e07042119b9988, boot0b550857-e3ee-4625-9ee8-875a70b85608.
  El guard confundía raíces privadas con modos exactos de descendientes firmados.
  Corrección de fuente posterior: publisher host5 tests y Prefix JVM11 pasan,
  sin nuevo ELF/APK ni nueva admisión. Próxima decisión separada en
  `.agents/planning/wine-template-mode-continuation-plan.md` (draft).
