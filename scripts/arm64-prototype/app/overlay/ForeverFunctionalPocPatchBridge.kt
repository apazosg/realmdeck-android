package app.gamenative

/**
 * Bridge for the unmanaged functional PoC gate: polling observes, applying writes.
 *
 * `nativePoll` is strictly read-only: it never writes child memory and its
 * results must stay logged as read-only `patch_scan` events. `nativeApply`
 * is the only writer: called with just the lease token, it independently
 * revalidates the current candidate, process identity, mapping, and lease,
 * then writes only the 32 group-8 public-key bytes. It takes no
 * address/PID/key from the caller, so there is nothing to parse or expose.
 * JSON status is authoritative: APPLIED (bytesWritten 32), ALREADY_PATCHED
 * (0), DENIED (0), or ERROR (actual, possibly partial, bytes).
 */
object ForeverFunctionalPocPatchBridge {
    init {
        System.loadLibrary("forever_patcher_jni")
    }

    /** Starts one native mutex lease; capture is a separate explicit opt-in. */
    @JvmStatic
    external fun nativeBegin(executablePath: String, nonce: String, contractPath: String, codeCaptureEnabled: Boolean): String

    /** Performs one bounded read-only identity/store scan. */
    @JvmStatic
    external fun nativePoll(token: String): String

    /**
     * Applies the natively retained candidate for this lease token only.
     * Revalidates everything before writing; writes at most the 32 group-8
     * public-key bytes. Never called by the capture path.
     */
    @JvmStatic
    external fun nativeApply(token: String): String

    /** Cancels the lease and restores the app's previous child-subreaper bit. */
    @JvmStatic
    external fun nativeEnd(token: String): String
}
