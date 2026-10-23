#!/usr/bin/env bash
# Gestiona el emulador Android x86_64 headless usado para desarrollar y probar
# el futuro launcher Android de wowforever.
#
# Por qué existe este script: el AVD se usa sin ventana (servidor/headless) y
# la sesión del usuario puede no tener el grupo `kvm` activo aunque el usuario
# pertenezca a él; este script centraliza el arranque con `sg kvm` solo cuando
# hace falta, guarda el PID y el log siempre en el mismo sitio, y evita
# patrones peligrosos como `pkill -f emulator` (el patrón puede coincidir con
# la propia línea de comandos de la shell que lo ejecuta y matarla: para parar
# se usa siempre el PID guardado o `adb emu kill`, nunca un patrón).
#
# Uso:
#   scripts/android-emulator.sh start|stop|status|screencap [ruta-png]
#
# Variables de entorno reconocidas:
#   ANDROID_SDK_ROOT        raíz del SDK (defecto: /home/adrian/.local/opt/android-sdk)
#   ANDROID_EMU_DIR         directorio de artefactos: PID, log y capturas
#                           (defecto: /home/adrian/wowforever-artifacts/android-emulator)
#   ANDROID_EMU_AVD         nombre del AVD (defecto: wowtest)
#   ANDROID_EMU_SERIAL      serie adb esperada (defecto: emulator-5554)
#   ANDROID_EMU_BOOT_TIMEOUT
#                           segundos máximos de espera al boot en `start` (defecto: 600)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/adrian/.local/opt/android-sdk}"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="${ANDROID_EMU_AVD:-wowtest}"
SERIAL="${ANDROID_EMU_SERIAL:-emulator-5554}"
EMU_DIR="${ANDROID_EMU_DIR:-/home/adrian/wowforever-artifacts/android-emulator}"
PIDFILE="$EMU_DIR/emulator.pid"
LOGFILE="$EMU_DIR/emulator.log"
BOOT_TIMEOUT="${ANDROID_EMU_BOOT_TIMEOUT:-600}"

msg() { printf '%s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }

tiene_kvm() { id -nG | tr ' ' '\n' | grep -qx kvm; }

pid_vivo() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

leer_pid() {
    if [ -f "$PIDFILE" ]; then
        tr -d ' \t\r\n' <"$PIDFILE"
    fi
}

adb_estado() {
    # Imprime el estado adb de la serie esperada o "ausente". Nunca falla.
    "$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || echo "ausente"
}

boot_completed() {
    "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r ' || true
}

do_start() {
    [ -x "$EMU" ] || { err "no existe el emulador: $EMU (revisa ANDROID_SDK_ROOT)"; exit 1; }
    [ -x "$ADB" ] || { err "no existe adb: $ADB (revisa ANDROID_SDK_ROOT)"; exit 1; }
    mkdir -p "$EMU_DIR"

    pid="$(leer_pid)"
    if [ -n "$pid" ] && pid_vivo "$pid"; then
        if [ "$(boot_completed)" = "1" ]; then
            msg "el emulador ya está arrancado (PID $pid, $SERIAL, boot completado)"
            return 0
        fi
        msg "el emulador ya está en marcha (PID $pid); esperando a que complete el boot…"
    else
        [ -n "$pid" ] && rm -f "$PIDFILE" # PID rancio
        msg "arrancando AVD '$AVD' en segundo plano (log: $LOGFILE)…"
        # Línea de arranque citada de forma segura para poder pasarla por `sg kvm -c`.
        arranque="nohup $(printf '%q ' "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 4096)"
        arranque="$arranque >> $(printf '%q' "$LOGFILE") 2>&1 & echo \$! > $(printf '%q' "$PIDFILE")"
        if tiene_kvm; then
            eval "$arranque"
        else
            msg "sesión sin grupo kvm activo; lanzando con 'sg kvm'"
            sg kvm -c "$arranque"
        fi
        pid="$(leer_pid)"
        [ -n "$pid" ] || { err "no se pudo obtener el PID (mira $LOGFILE)"; exit 1; }
        msg "PID $pid"
    fi

    "$ADB" start-server >/dev/null 2>&1 || true
    msg "esperando boot (timeout ${BOOT_TIMEOUT}s)…"
    i=0
    while [ "$i" -lt "$BOOT_TIMEOUT" ]; do
        if ! pid_vivo "$pid"; then
            err "el proceso del emulador murió durante el arranque; últimas líneas del log:"
            tail -20 "$LOGFILE" >&2 || true
            rm -f "$PIDFILE"
            exit 1
        fi
        if [ "$(boot_completed)" = "1" ]; then
            msg "boot completado en ~${i}s (PID $pid, $SERIAL)"
            return 0
        fi
        sleep 5
        i=$((i + 5))
    done
    err "timeout de ${BOOT_TIMEOUT}s esperando sys.boot_completed=1 (mira $LOGFILE)"
    exit 1
}

do_stop() {
    pid="$(leer_pid)"
    estado="$(adb_estado)"
    if [ "$estado" = "ausente" ] && { [ -z "$pid" ] || ! pid_vivo "$pid"; }; then
        rm -f "$PIDFILE"
        msg "el emulador no estaba en marcha"
        return 0
    fi
    if [ "$estado" != "ausente" ]; then
        msg "pidiendo apagado limpio vía 'adb -s $SERIAL emu kill'…"
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    elif [ -n "$pid" ]; then
        msg "adb no ve el dispositivo; terminando PID $pid…"
    fi
    # Espera a que muera el proceso conocido (máx 60 s); solo entonces SIGKILL.
    i=0
    while [ -n "$pid" ] && pid_vivo "$pid" && [ "$i" -lt 60 ]; do
        sleep 2
        i=$((i + 2))
    done
    if [ -n "$pid" ] && pid_vivo "$pid"; then
        msg "sigue vivo tras 60 s; SIGTERM a $pid…"
        kill "$pid" 2>/dev/null || true
        sleep 5
    fi
    if [ -n "$pid" ] && pid_vivo "$pid"; then
        msg "sigue vivo; SIGKILL a $pid…"
        kill -9 "$pid" 2>/dev/null || true
        sleep 2
    fi
    if [ -n "$pid" ] && pid_vivo "$pid"; then
        err "no se pudo detener el PID $pid"
        exit 1
    fi
    rm -f "$PIDFILE"
    msg "emulador detenido"
}

do_status() {
    pid="$(leer_pid)"
    if [ -n "$pid" ] && pid_vivo "$pid"; then
        msg "proceso: en marcha (PID $pid)"
    elif [ -n "$pid" ]; then
        msg "proceso: PID $pid guardado pero no existe (rancio)"
    else
        msg "proceso: no hay PID guardado"
    fi
    estado="$(adb_estado)"
    msg "adb ($SERIAL): $estado"
    if [ "$estado" = "device" ]; then
        boot="$(boot_completed)"
        msg "boot: sys.boot_completed=${boot:-desconocido}"
        [ "$boot" = "1" ]
    else
        msg "boot: desconocido (sin adb)"
        return 1
    fi
}

do_screencap() {
    out="${1:-$EMU_DIR/screencap-$(date +%Y%m%d-%H%M%S).png}"
    mkdir -p "$(dirname "$out")"
    if [ "$(adb_estado)" != "device" ]; then
        err "el dispositivo $SERIAL no responde en adb"
        exit 1
    fi
    "$ADB" -s "$SERIAL" exec-out screencap -p >"$out" 2>/dev/null
    if [ ! -s "$out" ]; then
        err "captura vacía: $out"
        exit 1
    fi
    if head -c 8 "$out" | od -An -tx1 | grep -q "89 50 4e 47 0d 0a 1a 0a"; then
        msg "captura guardada: $out ($(stat -c%s "$out") bytes)"
    else
        err "el fichero no parece un PNG válido: $out"
        exit 1
    fi
}

uso() {
    err "uso: $0 start|stop|status|screencap [ruta-png]"
    exit 2
}

case "${1:-}" in
    start) do_start ;;
    stop) do_stop ;;
    status) do_status ;;
    screencap) do_screencap "${2:-}" ;;
    *) uso ;;
esac
