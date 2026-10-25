# Referencia: control del dispositivo mediante scrcpy (UHID)

> Conocimiento operativo para controlar la UI del teléfono de pruebas sin
> `adb shell input`. Los valores concretos (serial/endpoint, rutas de
> herramientas, versión y SHA-256 del servidor fijado) viven solo en
> `private/device-control.md`, ignorado por Git. Este documento se puede
> publicar: no contiene identificadores de dispositivo ni secretos.

## Por qué scrcpy UHID

En los dispositivos de prueba HyperOS, `adb shell input tap/swipe/text` falla
con `SecurityException: INJECT_EVENTS`. Esa denegación no se rodea con root,
con ajustes de desarrollador adicionales ni cambiando la configuración de
seguridad.

El servidor oficial de scrcpy crea un teclado **UHID** en el dispositivo y
recibe las pulsaciones por su canal de control. La vía fue verificada: el
teclado aparece en `dumpsys input` y Tab cambia el foco sin modificar permisos.
No se usa el cliente gráfico ni se habilitan vídeo, audio o portapapeles.

## Reglas obligatorias

1. **Autorización explícita por ventana de interacción.** Aprobar un plan no
   autoriza instalar, reiniciar, matar procesos, pausar/reanudar descargas ni
   confirmar costes. Pedir autorización antes de cada acción con efecto.
2. **Solo navegación por teclado.** No enviar texto ni credenciales salvo
   autorización explícita para un envío concreto. Nunca registrar credenciales
   en logs, capturas, artefactos o documentación.
3. **Sin cambios de seguridad.** No habilitar `INJECT_EVENTS`, no usar root, no
   modificar ajustes de desarrollador ni desactivar verificaciones.
4. **Por defecto, sin vídeo, audio ni portapapeles.** Cualquier excepción
   requiere autorización explícita. No capturar pantallas de cuenta o login.
5. **Comprobar el contexto antes de cada tecla.** La actividad en primer plano
   debe ser el paquete propio; el dump de UI debe contener solo ese paquete y
   ningún `EditText` ni `password=true`. Si no se cumple, no se envía nada.
6. **Teclas permitidas por defecto:** Tab, Enter, Escape, espacio, flechas y
   Home. Cualquier otra combinación requiere revisión y autorización.
7. **Una acción cada vez,** verificando el estado posterior. No repetir Enter
   «por si acaso» ni encadenar acciones sin observar el resultado.
8. **Cierre y limpieza siempre:** destruir el UHID, cerrar el socket, terminar
   el servidor, retirar el `adb forward` y borrar el jar remoto. Los artefactos
   van fuera del repositorio y se sanean antes de publicar extractos.
9. **Servidor fijado.** Usar solo el servidor oficial registrado en
   `private/device-control.md` y verificar su SHA-256 antes de cada uso. No
   descargar ni actualizar scrcpy durante una sesión.
10. **Serial fuera de Git.** Exportar `WOWFOREVER_ADB_SERIAL` en la terminal; no
    incrustarlo en scripts, contratos ni documentación versionada.

## Procedimiento (host)

Variables definidas en `private/device-control.md`:

- `$WOWFOREVER_ADB` — `adb` oficial de platform-tools.
- `$WOWFOREVER_ADB_SERIAL` — serial USB o `ip:puerto` de ADB Wi-Fi.
- `$WOWFOREVER_SCRCPY_SERVER` — jar `scrcpy-server` oficial fijado.
- SHA-256 esperado del jar (en el mismo fichero privado).

Pasos:

1. Confirmar autorización y que responde el dispositivo correcto:
   `"$WOWFOREVER_ADB" -s "$WOWFOREVER_ADB_SERIAL" get-state`.
2. Verificar el SHA-256 del jar antes de publicarlo.
3. Publicarlo en `/data/local/tmp/` con un nombre único y abrir
   `adb forward tcp:0 localabstract:scrcpy_<scid>`.
4. Arrancar el servidor con `tunnel_forward=true video=false audio=false
   control=true clipboard_autosync=false power_on=false cleanup=false`.
5. Conectar al socket de control, consumir el byte de handshake y crear el
   teclado UHID (mensaje 12) con el descriptor de teclado boot.
6. Antes de cada pulsación, aplicar la comprobación de contexto de la regla 5;
   después enviar la pulsación (mensaje 13) y su release.
7. Al terminar, destruir el UHID (mensaje 14) y limpiar.

La implementación de referencia auditada está fuera del repositorio; la ruta
local figura en `private/device-control.md`. El adaptador
[`functional_login_uhid.py`](../../scripts/diagnostics/android-process/functional_login_uhid.py)
documenta el framing y las obligaciones de guarda para un envío de login ya
autorizado; no crea transporte ni autoriza nada por sí mismo.

## Si falta la configuración privada

El checkout público no incluye `private/device-control.md`. No inventes
seriales, endpoints, hashes ni rutas: pide los valores al responsable local y
crea el fichero ignorado antes de operar. Sin él, la única acción válida es
leer o diagnosticar sin controlar la UI.
