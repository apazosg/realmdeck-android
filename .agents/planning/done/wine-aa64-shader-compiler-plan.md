---
id: wine-aa64-shader-compiler-2026-10-08
state: done
date: 2026-10-08
---

## Summary

Code48 creó D3D11 con feature level11_1, pero la sonda termina
COMPILER_UNAVAILABLE. El prefijo tiene d3dcompiler_47 x64; el mismo Proton contiene
un builtin AA64 verificado. Ver `docs/panvk-wine-probe-poco-results.md`.
Se propone seleccionar **solo ese compilador builtin existente** en la sonda
gestionada, sin copiar DLLs ni cambiar backend. Requiere aprobación porque el
plan Wine/PanVK anterior congeló DLL/override y pidió parar ante esa dependencia.

## Content

- Único teléfono DEVICE_SERIAL/UID10330/debugpkg/API34. Preservar APK48 e inputs.
- Mismo Wine/Proton11/DXVK2.4.1/PanVK beta17/wrapper Xlib/noGL/startup/audio/FEX,
  fixed PE89600/hash a19e672.../RUN/cwd/A/args, memoria OFF. No WoW ni cuentas aún.
- Fuente AA64 existente1.114.112 bytes SHA
  `c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add`.
  Prefijo x64 original4.453.888 bytes SHA
  `06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52`
  permanece intacto: nada de overwrite, symlinks o descarga de DLLs externas.
- Fijar únicamente `d3dcompiler_47=b` en WINEDLLOVERRIDES del último merge Wine,
  conservando las demás entradas, incluidas dxgi/d3d11. No reemplazar el valor
  completo ni mover wined3d a otro renderer. Sus dependencias builtin del mismo
  Proton se cualifican por el loader; no suponer que sus imports equivalen a carga.
- Gated debug/probe/active RUN/fullmanagedprofile, flags/sourcepin congelados.
  Profile off byte-exacto; extras antiguos no activan el override sin flag.
- Registrar carga real del compilador AA64, módulo/path/exports y error si falla.
  D3D11 11_1 no implica shader/present. Un dependency failure para aquí, no añade
  más overrides/DLLs para aparentar éxito. Si esta selección no respeta la fuente
  conocida, control inválido: parar y revisar resolución, no copiar a ciegas.
- Mejorar la captura **solo de metadata propia**, durante la vida de la sonda,
  para PE+PanVK en el mismo PID/startticks/UID y vínculo al RUN/raíz. Mantener
  límites de bytes/tiempo y credenciales fuera. Falta de captura no es fallback
  demostrado ni permite relajar el analizador. No ptrace, memoria o señales.
- Una prueba USB automática después de reboot por Wine previo, host120s+cleanup
  bounded, one-shot UI, control aislado. API Present exige además screenshot real
  concordante de triángulo para Wine/Xlib; no usar el WSI Android ya aceptado.

## Implementation / Action Items

- [x] 1. Reproducir el resultado code48 y auditar header/hash de ambos compiladores.
- [x] 2. Aprobar la selección builtin y captura de metadata acotada.
  Usuario: «sigamos», tras presentar el plan y su alcance pendiente.
- [x] 3. Implementar/tests flags/merge no-destructivo/loader/ABI y review.
  Code49 16 JVM pasan; builtin declarado y fuente AA64 verificada, no DLL copiada.
- [x] 4. Build offline/freshrecipe/audit preservando48; una prueba USB.
  Controles49/50/51 guardados: 50 metadata/logger, 51 GetLastError inmediato en
  PE propio versionado (anterior conservado). 83 inputs, final51 audit PASS;
  D3D11_1 persiste pero compilerLoadError193. No éxito de shader/present.
- [x] 5. Decidir shader/readback/present y siguiente WoW solo por evidencia.
  La selección b sola no resolvió el error de arquitectura. El plan independiente
  de reparación del prefijo se aprobó y ejecutó después: code54 carga compiler y
  compila shaders, pero readbackFAILED/0presents y control no cualificado por maps.
  No avanzar a WoW; siguiente gate es diagnóstico acotado de render/readback.

## Open Issues

- **Histórico code51/52:** `LoadLibraryW(d3dcompiler_47.dll)` devuelve193
  ERROR_BAD_EXE_FORMAT con override efectivo b. El builtin seguía sin cargar.
  Fuente/prefijo wined3d AA64 coinciden por hash: no hay causa de dependencia
  concreta que permita añadir otro override. Ver
  `docs/wine-aa64-shader-compiler-results.md`; decisión siguiente es resolución
  precisa del archivo/import, no reemplazo arbitrario o nuevo backend.

- Alcance aprobado: se cambia solo modo de carga de una dependencia, no el driver.
- El x64 del prefijo es incompatibilidad concreta, pero no prueba aún que sea
  la única causa. Builtin AA64 importa wined3d/UCRT/kernel32/ntdll; no cualificados
  todos esos módulos por esta inspección estática.
- Code48 no capturó mismoPIDPE+PanVK/ancestría completa: no aceptado como control
  de loader aun con D3D creado. No ampliar autoría/ownership para resolverlo.
- No download/toolchain nueva/rebuilddriver/Mesa/Wine; solo componentes existentes.
  MinApi35 del ICD y aceptación GPU/WSI/D3D/fullCASC/privateauth mantienen límites.
- Code52 logging-only conserva la traza real: compiler47 de system32 se rechaza
  por arch8664 y load_dll devuelve c000007b antes de selección builtin observada.
  La reparación posterior se autorizó por separado en
  `.agents/planning/done/wine-aa64-compiler-prefix-plan.md`; este plan no autorizaba
  ese swap. Code54 confirma carga y compilación, no triángulo/present ni autoría
  samePE/PanVK completa. Ver results doc y cierre del plan de reparación.
