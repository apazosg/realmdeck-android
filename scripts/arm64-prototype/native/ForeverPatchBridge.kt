package com.wowforever.launcher

/**
 * Minimal production bridge for the automated ARM64 launcher.
 *
 * Both calls are blocking native work and must be dispatched by the app worker,
 * never by the UI thread. The app writes the canonical contract to its private
 * files directory and passes that path to inspectOwnedGame.
 */
object ForeverPatchBridge {
    init {
        System.loadLibrary("forever_patcher_jni")
    }

    /**
     * Returns JSON version 1 with `candidates: [{pid, startTicks}]`.
     * Candidates are same-UID descendants of this app and have an exact
     * executable path/mapping for the supplied PE AA64 executable.
     */
    @JvmStatic
    external fun findOwnedGameProcesses(executablePath: String): String

    /**
     * Performs one bounded read-only inspection, or one guarded patch pass when
     * apply is true. `startTicks` is the /proc stat starttime captured by find.
     */
    @JvmStatic
    external fun inspectOwnedGame(
        pid: Int,
        startTicks: Long,
        executablePath: String,
        contractPath: String,
        apply: Boolean,
    ): String

    /** Arms the bounded native SIGINT provenance record for one host/run. */
    @JvmStatic
    external fun armSignalTrace(
        outputPath: String,
        runId: String,
        bootId: String,
        expectedPid: Int,
        expectedStartTicks: Long,
    ): String

    /** Reports ownership of the currently installed SIGINT handler only. */
    @JvmStatic
    external fun signalTraceStatus(): String
}
