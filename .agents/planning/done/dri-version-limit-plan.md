---
id: dri-version-limit-2026-10-06
state: done
date: 2026-10-06
---

## Summary

El usuario acepta el último control numérico de Mesa/VirGL y pide no descartar
PanVK. Mantener dos decisiones separadas: cerrar la ambigüedad BAD_VERSION de la
pareja actual; investigar PanVK como alternativa Vulkan prioritaria, no instalar
un driver simultáneamente. Solo el POCO xaga `DEVICE_SERIAL` entra en pruebas.

El observer anterior autorizaba metadata/atributos/retorno, **no lectura del
struct screen**. Este control necesita una ampliación explícita: leer únicamente
el campo de máximo core que utiliza el binario real, dentro del hijo nativo
diagnóstico y sin escribir memoria. No se implementa antes de aprobar este plan.

## Content

### Evidencia y pregunta

- Original `driCreateContextAttribs` verificado: API3, pares major3/minor3,
  forwarding único, null/error3 BAD_VERSION, errno conservado. Resultado completo
  en `docs/dri-return-observer-poco-results.md` y plan anterior en `done/`.
- Gallium privado: SHA
  `910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5`.
  Su código AA64 en `driCreateContextAttribs` VA `0xaa856c` selecciona offset64
  para la petición core y, en `0xaa88b4–0xaa88c4`, lee un uint32 y rechaza máximo
  cero o petición superior antes de saltar al constructor en `0xaa88e0`.
- Ese disassembly no mide el máximo en vivo. El constructor posterior también
  puede devolver BAD_VERSION. No asumir límite 2.1, reinterpretar GLSL430 como
  versión GL ni decir que el GPU completo es incompatible.
- Pregunta única: ¿cuál es el máximo calculado que recibe esta llamada y es
  compatible el resultado con el check temprano, o queda después de ese check?

### Ampliación mínima autorizable

1. Nueva biblioteca/artefactos diagnósticos, con fuentes/pins distintos y observer
   anterior preservado. Mismo provider Gallium verificado y mismo backend/RUN,
   servidor, guest, atributos 3.3 core, UID10330 y entorno limpio del hijo.
2. Autoridad del campo derivada del **ELF exacto** y de la secuencia de instrucciones
   de esa función. Fijar bytes/offset y corroborar API/atributos antes de usarlo;
   no aplicar el offset a otros builds/APIs/structs. Si cambia el hash, no ejecutar.
3. Leer solamente **4 bytes del campo core antes y 4 después** de la llamada. Sin
   dump del struct, pointers en salida, caps completos, otras direcciones/PIDs,
   memoria del juego ni writes. No leer versiones compat/ES adicionales.
4. Utilizar una lectura propia que falle sin desreferenciar ciegamente un pointer
   ilegible, con identidad/ámbito del proceso fijados. Cualificarla primero en hijos
   sintéticos propios: error/lectura parcial/seccomp o syscall no soportado =>
   UNKNOWN/control inválido; no handlers, ptrace, root o permisos nuevos para
   rescatarla. No exponer PID/dirección/offset por CLI.
5. Reenviar exactamente una vez los nueve argumentos a la implementación original;
   conservar parámetros, error, retorno, errno, provider retenido y guard de
   recursión. Lecturas/logs no pueden modificar la decisión de Mesa.
6. Campo estable antes/después y rango razonable => registrar máximo/petición y
   clasificación **derivada del check del binario**. Diferenciar inferencia de
   rama de un PC ejecutado observado. Cambio del campo, lectura incompleta o
   datos contradictorios => no atribuir etapa. No etiquetar una conjetura como
   causa exclusiva ni dar por probado qué feature redujo ese máximo.

Mismos límites: <=4 eventos / <=4KiB metadata, observer/hijo <=64KiB, workspace
<=32MiB, NDK24 existente, sin nuevas descargas. Mismo runner privado con pins,
capture antes de ejecución, stdin cerrado, pipes/deadlines acotados, PID/startTicks
del servidor y limpieza del paquete propio solo ante error/timeout. No APK nueva,
Wine, driver, capacidades, grupos/sesiones, handler SIGINT, cuentas ni datos.
El observer se carga únicamente en el hijo de prueba, no en host Android/servidor.

### Criterio de parada

- Máximo nulo/inferior: se demuestra un límite calculado de **esta pareja**, no
  una incapacidad física general. Continuar solo si aparece una carencia concreta
  corregible; no forzar número/flags/features.
- Máximo suficiente + BAD_VERSION: el rechazo no queda explicado por el check
  temprano. No ampliar este control a nuevas funciones/structs automáticamente.
- Si la medición no puede cualificarse o no produce una corrección pequeña:
  cerrar este intento Mesa/VirGL y priorizar PanVK, sin otra APK equivalente.
- Contexto, presentación, D3D, WoW y login siguen sin aceptar por un dato diagnóstico.

### PanVK en paralelo, sin descartarlo

La revisión fijada `zenithblue@de577411e8f27da9de50aec26a0d8cc43890745e`
añade carga/enumeración publicada en G610/kernel5.10, aunque el intento DXVK se
detuvo antes de device/workload por BC. Upstream G610 es conforme y el port Android
requiere su propia cualificación. Ver `docs/panvk-mali-findings.md` actualizado.

Durante este control: solo investigación texto/metadatos de GPU-ID/UAPI/CSF y
compatibilidad privada, no descarga ni ejecución de ICD, apertura/ioctls GPU o
HAL global. El siguiente ensayo PanVK deberá fijar payload/hash/ABI, gate kernel,
presupuesto y límites antes de probar instance/device/workload. El permiso de
seguir investigando no convierte una build multi-arch/G615 en aceptación xaga.

### Verificación

- Harness sintético propio: campo0/inferior/igual/superior, pointer ilegible,
  overflow, API/attrs/build no coincidente, cambio del campo, lectura parcial,
  forwarding nueve argumentos/error/errno/retorno invariantes y límites/races.
- ABI/hash/instrucciones/ELF/pins, revisión independiente antes de ejecutar;
  clasificador HOST rechaza metadata sin testigo nativo íntegro o contradictoria.
- Una sola prueba USB con los componentes actuales; conservar raw y snapshot
  anterior, native/context exit real, sin borrar/reclasificar fracasos como PASS.
- No suites GPU exitosas repetidas ni cambios gráficos mezclados con la medida.

## Implementation / Action Items

- [x] 1. Releer retorno original y contrastar el check real del ELF.
- [x] 2. Revisar la evolución PanVK sin descartarlo por falta de validación previa.
- [x] 3. Aprobar la ampliación de lectura de este único campo propio.
  Usuario: «si hagámoslo». Se conserva el observer de retorno anterior.
- [x] 4. Implementar/cualificar/revisar artefactos y clasificador acotados.
  14 tests nativos/harness + ocho HOST dirigidos, pins/ELF/ABI y review cualificados.
- [x] 5. Ejecutar una observación USB y decidir cierre/arreglo pequeño por evidencia.
  RUN `VTEST-9c2fe8e63b974ecdb126313c8a0d7b6c`: lectura4+4 completa, máximo0 estable,
  petición33, retorno original null/BAD_VERSION y errno conservado. Check temprano
  derivado rechazaría; no se observa PC ni feature limitante. Sin corrección pequeña,
  se cierra esta pareja y se prioriza PanVK; no se acepta un backend gráfico.

## Open Issues

- Ampliación específica aprobada; no se amplía a otras funciones, structs, APIs o
  escrituras. PanVK sigue como alternativa prioritaria, no descartada.
- Lectura cualificada solo para este ELF/API/contexto propio; no reutilizar el
  offset sobre otro provider/build. Resultado en `docs/dri-version-limit-poco-results.md`.
- El campo cero no identifica el requisito que redujo el máximo. No ampliar este
  control a otros structs ni modificarlo para eludir la decisión de Mesa.
- PanVK es prioritario, pero el UAPI/GPU-ID xaga y trabajo real del port siguen
  desconocidos. `/sys/.../gpuinfo` fue denegado como shell; no elevar permisos.
