---
id: launch-loading-screen-2026-10-20
state: draft
date: 2026-10-20
---

## Summary

El usuario propone «añadir un progreso, imagen y animación» durante el arranque
actualmente negro de C121. La inicialización del primer prefijo y los servicios
Wine transcurre después de abrir la presentación, sin información visible.
Esta mejora de presentación no sustituye el diagnóstico del arranque del juego.

## Content

- Pantalla de carga dentro de la app con una imagen ya incluida y autorizada
  en el proyecto; sin nuevas descargas ni aumentar significativamente la APK.
- Animación ligera y estado por etapas reales: preparando entorno, preparando
  Wine e iniciando WoW. Porcentaje solo cuando existe una medida fiable de esa
  operación; en los demás casos indicador indeterminado, sin progreso ficticio.
- Conservarla sobre la presentación durante la inicialización. Retirarla con
  una señal comprobada de presentación del juego, no solo al crear el proceso o
  al vencer un temporizador. Identificar primero qué señal real ofrece el runtime;
  si no permite distinguir primer frame de juego de un frame vacío/de escritorio,
  documentar la limitación y resolverla antes de implementar la transición.
- Mostrar fallo o espera prolongada con diagnóstico honesto; no afirmar que el
  juego carga ni que ha fallado solo por el tiempo transcurrido.
- Mantener las puertas de almacenamiento, consentimiento y lanzamiento, y los
  controles de salida existentes. No cubrir errores ni formularios de login.
- No modificar Wine, drivers, autenticación, cliente USB ni descargador.

## Implementation / Action Items

- [ ] 1. Identificar recurso visual existente, estados de inicialización y señal
  fiable para cerrar la pantalla de carga; comprobar interacción con XServer.
- [ ] 2. Implementar el overlay mínimo y su animación, reutilizando los estados
  reales y sin crear una nueva infraestructura de progreso.
- [ ] 3. Verificar estados relevantes con pruebas focales, compilar y auditar APK.
- [ ] 4. Instalar conservando datos y comprobar visualmente preparación, espera,
  transición y error cuando pueda reproducirse sin resetear el entorno.

## Open Issues

- Pendiente aprobación de esta propuesta antes de modificar código.
- Señal de primera imagen útil todavía no identificada. El mero arranque de Wine
  y el contador de frames de una superficie vacía no prueban render del juego.
- El arranque C121 sigue bajo observación; no reinstalar durante el intento vivo.
