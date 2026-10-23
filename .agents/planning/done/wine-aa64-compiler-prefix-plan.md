---
id: wine-aa64-compiler-prefix-2026-10-08
state: done
date: 2026-10-08
---

## Summary

Code52 es logging-only. La traza del intento real demuestra que Wine encuentra
`C:\windows\system32\d3dcompiler_47.dll`, lo rechaza por machine8664 y termina
`status=c000007b`; no llega a seleccionar el builtin AA64 en ese intento. El
override `d3dcompiler_47=b` por sí solo no evita ese chequeo previo.

Se propone reparar **solo ese componente del prefijo gestionado**, usando la copia
AA64 del mismo Proton ya instalada, con backup verificable y rollback. No DLL
externa ni otro renderer/driver. Cambia un archivo que el plan anterior congeló:
alcance escrito aprobado por el usuario («size hazlo», sí hazlo). Se conserva el
backup y el criterio de parada descritos; no se autoriza sustituir otras DLLs.

## Content

### Evidencia exacta

- Único POCO `DEVICE_SERIAL`, UID10330, code52/0.2.28. Carpeta externa
  `trial-20261008T172634Z-7e46f046ad`, RUN `RUN-1791480413367-45929808617`.
- En stderr/tail, NT00e8:00ec:
  `load_dll looking for L"d3dcompiler_47.dll" in L"A:\\;C:\\windows\\system32;..."`;
  dos `open_dll_file ...system32\\d3dcompiler_47.dll is for arch8664, continuing
  search`; `load_dll Failed to load module ...status=c000007b`.
- Prefijo:4.453.888 bytes SHA
  `06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52`, x64/PE32+.
- Proton:1.114.112 bytes SHA
  `c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add`, AA64/PE32+
  con marker Wine builtin. Wined3d/ucrtbase/kernel32/ntdll de source y prefijo
  tienen machineAA64 en la lectura independiente; no tocar esos imports.
- Z: apunta a ImageFS, no a `/`; los paths `opt/wine` y `opt/proton-11...` no
  ofrecen este builtin. No inventar una ruta absoluta visible desde Windows ni
  añadir aliases de disco/LD paths para evitar la reparación del componente.
- Code52 mantiene D3D11 creado; compiler error193/0presents sigue bloqueando.
  El logging no cualifica aún PE+PanVK/samePID ni presentación del triángulo.

### Cambio único y protección de datos

1. Flag congelado para el debug managed graphics-probe actual. No activar por
   extras antiguos ni cambiar otro contenedor, Main normal, WSI o proceso ajeno.
2. Después de la provisión upstream y antes del exec, validar el prefijo real del
   contenedor/RUN activo dentro de appfiles, con los aliases de home legítimos
   permitidos únicamente hacia su destino propio. No aceptar otros prefix/root.
3. Leer streaming/NOFOLLOW el builtin source exacto, tamaño/hash/PEAA64, y el
   destino leaf system32/compiler47. Reemplazar SOLO si su hash coincide con el
   x64 conocido. Si ya es el AA64 exacto, revalidar y reutilizar; cualquier otro
   contenido/symlink/owner/path/mutation => STOP, no overwrite a ciegas.
4. Copiar el original a backup nuevo propio con hash/metadata verificados y
   fsync; conservarlo, no borrar. Preparar AA64 en temporal exclusivo del mismo
   directorio, fsync y pin. Commit atómico con revalidación inmediata del original;
   si proveedor no permite atomic rename, STOP, no copiar parcialmente al destino.
5. Journal transacción/backup/source/target antes/después. No existe nueva ruta
   de exec hasta posthash/PE/owner correcto. Comprobar nuevamente al armar la
   llamada; cancelar/stale RUN no arranca Wine. IO o fallo de commit conserva
   original/backup/temporales propios para recuperación, sin borrar datos desconocidos.
6. Rollback solo al original conocido desde backup verificado, después de ausencia
   demostrada de intento Wine o boot-boundary requerido; nunca sobre DLL distinta,
   durante proceso vivo o liberando supuestas cargas sin prueba. El fallo de
   restauración es explícito, no limpiado mediante force-stop para fingir rollback.

Mantener `d3dcompiler_47=b` y todas las demás entradas como en52. No nuevooverride,
registry, driver/PanVK/Wine/DXVK/WCP/noGL/startup/audio/FEX/shader/PE de sonda,
kernel/permissions/root/servicio/session/memoria Forever/login o datos del cliente.
Preservar sourceAPK52 y todos los reportes. No nueva descarga/toolchain/buildMesa.

### Verificación y prueba

- Tests de source/target hash/PE, backup, symlink/path/race, IO/partialwrite/fsync/
  commit/rollback/cancelled generation y que otros DLLs se mantienen byteidénticos.
- Review independiente del swap del componente, recetafresca/idempotente/build
  offline/audit de nuevo candidato; no repetir suites no afectadas.
- Una sonda USB AOA tras reboot por Wine previo, captura<=120s+cleanupbounded,
  misma prueba D3D/shaders/readback/30Present con evidencia visual separada.
- Exigir module/compiler_load y GetProcAddress real; DLL/import/error nuevo =>
  STOP, no ampliar a más swaps. Archivo ARM64 presente no equivale a cargado.
- Mantener samePIDPE+PanVK/rootUIDticks/RUN gate sin relajarlo por pérdida de maps.
  No aceptar Wine/Xlib/D3D/present/WoW por este único cambio de arquitectura.

## Implementation / Action Items

- [x] 1. Capturar archivo/arquitectura/status rechazados con code52 y sources existentes.
- [x] 2. Aprobar reparación de un DLL de prefijo con backup/rollback.
  Usuario: «size hazlo».
- [x] 3. Implementar/probar/revisar la transacción gated antes de otra ejecución.
  Backup, commit/rollback atómico, estados interrumpidos, cancelación y verificación
  pre-spawn implementados. Tests dirigidos y revisión completados; no se afirma
  una suite completa reejecutada tras cada corrección.
- [x] 4. Build/audit y una sonda USB; verificar módulo/shader/readback/present real.
  Code53 falló antes de mutación/spawn por alias de appfiles. Code54 normaliza
  únicamente /data/data y /data/user/0 del mismo package; receta fresca85inputs,
  build offline/audit PASS. Control físico54: compiler cargado y shaders compilados,
  pero readback FAILED/0presents; no aceptación del control gráfico.
- [x] 5. Restaurar o conservar reparación explícitamente, y decidir siguiente gate.
  Se conserva el AA64 correcto y el backup original verificado en teléfono y host.
  Siguiente gate: diagnosticar render/readback, no otra DLL ni WoW.

### Cierre del control físico

- Trial `trial-20261008T220723Z-00219df91e`, RUN
  `RUN-1791497262380-683075998117`, code54/0.2.30-automated. APK370710845 bytes,
  SHA `cee6ffaf9f4a6b7f28b402139dcbd4c7e6d1022e77e6358136697a94370005d9`;
  candidato y audit retenidos fuera del repo.
- Recibo `COMMITTED`, original4.453.888/06dc23... y instalado1.114.112/c374b107...
  verificados independientemente. Backup/destino UID10330, modo771, nlink1 y mtime
  original conservados. Recibo estable durante la colección readonly posterior.
- `compiler_load.loaded=true`, `getProcFound=true`; shaders vs_5_0/ps_5_0 S_OK.
  Readback S_OK pero coloredPixels0/hash3375453637, terminalFAILED y 0presents.
  El hash coincide con un frame uniforme del clearRGBA8(10,10,20,255): consistente
  con solo fondo, no causa de Draw demostrada ni fallo de carga del compilador.
- Analizador estricto `CONTROL_INVALID`: falta PE+PanVK en mismo PID/startTicks
  y raíz propia. No se relaja el requisito ni se infiere fallback System.
- Se conserva la reparación, porque resuelve el formato rechazado y carga el
  compilador. No rollback físico ni prueba de corte de energía realizados.
- Agent-stop propio completado; snapshot posterior solo contiene el `ps` de la
  colección. Sigue `termination_unknown`: reboot requerido antes de otro Wine,
  no quiescencia de kernel demostrada por ausencia de procesos muestreados.

Ver `docs/wine-aa64-shader-compiler-results.md` para rutas y límites de evidencia.

## Open Issues

- Alcance aprobado; el DLL x64 solo se reemplaza tras comprobar su pin exacto.
- La traza demuestra el candidato x64 rechazado, no que la reparación garantice
  todos los imports/shaders/present. No ignorar un error posterior.
- Upstream puede reprovisionar componentes; validar el último punto de selección
  efectivo y no modificar globalmente su instalador WinComponent por hipótesis.
- Reparación/carga del compilador confirmadas en54; no WoW/frame/login aceptados.
  Faltan render/readback positivo y captura samePE/PanVK completa. El siguiente
  diagnóstico cambia una sonda congelada, por lo que requiere su propio plan.
