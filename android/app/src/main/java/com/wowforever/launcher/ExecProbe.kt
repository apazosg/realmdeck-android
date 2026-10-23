package com.wowforever.launcher

/** Sonda execve (experimento targetSdk): ¿puede la app ejecutar un binario
 * que ella misma escribe? Implementada en `app/src/main/cpp/exec_probe.cpp`
 * (`libexec_probe.so`, empaquetada vía `jniLibs`, sin CMake). */
object ExecProbe {
    init {
        System.loadLibrary("exec_probe")
    }

    /** Ejecuta la sonda sobre los cuatro directorios y dos controles.
     * Devuelve el informe multi-línea (también va a logcat, tag ForeverJNI).
     * Llamar fuera del hilo UI (fork + waits de hasta ~30 s en total). */
    @JvmStatic
    external fun runExecProbe(
        filesDir: String,
        codeCacheDir: String,
        extDir: String?,
        nativeLibDir: String,
    ): String
}
