---
id: wow-functional-poc-2026-10-11
state: in-progress
date: 2026-10-11
---

## Summary

El usuario propone priorizar una PoC funcional y aplazar limpieza y securización.
La ruta code55 ya midió dibujo D3D11 y Present bajo Wine. La posterior búsqueda de
ownership formal y recuperación de estados no ha producido una prueba de WoW.
Este plan cambia el criterio de éxito: arranque y render visible del juego, no
aceptación de producto. El usuario confirma: «una vez tengamos eso funcionando,
ya nos preocuparemos de securizarlo y mejorar funcionalmente y resolver deuda
técnica. ahora mismo lo crítico es tener avances y que funcione», y concreta:
«lo que estamos intentando es que WoW se ejecute en arquitecturas gráficas Mali.
adelante». La primera prueba será sobre Mali-G610 del POCO autorizado; no se
generaliza un resultado de este dispositivo a todas las GPU Mali.

## Content

### Objetivo y criterio de éxito

- Un APK de laboratorio en el POCO X4 GT xaga que lance el cliente real y muestre
  su splash, menú o pantalla de login, sin introducir credenciales.
- Registrar por separado: intento de ejecución, proceso observado, ventana,
  imagen del juego, fallo y duración. Un exit 0 o Present no demuestra imagen.
- No afirmar gameplay, login Forever, identidad PE formal ni seguridad de
  producto por alcanzar este objetivo. La autenticación queda para otro paso.

### Ruta corta

- Reutilizar GameNative/Wine/XServer y el runtime gráfico de la ruta code55 que
  dio readback positivo: Proton, DXVK, PanVK, noGL y compiler AA64 conocidos.
- Preferir el prefijo ya funcional para esta prueba. No convertir la creación de
  un prefijo nuevo, SERVER_ISOLATION o un supervisor nuevo en requisitos previos.
  El prefijo de laboratorio puede recibir las escrituras normales de Wine;
  conservar su backup del compiler y no borrar instalación ni historial.
- Inventariar primero el cliente realmente disponible y seleccionar el PE
  adecuado: la ruta existente contempla WowB-ARM64.exe. No asumir instalación
  completa, catálogo correcto o compatibilidad a partir del nombre del archivo.
- Reutilizar una entrada GUI de lanzamiento lo más pequeña posible; el modo PoC
  evita la admisión del Store de producto y escribe solo logs propios de prueba.
  No rehabilita ni modifica los UNKNOWN/ACTIVE históricos y no reutiliza sus
  recibos como autorización de cierre limpio.
- Lanzar WoW directamente. Usar un control gráfico corto únicamente si hace
  falta distinguir fallo del runtime de fallo del juego, no como nueva campaña
  de cualificación ni requisito de ownership.

### Qué se aplaza

Identidad criptográfica del PE en memoria, prueba de ascendencia y ECHILD como
condición de arranque, tickets de recuperación por cada fallo, transacciones de
publicación del prefijo y cumplimiento de todos los guards propios de producto.
Se mantienen en su ruta original; no se borran globalmente ni se falsean sus
resultados. La PoC no se presenta como release endurecida.

### Límites básicos que permanecen

- Solo el dispositivo autorizado DEVICE_SERIAL y recursos del paquete propio.
- Sin root, ptrace, bypass de SELinux/Android, datos/UI de otras aplicaciones,
  contraseñas, login ni parche de autenticación/memoria.
- APK y evidencias fuera del repo, build local con herramientas existentes;
  conservar APKs, historial y backups, sin commits ni publicación.
- Comprobación básica del APK instalado y del ejecutable que se pretende lanzar;
  logs acotados y entrada/captura únicamente cuando sea de la aplicación propia.
- Intentos finitos; parar una prueba que se cuelgue y reiniciar entre intentos
  Wine inciertos. No barridos de procesos ajenos ni afirmar cierre kernel por
  force-stop. Repetir solo con un diagnóstico o cambio concreto.

## Implementation / Action Items

- [x] 1. Confirmar el cambio de alcance PoC funcional, sustituyendo como prioridad
  la continuación de guards/publisher/supervisor del laboratorio anterior.
- [x] 2. Inspección mínima del cliente disponible y ruta GUI code55; identificar
  el menor cambio necesario para lanzar el juego sin los gates nuevos.
- [x] 3. Crear y verificar el APK PoC: compilación, instalación propia y pruebas
  dirigidas de selección de ruta/argumentos; no repetir campañas formales previas.
- [ ] 4. Reiniciar y probar WoW; conservar logs y captura propia durante el
  arranque, no solo después de que desaparezca la ventana.
- [ ] 5. Corregir únicamente el bloqueo observado, medir de nuevo y publicar un
  resultado funcional o un impedimento concreto, sin confundir tests con render.

## Open Issues

- PoC63 ya entra en el runtime GUI: corregido el init de ControllerManager;
  la siguiente prueba cargó PanVK en explorer, pero volvió al launcher sin
  ventana WoW. Correcciones acotadas siguientes: preservar usr/lib en
  LD_LIBRARY_PATH (SDL faltante) y no añadir comillas al PE fijo sin espacios
  (ProcessHelper las conserva en argv). No se atribuye aún el fallo a Mali.

- La disponibilidad de datos CASC suficientes en el teléfono no está verificada.
  El bootstrap finito no equivale al cliente completo. No iniciar una descarga
  de decenas de GB ni alterar el cliente host en vivo sin resolver ese alcance.
- La ruta actual del launcher del laboratorio está sustituida por server-only;
  hay que restaurar/reutilizar el GUI conocido, no parchear su ticket cerrado
  para aceptar argumentos arbitrarios.
- Code55 midió readback positivo, pero no imagen visible independiente. La PoC
  exige observar la ventana real; no convierte el resultado histórico en WoW.
- Las protecciones del sistema operativo no son guards propios que podamos
  omitir. Si aparece una denegación real, se diagnostica con ejecución normal,
  sin root ni excepciones globales de permisos.
