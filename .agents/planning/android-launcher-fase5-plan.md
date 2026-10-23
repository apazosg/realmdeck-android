---
id: android-launcher-fase5-2026-09-28
state: in-progress
date: 2026-09-28
---

## Summary

Arrancar el proyecto Android del launcher (Fase 5) de forma que sirva, primero,
para **retirar el mayor riesgo de producto**: comprobar en **Android real** (el
emulador) que se puede parchear la memoria de un proceso hijo propio **sin root**
(decisión D-L3). El resto del launcher se construye encima de ese esqueleto.

Sin móvil físico disponible: el emulador x86_64 sirve para esto (el mecanismo de
memoria no depende de la arquitectura), pero **no** para el runtime del juego
(Winlator/FrontMage son arm64-only) ni para validar GPUs reales.

## Content

### Contexto y decisiones ya tomadas
- Fase 5 documentada en `docs/launcher-design.md` (D-L1..D-L7).
- **D-L3**: si la app lanza el juego, el proceso es hijo y del mismo uid ⇒ se puede
  leer/escribir su memoria sin root. Validado en Linux (`docs/fase-3-patcher.md`),
  **no** en Android.
- **D-L7**: el driver gráfico se detecta; el objetivo no es solo PowerVR.
- El patcher ya existe y está probado: `crates/forever-patcher` (12/12 tests,
  prueba cross-proceso en Linux).

### Riesgo que ataca este plan
En Android, `/proc/<pid>/mem` y `process_vm_readv/writev` están sujetos a SELinux y a
`yama/ptrace` restricciones. Si el dominio de la app no puede tocar a su hijo, **D-L3
se cae** y habría que replantear el parcheo (p. ej. el enfoque de DLL proxy que usa
el launcher de Windows). Es mejor saberlo ahora, y el emulador puede responderlo.

### Alcance de este plan
1. Esqueleto de app Android que compila y corre en el emulador.
2. Biblioteca nativa del patcher (Rust) con puente JNI.
3. **Prueba del mecanismo en Android**: proceso hijo con un almacén sintético,
   parcheo desde el padre, verificación desde el hijo.
4. Detección de GPU y lógica de selección de driver (D-L7), mostrada en la UI.

Fuera de alcance (requiere arm64 + runtime): provisionar contenedor, integrar Wine,
descargar el cliente.

## Implementation / Action Items

- [x] 1. Instalar el **NDK** que corresponda a los build-tools ya instalados y
  dejarlo documentado en `docs/android-emulator.md`.
- [x] 2. Crear el proyecto Android en `android/` (Gradle Kotlin DSL, Kotlin, minSdk
  26, targetSdk 28, ABI `x86_64` + `arm64-v8a`), con un módulo de app mínimo que
  compile un debug APK e instale en el emulador. UI placeholder.
- [x] 3. Añadir el crate nativo (Rust `cdylib`) que exponga el **núcleo** de
  `forever-patcher` (sin `unsafe` en el núcleo) con un puente JNI mínimo; compilarlo
  para `x86_64-linux-android` y `aarch64-linux-android`.
- [x] 4. **Milestone clave**: prueba en el dispositivo que (a) crea un proceso hijo
  con un almacén de claves sintético en memoria, (b) lo localiza y lo parchea desde
  el proceso padre, (c) el hijo confirma el cambio. Registrar evidencia y el
  resultado exacto; si SELinux lo impide, **documentar el bloqueo y parar** para
  decidir la alternativa.
  → **OK**: `result=OK … patched=true child_confirm=PATCHED`, `first_err=none`,
  396/396 regiones legibles. **D-L3 viable en Android sin root** (ver
  `docs/fase-5-launcher.md`).
- [ ] 5. Detección de GPU (vendor/renderer vía `vkjson`/Vulkan) + tabla de selección
  de driver (D-L7) con override manual, visible en la UI.
- [x] 5b. **Validar la restricción de ejecución (W^X / targetSdk)**: CONFIRMADO en el
  emulador si una app puede **ejecutar** un binario escrito en su propio directorio
  de datos, comparando `targetSdk 35` con `targetSdk 28`. Si no puede, el runtime no
  arrancaría con la configuración actual y hay que cambiar el enfoque (bajar
  targetSdk, o mantener los ejecutables en el directorio de librerías nativas).
 logging claro del `errno` en cada caso.
- [ ] 6. Registrar hallazgos y estado en `docs/` (nueva bitácora de Fase 5) y
  actualizar `AGENTS.md`.

## Open Issues

- **Aprobación del usuario** antes de empezar (plan nuevo, componente nuevo).
- El NDK añade ~1–2 GB de descarga; no hay problema de disco.
- La prueba del punto 4 en el emulador usa el dominio SELinux de una app normal
  (`untrusted_app`), que es el caso real; pero el runtime final (Wine) lanzará al
  juego desde un proceso nativo propio, así que conviene cubrir también el caso
  "hijo lanzado desde código nativo" y no solo desde Java.
- El emulador es **x86_64**: valida el mecanismo, no la arquitectura final.
- Sin móvil no se puede probar nada del runtime del juego; la Fase 2 sigue en espera.
