# POCO: retorno DRI observado — BAD_VERSION, no contexto aceptado

2026-10-05. Control único aprobado en
`.agents/planning/done/dri-return-observer-plan.md`. El observer reenviaba una
llamada nativa del hijo de diagnóstico; no se instaló en APK41, Wine, servidor
o drivers. No se modifica el contexto solicitado, capacidades, retorno, error
ni errno. La memoria del juego y el login permanecen fuera de esta fase.

## Inputs y verificación previa

- POCO xaga/Mali-G610, serial `DEVICE_SERIAL`, UID10330, `runas_app`.
- Servidor de negociación `e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3`.
- Guest EGL manifest `30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9`,
  tree `1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216`.
- Hijo `GuestEglDriObservation`: 36.736 bytes, SHA
  `7e5d7bb979e0d4a6db0a38625cdf22942b54edc04c6efe7ab5c990bab1e5df76`.
- Observer `libdri_return_observer.so`: 17.880 bytes, SHA
  `d09d4aad3241e074cc142f6b5510bba8c505bfaf010fda77d837a9a259d5a4ef`.
  Export único `driCreateContextAttribs`, AA64 ET_DYN, libc/libdl solamente.
- Fuente observer `2319381505d6c11481408c7bda03042206308698b247d7227ac87eedac64770b`.
- ABI de nueve parámetros contrastado con ELF real y referencia Mesa26.2.4:
  bool final en stack y `num_attribs` como número de **pares**, no DWORDs.
- Ocho tests sintéticos de forwarding/bounds/concurrencia y siete HOST afectados
  comprobados durante las rondas de reparación, cross-build NDK24 y review sin
  P0/P1/P2 pendientes antes del trial. Provider retenido durante la llamada,
  owner exacto, recursión rechazada, máximo cuatro registros acotados.

## Control físico

Evidencia completa fuera del repo:

```text
/home/adrian/wowforever-artifacts/physical-trials/xaga-20261002/native-DRI-VTEST-9cc0884dc09d4f699cfcb8dc0527fabd
```

Servidor PID25836/startTicks20190271; hijo PID25878/UID10330; RUN
`VTEST-9cc0884dc09d4f699cfcb8dc0527fabd`. Namespace, propietario y hashes
comprobados. Servidor exit0, hijo exit1, pipes completos, sin timeout, force-stop,
error de ownership ni procesos propios restantes. El exit1 conserva el contexto
fallido, no se convierte en éxito gráfico.

Registro real de la implementación original:

```text
callSequence=1
api=3
numAttribs=2
attributePairs=[[0,3],[1,3]]
threadSafe=true
providerVerified=true
forwarded=true
resultPresent=false
errorPresent=true
error=3
incomingErrno=9
returnedErrno=9
```

RUN/PID/UID/result/error están vinculados a los diez eventos del hijo y a la
identidad HOST. El provider es el Gallium privado fijado, no el observer mismo.
El resolver conserva su handle hasta terminar forwarding/registro; no se escribió
ningún struct o cap de Mesa. El record indica **DRI BAD_VERSION** para OpenGL core
3.3, antes de que EGL lo agrupe como `EGL_BAD_MATCH`. `DRI_CONTROL_OBSERVED` acepta
esta medición solamente; `actualContextStatus=CONTEXT_FAILED` permanece.

El display vuelve a ser Mesa EGL1.5, una config y bind OpenGL válidos; módulos
privados GLVND/vendor/Gallium/LLVM observados, sin Android EGL/GLES fallback.
Protocolo4, caps2 1408/caps1 308 válidos, GLSL430 y host GLES se conservan. No
contexto current, strings GL guest, draw, presentación, D3D ni WoW aceptados.

## Interpretación y límite

Referencia pública [Mesa26.2.4 dri_util.c](https://raw.githubusercontent.com/chaotic-cx/mesa-mirror/mesa-26.2.4/src/gallium/frontends/dri/dri_util.c):
API3 selecciona core; atributos0/1 son major/minor. `validate_context_version`
devuelve BAD_VERSION si el máximo calculado es cero o inferior al pedido. La
referencia [dri_context.c](https://raw.githubusercontent.com/chaotic-cx/mesa-mirror/mesa-26.2.4/src/gallium/frontends/dri/dri_context.c)
también propaga `ST_CONTEXT_ERROR_BAD_VERSION` desde el state tracker. El enum
observado no distingue automáticamente ambas etapas.

El ELF real muestra el cálculo de versión y los rechazos antes del salto al
constructor (`driCreateContextAttribs` VA0xaa856c, comprobaciones0xaa88b4–0xaa88c4).
Esto corrobora la familia de checks, **no el valor del máximo en vivo ni el branch
ejecutado**. No se leyó el `screen`, ni se infiere un máximo2.1/3.0 a partir de
cuatro requests fallidos. Source tag tampoco prueba equivalencia completa del
paquete Termux. GLSL430 en la respuesta del servidor no garantiza un contexto
desktop4.3 en el cliente.

Decisión: el objetivo de obtener el enum original se ha cumplido. La pareja
actual sigue **no aceptada**. No hay todavía una corrección pequeña demostrada
ni motivo para forzar versiones/features o publicar otra APK equivalente.
Si se amplía, debe buscarse una pareja cliente/servidor Android cualificada o
definirse una instrumentación fuente específica del cálculo de versión con
fuente/coste/gate explícitos. No extender el observer a structs internos sin
una nueva decisión. Los triángulos nativos previos permanecen aceptados solo
en su ámbito, no como prueba de WineD3D o del driver completo.
