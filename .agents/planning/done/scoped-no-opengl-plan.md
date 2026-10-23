---
id: scoped-no-opengl-2026-10-02
state: done
date: 2026-10-02
---

## Summary

Objetivo: que WoW ARM64 cree su ventana y avance hacia D3D11, no un escritorio
Windows como producto. El teléfono USB es Mali-G610 MC6, **no Adreno**.
La repetición code 36 asigna el primer caller del execute fault a
`libGL.so.1.5.0`, Zink, dispatch `vkCmdPipelineBarrier2`; ver
`docs/usb-lab-xaga.md`. Proponer un único control: OpenGL no disponible en los
hijos Wine de la corrida gestionada, sin cambiar Vulkan/DXVK/Wine ni datos.

## Content

### Evidencia y alcance

- WoW 70009 ejecuta y lee `auth.gpon.com.co`, pero aborta `CreateWindowEx failed`,
  GX inexistente, frame 0. No login ni layout CertStore real demostrado.
- Code 36 conserva escritorio stock, target AA64 verificado sin winhandler.
  Repetir con mapas no cambió ningún input del juego.
- Linux Explorer 14047/startTicks 10859, LR `0x7052b34894`: callsite Zink VA
  `0xd32890`, `blr x8`, miembro `vk.CmdPipelineBarrier2` confirmado por DWARF.
- La causa del dispatch inválido (wrapper/API/extensiones) aún no es exclusiva.
  No afirmar equivalencia con PowerVR ni que Explorer sea obligatorio.
- Thor ofrece un shim MIT mínimo que no exporta GL: la lookup comprobada de
  `glGetString` permite a Wine informar GL no disponible. Su ubicación efectiva
  y el fallback en **nuestro** WCP deben verificarse, no darse por garantizados.

### Cambio propuesto y límites

1. Compilar con NDK existente un `libGL.so.1` AA64 pequeño sin APIs GL, a partir
   del patrón Thor fijado; fuente en repo, artefacto/hash fuera. No recompilar Mesa
   o Wine, no sustituir el archivo ImageFS de 80 MB ni instalar Turnip en Mali.
2. Añadir un directorio **privado y exclusivo del control**, antepuesto a la
   búsqueda de librerías SOLO en el entorno final de los hijos Wine gestionados.
   Mantener íntegros SHM/evshim/WX/preloads y resolución Vulkan. Evitar cambiar
   entorno del host Android u otras aplicaciones/ramas. Reversible desactivando
   únicamente ese flag; no borrar original ni perfilar datos del usuario.
3. Registrar selección, ELF/hash y resolución efectiva mediante mapas. Un control
   válido debe demostrar el shim en lugar del libGL Zink; si se carga el original,
   informar control inválido, no sacar una conclusión gráfica.
4. Una prueba USB AOA automática con APK nueva/same package y firma, tras reinicio
   por el Wine previo. Capturar proceso WoW, primer error, ventana/GX/frame.
   Si falla, detener el loop y conservar resultado; no añadir otra variable.

No cambia driver Vulkan, DXVK 2.4.1 AA64, WCP, startup, escritorio de esta baseline,
CDN/CASC, flags Forever, JNI/keys, grupos/sesiones ni política de quiescencia.
No activar el handler SIGINT no validado. No root/SIM/permisos UI nuevos, servicios,
credenciales, fuente Battle.net o limpieza de artefactos. Vulkan 1.1 del laboratorio
frente a requisitos DXVK sigue siendo una puerta separada: evitar Zink no prueba
que D3D11 funcione. El Poco PowerVR no queda validado por este ensayo Mali.

### Verificación requerida

- Tests de flag/contexto gestionado y orden de path; fuera del control conservar
  exactamente el entorno upstream; cancelar/recrear no inicia un segundo Wine.
- Confirmar ELF AA64/SONAME/exports sin APIs GL y hashes; fuente/licencia MIT.
- Review de ámbito y búsqueda antes del trial, receta fresca/idempotente, build
  offline y audit del APK real conservando la versión 36 y reportes anteriores.
- En dispositivo, comprobar resolución y fallback GL no disponible. Diferenciar
  init X11 parcial, ventana creada, GX/D3D iniciado y render/login; no falso PASS.
- No repetir suites exitosas sin motivo ni descargar cliente/runtime de nuevo.

## Implementation / Action Items

- [x] 1. Verificar hardware y localizar caller del fault en el proceso correcto.
- [x] 2. Aprobar el control OpenGL aislado.
  Usuario: «venga adelante ... una vez tengamos eso funcionando podemos pensar
  en el parcheo se memoria y el resto de pasos».
- [x] 3. Verificar fallback Wine e implementar flag/artefacto/entorno acotados.
- [x] 4. Ejecutar checks/review/build/audit y una prueba USB automática.
- [x] 5. Evaluar ventana/GX/primer error y decidir siguiente paso por evidencia.

## Open Issues

- Prioridad aprobada: ventana/ejecución/render antes de parcheo/login. Se conserva
  el código/contrato del patcher pero se desactiva su worker para las pruebas de
  arranque. Para no mezclar dos variables, establecer primero una baseline sin
  worker de memoria y luego el control GL con exactamente esa misma política.
  No declarar login privado sin grupo 8 validado/aplicado.
- Resolución real probada en code 39: mapa Explorer con shim privado y Wine
  `glGetString not found ... disabling OpenGL`. Se crea la ventana WoW, no GX.
- Control completado, **no proyecto completado**. Code 40 mantiene la misma ruta
  y solo agrega logging: DXVK enumera Wrapper(Mali-G610 MC6), pero nivel máximo
  D3D11 0; rechaza 11_1 y 9_1. Ver `docs/scoped-no-opengl-results.md` para los
  informes/rondas, artefactos y límites. No afirmar render o login.
- Dispatch Vulkan inválido, suficiente CASC, GX y autenticación privada siguen
  abiertos. No presentar el control como arreglo definitivo o garantía jugable.
