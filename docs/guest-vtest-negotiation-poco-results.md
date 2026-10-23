# POCO: negociación vtest observada; contexto desktop todavía rechazado

2026-10-04. Ronda acotada aprobada de GPU Phase B. No modifica APK41, Wine,
DXVK, el driver, ImageFS, datos ni memoria. Se conserva el Android-default
anterior y se añade únicamente logging numérico acotado al servidor.

## Inputs y verificación

- Fuente oficial virglrenderer 1.3.0, SHA
  `56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce`.
- Perfil `android-negotiation`, mismo Android-default, 14 patches fuzz 0; el
  adicional observa headers/versiones/caps sin mutar paquetes, retorno o features.
  Máximo 64 filas JSON de 2048 bytes más indicador de truncación.
- Workspace privado `controls/vtest-android-negotiation-v3`, 30.011.694 bytes;
  build offline, dos jobs y NDK24. Patch host absoluto y hash verificado,
  entorno reducido, sin PATH/preloads heredados.
- Manifest `candidate_android_negotiation_verified/candidate-manifest.json`, SHA
  `e1aa871dc22f53e1a9bc68fd86a4d9838c4138d2c9ecf9a7da2d83b2cee37ba3`.
- Servidor 155.704 bytes, SHA
  `6a73e38866ac4a4fe6eeae00e792d9ff1ce2ac21aae925afe5c322cf85e30476`;
  biblioteca 654.752, SHA
  `b6e4f9f91a7c1d97896e374e60d86756e194534a15a364350962ee9e0edfff4e`.
- Cliente sin cambios: `GuestEglContext`, 36.528 bytes, SHA
  `8db0e0565017743c29ad4814541b2c9607b42a3a48000bab538e3f16a5775975`.
  Misma petición 3.3 core, guest manifest `30c438...`, tree `1cc803...`.
- Tests dirigidos del nuevo build/runner y antiguos controles afectados pasan;
  review no P0/P1 pendientes antes de ejecutar. No suites ni GPU gates previos
  repetidos como sustituto de esta medición.

## Control físico

Carpeta fuera del repo:
`physical-trials/xaga-20261002/native-NEGOTIATION-VTEST-f704d8e8a71244d79bb1b4c2f6ab0677`.
Servidor PID18200/startTicks10786830/UID10330; guest18234. Identidad real del
servidor revalidada, namespace propio, ningún proceso de otra app señalado.
Servidor exit0, guest exit1, pipes completos, sin timeout/force-stop ni procesos
propios restantes. Stdout servidor 90, stderr 1655; stdout guest6003, stderr95
(únicamente warning exacto de caché opcional desactivada).

Traza real:

1. CREATE_RENDERER 8, length16 (nombre del cliente no se registra).
2. Ping10 / busy-wait7 / negociación11: **solicitado4, seleccionado4**.
3. GET_CAPS2 9: capset2/version1, **1408 bytes**, `glsl_level=430`,
   `bset=117472583`, `prim_mask=31871`, `capability_bits=387901405`.
   Esta máscara contiene `HOST_IS_GLES` (bit19).
4. GET_CAPS1 1: capset1/version1, **308 bytes**, mismos valores base y GLSL430.

Ambos se llenan y escriben mediante el código oficial sin cambios. La secuencia
9+1 coincide con el cliente Mesa26.2.4 publicado: pide ambos y descarta el v1
redundante después de consumir v2. Ese texto es referencia de un mirror/tag,
no prueba de reproducibilidad de las bibliotecas Termux cargadas.

## Contexto y límite

El display privado vuelve a inicializar EGL1.5, config elegida, bind OpenGL válido,
pero **eglCreateContext3.3 core devuelve EGL_BAD_MATCH (0x3009)**. No contexto
current ni strings GL. La negociación sí sucede; no se demostró ausencia de
capsets o rechazo de la versión wire. Tampoco se prueba que Mesa los interprete
correctamente o que GLSL430 implique soporte de un contexto OpenGL4.3.

Las referencias Mesa muestran que BAD_API/BAD_VERSION/BAD_FLAG de DRI pueden
colapsar a EGL_BAD_MATCH; el error concreto de esa biblioteca no está registrado.
No hay una corrección pequeña verificada para ese rechazo. Esta pareja sigue
**no aceptada**: sin presentación, D3D ni WoW. No cambiar más versiones/flags a
ciegas ni falsear caps. Si se retoma, requiere un diagnóstico específico del
rechazo local Mesa/DRI o una pareja client/server ya cualificada, con alcance y
coste explícitos; no otra APK funcionalmente igual.

## Corrección de nuestra clasificación, no del driver

El primer resumen queda UNKNOWN: el parser asumía capset2 máximo1284 bytes, cifra
incorrecta. El header oficial fija sizeof(v1)=308, sizeof(v2)=1408 y offset
`capability_bits=392`, comprobados por harness host y static_assert aarch64/NDK.
Se corrigió el límite y el conteo de requests legacy1/9, con regresiones. No se
repite el teléfono para cambiar una etiqueta ni se reescribe el informe original.
`interpretation.json` exclusivo liga hashes de capture/summary/los cuatro streams
y declara **NEGOTIATION_OBSERVED / CONTEXT_FAILED**, con todas las aceptaciones
gráficas superiores false. Esta observación no es un PASS de backend.
