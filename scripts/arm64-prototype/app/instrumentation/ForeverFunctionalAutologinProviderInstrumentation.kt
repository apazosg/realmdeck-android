package app.gamenative

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Device-only provider validation for the autologin vault. This deliberately
 * does not use the production store factory, settings, preferences, or UI.
 */
class ForeverFunctionalAutologinProviderInstrumentation : Instrumentation() {
    private var requestedPhase: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedPhase = arguments?.getString(ARG_PHASE)
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = runPhase(requestedPhase)
        finish(if (result.getBoolean(KEY_PASS)) RESULT_OK else RESULT_CANCELED, result)
    }

    private fun runPhase(phase: String?): Bundle {
        if (phase == null || phase !in ALLOWED_PHASES) return result(false, "INVALID_PHASE")

        return try {
            when (phase) {
                PHASE_WRITE -> runWrite()
                PHASE_RESTORE_AND_FORGET -> runRestoreAndForget()
                PHASE_CLEANUP -> runCleanup()
                else -> result(false, "INVALID_PHASE")
            }
        } catch (_: Throwable) {
            runCatching { cleanupScratch() }
            result(false, failureCode(phase))
        }
    }

    private fun failureCode(phase: String): String = when (phase) {
        PHASE_WRITE -> "WRITE_FAILED"
        PHASE_RESTORE_AND_FORGET -> "RESTORE_FORGET_FAILED"
        PHASE_CLEANUP -> "CLEANUP_FAILED"
        else -> "INVALID_PHASE"
    }

    private fun runWrite(): Bundle {
        val scratch = scratchContext()
        val backend = ForeverFunctionalAutologinAndroidKeystoreBackend.forProviderValidation()
        val store = ForeverFunctionalAutologinStore(scratch, backend)
        syntheticCredentials().use { credentials ->
            store.save(credentials, remember = true)
            val firstEnvelope = payloadFile(scratch).readBytes()
            requireNotPlaintext(firstEnvelope)

            store.save(credentials, remember = true)
            val secondEnvelope = payloadFile(scratch).readBytes()
            requireNotPlaintext(secondEnvelope)

            val firstIv = envelopeIv(firstEnvelope)
            val secondIv = envelopeIv(secondEnvelope)
            require(firstIv.size == PROVIDER_IV_BYTES)
            require(secondIv.size == PROVIDER_IV_BYTES)
            require(!firstIv.contentEquals(secondIv))

            return result(true, "WRITE_PASS").apply {
                putBoolean(KEY_CIPHERTEXT_NOT_PLAINTEXT, true)
                putBoolean(KEY_FRESH_PROVIDER_IV, true)
                putBoolean(KEY_PROVIDER_IV_12, true)
            }
        }
    }

    private fun runRestoreAndForget(): Bundle {
        val scratch = scratchContext()
        try {
            val backend = ForeverFunctionalAutologinAndroidKeystoreBackend.forProviderValidation()
            val store = ForeverFunctionalAutologinStore(scratch, backend)
            val restored = store.acquire() ?: error("missing synthetic validation state")
            val valuesMatch = restored.use {
                var match = false
                it.useValues { username, password ->
                    match = username.contentEquals(SYNTHETIC_USERNAME) &&
                        password.contentEquals(SYNTHETIC_PASSWORD)
                }
                match
            }
            require(valuesMatch)

            store.forget()
            require(!payloadFile(scratch).exists())
            require(!backend.hasKey())
            require(store.acquire() == null)

            val corruptionResult = verifyCorruptionRequiresReentry(scratch)
            cleanupScratch()
            requireScratchIsEmpty()
            return result(true, "RESTORE_FORGET_PASS").apply {
                putBoolean(KEY_VALUES_MATCH, true)
                putBoolean(KEY_CORRUPTION_REENTRY, corruptionResult)
                putBoolean(KEY_CLEANUP_EMPTY, true)
            }
        } catch (failure: Throwable) {
            runCatching { cleanupScratch() }
            throw failure
        }
    }

    private fun verifyCorruptionRequiresReentry(scratch: Context): Boolean {
        val backend = ForeverFunctionalAutologinAndroidKeystoreBackend.forProviderValidation()
        val writer = ForeverFunctionalAutologinStore(scratch, backend)
        syntheticCredentials().use { writer.save(it, remember = true) }

        val payload = payloadFile(scratch)
        val corrupted = payload.readBytes()
        require(corrupted.isNotEmpty())
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        payload.writeBytes(corrupted)

        val freshBackend = ForeverFunctionalAutologinAndroidKeystoreBackend.forProviderValidation()
        val freshStore = ForeverFunctionalAutologinStore(scratch, freshBackend)
        require(freshStore.acquire() == null)
        require(freshStore.settingsSummary().requiresReentry)
        require(!payload.exists())
        require(!freshBackend.hasKey())
        return true
    }

    private fun runCleanup(): Bundle {
        cleanupScratch()
        requireScratchIsEmpty()
        return result(true, "CLEANUP_PASS").apply {
            putBoolean(KEY_CLEANUP_EMPTY, true)
            putBoolean(KEY_PROVIDER_KEY_ABSENT, true)
        }
    }

    private fun cleanupScratch() {
        val scratch = scratchContext()
        val backend = ForeverFunctionalAutologinAndroidKeystoreBackend.forProviderValidation()
        var fileFailure: Throwable? = null
        try {
            val vault = vaultDirectory(scratch)
            if (Files.exists(vault.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(vault.toPath(), LinkOption.NOFOLLOW_LINKS))
                val entries = vault.listFiles() ?: error("scratch listing unavailable")
                for (entry in entries) {
                    require(entry.name == PAYLOAD_FILE || entry.name == TEMP_FILE)
                    require(!Files.isSymbolicLink(entry.toPath()))
                    require(Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS))
                    require(entry.delete())
                }
            }
        } catch (failure: Throwable) {
            fileFailure = failure
        }

        var keyFailure: Throwable? = null
        try {
            backend.deleteKey()
        } catch (failure: Throwable) {
            keyFailure = failure
        }
        if (fileFailure != null || keyFailure != null) error("scratch cleanup failed")
    }

    private fun requireScratchIsEmpty() {
        val scratch = scratchContext()
        val vault = vaultDirectory(scratch)
        val entries = vault.listFiles() ?: error("scratch listing unavailable")
        require(entries.isEmpty())
    }

    private fun scratchContext(): Context {
        val base = targetContext
        val baseNoBackup = base.noBackupFilesDir
        require(Files.isDirectory(baseNoBackup.toPath(), LinkOption.NOFOLLOW_LINKS))
        val root = File(baseNoBackup, SCRATCH_DIRECTORY)
        if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        } else {
            require(root.mkdir())
        }
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        Os.chmod(root.absolutePath, PRIVATE_DIRECTORY_MODE)
        require((Os.stat(root.absolutePath).st_mode and MODE_MASK) == PRIVATE_DIRECTORY_MODE)

        val entries = root.listFiles() ?: error("scratch listing unavailable")
        for (entry in entries) require(entry.name == VAULT_DIRECTORY)
        return ScratchContext(base, root)
    }

    private fun vaultDirectory(context: Context): File {
        val root = context.noBackupFilesDir
        val vault = File(root, VAULT_DIRECTORY)
        if (!Files.exists(vault.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(vault.mkdir())
        }
        require(Files.isDirectory(vault.toPath(), LinkOption.NOFOLLOW_LINKS))
        return vault
    }

    private fun payloadFile(context: Context): File = File(vaultDirectory(context), PAYLOAD_FILE)

    private fun envelopeIv(envelope: ByteArray): ByteArray {
        require(envelope.size >= ENVELOPE_HEADER_BYTES)
        return envelope.copyOfRange(ENVELOPE_IV_OFFSET, ENVELOPE_IV_OFFSET + PROVIDER_IV_BYTES)
    }

    private fun requireNotPlaintext(envelope: ByteArray) {
        val bytes = envelope
        require(!bytes.containsSequence(SYNTHETIC_USERNAME_BYTES))
        require(!bytes.containsSequence(SYNTHETIC_PASSWORD_BYTES))
    }

    private fun syntheticCredentials() =
        ForeverFunctionalAutologinCredentials(SYNTHETIC_USERNAME, SYNTHETIC_PASSWORD)

    private fun result(pass: Boolean, code: String) = Bundle().apply {
        putString(KEY_STATUS, if (pass) "PASS" else "FAIL")
        putString(KEY_CODE, code)
        putBoolean(KEY_PASS, pass)
    }

    private class ScratchContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = root

        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val ARG_PHASE = "phase"
        private const val PHASE_WRITE = "write"
        private const val PHASE_RESTORE_AND_FORGET = "restore-and-forget"
        private const val PHASE_CLEANUP = "cleanup"
        private val ALLOWED_PHASES = setOf(PHASE_WRITE, PHASE_RESTORE_AND_FORGET, PHASE_CLEANUP)

        private const val RESULT_OK = -1
        private const val RESULT_CANCELED = 0
        private const val KEY_STATUS = "status"
        private const val KEY_CODE = "code"
        private const val KEY_PASS = "pass"
        private const val KEY_CIPHERTEXT_NOT_PLAINTEXT = "ciphertext_not_plaintext"
        private const val KEY_FRESH_PROVIDER_IV = "fresh_provider_iv"
        private const val KEY_PROVIDER_IV_12 = "provider_iv_12"
        private const val KEY_VALUES_MATCH = "values_match"
        private const val KEY_CORRUPTION_REENTRY = "corruption_requires_reentry"
        private const val KEY_CLEANUP_EMPTY = "scratch_empty"
        private const val KEY_PROVIDER_KEY_ABSENT = "provider_key_absent"

        private const val SCRATCH_DIRECTORY = "forever-autologin-provider-validation"
        private const val VAULT_DIRECTORY = "forever-autologin"
        private const val PAYLOAD_FILE = "credentials.bin"
        private const val TEMP_FILE = ".credentials.bin.tmp"
        private const val PRIVATE_DIRECTORY_MODE = 0x1c0
        private const val MODE_MASK = 0x1ff
        private const val ENVELOPE_HEADER_BYTES = 17
        private const val ENVELOPE_IV_OFFSET = 5
        private const val PROVIDER_IV_BYTES = 12
        private val SYNTHETIC_USERNAME = "provider-validation-only-user".toCharArray()
        private val SYNTHETIC_PASSWORD = "provider-validation-only-password".toCharArray()
        private val SYNTHETIC_USERNAME_BYTES =
            String(SYNTHETIC_USERNAME).toByteArray(StandardCharsets.UTF_8)
        private val SYNTHETIC_PASSWORD_BYTES =
            String(SYNTHETIC_PASSWORD).toByteArray(StandardCharsets.UTF_8)
    }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.isEmpty() || sequence.size > size) return false
    for (start in 0..(size - sequence.size)) {
        var matches = true
        for (offset in sequence.indices) {
            if (this[start + offset] != sequence[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}
