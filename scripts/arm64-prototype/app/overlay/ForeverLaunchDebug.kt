package app.gamenative

/** Frozen per-APK lab policy: first window/render, then private authentication. */
object ForeverLaunchDebug {
    const val MEMORY_PATCHING_ENABLED = false
    const val SCOPED_NO_OPENGL = true
    const val GRAPHICS_PROBE_ENABLED = true
    const val PANVK_WINE_PROBE = true
    const val BUILTIN_SHADER_COMPILER = true
    const val COMPILER_PREFIX_REPAIR = true
}
