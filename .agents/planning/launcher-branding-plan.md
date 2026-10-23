---
id: launcher-branding-2026-10-19
state: in-progress
date: 2026-10-19
---

## Summary

El usuario pide aplicar también el emblema aprobado al icono Android y al arranque
de la app: «siz hazlo». La interfaz C115 ya está aprobada y su descarga reanudada;
esta continuación no reabre ajustes, runtime ni motor del descargador.

## Content

- Reutilizar el escudo y rombo originales de `ForeverFunctionalIcons.Emblem`,
  dorado sobre azul oscuro, sin imágenes ajenas, dependencias ni texto diminuto.
- Icono adaptativo Android, comprobado con máscaras circular y redondeada y con
  margen de seguridad. Variante monocroma para los iconos temáticos del sistema.
  Mantener nombre de app, paquete, aliases y rutas existentes; ambos aliases
  actuales deben mostrar la marca Forever y no el icono upstream.
- Arranque nativo Android con el mismo emblema/fondo, sin actividad intermedia,
  temporizadores, animaciones nuevas ni demoras artificiales. Conservar la
  configuración de barras/edge-to-edge y el primer frame de la interfaz.
- Evidencia inicial: el manifiesto instalado aún usa `${icon}` y dos aliases con
  `@mipmap/ic_launcher`/`ic_launcher_alt`; la actividad funcional no tiene un
  splash propio. La variante modern requiere Android29 y existen temas base y
  API35: evitar perder sus ajustes al añadir recursos de arranque API31+.
- Cambios previstos: recursos XML propios bajo `scripts/arm64-prototype/app/`,
  staging/transformación explícita del manifiesto en la receta canónica, pruebas
  focales y documentación. Candidata C116/`0.2.92-app-branding`.
- Mantener baseline62, `code59-source-baseline.json`, `apply_overlay.py`, hashes y
  payloads de runtime/cliente intactos. Admitir solo recursos concretos propios;
  no ampliar allowlists mediante directorios o patrones genéricos.
- La instalación reemplaza el proceso: conservar el protocolo ya autorizado de
  pausa cooperativa, comprobar ausencia de foreground/fetch workers, verificar
  checkpoint/recibo/preferencias byte-idénticos tras `install -r` y reanudar la
  misma70450 ARM64/enUS en USB6AD6-EF0E. Sin clear-data, reset ni repair. No superar
  el último consentimiento31,6GiB de descarga/33,5GiB adicionales.

## Implementation / Action Items

- [x] 1. Añadir recursos de icono adaptativo/monocromo y arranque; registrar origen
  y rutas exactas en la reconstrucción canónica sin tocar lógica de la app.
- [x] 2. Verificar márgenes/máscaras con previsualizaciones, referencias de
  manifiesto/aliases/temas con pruebas focales y compilar/auditar la APK canónica.
  No repetir suite global/JVM de lógica si esta no cambia.
- [ ] 3. Actualizar preservando datos, comprobar icono/arranque en el dispositivo
  y que Inicio/Ajustes aprobados no cambian. No detener una descarga activa para
  obtener un cold start: hacerlo dentro de la pausa controlada de actualización.
- [ ] 4. Reanudar la misma transferencia y probar nuevos commits durables,
  foreground y regreso del gestor sin pausa. Dejar descarga ejecutándose.
- [ ] 5. Actualizar estado del launcher, handoff y README con evidencias, límites
  y resultado visual; cerrar este plan en `done/` al completar la aceptación.

## Open Issues

**Bloqueo de reanudación (prioritario):** C116 no llegó a la pantalla de costes
ni a confirmar la descarga. Logcat propio prueba que `plan()` rechaza
`recover the unfinished client transaction before planning or updating`.
La frase UI «Falló la recuperación…» no demuestra una recuperación fallida: en
este flujo no se llamó a `recover()`. Los tres ficheros durables siguen con los
mismos hashes; FGS0, PID4811. No reintentar ni borrar estado para eludir la puerta.
Se inspeccionan solo metadatos/recibo/journal/staging para identificar qué rama
detecta pendiente. `recover()` puede marcar/resetear CASC en determinadas rutas:
no ejecutarlo sin probar sus consecuencias y aprobar cualquier cambio de alcance.

Causa ya identificada mediante lectura: rotó el CDN-config conservando versión y
build-config, se publicó otro catálogo y cambió la clave de workspace. Journal
limpio, staging/owner correctos; el recibo original existe bajo la identidad
anterior. No intentar limpiar esa descarga como huérfana.
Propuesta de reparación separada, pendiente de aprobación:
[conservar catálogo de reanudación](client-resume-catalog-pin-plan.md).

Confirmación recibida el2026-10-20 («ai», en respuesta a «¿Apruebas el plan para
continuar?», entendida como «sí» por el contexto). El alcance visual
está decidido; si la inspección descubre un cambio de contrato, recursos sin
procedencia verificable o costes superiores, parar y comunicarlo. La variante
monocroma depende del tema elegido por el usuario: no cambiar ajustes del sistema.
La descarga completa, integridad final y arranque/login del juego siguen fuera
del alcance; la suite global histórica no está verde y no se oculta ese límite.

**Hallazgo físico C116:** el arranque nativo muestra el escudo correcto, pero
Información de aplicación de Android sigue mostrando el icono upstream. No se
localizó Forever en la página visible del escritorio, por lo que esa ficha no
demuestra qué renderiza el launcher. La causa aún no está probada: no afirmar
«caché MIUI» sin distinguir el icono servido por PackageManager del mostrado por
la ficha. Diagnóstico solo lectura, sin limpiar cachés/datos, reiniciar launcher,
cambiar tema o mover accesos. La descarga se reanuda independientemente de esta
comprobación; no mantenerla pausada por el icono. Plan abierto, no marca aceptada.

### Evidencia de ejecución

- Nueve recursos XML admitidos por nombre/ruta exactos y hashes en el recibo;
  icono adaptativo API26+, monocromo API33+, arranque nativo API31+ y capa de
  ventana inicial con emblema para API29–30. Sin nueva actividad ni dependencia.
- Pruebas focales:7 de branding pasan (repetidas tras completar el fallback
  legado y aislar el caso de recurso inesperado en un directorio temporal);
 39 de receta/auditoría/smoke pasan. `py_compile` pasa. No suite global/JVM repetida.
- Revisión propia de `icon-masks.png`: circular/redondeado/monocromo legibles,
  sin recorte de trazos; imagen generada desde los XML reales y no presentada
  como captura del dispositivo. `cmp` confirma la Activity idéntica a C115.
- Artefactos de esta continuación fuera del repo:
  `/home/adrian/wowforever-artifacts/work/launcher-branding-20261020/`.
  Estado inicial antes de actuar: compilación/auditoría iniciada, sin pausa ni
  instalación todavía. Resultados posteriores, abajo.
- ADB reconectado por mDNS a192.168.1.130:35279: instaladaC115, PID20462 y servicio
  de descarga en foreground en la comprobación inicial (solo lectura).
- Revisión independiente de procedencia/manifiesto: sin defectos bloqueantes ni
  cambios de permisos/exportación/payloads. Comparación estructural propia entre
  manifiestos C115/C116: exactamente cinco atributos de marca cambian (tres iconos,
  roundIcon y tema de la actividad funcional); todas las entradas restantes
  conservadas. Evidencia `manifest-source-comparison.json`.
- API real del dispositivo34, densidad440,1080×2460 y launcher
  `com.mi.android.globallauncher/com.miui.home.launcher.Launcher`. El enlace ADB
  volvió a perderse en el host y se reconectó sin actuar sobre la descarga.
- Compilación y auditoría canónicas pasan: C116/`0.2.92-app-branding`,
  2196fuentes/178payloads, APK483995323bytes,
  SHA256`3267df6195e5a412a34a968d78c4da2c9a05779b45fe2960149cbc33330842b3`.
  Árbol inmutable en
  `/home/adrian/wowforever-artifacts/arm64-prototype/client-manager-code116-app-branding/`.
- Inspección propia de `aapt`: icono de aplicación/roundIcon/ambos aliases resuelven
  al mismo recurso compilado; tema de la actividad funcional al estilo propio.
  Capas API26/33, monocromo y tema API31 con fondo/emblema correctos, padre Pluvia
  y configuración API35 conservados. `compiled-binding-review.json` recoge IDs y
  volcados. No se confunde esta comprobación con prueba física de otros SDK.
- Instalada C116 tras una pausa cooperativa:938819objetos/74581621578bytes encoded,
  cola parcial0, objeto cifrado retenido, FGSfalse/fetch0 antes de instalar.
  Checkpoint/recibo/preferencias byte-idénticos antes/después. Verificación propia
  de `c116-installed-preserved.json`: versión116 y SHA de APK coincidente.
- Capturas reales inspeccionadas: `c116-splash-best.png` muestra arranque nativo
  dorado sobre azul oscuro, sin segunda pantalla artificial. `c116-settings.png`
  conserva las columnas y cinco botones de748×176px separados33px. La Activity
  aprobada sigue idéntica. No cambiar cachés ni estilo global para la captura.
- `c116-app-icon-crop.png` confirma icono upstream en la ficha de Android; prueba
  de escritorio pendiente, causa en diagnóstico (ver Open Issues). La grabación
  comienza antes de abrir la app y contiene una pantalla Android previa: solo se
  usa el frame propio de arranque, no se presenta el escritorio como propio.
- Reanudación C116 encargada una sola vez, mismos70450/USB y techo31,6/33,5GiB.
  Bloqueada antes de costes/consentimiento. Metadatos consultados hasta95,2MiB,
  disponible70450/destinoUSB sin cambios. No se confirmó, reseteó ni reparó.
- `c116-resume-blocker.json` confirma checkpoint/recibo/preferencias intactos,
  FGS0/PID4811; `c116-recovery-logcat.txt` contiene la excepción real de planificación.
  Sin prueba nueva de commits ni foreground. Investigación solo lectura activa;
  el plan sigue abierto y no se afirma reanudación ni icono de escritorio válido.
