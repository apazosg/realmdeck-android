# Pixel 9a: referencia histórica, fuera del alcance actual

2026-10-03. El usuario mencionó inicialmente Pixel 9a como teléfono de juego,
pero después aclaró: **«no vamos a usar el pixel. vas a seguir con el poco con el
que estás de momento»**. Este documento conserva las especificaciones consultadas,
no una prioridad activa ni una petición de conectar/probar el Pixel. El único
objetivo actual es el POCO xaga USB. No se ha conectado, instalado ni probado esta
aplicación en el Pixel 9a durante esta investigación.

## Especificaciones publicadas, no medición propia

- Google [publica Tensor G4 y 8 GB de RAM](https://store.google.com/product/pixel_9a_specs).
  El modelo salió con Android 15; la versión actualmente instalada por el usuario
  es desconocida.
- La familia Tensor G4 utiliza **Mali-G715**, según
  [información pública de Tensor G4](https://www.androidauthority.com/exclusive-tensor-g4-small-upgrade-3466398/).
  Identidad/revisión/driver/API Vulkan y extensiones del Pixel concreto requieren
  una lectura ADB; no se derivan del resultado Mali-G610.
- El teléfono USB xaga tiene Mali-G610 MC6, Android 14 y Vulkan físico 1.1.177;
  la sonda actual midió otra capacidad efectiva a través del wrapper y rechazó
  D3D11. Ese rechazo no acepta ni descarta el Pixel 9a.
- Los resultados PanVK publicados para G615 tampoco aceptan G715: compartir una
  generación de arquitectura no valida kernel kbase, loader o presentación.

## Gates si se retomase ese dispositivo — no activos

1. Mantener xaga como banco automatizado de provisión, ABI, lifecycle y sondas.
2. Cuando el Pixel esté disponible y autorice acceso, inventariar únicamente sus
   capacidades y ejecutar la misma sonda AA64 con el perfil existente. Primero
   medir; no dar por necesaria una sustitución de driver.
3. Si D3D11 y triángulo/present pasan, probar WoW y estabilidad en el Pixel. Si no,
   clasificar sus carencias concretas antes de elegir PanVK o WineD3D/VirGL.
4. No instalar un driver G615/G610 por el nombre Mali, no root, no `/vendor`, no
   features ficticias. El permiso anterior de borrar contenido del laboratorio
   xaga no se extiende al Pixel ni se necesita para este inventario.

No se retoma este dispositivo sin una nueva decisión del usuario. Ningún frame
3D, gameplay o autenticación del Pixel se declara demostrado por sus
especificaciones; estos gates no retrasan las pruebas en el POCO conectado.
