# Referencia: control del dispositivo mediante scrcpy

> Guía operativa para controlar el dispositivo de desarrollo por ADB Wi-Fi.
> Los valores concretos (serial/endpoint, rutas, versión y SHA-256 del servidor)
> están en `private/device-control.md`, ignorado por Git.

## Contexto

El teléfono es un dispositivo dedicado a desarrollo, con ADB Wi-Fi habilitado.
Se puede usar de forma completa: instalar y desinstalar APKs, lanzar
actividades, reiniciar, capturar pantalla, transferir archivos y controlar la
UI. No hay un flujo de autorización por acción ni una lista cerrada de
operaciones.

La única separación es de publicación: los identificadores del dispositivo y
las capturas con datos de cuenta no se versionan.

## Por qué scrcpy UHID

En HyperOS, `adb shell input tap/swipe/text` suele fallar con
`SecurityException: INJECT_EVENTS`. scrcpy evita esa vía: su servidor crea un
teclado UHID en el dispositivo y recibe las pulsaciones por el canal de control.
Funciona sin root y sin tocar los ajustes de seguridad.

## Opciones de uso

### Cliente scrcpy completo

Si el cliente oficial está instalado en el host:

```bash
scrcpy -s "$WOWFOREVER_ADB_SERIAL" --keyboard=uhid
```

Vídeo, audio, portapapeles y control están disponibles según los flags del
cliente. `--keyboard=uhid` es la opción que funcionó en los dispositivos
HyperOS.

### Solo servidor (sin cliente gráfico)

Es la vía usada en las pruebas históricas: no hay vídeo/audio/portapapeles y el
control se hace por socket. Pasos:

1. Verificar que el dispositivo responde:
   `"$WOWFOREVER_ADB" -s "$WOWFOREVER_ADB_SERIAL" get-state`.
2. Publicar el jar fijado:
   `adb -s "$WOWFOREVER_ADB_SERIAL" push "$WOWFOREVER_SCRCPY_SERVER" /data/local/tmp/scrcpy-server.jar`.
3. Abrir el forward:
   `adb -s "$WOWFOREVER_ADB_SERIAL" forward tcp:0 localabstract:scrcpy_<scid>`.
4. Arrancar el servidor:

   ```bash
   adb -s "$WOWFOREVER_ADB_SERIAL" shell \
     "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / \
      com.genymobile.scrcpy.Server 5.0.1 scid=<scid> tunnel_forward=true \
      video=false audio=false control=true clipboard_autosync=false \
      power_on=false cleanup=false"
   ```

5. Conectar al puerto local del forward, leer el byte de handshake y crear el
   teclado UHID (mensaje 12) con el descriptor de teclado boot.
6. Enviar pulsaciones con el mensaje 13 (`>BHH` tipo 13, id 1, longitud 8,
   reporte de 8 bytes) y su release.
7. Destruir el UHID (mensaje 14) al terminar.

El probe histórico
`/home/adrian/wowforever-artifacts/work/wifi-uhid-control-20261017/probe-uhid.py`
implementa este flujo y limita las teclas a navegación (Enter, Escape, Tab,
espacio y flechas). Para escribir texto, usa el cliente scrcpy o amplía el
helper; el servidor acepta los mensajes de texto y control del protocolo.

## Notas prácticas

- El endpoint Wi-Fi cambia al reconectar o emparejar. Comprobar con
  `adb devices -l` y actualizar `private/device-control.md`.
- El serial se pasa por `WOWFOREVER_ADB_SERIAL` para no incrustarlo en scripts
  versionados.
- El servidor y su SHA-256 están fijados en la ficha privada para reproducir
  las pruebas; se puede usar otra versión oficial si se actualiza esa ficha.
- Cerrar el forward y el proceso del servidor al terminar evita sesiones
  huérfanas; el jar remoto se puede borrar o reutilizar.
- Los artefactos y capturas van fuera del repositorio. No versionar seriales,
  IPs, pantallas de cuenta o login.
