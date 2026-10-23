# Diseño del launcher (Fase 5) — DIRECCIÓN HISTÓRICA preliminar

> **Estado: dirección histórica preliminar (origen: bloqueo Fase 2 «el cliente aún
> no arranca en el móvil»).** No es la implementación actual: el arranque gráfico
> posterior (PoC) no implica mundo/auth/go. Estado actual:
> [downloader-handoff](downloader-handoff.md) y
> [launcher-app-status](launcher-app-status.md#corte-actual-descargador-aparcado-por-el-usuario).
> Este documento fija el enfoque y las decisiones ya justificadas por la
> experiencia de las fases anteriores; no es una implementación. Los principios
> D-L se conservan; las hipótesis no probadas se rotulan como tales.

## Objetivo

Un APK que haga transparente todo el procedimiento que hoy hacemos a mano:
instalar el cliente, aplicar Forever (portal + parche de la clave del grupo 8) y
lanzar `WowB.exe`, sin que el usuario vea Wine, FEX/Box64, DXVK, CASC ni scripts.

Flujo de primera ejecución:

```
Instalar APK → descargar runtime → descargar cliente → verificar
→ configurar Wine → configurar Forever → JUGAR
```

## Decisiones ya justificadas

### D-L1 — El cliente no se empaqueta en el APK
Pesa **100,24 GiB** (medido, `docs/device-poco-m7-pro-5g.md`). Se descarga en el
dispositivo. El APK queda ligero y el cliente se actualiza sin reinstalar.

### D-L2 — Descarga progresiva, no los 100 GiB de golpe (hipótesis histórica, no capacidad probada)
La prioridad 0 del download manifest son ~0,44 GiB. El cliente de este build trae
`build-partial-priority` y en su `.build.info` la configuración de CDN, así que
**podría** streamear el resto bajo demanda. Hipótesis a evaluar: el launcher instalaría
el esqueleto primero y dejaría que el cliente complete por sí mismo, mostrando progreso
real. **No está probado que sea una instalación jugable ARM64; C112 exige catálogo
completo validado antes de activar. `install --loose-only` extrae el subset loose del
install manifest (bootstrap), no un cliente completo jugable ni CASC íntegro.**

### D-L3 — El launcher debe ser quien arranca el juego (conclusión clave)
Upstream parchea la memoria del proceso desde fuera (ptrace/`/proc/pid/mem`). En
Android sin root eso no es viable en general… **salvo si el proceso es nuestro**:
si el launcher lanza `WowB.exe`, el proceso Wine es hijo de nuestra app, corre
con **nuestro mismo UID** y podemos leer/escribir su memoria de forma nativa
(`/proc/<pid>/mem`, `process_vm_writev`) **sin root**.

Esto invierte el diseño: **el parche Forever no es un paso aparte, es parte del
lanzamiento**. No hay que "buscar el proceso"; lo tenemos.

### D-L4 — El contenedor se puede provisionar sin UI
Verificado en `docs/winlator-provisioning.md`: la app del runtime es *debuggable*,
su `run-as` permite escribir el `.container` (JSON), el prefijo Wine y los
accesos directos `*.desktop`. Un launcher basado en un fork del runtime puede
crear y configurar el contenedor sin que el usuario toque nada.

### D-L5 — La captura de logs es requisito de primera clase
Toda la Fase 2 se ha atascado por falta de observabilidad (Wine mudo, sin trazas).
El launcher debe **redirigir y persistir** la salida del runtime desde el primer
día, y distinguir **proceso vivo** de **frames presentados** (lección de Pocket
Realm): "arrancó" no es "funciona".

### D-L6 — Runtime como componente, no como caja negra
El runtime (Wine + traducción + DXVK) se instala/actualiza como componente
versionado y sustituible, para poder cambiar FEX↔Box64 o de versión de Wine sin
recompilar la app entera.

### D-L7 — El driver gráfico se **detecta**, no se fija (no es un producto "PowerVR")
El dispositivo de pruebas es **un** caso, no el objetivo. Habrá móviles con Adreno,
Mali o PowerVR, y cada uno necesita una ruta distinta. La app debe detectar el
renderer y elegir, **con override manual** siempre disponible:

| GPU | Ruta preferente | Alternativas | Madurez |
|---|---|---|---|
| **Adreno** (Qualcomm) | **Turnip** (Adreno-only, vía Adrenotools) | Vulkan del sistema, Vortek | Camino más rodado de la comunidad |
| **Mali** (ARM) | Vulkan del sistema / Vortek | VirGL (+WineD3D) | Conocido, variable |
| **PowerVR** (Imagination) | Vulkan del sistema / Vortek | VirGL (+WineD3D) | Sin driver alternativo: el más frágil |
| Genérico | Vulkan del sistema | VirGL | — |

Consecuencias de diseño:
- La detección (`GPUInformation`/`vkjson`) y la selección de driver son **lógica del
  launcher**, no configuración manual del usuario.
- Un fallo de arranque en PowerVR **no invalida el producto**: hay que comprobarlo
  también en Adreno antes de concluir que el cliente no es viable.
- La matriz entra en el diagnóstico: conviene registrar siempre GPU, driver elegido y
  versión del traductor en cada informe de error.

### D-L8 — El cliente puede llegar ya descargado en el USB y se reutiliza (no se vuelve a bajar)

Decisión (2026-10-20, plan `existing-install-import-plan`, spec US-009). El
usuario puede tener la instalación del cliente ARM64 en un USB. La app la
**revisa** y la **reutiliza**: los objetos CASC son direccionables por
contenido (EKey), así que los que coincidan con el catálogo destino se
verifican y se reutilizan in situ; solo se descarga el delta. Medido el
2026-10-20: comparar los planes completos ARM64 70009 y 70450 deja
**~1,9 GiB** de objetos nuevos frente a ~85 GiB del destino. No es una
verificación de una copia física; excluye archivos sueltos y metadatos. La app
debe calcular y mostrar el coste de la instalación real antes del consentimiento.

Restricciones que la decisión impone, y que no son negociables:

| Restricción | Por qué |
|---|---|
| El destino de pegado es la **raíz del cliente en el volumen extraíble** (`Android/data/<paquete>/files/CustomGames/WoW Forever/`), la misma que la app ya usa | Es una ruta app-specific legible sin permisos amplios y sin copiar; Wine la mapea a `A:` |
| **Nada de `MANAGE_EXTERNAL_STORAGE`** | La auditoría del APK lo prohíbe; el modelo de almacenamiento es app-specific por decisión (US-007) |
| **Nada de SAF como ruta ejecutable ni de copia automática** | SAF obligaría a copiar decenas de GB; US-007-FR-001/FR-004 lo prohíben |
| Se pega **solo cliente ARM64**; un x86_64 se rechaza con mensaje | La app ejecuta el ARM64; el solapamiento de datos no compensa y el ejecutable siempre hay que bajarlo |
| Lo pegado se **revisa**, no se adopta a ciegas | La revisión local no acredita integridad: cada objeto reutilizado se revalida durante el plan/actualización |
| Se pegan `Data/` y `.build.info`, no el estado interno de la app | El marcador pertenece al volumen; los archivos sueltos ajenos que colisionen con el destino pueden bloquear la actualización |

Consecuencia operativa: el USB que la descarga en curso está llenando se
convierte, cuando termina, en una fuente para otro dispositivo: copiar solo
`Data/` y `.build.info`, nunca `.forever-client-storage`, `.forever-client-update`
ni `.forever-update-staging`. Las instrucciones quedan fuera de la raíz del
cliente. No se garantiza descarga cero ni compatibilidad de login por coincidir
el build. Integración offline compilada, con pruebas y auditoría aprobadas;
despliegue y prueba física pendientes.

## Riesgos abiertos

| Riesgo | Impacto | Estado |
|---|---|---|
| **Compatibilidad por GPU** (Adreno/Mali/PowerVR) | Cada GPU necesita su ruta de driver; PowerVR no tiene Turnip | Abierto (Fase 2); mitiga el diseño D-L7 |
| Cliente no arranca en el dispositivo de pruebas | Bloquea la validación local, **no** el producto | Abierto (Fase 2) |
| Licencias al distribuir | Wine (LGPL), Mesa, Box64 (MIT), DXVK (zlib), forks (MIT/GPL) | Revisar antes de publicar; hoy es proyecto interno |
| Almacenamiento | 100 GiB de cliente + runtime en un móvil | Hipótesis histórica: streaming (D-L2), **no probado** como instalación jugable ARM64; C112 exige catálogo completo validado antes de activar |
| Android 16 / HyperOS | Restricciones de `mmap`, depuración inalámbrica, permisos | Vigilar |

## No decidido todavía (bloque histórico de preguntas abiertas; el componente Rust/JNI actual es referencia, no elección cerrada de método ARM64)

- **Lenguaje del patcher (pregunta histórica):** en su momento se barajaban Rust o
  C++ (nativo, dentro del proceso de la app); Python no es una opción de producto.
  Hoy existe como referencia el núcleo Rust (`crates/forever-patcher`, sin `unsafe`)
  y el puente JNI (`crates/forever-patcher-jni`); ver [bitácora Fase 3](fase-3-patcher.md).
  Eso no afirma que el método de auth ARM64 esté elegido ni probado en ARM64.
- **Reutilizar un fork de Winlator** como base del APK o integrar el runtime
  nosotros (decide la Fase 6).
- **Presets de rendimiento** y perfiles por dispositivo.

## Lista histórica de preparación offline (no próximos pasos vigentes)

1. Runner de diagnóstico y sondas (`scripts/diagnostics/`) — **hecho**.
2. Contrato Forever y su verificación de deriva — **hecho** (`forever/contract.json`,
   `scripts/contract-drift-check`).
3. Provisioning del cliente (`forever-client`) — **hecho** en su parte loose;
   pendiente cubrir la verificación de los datos CASC.
4. Prototipo del patcher Forever con pruebas — pendiente en el corte original;
   hoy existe el núcleo Rust + JNI de referencia citado arriba, validado en
   emulador/hijo sintético. No acredita el método de compatibilidad del juego
   ARM64 real ni prescribe reabrir ese diagnóstico.
