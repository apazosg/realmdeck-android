# Thor Forever — revisión de la referencia Android

Revisión estática del 2026-09-30, solicitada por el usuario mientras se implementa
el APK automatizado. No se descargaron ni ejecutaron APKs, runtimes o cliente del
proyecto. Las pruebas en dispositivo son observaciones publicadas por su autor,
no una reproducción nuestra.

## Fuente fijada

- Repositorio: <https://github.com/AeroNico/thor-forever>.
- Revisión inspeccionada: `4d332477b489863c7d5da686403ac92acb1ebad1`,
  2026-09-29 17:13:58 UTC. El cambio final elimina un borrador público; el padre
  `89b930a1c7508df4dad7862a56482b503981013a` contiene el trabajo de release.
- Fuentes originales MIT; Wine/Mesa/DXVK conservan sus propias licencias.
  [Alcance de licencia](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/LICENSING.md).
- Es un conjunto de fuentes, parches, scripts e instalador para GameHub, **no un
  proyecto de APK autónomo**. El árbol Git no contiene el cliente ni CASC.

## Combinación reportada

| Componente | Configuración del autor |
|---|---|
| Dispositivo | AYN Thor, Adreno 740 |
| App host | GameHub Lite Ludashi, `com.ludashi.aibench` |
| Cliente | `WowB-ARM64.exe`, beta `1.60.1.70009` |
| Wine | Wine/Proton Android 11 separado y parcheado |
| Direct3D | DXVK 2.4.1, DLLs Windows ARM64 nativas, D3D11 |
| Vulkan | Mesa Turnip/Freedreno, revisión `fe067b17d908d8f02e88ef3c4433ec5fbb66b2a9` + parche IR3 |
| Perfil inicial | 1280×720, calidad baja, VSync desactivado, límite 30 FPS |

[README fijado](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/README.md)
y [registro de validación](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/docs/VALIDATION.md).

El autor informa llegada a login y juego en mundo, lanzamiento directo desde
GameHub, controles persistentes y arranque tras reiniciar el dispositivo. Es una
segunda referencia de dispositivo para el mismo build ARM64, no un benchmark,
una garantía de 30 FPS ni una validación de nuestro Poco/PowerVR. Su instalador
comprueba el nombre del EXE; no verifica el encabezado PE como nuestro catálogo.

## Qué aporta frente a las referencias anteriores

1. **Runtime coherente, no solo versión de Wine.** Requiere el `ntdll.dll`
   Windows correspondiente, bibliotecas Unix y soporte Android de memoria SysV.
   Su fork Wine está fijado a
   `52796bf615c265c23b22ae9dacc3da9e38c8487f`:
   <https://github.com/AeroNico/proton-wine/commit/52796bf615c265c23b22ae9dacc3da9e38c8487f>.
   Los arreglos de syscall ARM64 copiado/TEB y asignación NLS coinciden en
   propósito con los parches incluidos en nuestro paquete RP6. Esto no demuestra
   igualdad binaria entre ambos runtimes ni justifica mezclar DLLs sueltas.
2. **Fallo GLX independiente y diagnosticado.** Sus trazas identificaron una
   aserción `driQueryOptionb` en el `libgallium-25.1.4.so` de GameHub, durante
   inicialización de ventanas Wine. Para ese lanzamiento D3D11/Vulkan usaron un
   `libGL.so.1` aislado que declara OpenGL no disponible. No es un arreglo de
   PowerVR, no se instala globalmente y no sirve para aplicaciones que usan GL.
   Es una firma útil que buscar en nuestros logs, **no la causa demostrada** de
   los cierres del APK actual.
3. **Separación del fallo de shader.** El cambio IR3 evita esperar `baryf` de otro
   bloque durante kill/demote. Su corpus pasó de 491 shaders correctos/1 fallo a
   492/0, pero no reproduce exactamente todo el pipeline capturado del juego.
   Es específico de Adreno/Turnip y no se traslada al PowerVR.
4. **Supervisión del lanzamiento.** Usan un EXE persistente, puente
   `start.exe /unix`, marcador de terminación y bloqueo de lanzamiento, con
   redirección de streams antes de crear hijos. Es una referencia para mantener
   vivo el host y conservar el resultado, no una API Android que podamos copiar
   directamente de GameHub a GameNative.
5. **Nombre de configuración correcto.** El autor corrigió `-config WTF\\...`
   a `-config NOMBRE.wtf` para persistir ajustes. Nuestro contrato ya usa la forma
   correcta: `-config BetaSuspendedTest.wtf`; el fichero vive dentro de `WTF/`.

Fuente de los fallos diferenciados y sus límites:
[TECHNICAL-NOTES.md](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/docs/TECHNICAL-NOTES.md).

## No demuestra la autenticación de Classic Forever

La configuración publicada contiene **`SET portal "test"`**, no el portal
`auth.gpon.com.co` del contrato de este repositorio:
[Config-Thor-Forever.wtf](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/installer/Config-Thor-Forever.wtf).

Las instrucciones parten de un cliente instalado mediante Battle.net y una cuenta
elegible. El código no implementa el parche de la clave del grupo 8, escaneo del
almacén, DLL proxy ni escritura de memoria de nuestro contrato. Por tanto:

- Sí aporta un **reporte de ejecución del cliente beta ARM64**.
- No establece login en el servidor privado Classic Forever; tampoco se deduce
  por el nombre del proyecto o por haber llegado a mundo.
- No sustituye nuestro trabajo de portal, JNI y validación del almacén ARM64.

## No resuelve la provisión mínima del cliente

[`installer/stage-game.sh`](https://github.com/AeroNico/thor-forever/blob/4d332477b489863c7d5da686403ac92acb1ebad1/installer/stage-game.sh)
requiere `Data/` existente, copia ficheros del lado del ejecutable y enlaza el
almacén CASC previo. Ese enlace **no es de solo lectura**. No hay motor NGDP/TACT,
descargador CDN ni prueba de un conjunto mínimo de datos o streaming.

Su referencia no valida, por tanto, la suficiencia jugable de nuestros 35 loose y
832 objetos de metadata (~748 MB de transferencia inicial). Esa puerta sigue
abierta aunque la estructura del bootstrap se haya validado con cascette.

## Aplicación al APK automatizado

- Mantener el cliente ARM64 y la combinación Wine/DXVK coherente; no volver a
  Box64 ni cambiar de fork por la marca “Forever”.
- Mantener Vulkan del sistema para PowerVR; no descargar Turnip ni aplicar IR3.
- Capturar desde el primer spawn los fallos de loader/NLS/GLX, stdout/stderr y
  resultado del proceso. Comparar la firma antes de introducir un shim de GL.
- Conservar prefijo dedicado, control de lanzamiento y retorno a la pantalla de
  Forever con resultado exportable. El usuario no debe operar GameHub o scripts.
- Tratar datos suficientes y autenticación privada como puertas independientes.

**Decisión:** referencia útil para verificar la dirección ARM64 y mejorar la
observabilidad. No requiere cambiar el plan aprobado ni integrar otro runtime;
no es una solución ya terminada del launcher ni evidencia de Forever privado.
