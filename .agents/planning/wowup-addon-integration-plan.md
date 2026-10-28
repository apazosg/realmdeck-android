---
id: wowup-addon-integration-2026-10-28
state: draft
date: 2026-10-28
---

## Summary

Integrar WowUp Hub en el gestor Android existente para poder buscar e instalar
addons Forever mientras el usuario espera la clave de CurseForge, ya solicitada.
El usuario pide planificar y crear un goal que incluya pruebas en dispositivo.
Este documento propone la ejecución; no autoriza todavía cambios de código,
despliegues, pagos ni aceptación de términos externos.

Se conserva el trabajo de CurseForge y su aceptación pendiente. Este goal nuevo
no completa ni sustituye retrospectivamente el goal anterior, cerrado como unmet.
La integración WowUp debe funcionar sin clave CurseForge y sin depender de su
concesión para su aceptación propia.

## Content

### Evidencia y punto de partida

- Gestor local y UI ya implementados: commits `4005100`, `c26ca47`, `e4fb01a`;
  [evidencia y límites](../../docs/addon-management-status.md). No repetir pruebas
  históricas sin motivo; comprobar las partes modificadas y sus regresiones.
- Consulta de lectura real a `https://hub.wowup.io/addons/search/forever?query=questie&limit=3`
  respondió 200 sin credencial. La consulta del proyecto `47671` también respondió
  200 y contiene releases GitHub con `game_type=forever`, `interface=16001`.
  No se descargó ni ejecutó ese contenido: esto no demuestra funcionamiento en WoW.
- La muestra no incluye tamaño, hash ni dependencias explícitas de release.
  El cliente actual exige tamaño/SHA-1 y maneja identidades numéricas CurseForge:
  no se puede conectar el JSON nuevo sin adaptar y verificar esos contratos.
- [Guía Hub](https://wowup.io/guide/wowup/hub): distribuye releases GitHub/GitLab.
  [Proveedor upstream](https://github.com/WowUp/WowUp/blob/master/wowup-lib/src/addon-providers/wowup-addon-provider.ts)
  y [términos](https://wowup.io/terms-of-service) son referencias de investigación,
  no autorización automática de uso del servicio por terceros. El cliente declara
  GPLv3 y el paquete wowup-lib declara ISC; verificar licencia aplicable antes de
  copiar código. Preferir un adaptador propio mínimo sin incorporar el cliente Electron.
- Árbol observado: cambios ajenos en updater/CASC y un plan de voice chat. No
  editarlos, incluirlos en commits ni reconstruir sobre ellos sin separar su
  procedencia. Revalidar HEAD y baseline al ejecutar; no asumir que C161 siga siendo
  la versión candidata adecuada.

### Alcance y decisiones propuestas

1. Conservar **Instalados / Buscar**. En Buscar, selector sencillo de fuente:
   **WowUp Hub** disponible cuando se valide; **CurseForge** conserva estado honesto
   de no configurado. Mostrar procedencia y atribución; no hacer fallback silencioso.
2. Consulta explícita, resultados acotados y navegación solo si el proveedor la
   acredita; no inventar paginación. Archivo exacto Forever/16001 y destino visibles
   antes de confirmar. No asumir que el primer resultado o el último release sirve.
3. Reutilizar instalación segura, exclusión de juego/updater, recuperación y gestión
   offline. Sin actualizaciones automáticas, perfiles, sobrescritura ni dependencias
   automáticas. La disponibilidad local no equivale a carga efectiva en el juego.
4. Identidad persistente `(fuente, proyecto, release)`, evitando colisiones con IDs
   CurseForge. Leer manifiestos ya creados sin reinterpretarlos como WowUp. No
   identificar ni fusionar paquetes previos por nombre; conflictos bloquean cambios.
5. Resolver dependencias desde evidencia verificable, incluidos TOC apropiados para
   Forever y componentes incluidos en el mismo paquete. Ausencia de un campo en Hub
   no demuestra ausencia de dependencias. Dependencias externas desconocidas o sin
   identidad verificable bloquean instalación; no debilitar AC-08 para instalar un ejemplo.
6. Verificar tamaño e integridad con metadatos confiables del asset del autor,
   usando digest publicado cuando exista; acordar en el contrato el tratamiento de
   assets sin digest. Un hash calculado al descargar no se presentará como verificación
   contra el autor. No fabricar tamaño/hash ni bajar garantías silenciosamente.

### Transporte y fronteras

Propuesta mínima: catálogo Hub y assets públicos del autor desde el cliente Android,
sin exigir un nuevo despliegue Vercel para avanzar. Confirmar antes viabilidad Android,
condiciones del Hub y metadatos del asset. CurseForge mantiene sus rutas y secreto
solo en servidor; diagnósticos no cambia. Si acceso, cuota o requisitos del proveedor
exigen backend, detener esa decisión, registrar impacto y aprobarla antes de publicar.

Admitir exclusivamente endpoints fijos de catálogo e identificadores validados,
repositorio y asset contrastados con el autor; no un descargador de URLs arbitrarias.
HTTPS, tiempos/tamaños acotados, sin cookies ni tokens ajenos. Redirecciones GitHub
solo con destinos HTTPS oficiales expresamente admitidos, salto limitado y sin
propagar credenciales. Comprobar bytes reales, ZIP peligroso, TOC y contenido completo
antes de publicar. Conservar límites actuales salvo necesidad medida y aprobada.

### Impacto de especificaciones, antes de código

Actualizar US-010 tras aprobación del impacto: US/UC-04 y AC-06/07/08/11 para fuente
adicional, compatibilidad, dependencias y transporte; FR-005/006/007/008/011,
NFR-003 y TC de catálogo/instalación requieren reconciliación trazable. Mantener
identidad/recuperación y todas las salvaguardas existentes. Registrar supuestos y
revisiones humanas de UC/AC, FR/NFR y TC antes de implementar esos cambios.
No modificar ahora el contrato aprobado de CurseForge ni historias de almacenamiento
o updater; presentar cualquier impacto real en ellas. SYSTEM no necesita requisitos
de este proveedor.

### Pruebas y criterios obligatorios de aceptación

- Host: pruebas focales de parsing Forever frente a Classic/Retail, errores/vacío,
  identidad entre fuentes y manifiesto previo, dependencias incluidas/externas,
  integridad/truncado/redirecciones no permitidas y no regresión de recuperación
  y exclusión. Revisar independientemente seguridad de red/ZIP y persistencia.
- Generar APK desde reconstrucción canónica de una baseline identificada; pruebas
  Android focales y auditoría de fuentes/payloads sin ampliar admisiones genéricas
  ni rebajar pins. Artefactos y receipts fuera del repo.
- Dispositivo dedicado por ADB/scrcpy según
  [procedimiento](../references/device-control-scrcpy.md): comprobar conexión actual
  y versión instalada, preservar datos y actualizar APK compatible sin desinstalar
  la app ni borrar cliente/cuentas. Si firma/versión exige borrado, detener y preguntar.
- Inventario inicial y salvaguarda acotada de carpetas de prueba/preferencias con
  juego cerrado. No archivar cliente vivo ni modificar addons del usuario para
  forzar una prueba. Elegir addon pequeño admitido, sin conflicto ni dependencias
  irresueltas; Questie es evidencia de catálogo, no fixture obligatorio.
- Recorrido visual real: buscar en Hub **sin clave CurseForge**, confirmar fuente,
  archivo/destino, descargar e instalar, ver inventario correcto y persistencia al
  reabrir la app. Comprobar UI legible, controles táctiles, progreso y errores reales.
- Iniciar el juego por el flujo normal, comprobar addon en listado del cliente y
  carga observable con una evidencia específica (API del juego o efecto del addon),
  no solo presencia de carpetas/login. Cerrar de forma comprobada, desactivar desde
  app, relanzar y verificar ausencia de carga; cerrar, reactivar y verificar carga.
- Con juego cerrado, cancelar desinstalación no cambia nada; confirmar retira solo
  el paquete y conserva SavedVariables, Blizzard, otros addons y datos de cliente.
  Verificar gestión local sin red sin alterar ajustes de seguridad del dispositivo.
- Verificar bloqueo con runtime activo y operación IO incompatible sin forzar cierres
  ni interferir en una actualización real. Fallos/interrupciones destructivas y ZIP
  hostiles se prueban en fixtures aisladas, no sobre la instalación real.
- Evidencia fechada identifica build/APK, cliente/runtime, fuente/proyecto/release,
  dispositivo sin identificadores privados en Git, resultados y límites. Capturas,
  logs privados y copias fuera del repo; limpiar únicamente artefactos de prueba.
  No afirmar estabilidad sostenida ni soporte general por esta sesión.

Éxito requiere todo el flujo Hub real y físico anterior, no solo mocks, un enlace
web o un APK compilado. Si falta permiso del proveedor, dispositivo o evidencia
de carga, registrar bloqueo concreto y no cerrar como complete. La clave CurseForge
pendiente no es por sí sola bloqueo de este goal WowUp.

## Implementation / Action Items

- [x] 1. Revisar implementación/evidencia existente y observar catálogo Hub público.
- [ ] 2. Aprobar este plan e impacto de US-010; confirmar condiciones de uso Hub,
  contrato de integridad/dependencias/transporte y conectividad del dispositivo.
- [ ] 3. Actualizar y revisar capas US-010 y definir adaptador/identidad mínimos.
- [ ] 4. Implementar Hub, persistencia compatible y selector de fuente sin tocar
  trabajos ajenos ni descartar CurseForge.
- [ ] 5. Ejecutar pruebas focales y revisión de seguridad; reconstruir/auditar APK.
- [ ] 6. Instalar conservando datos y ejecutar aceptación física con carga real,
  desactivar/reactivar, desinstalar y conservación de preferencias.
- [ ] 7. Corregir hallazgos, documentar evidencia, commits atómicos, auditar criterios
  del goal y archivar este plan solo si todos se cumplen.

## Open Issues

1. Aprobación del plan y del impacto propuesto en US-010 antes de implementación.
2. Uso del servicio Hub por nuestra app: confirmar permiso/condiciones y límites;
   endpoint público y licencia de código no equivalen a permiso del servicio.
3. Completar contrato con asset real del autor: digest/tamaño verificables,
   dependencias y TOC Forever; resolver sin inventar garantías ni abrir proxy general.
4. Conexión y estado actual del dispositivo no comprobados en esta planificación;
   no reutilizar como dato actual la ausencia de conexión de la sesión anterior.
5. Baseline de build compatible con trabajo concurrente: hay modificaciones ajenas
   updater/CASC que no deben mezclarse, sobrescribirse ni quedar implícitas en evidencia.
6. Clave CurseForge solicitada: conservar integración pendiente separada. No aceptar
   términos, desplegar, gastar ni publicar en nombre del usuario como efecto del plan.
