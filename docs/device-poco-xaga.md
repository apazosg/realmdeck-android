# Dispositivo USB de pruebas — POCO 22041216G (xaga)

Inventario inicial de **solo lectura**, 2026-10-02. El usuario lo ha conectado
como banco permanente y permite borrar aplicaciones/contenido si hace falta.
No se ha borrado nada: el espacio actual basta para el ensayo inicial. No se ha
instalado ni ejecutado Forever en este dispositivo durante esta inspección.

## Acceso y hardware observado

| Campo | Evidencia ADB |
|---|---|
| Serial USB | `DEVICE_SERIAL`, estado `device`, autorizado |
| Fabricante / marca / modelo | Xiaomi / POCO / `22041216G` |
| Device / producto | `xaga` / `xaga_eea` |
| SoC / plataforma | Mediatek `MT6895Z/TCZA` / `mt6895` |
| ABI | `arm64-v8a,armeabi-v7a,armeabi` |
| Android | 14 / API 34 |
| Build | `POCO/xaga_eea/xaga:14/UP1A.231005.007/OS2.0.2.0.ULOEUXM:user/release-keys` |
| Security patch | 2025-03-01 |
| GPU / GLES | ARM Mali-G610 MC6 / OpenGL ES 3.2, driver `r32p1-01eac0` |
| Vulkan real | `cmd gpu vkjson`: API **1.1.177**, valor `4198577` |
| Vulkan extensiones | 71; presentes `VK_EXT_transform_feedback`, `VK_EXT_custom_border_color` |
| Extensiones no anunciadas | `VK_KHR_dynamic_rendering`, `VK_KHR_synchronization2`, `VK_EXT_robustness2` y las tres consultadas `VK_EXT_extended_dynamic_state{,2,3}` |
| RAM visible / disponible | 7.31 GiB total; ~2.88 GiB disponibles en la lectura inicial |
| Disco libre | 20.500.520 KiB, ~19.55 GiB en `/data` y almacenamiento emulado compartido |
| Batería inicial | 11 %, USB conectado/cargando; 32.7 °C. Lectura transitoria, no límite permanente. |
| APK Forever instalada | Ningún package `com.wowforever` encontrado |

La feature Android declara Vulkan 1.1.0; el servicio Vulkan informa 1.1.177.
La identidad GLES procede de `dumpsys SurfaceFlinger`, no de una sonda instalada.
No se han leído fotos, cuentas, lista de aplicaciones personales ni contenido.

## Utilidad y límite gráfico

Este ARM64 físico autorizado permite automatizar instalación, UI y captura
externa de logcat/archivos del APK debug, previa autorización del ensayo. No es
el Poco M7 Pro/PowerVR de los informes anteriores: SoC, GPU y Android cambian.
Una comparación de dispositivos no identifica por sí sola cuál variable causa
SIGINT. El ADB externo puede seguir recogiendo evidencia aunque muera el host.

**No asumir render con el DXVK 2.4.1 AA64 actual.** El driver de este dispositivo
solo anuncia Vulkan 1.1.177 y carece de las extensiones consultadas de rendering
dinámico/synchronization2. Upstream DXVK 2.4.1 solicita API 1.3.0 y describe la
necesidad de un driver Vulkan 1.3:

<https://raw.githubusercontent.com/doitsujin/dxvk/v2.4.1/src/dxvk/dxvk_instance.cpp>

Esto es una incompatibilidad esperable para DXVK upstream, no una prueba de que
todo Wine sea inviable ni de que el WCP experimental se haya ejecutado. Falta
medir el camino real por el wrapper. Sirve inicialmente para investigar el
arranque/host SIGINT; si alcanza D3D, distinguir su límite gráfico del problema
previo. No aplicar Turnip a Mali ni cambiar runtime/DXVK sin evidencia y decisión.

## Próximo ensayo

- No borrar aplicaciones/contenido para este primer ensayo; hay espacio suficiente.
- Dejar cargar antes de la provisión de varios GB; evitar presión de batería.
- Autorizar instalación del APK existente y una tentativa supervisada por ADB,
  sin nuevos hooks, JNI-free control ni modificaciones de la aplicación.
- Capturar desde el host logs Android y estado de la corrida, incluso tras cierre.
- Comprobar `run-as` en la instalación real antes de depender de él.
- Mantener el criterio de reinicio tras un Wine desconocido hasta verificar un
  cierre completo; USB permite automatizar el reinicio si se autoriza, pero no
  demuestra quiescencia solo por conectarse.
- El control sin JNI sigue siendo draft no aprobado. No iniciar login/credenciales
  ni declarar estabilidad del juego por haber conectado un nuevo móvil.
