---
id: runtime-without-jni-control-2026-10-02
state: draft
date: 2026-10-02
---

## Summary

El usuario aporta la corrida `RUN-1790948680875-8382457650114` de code 30.
La ruta AA64 directa y el catálogo funcionan, pero el host vuelve a terminar por
SIGINT, esta vez 11,830 s después del spawn. Wineboot termina correctamente y
Explorer se crea; no hay identidad WoW validada ni fatal gráfico que lo explique.
La cola de logs está disponible hasta 677 ms antes de la señal. El emisor sigue
desconocido. Evidencia completa en `docs/run-code30-sigint.md`.

El siguiente control propuesto **quita una capa, no añade otro mecanismo nativo**:
ensayar el mismo arranque sin cargar ni llamar al JNI Forever durante esa corrida.
Se conserva una sola app y una sola acción. No equivale a login Forever: es un
control temporal y explícito de arranque, distinto del modo normal de US-003.
Requiere aprobación antes de implementar esa excepción; no se cambia la spec
ready ni se desactiva silenciosamente la autenticación de la ruta normal.

## Content

### Comparación exacta

| Variable | Code 30 / modo normal | Control propuesto |
|---|---|---|
| Wine/DXVK/driver/SHM/evshim/WX | Componentes actuales | Idénticos |
| Binario/datos/catálogo | AA64 verificado, instalación gestionada | Idénticos; reutilizar lo válido |
| Comando/flags/cwd/A/prefijo | Wine/explorer directo y flags canónicos | Idénticos |
| Startup | ESSENTIAL | Idéntico; no mezclar NORMAL ni layout nuevo |
| Configuración | BetaSuspendedTest.wtf y portal canónico | Idénticos; no tocar Config.wtf |
| JNI Forever | Arming UNSUPPORTED y descubrimiento/parcheo condicionado | Ninguna carga ni llamada en esa corrida |
| Ownership/retención/cancelación | Process propio, gate único, logs head/tail | Idénticos y activos |
| Login | No demostrado | Fuera del objetivo del control; no intentarlo |

El cambio no afirma que el JNI provoque SIGINT. En code 30 no escribió ninguna
clave; incluso una prueba positiva solo apoyaría una hipótesis de integración.
Una prueba negativa no demostraría incompatibilidad de PowerVR ni excluiría las
demás diferencias respecto al fork funcional. No se recomienda otra repetición
idéntica de code 30 ni borrar datos.

### Implementación acotada, si se aprueba

- Añadir un modo experimental claramente identificado `CONTROL_SIN_JNI`, opt-in,
  sin alterar el comportamiento normal ni activar fallback automático.
- Congelar el modo en el RUN antes de preparar/lanzar; un RUN no puede cambiar de
  control a normal cuando llega un callback tardío.
- No referenciar/inicializar `ForeverPatchBridge` ni su adapter nativo, no llamar
  `armSignalTrace`, `findOwnedGameProcesses` o `inspectOwnedGame` en control.
- Journal `patchState=DISABLED_FOR_CONTROL`, `signalCoverage=NONE`, render/login
  no verificados. No marcar claves aplicadas ni ofrecerlo como login funcional.
- Registrar únicamente si `libforever_patcher_jni.so` está mapeada en el host al
  iniciar, sin volcar el mapa completo. Si ya está cargada, el control no es válido:
  no se intenta descargar una biblioteca cargada ni se presenta como aislado.
- Mantener verificación real del exe, single-spawn, owned Process/drenaje, IO y
  terminal inmutable. No session/grupo nuevo, servicio foreground, handler global,
  señales ignoradas, kill global o reducción de las validaciones de US-002.
- Mismo package/firma y datos para actualizar; conservar code 30 y ambos informes
  anteriores. No reconstruir runtime, descargar cliente grande o limpiar artefactos.

### Validación

1. Adapter falso: cero cargas/llamadas JNI en control antes/después de spawn,
   cancelación, recreación y callbacks tardíos; el modo normal mantiene llamadas.
2. Assert misma selección PE, comando, flags, runtime/driver/startup/cwd y entorno.
   Un control con JNI ya mapeada queda explícitamente inválido/no ejecutado.
3. Tests de freeze de RUN, doble inicio, errores post-exec, metadata/export y estado
   `DISABLED_FOR_CONTROL`; no reutilizar un resultado normal como si fuera control.
4. Revisión independiente del límite normal/control, checks afectados, receta
   fresca/idempotente, Gradle offline y audit del APK real. Nada de ejecutar Wine
   o señales contra procesos ajenos durante tests.
5. Un intento en foreground tras reiniciar Android por el Wine anterior. Exportar
   aunque muera el host. Comparar creación de Explorer/WoW, vida del host y salida,
   no solo pantalla negra o controles. No solicitar credenciales/login en control.

La política actual de reinicio tras un intento Wine no cambia. Un PGID o ausencia
de JNI no prueba que todo el árbol haya terminado. Si el control repite SIGINT,
no seguir añadiendo guards: el resultado exige acotar el baseline runtime/renderer
o conseguir atribución fiable de la señal, no inventar causalidad.

## Implementation / Action Items

- [x] 1. Inspeccionar el informe y contrastar la ruta directa y cierre con el código.
- [ ] 2. Aprobar el control explícito sin JNI, fuera del objetivo de login.
- [ ] 3. Implementar el modo acotado y tests normales/control sin solapar escritores.
- [ ] 4. Revisar, construir/auditar y publicar candidato con instrucciones claras.
- [ ] 5. Recoger un resultado y decidir por evidencia; no prometer solución del SIGINT.

## Open Issues

- **Aprobación pendiente:** la próxima prueba no aplicaría el parche de clave;
  es un control de arranque, no una sustitución de Forever normal.
- Emisor SIGINT sigue desconocido. JNI es una variable comprobable, no culpable
  demostrado. Un mapa de biblioteca solo prueba carga, no emisor ni ejecución.
- No están demostrados WoW, CASC suficiente, render PowerVR ni CertStore/login.
- No se cambia US-003 ready ni el default de autenticación por este documento.
