---
id: arm64-wine-auth-diagnosis-2026-10-14
state: done
date: 2026-10-14
---

## Summary

**D1 terminado sin resolver autenticación.** Operaciones21:21:22–21:38:57UTC,
1055s/17min35s. Resultado: `docs/arm64-wine-auth-diagnosis.md`. WINEDEBUGrestaurado,
instrumentosOFF, cierre solicitado. Un Enter autorizado con cuenta vigente;
ningún reintento de auth pese a permiso ampliado durante la prueba. Documento
redactado tras cerrar operaciones, sin más experimentos.

Alcance aprobado por el usuario: «di hazlo». Ejecución diagnóstica sin credenciales
antiguas; el Login manual se coordinará si el usuario tiene la cuenta vigente y
la observación está preparada. No interpretar esta aprobación como disponibilidad
de secretos o permiso para un reintento automático.

Cambio de estrategia solicitado por el usuario: detener capturador, búsqueda de
claves en memoria y análisis estático del cliente hasta identificar el fallo real
de conexión. Diagnosticar directamente el cliente ARM64 en su Wine real, localizar
la última etapa positivamente observada y el primer fallo concreto, y proponer
solo la corrección mínima respaldada por esa evidencia. No asumir Ed25519 ni TLS.

Presupuesto inicial: **20 minutos de reloj**, incluyendo preflight, inventario en
el dispositivo, configuración diagnóstica permitida, pruebas y recogida/cleanup.
Empieza al iniciar la ejecución aprobada; no incluye esta preparación del plan
ni una espera de intervención del usuario. Registrar inicio/fin y entregar un
resultado al agotarse, incluso con etapas desconocidas. No alargar el plazo por
reparar herramientas, seguir referencias o intentar conseguir un resultado.

## Content

### Fronteras

- Ninguna continuación del capturador, lector de claves, escaneo de procesos o
  memoria, Ghidra, análisis del PE, offsets o modificaciones de binarios.
- No construir, ensamblar o instalar otro APK, desarrollar probes nuevos ni
  introducir una infraestructura de captura. No repetir los controles anteriores
  de120s que solo llegan al Login. Usar componentes/herramientas ya presentes.
- Mantener verificaciones criptográficas activadas. El consentimiento anterior
  para considerar un bypass no reemplaza la instrucción actual: solo plantearlo
  después de demostrar un fallo de validación concreto y su impacto.
- Btrfs/wowserver: ninguna modificación, permiso, comando de arranque, sesión,
  contenedor, snapshot o servicio. Solo referencia técnica existente en lectura;
  no leer cuentas, credenciales o logs personales de ese proyecto.
- Operaciones propias permitidas tras aprobar: ADB de diagnóstico del dispositivo
  xaga conocido, entorno Wine de la PoC, red a los endpoints de auth que usa el
  cliente, logs acotados y controles sobre esa app. Nunca Wine/juego como root.
  Ningún barrido de hosts/puertos, bypass de permisos o cambio de red del sistema.
- Preparar primero la desactivación explícita de **ambas** preferencias
  `memory_patch_enabled` y `code_capture_enabled` en la UI/perfil propio y
  verificar `false` en el estado de preparación. Omitir flags CLI no basta:
  SharedPreferences conserva el opt-in del último ensayo. Si no se demuestra
  que están desactivadas, no iniciar el cliente.

### Evaluación de capacidades ya realizada (sin pruebas nuevas)

- La PoC funcional usa el Wine/Proton ARM64EC y perfil existentes, PE ARM64
  build70009, portal derivado de `forever/contract.json`.
- `ForeverFunctionalPoc.kt::prepare` establece actualmente
  `WINEDEBUG=+timestamp,+pid,err+module,warn+module`. No es un log completo de
  DNS/TCP/TLS. No hay opción `--wine-debug` en el runner.
- El WTF derivado no controla el entorno Wine. `prepare()` también guarda
  configuración del contenedor propio: no presentarlo como operación sin writes.
- El runner de Login existente exige `--patch-read-only`; **no utilizar esa vía**
  ni activar un escaneo para satisfacerla. No repararlo/ampliarlo en esta fase.
  Un intento manual del usuario puede evitar esa dependencia sin otro APK.
- No se identificó en las recetas del repo un probe de red Windows ARM64 ya
  preparado. Eso no demuestra que no exista una utilidad en la instalación real;
  el preflight comprobará capacidades antes de escoger un comando.
- Los handshakes Linux TLS1.2/1.3 de la ronda anterior solo prueban el host.
  Android `curl`/`openssl` o conectividad de otra app tampoco prueban Schannel ni
  la llamada concreta del cliente. No repetir esos controles como éxito Wine.

### Ejecución por tiempos máximos acumulados

| Tiempo | Acción | Evidencia o parada |
|---|---|---|
| 0–4min | Identificar dispositivo, PoC/runtime/perfil y procesos propios; confirmar ambos diagnósticos de memoria OFF; comprobar utilidades ya instaladas y logs disponibles. | Registrar versión/contexto sin exponer cuentas. Si falta una forma existente de observar transporte, declarar ese límite; no construirla. |
| 4–9min | Comprobar DNS, conexión TCP al portal/puerto y TLS estricto desde una utilidad ya disponible en el contexto Wine real, si la hay. | Identificar la API/implementación y contexto efectivos. Si solo hay herramientas Android/Linux, etiquetarlas como auxiliares y dejar Wine desconocido. Timeout5s por operación, máximo dos conexiones por prueba diferenciada, sin reintentos automáticos. |
| 9–15min | Observar una sola conexión del propio cliente, con logs existentes o diagnóstico estándar que pueda habilitarse reversiblemente sin APK nuevo. Un único envío de Login solo con autorización/cuenta de test actuales. | Correlacionar proceso/tiempos/endpoint y estado de handshake/protocolo. Si no hay cuenta/observabilidad, entregar esa limitación y no afirmar que una pantalla equivale a conexión. |
| 15–18min | Comparación x64 funcional si existe evidencia accesible y comparable sin tocar Btrfs ni iniciar su infraestructura. | Mismo servidor/build aproximado, arquitectura y runtime explícitos. No convertir otra build o un proceso vivo en control de auth. Si no es posible, omitir y explicar. |
| 18–20min | Recoger únicamente evidencia pertinente, restaurar ajustes diagnósticos propios y cerrar el ensayo; escribir resultado y propuesta mínima. | No sobrepasar20min ni iniciar un segundo experimento de reparación en esta fase. |

La configuración de logging no puede alterar el launcher o runtime. Si una
opción se sobrescribe al preparar, no insistir editando archivos a ciegas. Dentro
del preflight se decidirá si existe una vía soportada ya presente; en caso
contrario el resultado es un bloqueo de observabilidad, no un fallo TLS demostrado.

### Logging, privacidad e intento de autenticación

- Preferir errores/estados públicos de Winsock/Schannel/WinHTTP y del cliente,
  con timestamps y procedencia. No `+all`, relay/snoop, volcados de buffers,
  material criptográfico, packet captures generales ni intercepción TLS/MITM.
- Los nombres de canales son candidatos, no garantía de que existan o de que
  WoW use esas APIs. No habilitar trazas de datos sensibles por suposición.
- Logs por corrida propia, máximo2MiB de salida Wine/logcat y256KiB de eventos
  PoC; metadatos y resultados privados0700/0600 fuera del repo. Registrar si se
  truncan. No copiar automáticamente históricos de cuentas a la nueva evidencia.
- No recuperar ni reutilizar credenciales antiguas. Propuesta: el usuario hace
  **un solo Login manual** con una cuenta temporal vigente, con Remember Account
  desmarcado; no entregar secretos en el chat ni capturar campos/buffers de cuenta.
  Sin esa confirmación se limita el ensayo a conexión sin autenticación y se
  declara que la etapa que requiere credenciales no se ha probado.
- La autorización del plan no equivale a autorización para un nuevo Login con
  una cuenta concreta: confirmar quién lo envía y la disponibilidad de la cuenta.

### Tabla de resultados obligatoria

Por etapa registrar `OBSERVADA_OK`, `FALLO_CONCRETO` o `NO_OBSERVABLE/NO_PROBADA`,
con timestamp, herramienta/API, entorno, endpoint y error literal no sensible:

1. DNS: resolución desde Wine o llamada del cliente; Android/host por separado.
2. TCP: conexión establecida y destino real. Un timeout no identifica TLS.
3. TLS: handshake completado/alerta o error de cadena/hostname/protocolo específico.
   Un ClientHello o actividad `fixme` no demuestra handshake completo.
4. Protocolo: evidencia explícita del cliente de petición/respuesta WoW/BGS.
   Datos TLS de aplicación, envío de Enter o paquetes cifrados por sí solos no
   identifican el mensaje ni que el servidor lo haya aceptado.
5. Autenticación/reino/mundo: separar aceptación de cuenta, selección y entrada;
   WOW51900317 aislado no establece causa, ni invalida etapas desconocidas.

La primera etapa sin evidencia no es automáticamente la primera que falla.
Éxito del diagnóstico: aislar con evidencia una frontera de fallo o declarar por
qué no fue observable dentro del presupuesto. Éxito de una futura corrección:
**superar una etapa antes fallida** y después verificar autenticación/mundo; no
arrancar WoW, generar otro APK, pasar tests o borrar un mensaje de error.

## Implementation / Action Items

- [x] 1. Aprobar este diagnóstico limitado. Login manual/cuenta vigente pendientes
  de coordinación; no se piden sus valores ni se reutilizan cuentas históricas.
- [x] 2. Ejecutar preflight y desactivar/verificar los dos instrumentos anteriores.
- [x] 3. Ejecutar pruebas disponibles por etapas dentro del presupuesto, sin
  reparar capturador/herramientas ni cambiar validaciones.
- [x] 4. Evaluar control x64: no hubo uno emparejado accesible sin reabrir su
  infraestructura; no se ejecutó ni se sustituyó por otra build/MsQuic.
- [x] 5. Entregar tabla de etapas, último éxito/primer error observado, límites y
  una sola corrección mínima o la observación que falta; actualizar estado central
  y roadmap marcando suspendidas las vías anteriores, conservando su historial.

## Open Issues

- DNS/TCP Android/UID app positivos, no equivalentes a Wine. Schannel consultó
  metadatos ChaCha20/AEAD: FIXME no demuestra fallo X509 ni endpoint remoto.
- Aserción21:27:55.768UTC anterior al Enter21:35:57.823UTC. Falta causa/impacto;
  no afirmar que es fatal o causa auth ni atribuirla a estas credenciales.
- Proc/net, netlink y metadatos de Errors inaccesibles; sin bypass de permisos.
  Faltan handshake atribuible y respuesta WoW. No corrección funcional justificada.

- Resuelto durante ejecución: usuario aportó cuenta de test y autorizó entrada
  por el asistente; se hizo un envío. Se consumió/eliminó el fichero privado y no
  se documentó contraseña. Primera guarda venció sin escribir; helper posterior
  usó espera visual35s sin cambiar sus comprobaciones/código. No nuevas herramientas.
- La instalación Wine real puede carecer de una utilidad de red o una vía de
  logging soportada sin modificar APK. No emplear20min arreglando ese problema:
  informar el límite y no inventar qué capa falló.
- x64 funcional no está garantizado como control accesible bajo la frontera de
  Btrfs de solo lectura. No reabrir su laboratorio CASC ni exportar bases.
- La preparación/consulta de fuentes anterior a este documento no constituye
  ejecución del diagnóstico ni un nuevo resultado de auth. Alcance ya aprobado.
