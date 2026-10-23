---
id: dri-return-observer-2026-10-04
state: done
date: 2026-10-04
---

## Summary

La observación de logs autorizada sí alcanzó el error local
`eglCreateContext: dri2_create_context`, pero no revela el enum DRI original.
Ver `docs/guest-egl-local-logs-poco-results.md`. El objetivo sigue siendo WoW en
el POCO conectado, no desarrollar otra pila gráfica. Se propone capturar ese
único retorno con un observador privado, evitando un rebuild de Mesa completo.
La interposición modifica resolución de una función nativa del hijo y requiere
aprobación específica y revisión antes de ejecutar.

## Content

### Evidencia y alternativa

- La pareja actual aún no crea contexto OpenGL; servidor native triangle sí pasó.
- El libEGL Mesa real importa una función dinámica pública
  `driCreateContextAttribs`; el Gallium real la exporta, no es un supuesto textual.
- Una referencia tag26.2.4 define argumentos/errores, pero no prueba por sí sola
  ABI equivalencia de estos paquetes. No ejecutar si no se puede cualificarla.
- El observer puede registrar un error exacto BAD_API/BAD_VERSION/BAD_FLAG u otro;
  no promete identificar automáticamente una corrección viable o lograr un frame.

### Ámbito mínimo

1. Fijar ABI de **esa función concreta**, no el typedef antiguo de ocho argumentos;
   contrastar declaración/llamada actual, los pins de fuentes/ELF conocidos y un
   harness de forwarding con los nueve argumentos, el bool final, error y retorno.
2. Compilar una biblioteca diagnóstica AA64 pequeña, NDK existente, que intercepte
   únicamente esa función dentro del hijo nativo de prueba. Reenviar exactamente
   una vez a la implementación original verificada en el Gallium privado.
   Ninguna modificación de atributos, bits de soporte, retorno ni `*error`;
   conservar errno alrededor de la escritura diagnóstica.
3. Resolución exacta del provider con identidad/owner/pins, prevención de recursión,
   sin rutas/arbitrary symbols proporcionados por el usuario. Si no se puede
   resolver/forwardear con garantías, **no ejecutar** o marcar control inválido;
   nunca simular que el backend rechazó ni devolver un falso contexto.
4. Solo metadata acotada: RUN/PID/UID, API, pares enteros de atributos <=16,
   existencia del retorno y valor del error. No imprimir pointers, structs
   internos, memoria del juego, credenciales o caps completos. Máximo4 eventos
   y <=4KiB stderr adicional. No handlers, ptrace ni modificaciones de memoria.
5. Cargar el observer exclusivamente en el entorno limpio del hijo mediante un
   `LD_PRELOAD` fijo a su biblioteca privada verificada. No entorno del host
   Android, servidor, APK, Wine u otras apps. Mantener los tres logs anteriores y
   la petición única 3.3 core. No sustituir Mesa ni sus archivos originales.
6. Reutilizar capture/UID/RUN/serverPID-startTicks/argv/exe/deadlines/cleanup actuales;
   probar ausencia de intercepción, provider incorrecto, llamada duplicada y
   salida corrupta. Si no hay testigo de la llamada original, TRACE_NOT_OBSERVED,
   no causa inventada ni PASS de contexto.

### Verificación y presupuesto

- Tests host con un proveedor sintético propio: forwarding de todos los parámetros,
  retorno y error invariantes; logger no alterando errno, límites/unknown/races.
- ELF AA64/exact imports/exports/pins, build aislado reproducible, no descarga de
  Mesa/LLVM/Wine/NDK. Artefacto <=64KiB; fuentes versionadas y binarios/output fuera
  del repo según el patrón existente.
- Review independiente del ABI, scope y error handling antes del único trial USB.
- POCO únicamente `DEVICE_SERIAL`, UID10330, sin root/SIM/cambios de permisos.
  Memoria off; ningún scope de private Forever auth autorizado por esta medida.
- Estimación: <=32MiB workspace nuevo y toolchains existentes. Si hace falta un
  rebuild/dependencias mayores, parar y presentar fuente/coste antes de ampliar.
- Después de un retorno demostrado: clasificarlo y decidir corrección acotada o
  descartar esta pareja. No seguir cambiando versiones/flags sin ese dato.

APK41, Wine, DXVK, driver Vulkan, ImageFS, cliente/cache y políticas de quiescencia
se mantienen. No presentación, D3D o WoW aceptados por un éxito diagnóstico.

## Implementation / Action Items

- [x] 1. Probar logs disponibles con los mismos componentes y preservar el resultado.
- [x] 2. Comprobar imports/exports reales y documentar una alternativa pequeña al rebuild.
- [x] 3. Aprobar interposición privada de una única función diagnóstica.
  Usuario: «adelante vamos a ello».
- [x] 4. Cualificar ABI/forwarding/owner y construir/revisar el observer acotado.
- [x] 5. Ejecutar una prueba USB y registrar el retorno DRI con límites explícitos.
- [x] 6. Decidir arreglo pequeño demostrado o cerrar esta combinación sin otra APK ciega.

## Open Issues

- Interposición privada aprobada; no se amplía a APK, Wine, servidor o drivers.
- RTLD_LOCAL/linker Bionic puede impedir la interposición; comprobarla, no asumirla.
- Fuente tag y símbolo público no bastan para probar todos los detalles ABI; si
  no se cualifica, no desplegarlo y presupuestar la instrumentación fuente de Mesa.
- Error BAD_VERSION no explica por sí solo qué feature reduce la versión: no
  forzar número ni prometer compatibilidad por ese retorno.
- **Resultado 2026-10-05:** RUN `VTEST-9cc0884dc09d4f699cfcb8dc0527fabd`, llamada
  original verificada/reenviada una vez, API3, atributos major3/minor3, retorno
  null y error DRI3/BAD_VERSION. Contexto fallido conservado; ambos streams
  completos, sin timeout, cleanup forzado ni procesos restantes. Ver
  `docs/dri-return-observer-poco-results.md` para pins, tests y límites.
  Se cierra únicamente el observer. La pareja no es backend aceptado; no hay
  arreglo pequeño demostrado ni lectura autorizada del struct screen. D3D,
  presentación y WoW siguen pendientes en el plan gráfico principal.
