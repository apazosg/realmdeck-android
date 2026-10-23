---
id: functional-x64-mali-ab-2026-10-13
state: draft
date: 2026-10-13
---

## Summary

**No ejecutar:** propuesta rechazada por el usuario tras su presentación.
Priorizar el cliente ARM64 nativo y el análisis de su parche de autenticación.
Se conserva este borrador como historial, no como tarea pendiente de aprobación.

El usuario quiere parchear ARM64 **o encontrar una alternativa que cumpla el
mismo objetivo**: WoW Forever funcionando en Android con GPU Mali. El renderer
ARM64 ya muestra el login limpio, pero no se ha identificado su CertStore.
Ghidra y el contraste de ventanas de ejecución no han localizado un parche válido.

Proponemos una PoC A/B del cliente Windows x64 ya disponible, traducido mediante
el ARM64EC/FEX existente, sobre el mismo renderer Mali. Es una decisión de ruta
de ejecución, no un cambio menor del scanner. Este documento **no está aprobado**;
no autoriza aún copiar/ejecutar x64 ni cambiar la PoC.

## Content

### Alcance mínimo

- Mantener ARM64 como opción por defecto y conservar APKs, source bindings y
  configuración gráfica limpia. Nueva opción de arquitectura separada del perfil
  de servidor; no sobrecargar el selector Original/Classic Forever.
- Referencia x64 local: `/home/adrian/dev/wowforever/client/WowB.exe`, 102916816
  bytes/SHA-256 `5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92`.
  Confirmar PE8664/PE32+ y versión70009 antes de cualquier copia. No descargar otro
  cliente ni tocar el cliente host, CASC o cuentas.
- Copiar únicamente ese ejecutable a la carpeta externa propia de la PoC con
  nombre fijo `WowB.exe`, sin sobrescribir un archivo desconocido. Compartir los
  datos CASC existentes como entrada, no duplicarlos ni considerarlos completos.
- Usar un contenedor/prefijo PoC x64 **distinto** del ARM64, creado por la ruta GUI
  ordinaria existente. No reutilizar/convertir el prefijo ARM64 reparado, no tocar
  su compiler47/backup, ni volver a la campaña de supervisor/Store recuperaciones.
- Ejecutable fijo `A:\WowB.exe`, argv por la ruta XServer/Bionic existente.
  Runtime ARM64EC/FEX ya empaquetado; comprobar HODLL/FEX y errores del cargador.
  Sin relajar validadores managed/catalog ni añadir ejecutables arbitrarios.
- Conservar PanVK, wrapper, noGL y ajustes BCnone/software/AHB/cache0; no sustituir
  DLLs/drivers a ciegas. El profile DXVK actual puede no ser suficiente para x64:
  si el loader lo rechaza, registrar el límite sin declarar compatibilidad.
- En x64, la sesión de parche ARM64 se mantiene **desactivada y no se invoca**.
  UI debe mostrar arquitectura y estado real. Cambio de arquitectura exige cerrar
  la GUI y nuevo arranque; nunca retargetear una sesión viva.

### Verificación y puerta física

- Tests de selector/defaultARM64/patch hook OFF en x64, nombres y args fijos,
  regreso al perfil ARM64 y no cambios en datos/portal original.
- Recipe funcional reproducible, marker con hashes reales, tests dirigidos y
  build offline/JDK17/CPU2/Xmx4GiB/1worker con assets merge+compress frescos.
- Nuevo candidato/versionado sin sobrescribir APKs retenidos; verificar cert,
  ABI, permisos de launcher y comparación de payload. Sin nuevos binarios de
  runtime salvo el ejecutable x64 explícitamente autorizado.
- Solo POCO xaga `DEVICE_SERIAL`, paquete propio UID10330, sin root. Nuevo boot
  antes de cada ejecución incierta, runner acotado120s+20cleanup, volumen1/15,
  pantallazos únicamente de GUI propia y logs sin secretos.
- Primera prueba **sin Login ni credenciales, sin lectura/escritura de memoria**:
  demostrar proceso `WowB.exe`, loader/FEX, dispositivo D3D en Mali y escena limpia.
  Un proceso iniciado o un PNG del launcher no son éxito de WoW.
- Repetición solo tras diagnóstico concreto. Si arranca, el siguiente contrato
  será una sesión read-only x64 propia para localizar el Store que corresponde al
  patcher de PC. **No se implementa escritura ni se repite Login en esta operación.**

### No objetivos

No root/ptrace/SELinux bypass, binarios ajenos ejecutados, memoria arbitraria,
parches de instrucciones no demostrados, claves distintas inventadas, cuentas,
login automático, world/gameplay afirmado, instalación completa, descarga masiva,
revisión de seguridad de producto, APK pública, commits o limpieza de trabajo ajeno.

## Implementation / Action Items

- [ ] 1. Aprobar la ruta alternativa x64/FEX manteniendo el baseline ARM64.
- [ ] 2. Verificar referencia x64 y decidir prefijo/contenedor separado sin tocar
  el original; registrar contrato de argv y perfiles DLL efectivos.
- [ ] 3. Implementar selector PoC y tests, con hook de memoria OFF en x64.
- [ ] 4. Build/audit/retención y comparación reproducibles.
- [ ] 5. Prueba propia sin Login: loader → D3D/Mali → escena; o fallo específico.
- [ ] 6. Documentar resultado y decidir lectura x64 posterior según evidencia.

## Open Issues

- El usuario descarta la prueba: añadir traducción puede perjudicar rendimiento
  y comprobar x64/FEX no resuelve el parche ARM64. No copiar cliente ni implementar
  selector/ejecución bajo este borrador; la investigación x64 queda solo como
  referencia estática del comportamiento ya validado en PC.
- La maquinaria Bionic ARM64EC/FEX existe; no se ha probado este WoW x64 con el
  renderer PanVK/DXVK actual. Una prueba antigua FrontMage/FEX falló C0000005,
  usando otro renderer; no prueba incompatibilidad de esta combinación.
- Los directorios `aarch64-windows`/`i386-windows` y HODLL no demuestran por sí solos
  que las DLLs DXVK aarch64 actuales puedan satisfacer al cliente x64. No cambiar
  drivers ni ampliar el profile silenciosamente si aparece incompatibilidad.
- CASC móvil es bootstrap parcial (~1.2GiB), no instalación completa. Fallos de
  streaming y de traducción pueden coexistir; no atribuir uno sin sus logs.
- No se ha observado el Store de x64 en Android. El éxito del patcher PC no
  autoriza memoria ni Login aquí sin localizar/verificar el bloque real.
