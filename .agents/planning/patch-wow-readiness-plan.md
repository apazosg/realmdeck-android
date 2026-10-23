---
id: patch-wow-readiness-2026-10-22
state: in-progress
date: 2026-10-22
---

## Summary

El usuario propone acelerar el parcheo detectando cuándo aparece WoW cargado y
empezando a buscar ahí. Conservar C134 como referencia positiva de parche y Login;
no repetir el rastreo de claves ni volver al sondeo indiscriminado anterior a Wine.

Primera candidata propuesta: sustituir el disparador Enter por la aparición de la
ventana propia de WoW con actividad gráfica, y medir los tiempos. Mantener el lector
y escritor nativos C127, el intervalo actual y todas las guardas. La mejora de
velocidad es un objetivo del ensayo, no un resultado demostrado.

El usuario aprueba el 2026-10-22: «perfecto hagámoslo. usemoslo como arranque de
WoW documentalo». Se documenta la señal como **arranque observado de WoW** en
esta variante: ventana propia mapeada con actividad de contenido, no identidad
nativa demostrada, autenticación lista, frame presentado o Login confirmado.

## Content

### Evidencia reutilizada, sin interacción con el dispositivo

- [C134](done/patch-c127-regression-plan.md) aplicó 32 bytes, registró después
  66 ALREADY_PATCHED y consiguió Login confirmado por el usuario. Es la referencia
  recuperable; no se modifican sus datos, gráficos, preferencias ni contrato.
- El JSONL privado ya obtenido del intento `1792698913622` contiene cinco ventanas
  mapeadas. Las tres primeras no coinciden con la clase del ejecutable; la primera
  coincidencia WoW mide 1180×540 y aparece a107284ms de Begin. Una ventana de Wine
  tenía también ese tamaño: **tamaño o superficie XServer por sí solos no bastan**.
- Primer scan completado a142940ms y primer APPLIED a211645ms de Begin: hay
  35656ms entre la ventana WoW observada y el primer resultado, y otros68705ms
  entre ese resultado y aplicación. No hay timestamps de Enter, inicio de poll
  o primer contenido: no atribuir esos intervalos enteros a una causa ni prometer
  que se recuperarán como ahorro.
- Los 41 scans son INCOMPLETE. Cuarenta agotan presupuesto; muchos registran cero
  bytes efectivos de memoria y miles de errores de lectura. La separación mediana
  entre resultados es1493ms. El bucle Kotlin añade nominalmente1000ms después de
  cada iteración; quitar esa pausa a ciegas también puede multiplicar trabajo inútil.
- Fuente: `ForeverFunctionalPocActivity.kt` ya observa `onWindowMapped` y una
  coincidencia de clase; `Window` expone clase, estado mapeado y tamaño. El
  XServer canónico dispone de `onUpdateWindowContent` y cambios de propiedades,
  aunque el callback de contenido aún no se expone a esta Activity. Un update
  señala actividad del drawable, **no demuestra píxeles presentados, pantalla de
  Login lista ni existencia del keystore**.

### Alcance mínimo propuesto

1. Disparador automático por ventana WoW identificada mediante su clase, mapeada,
   con geometría principal compatible con el plan congelado y actividad de contenido.
   No confundir el escritorio, ventanas Wine o ventanas transitorias con WoW cargado.
   Reutilizar eventos XServer; exponer solo el callback mínimo si es necesario.
   No OCR, capturas de pantalla, análisis de píxeles ni cambios del renderer.
2. Asociar observaciones al intento y ventana actuales, admitiendo que propiedad,
   mapeo y contenido lleguen en distinto orden. Un único armado, sin perder eventos
   por carreras y sin que callbacks viejos armen una sesión nueva. Retirar el Enter
   como disparador de búsqueda; no introducir un temporizador que busque a ciegas.
3. Conservar `nativeBegin` **antes de Wine**, identidad/parentesco, opt-in, captura
   independiente, cancelación, cierre exactamente una vez y estados veraces C133.
   Pendiente hasta la señal, buscando al comenzar lecturas, verificado únicamente
   tras APPLIED/ALREADY_PATCHED de apply. La señal visual no autoriza escrituras.
4. Mantener900000ms totales y180000ms activos, estos últimos desde el nuevo armado
   y limitados por el plazo total. No reiniciar ni extender por nuevas ventanas,
   contenido, Enter o reintentos de Login. Si no se identifica WoW, conservar el
   estado pendiente y la terminación acotada, sin fingir que está buscando.
5. Registrar únicamente hitos y duraciones relativos/monotónicos por intento:
   señal aceptada, inicio de búsqueda, duración de los polls y primera aplicación.
   Reutilizar el JSONL acotado; sin títulos crudos, teclas, credenciales, direcciones
   ni datos de memoria. Nada pesado en el callback de interfaz.
6. Una comparación manual con C134, mismo nativo y opciones. No exigir parche
   verificado antes de permitir Login ni automatizar entradas. Separar tiempo de
   carga, espera del disparador, lectura y aplicación. Si no hay aplicación/Login,
   detener el ensayo y decidir cómo recuperar la referencia, sin encadenar cambios.

No cambiar intervalos, presupuestos, orden/regiones de lectura, permisos, ancla,
padding, claves, autoridad o mantenimiento en esta candidata. Una segunda mejora
del lector o de su cadencia requerirá evidencia temporal y aprobación separadas;
no combinarla con el cambio de disparador ni fijar una promesa de segundos sin medir.

## Implementation / Action Items

- [x] 1. Aprobar el disparador y reflejar su impacto en US-004-AC-12/FR-020/TC-027
  y las regresiones temporales; conservar los contratos US-002/US-003. Documentar
  que reemplaza el gate experimental Enter, sin atribuir causalidad única a C128.
- [x] 2. Implementar el detector mínimo y la instrumentación, sin cambios nativos.
- [ ] 3. Probar clasificación/eventos fuera de orden, aislamiento, armado único,
  cancelación/plazos y estados con las pruebas existentes; revisión independiente
  de concurrencia y de ausencia de cambios en autoridad o entrada del usuario.
- [x] 4. Reconstruir, compilar y auditar una candidata auténtica, preservando trabajo
  concurrente y verificando paridad del nativo C127; conservar C134 y sus receipts.
- [x] 5. Coordinar cierre normal, instalar conservando datos, verificar versión/UID/hash
  y realizar un Login manual; registrar tiempos, resultado y límites de la evidencia.

### Resultado local — C135 instalada, Login manual confirmado

- Código `881e501`, candidata135 / `0.2.111-wow-startup`. Detector por clase que
  contiene el nombre del ejecutable (igual que la observación C134), tamaño exacto
  del plan congelado, mapeo y contenido de la misma ventana. Captura del observador
  del guest actual antes de Wine; no se añade otro parámetro al composable XServer
  con límite de registros DEX. Unmap/destroy descartan contenido anterior.
- Se elimina el Enter como disparador sin cambiar el reenvío de teclas. Armado
  vinculado al intento; cancelación cierra el observador. El callback XServer solo
  observa y agenda una tarea única: sin JNI ni IO de archivos en ese callback.
- `wow_start_observed`, `patch_session` (incluido `SEARCH_STARTED`), `patch_scan` y
  `patch_apply` comparten `elapsedSinceWatchStartMs` monotónico. Los scans incluyen
  `pollDurationMs`; primera aplicación verificable identificable sin registrar
  contenido de memoria. No cambia el reloj de los plazos ni la cadencia.
- **48 JVM pasan** (45 UX/sesión y3 detector), sin fallos/errores/skips; build6min52s.
  **34 host focales pasan**:24 reconstrucción,2 receta/disparador y8 auditor.
  No se ha ejecutado ni se declara verde la suite global.
- Snapshot aislado del mismo origen `9e168db` usado para C134, más archivos propios;
  excluye trabajo concurrente de autologin/privacidad. Receipt, manifiesto de hashes,
  build, XML y auditoría externos en
  `/home/adrian/wowforever-artifacts/arm64-prototype/client135-wow-startup-20261022/`.
- Auditoría `PASS_SOURCE_BUILD_AND_PAYLOAD_AUDIT`:2217 fuentes sin cambios y178
  payloads. Tres descriptores DEX nuevos admitidos por nombre exacto (detector,
  reloj y wrapper de poll), con vecinos desconocidos rechazados por las pruebas.
  APK484556071 bytes, SHA-256
  `02ff390df68fa98f30447f8b56790fbb27dd739216b46067b8ff89e360f5c8e1`.
  JNI idéntico a C127, SHA-256
  `2f7014d4133ba17d668e193b5aea0e7290d5a093168c58ace22b83647c69bd67`.
- Inspección local realizada; revisión independiente bloqueada. Tras comunicar el
  límite del revisor alternativo, el usuario autoriza «instalamos y lo pruebo».
  Instalación `install -r` verificada: versión135, UID10330 conservado y hash del
  `base.apk` igual al auditado. Preflight sin guest vivo (app/logcat/audio y zombies),
  sin servicios activos del paquete. Receipt externo; sin borrar datos ni abrir
  WoW desde el agente.
- Ensayo confirmado por el usuario: pendiente en negro, buscando al aparecer WoW;
  Login fallido a unos2min, aplicado unos30s después y siguiente Login correcto.
  Lectura posterior del JSONL del intento `1792701487652` (sin memoria/config/input):
  señal a110161ms, búsqueda a110171ms, primer scan a119579ms (duración9408ms),
  APPLIED32 a253973ms y34 ALREADY_PATCHED;81 scans INCOMPLETE. Resumen saneado
  `successful-login-summary.json` y copia privada externa, no incluidos en Git.
- Señal→búsqueda10ms; búsqueda→aplicación143802ms. Se valida el disparador en este
  intento y se conserva Login correcto, pero no se demuestra ahorro frente a C134.
  No se explica aún la espera ni se encadena una modificación del lector.

## Open Issues

- La revisión independiente está bloqueada por la configuración del trabajador
  (`Variant unavailable for openai2/gpt-6-luna: xhigh`). Se realizó inspección local,
  pero no se presenta como revisión independiente ni se marca completado el paso3.
  Compilación/auditoría y el ensayo manual no sustituyen esa revisión.
- El usuario autoriza usar GPT-6.1 Sol como revisor alternativo. El intento también
  falla, esta vez por límite de uso (`The usage limit has been reached`), antes
  de producir una revisión. Posteriormente se instala C135 por autorización
  explícita del usuario; la revisión sigue sin superarse. C134 se conserva como
  referencia positiva externa recuperable, no como versión instalada actual.
- La clase WoW sí aparece en el intento positivo, pero aún no se sabe cuándo tuvo
  lista la pantalla de autenticación. C135 sí registra la señal de contenido y
  búsqueda coincidente con la aparición comunicada por el usuario; no venderla como prueba de carga
  completa. Si el contrato real de ventana impide identificarla con seguridad,
  detenerse y presentar el dato, no ampliar heurísticas silenciosamente.
- Adelantar el armado también adelanta el fin de los180s activos. No extenderlos
  para ocultar una señal prematura; comprobar que el ensayo conserva aplicación.
- Los errores de lectura/cambios de mapas y el momento de creación del bloque no
  están explicados. Esta candidata no pretende corregirlos ni demuestra que
  reducir el intervalo mejore el tiempo hasta Login.
- La aprobación cubre implementación y ensayo coordinado tras build/auditoría;
  no autoriza interrumpir una partida, introducir credenciales ni rastrear claves.
