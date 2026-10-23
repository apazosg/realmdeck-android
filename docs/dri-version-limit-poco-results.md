# POCO: el máximo core calculado por esta pareja Mesa/VirGL es cero

2026-10-06. Último control acotado aprobado antes de priorizar PanVK. No cambia
APK41, Wine, DXVK, el driver, servidor, ImageFS, datos, capacidades ni memoria del
juego. La lectura se realiza únicamente dentro del hijo diagnóstico propio.

## Autoridad de la medida

- Gallium privado 26.2.4: 17.656.040 bytes, SHA
  `910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5`.
- Símbolo real `driCreateContextAttribs`, nueve argumentos; ELF, hash, tamaño del
  símbolo y 22 instrucciones del check core verificados antes de usar offset64.
- Solo cuatro bytes antes y cuatro después, vía `process_vm_readv` al PID propio;
  sin desreferencia ciega, writes, ptrace, handlers o lecturas adicionales.
- Child `GuestEglDriVersion`: 36.736 bytes, SHA
  `f23e616bfc20fe63249c8ac47004587bf83bddc81ed0f257edb859e405ab8a37`.
- Observer `libdri_version_observer.so`: 25.456 bytes, SHA
  `46d7aaa9be73557bb9129b72a4b3b5ea38cafdd2feda428c999b114aafc222cb`.
- 14 tests nativos/harness y ocho HOST dirigidos pasan; review sin P0/P1 pendiente
  antes de la única ejecución. No se repiten GPU gates ni suites no afectadas.

## Resultado físico

Carpeta fuera del repo:
`physical-trials/xaga-20261002/native-DRI-VERSION-VTEST-9c2fe8e63b974ecdb126313c8a0d7b6c`.
RUN `VTEST-9c2fe8e63b974ecdb126313c8a0d7b6c`, child PID4671/UID10330;
servidor PID4647/startTicks29002043, identidad y ámbito revalidados.

El único registro ligado al child aceptado contiene:

```text
api=3, attributePairs=[[0,3],[1,3]], requestedVersion=33
providerVerified=true, layoutVerified=true, scopeMatches=true
beforeReadBytes=4, afterReadBytes=4, beforeReadErrno=0, afterReadErrno=0
maximumBefore=0, maximumAfter=0, measurementValid=true
forwarded=true, resultPresent=false, error=3 (BAD_VERSION)
incomingErrno=9, returnedErrno=9
checkInference=EARLY_CHECK_WOULD_REJECT
```

La llamada original se reenvía una vez. Display/config/bind OpenGL son válidos;
`eglCreateContext` permanece en `EGL_BAD_MATCH` y no hay contexto current ni
strings GL. Continúan protocolo4 y capsets válidos1408/308; GLSL430 no invalida
la medida del máximo core. Server exit0/child exit1, pipes completos y no truncados,
sin timeout/force-stop/ownership error ni procesos propios restantes.

## Interpretación y decisión de parada

El check real de este binario rechaza máximo cero. El valor estable medido basta
para explicar que la petición core3.3 no supera ese check. Es una inferencia
derivada de bytes y datos verificados, **no una traza del PC ejecutado** ni prueba
de qué requisito produjo cero. No es el máximo físico universal del Mali, ni
equivale a «OpenGL máximo 0» del sistema Android: el EGL/GLES nativo y el triángulo
del servidor ya pasaron gates separados.

No se demuestra una corrección pequeña. Se cierra este intento de la pareja
guest Mesa/VirGL actual sin forzar versiones, modificar el campo, fingir features,
reconstruir Mesa por inercia o generar otra APK equivalente. Los controles y
artefactos quedan preservados; no se retiran los resultados nativos positivos.

El control estrecho se completa en
`.agents/planning/done/dri-version-limit-plan.md`; el proyecto y soporte gráfico
siguen pendientes. **PanVK queda como vía prioritaria siguiente**, con su propia
cualificación de kernel/ICD privado, no como driver ya aceptado en nuestro POCO.
No hay presentación, D3D, frame WoW, gameplay o login demostrados por esta medida.
