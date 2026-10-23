package app.gamenative

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A small, owned credential value. It is neither a data class nor renderable. */
class ForeverFunctionalAutologinCredentials(
    username: CharArray,
    password: CharArray,
) : AutoCloseable {
    private val lock = Any()
    private val username: CharArray
    private val password: CharArray
    private var closed = false

    init {
        validate(username, "username")
        validate(password, "password")
        this.username = username.copyOf()
        this.password = password.copyOf()
    }

    /** The callback receives temporary copies, which are wiped before it returns. */
    fun <T> useValues(block: (CharArray, CharArray) -> T): T {
        synchronized(lock) {
            checkOpen()
            var userCopy: CharArray? = null
            var passwordCopy: CharArray? = null
            return try {
                userCopy = username.copyOf()
                passwordCopy = password.copyOf()
                block(requireNotNull(userCopy), requireNotNull(passwordCopy))
            } finally {
                userCopy?.fill('\u0000')
                passwordCopy?.fill('\u0000')
            }
        }
    }

    /** Returns another owned value. The caller must close it. */
    fun copyForUse(): ForeverFunctionalAutologinCredentials = synchronized(lock) {
        checkOpen()
        ForeverFunctionalAutologinCredentials(username, password)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            username.fill('\u0000')
            password.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "ForeverFunctionalAutologinCredentials(REDACTED)"

    private fun checkOpen() {
        check(!closed) { "credentials are closed" }
    }

    companion object {
        const val MAX_CHARS = 128

        private fun validate(value: CharArray, field: String) {
            require(value.isNotEmpty() && value.size <= MAX_CHARS) { "$field has invalid length" }
            var index = 0
            while (index < value.size) {
                val character = value[index]
                require(!character.isISOControl()) { "$field contains a control character" }
                if (character.isHighSurrogate()) {
                    require(index + 1 < value.size && value[index + 1].isLowSurrogate()) {
                        "$field contains invalid characters"
                    }
                    index++
                } else {
                    require(!character.isLowSurrogate()) { "$field contains invalid characters" }
                }
                index++
            }
        }
    }
}

/** Only static, non-secret status is exposed to settings and diagnostics. */
enum class ForeverFunctionalAutologinStatus {
    EMPTY,
    MEMORY_ONLY,
    PERSISTED,
    REENTRY_REQUIRED,
}

data class ForeverFunctionalAutologinSettingsSummary(
    val persistenceAvailable: Boolean,
    val remembered: Boolean,
    val sessionAvailable: Boolean,
    val requiresReentry: Boolean,
    val status: ForeverFunctionalAutologinStatus,
)

class ForeverFunctionalAutologinStoreFailure internal constructor(
    val code: String,
) : IllegalStateException(code)

/** The crypto seam keeps JVM tests independent of Android Keystore. */
interface ForeverFunctionalAutologinCryptoBackend {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ForeverFunctionalAutologinEncryptedValue
    fun decrypt(value: ForeverFunctionalAutologinEncryptedValue, aad: ByteArray): ByteArray
    fun deleteKey()
    fun hasKey(): Boolean
}

class ForeverFunctionalAutologinEncryptedValue(
    val iv: ByteArray,
    val ciphertextAndTag: ByteArray,
)

/** Android Keystore AES/GCM backend. It never creates a key while decrypting. */
class ForeverFunctionalAutologinAndroidKeystoreBackend private constructor(
    private val keyAlias: String,
) : ForeverFunctionalAutologinCryptoBackend {
    constructor() : this(PRODUCTION_KEY_ALIAS)

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ForeverFunctionalAutologinEncryptedValue {
        val key = loadKey(create = true) ?: throw GeneralSecurityException("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore keys generated with the default randomized-encryption
        // policy must supply the IV themselves. The provider-generated IV is
        // authenticated and is persisted alongside the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv.copyOf()
        if (iv.size != ForeverFunctionalAutologinStore.IV_BYTES) {
            throw GeneralSecurityException("invalid provider iv")
        }
        cipher.updateAAD(aad)
        return ForeverFunctionalAutologinEncryptedValue(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(value: ForeverFunctionalAutologinEncryptedValue, aad: ByteArray): ByteArray {
        if (value.iv.size != ForeverFunctionalAutologinStore.IV_BYTES) {
            throw GeneralSecurityException("invalid iv")
        }
        val key = loadKey(create = false) ?: throw GeneralSecurityException("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(ForeverFunctionalAutologinStore.TAG_BITS, value.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(value.ciphertextAndTag)
    }

    override fun deleteKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    override fun hasKey(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").let { store -> store.load(null); store.containsAlias(keyAlias) }

    private fun loadKey(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null || !create) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PRODUCTION_KEY_ALIAS = "forever_functional_autologin_v1"
        private const val PROVIDER_VALIDATION_KEY_ALIAS =
            "forever_functional_autologin_provider_validation_v1"

        internal fun forProviderValidation(): ForeverFunctionalAutologinAndroidKeystoreBackend =
            ForeverFunctionalAutologinAndroidKeystoreBackend(PROVIDER_VALIDATION_KEY_ALIAS)
    }
}

/**
 * Process-session credentials plus an opt-in encrypted vault. `save` and
 * destructive operations are synchronous; callers should invoke them off the
 * UI thread when the vault is enabled.
 */
class ForeverFunctionalAutologinStore(
    context: Context,
    private val crypto: ForeverFunctionalAutologinCryptoBackend =
        ForeverFunctionalAutologinAndroidKeystoreBackend(),
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var session: ForeverFunctionalAutologinCredentials? = null
    private var status = ForeverFunctionalAutologinStatus.EMPTY

    fun save(credentials: ForeverFunctionalAutologinCredentials, remember: Boolean) {
        synchronized(lock) {
            var candidate: ForeverFunctionalAutologinCredentials? = null
            try {
                credentials.useValues { username, password ->
                    candidate = ForeverFunctionalAutologinCredentials(username, password)
                    if (remember) {
                        val payload = encodePayload(username, password)
                        try {
                            val encrypted = crypto.encrypt(payload, associatedData())
                            writeEncrypted(encrypted)
                        } finally {
                            payload.fill(0)
                        }
                    } else {
                        clearPersistedLocked()
                    }
                    session?.close()
                    session = candidate
                    candidate = null
                    status = if (remember) {
                        ForeverFunctionalAutologinStatus.PERSISTED
                    } else {
                        ForeverFunctionalAutologinStatus.MEMORY_ONLY
                    }
                }
            } catch (error: IllegalStateException) {
                candidate?.close()
                failClosedLocked()
                if (error is ForeverFunctionalAutologinStoreFailure) throw error
                throw ForeverFunctionalAutologinStoreFailure(
                    if (remember) "PERSISTENCE_WRITE_FAILED" else "PERSISTENCE_CLEAR_FAILED",
                )
            } catch (_: Exception) {
                candidate?.close()
                failClosedLocked()
                throw ForeverFunctionalAutologinStoreFailure(
                    if (remember) "PERSISTENCE_WRITE_FAILED" else "PERSISTENCE_CLEAR_FAILED",
                )
            }
        }
    }

    /** Returns a separately owned session copy. The caller must close it. */
    fun acquire(): ForeverFunctionalAutologinCredentials? = synchronized(lock) {
        if (status == ForeverFunctionalAutologinStatus.REENTRY_REQUIRED) return@synchronized null
        session?.let { return@synchronized it.copyForUse() }
        val file = try {
            persistedFile()
        } catch (_: Exception) {
            failClosedLocked()
            return@synchronized null
        }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val keyPresent = try {
                crypto.hasKey()
            } catch (_: Exception) {
                failClosedLocked()
                return@synchronized null
            }
            if (keyPresent) {
                failClosedLocked()
            } else if (status != ForeverFunctionalAutologinStatus.REENTRY_REQUIRED) {
                status = ForeverFunctionalAutologinStatus.EMPTY
            }
            return@synchronized null
        }
        try {
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            val bytes = readBounded(file)
            val encrypted = decodeEnvelope(bytes)
            val plaintext = crypto.decrypt(encrypted, associatedData())
            try {
                val restored = decodePayload(plaintext)
                session = restored
                status = ForeverFunctionalAutologinStatus.PERSISTED
                return@synchronized restored.copyForUse()
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            failClosedLocked()
            return@synchronized null
        }
    }

    /** Removes this store's encrypted payload, key, and process session. */
    fun forget() = synchronized(lock) {
        var failure = false
        try {
            clearPersistedLocked()
        } catch (_: Exception) {
            failure = true
        } finally {
            session?.close()
            session = null
            status = if (failure) {
                ForeverFunctionalAutologinStatus.REENTRY_REQUIRED
            } else {
                ForeverFunctionalAutologinStatus.EMPTY
            }
        }
        if (failure) throw ForeverFunctionalAutologinStoreFailure("PERSISTENCE_CLEAR_FAILED")
    }

    /** Explicit spelling for the settings action; it has the same destructive semantics. */
    fun disableAndClear() = forget()

    fun settingsSummary(): ForeverFunctionalAutologinSettingsSummary = synchronized(lock) {
        var keyPresent = false
        val available = try {
            vaultDirectory()
            keyPresent = crypto.hasKey()
            true
        } catch (_: Exception) {
            failClosedLocked()
            false
        }
        var remembered = if (available) runCatching {
            val file = persistedFile()
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false) else false
        if (status == ForeverFunctionalAutologinStatus.REENTRY_REQUIRED) {
            session?.close()
            session = null
            // A failed destructive operation is durable fail-closed state. The
            // old file/key may still exist, but they are never reported or read
            // until a new save succeeds explicitly.
            remembered = false
        } else if (available && keyPresent != remembered) {
            failClosedLocked()
            // A file/key mismatch is not a remembered, usable credential set,
            // even if an I/O failure prevents removing the old ciphertext.
            remembered = false
        }
        ForeverFunctionalAutologinSettingsSummary(
            persistenceAvailable = available,
            remembered = remembered,
            sessionAvailable = session != null,
            requiresReentry = status == ForeverFunctionalAutologinStatus.REENTRY_REQUIRED,
            status = status,
        )
    }

    private fun associatedData(): ByteArray =
        ("forever-functional-autologin\u0000" + appContext.packageName + "\u0000schema=1")
            .toByteArray(StandardCharsets.UTF_8)

    private fun writeEncrypted(value: ForeverFunctionalAutologinEncryptedValue) {
        require(value.iv.size == IV_BYTES && value.ciphertextAndTag.size in TAG_BYTES..MAX_PERSISTED_CIPHERTEXT)
        val directory = vaultDirectory()
        val target = File(directory, PAYLOAD_FILE)
        require(!Files.isSymbolicLink(target.toPath()))
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val envelope = encodeEnvelope(value)
        val temporary = File(directory, TEMP_FILE)
        try {
            java.nio.channels.FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(envelope)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            makePrivate(temporary)
            require(!Files.isSymbolicLink(target.toPath()))
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            require(!Files.isSymbolicLink(target.toPath()))
            makePrivate(target)
        } finally {
            envelope.fill(0)
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun clearPersistedLocked() {
        var fileFailure = false
        try {
            deletePayloadAndTemporaryLocked()
        } catch (_: Exception) {
            fileFailure = true
        }
        var keyFailure = false
        try {
            crypto.deleteKey()
        } catch (_: Exception) {
            keyFailure = true
        }
        if (fileFailure || keyFailure) {
            throw ForeverFunctionalAutologinStoreFailure("PERSISTENCE_CLEAR_FAILED")
        }
    }

    private fun failClosedLocked() {
        session?.close()
        session = null
        status = ForeverFunctionalAutologinStatus.REENTRY_REQUIRED
        runCatching { deletePayloadAndTemporaryLocked() }
        runCatching { crypto.deleteKey() }
    }

    private fun deletePayloadAndTemporaryLocked() {
        val file = persistedFile()
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file.toPath()))
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            Files.delete(file.toPath())
        }
    }

    private fun vaultDirectory(): File {
        val root = appContext.noBackupFilesDir
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        val directory = File(root, VAULT_DIRECTORY)
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        } else {
            require(directory.mkdir())
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        cleanupOwnedTemporary(directory)
        return directory
    }

    private fun cleanupOwnedTemporary(directory: File) {
        val entries = directory.listFiles() ?: throw IllegalStateException("vault listing unavailable")
        for (entry in entries) {
            when (entry.name) {
                PAYLOAD_FILE -> Unit
                TEMP_FILE -> {
                    require(!Files.isSymbolicLink(entry.toPath()))
                    require(Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS))
                    require(entry.length() <= MAX_PERSISTED_BYTES)
                    Files.delete(entry.toPath())
                }
                else -> throw IllegalStateException("unknown vault entry")
            }
        }
    }

    private fun persistedFile(): File = File(vaultDirectory(), PAYLOAD_FILE)

    private fun makePrivate(file: File) {
        if (runCatching { android.system.Os.chmod(file.absolutePath, 0x180); true }.getOrDefault(false)) return
        require(file.setReadable(false, false))
        require(file.setReadable(true, true))
        require(file.setWritable(false, false))
        require(file.setWritable(true, true))
        file.setExecutable(false, false)
    }

    private fun readBounded(file: File): ByteArray {
        require(file.length() in ENVELOPE_MIN_BYTES..MAX_PERSISTED_BYTES)
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream(MAX_PERSISTED_BYTES)
            val buffer = ByteArray(256)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_PERSISTED_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val PAYLOAD_FILE = "credentials.bin"
        private const val TEMP_FILE = ".credentials.bin.tmp"
        private const val VAULT_DIRECTORY = "forever-autologin"
        private const val PAYLOAD_VERSION = 1
        private const val ENVELOPE_VERSION = 1
        private const val MAX_PAYLOAD_BYTES = 4 * 1024
        private const val MAX_PERSISTED_CIPHERTEXT = MAX_PAYLOAD_BYTES + TAG_BYTES
        private const val ENVELOPE_HEADER_BYTES = 4 + 1 + IV_BYTES
        private const val ENVELOPE_MIN_BYTES = ENVELOPE_HEADER_BYTES + TAG_BYTES
        private const val MAX_PERSISTED_BYTES = ENVELOPE_HEADER_BYTES + MAX_PERSISTED_CIPHERTEXT
        private val ENVELOPE_MAGIC = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
        private val PAYLOAD_MAGIC = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

        private fun encodeEnvelope(value: ForeverFunctionalAutologinEncryptedValue): ByteArray =
            ByteBuffer.allocate(ENVELOPE_HEADER_BYTES + value.ciphertextAndTag.size).apply {
                put(ENVELOPE_MAGIC)
                put(ENVELOPE_VERSION.toByte())
                put(value.iv)
                put(value.ciphertextAndTag)
            }.array()

        private fun decodeEnvelope(bytes: ByteArray): ForeverFunctionalAutologinEncryptedValue {
            require(bytes.size in ENVELOPE_MIN_BYTES..MAX_PERSISTED_BYTES)
            val input = ByteBuffer.wrap(bytes)
            val magic = ByteArray(ENVELOPE_MAGIC.size).also(input::get)
            require(magic.contentEquals(ENVELOPE_MAGIC) && input.get().toInt() == ENVELOPE_VERSION)
            val iv = ByteArray(IV_BYTES).also(input::get)
            val ciphertext = ByteArray(input.remaining()).also(input::get)
            require(ciphertext.size in TAG_BYTES..MAX_PERSISTED_CIPHERTEXT)
            return ForeverFunctionalAutologinEncryptedValue(iv, ciphertext)
        }

        private fun encodePayload(username: CharArray, password: CharArray): ByteArray {
            var userBytes: ByteArray? = null
            var passwordBytes: ByteArray? = null
            try {
                userBytes = encodeUtf8(username)
                passwordBytes = encodeUtf8(password)
                val userOutput = requireNotNull(userBytes)
                val passwordOutput = requireNotNull(passwordBytes)
                val total = PAYLOAD_MAGIC.size + 1 + 2 + 2 + userOutput.size + passwordOutput.size
                require(total <= MAX_PAYLOAD_BYTES && userOutput.size <= 0xffff && passwordOutput.size <= 0xffff)
                return ByteBuffer.allocate(total).apply {
                    put(PAYLOAD_MAGIC)
                    put(PAYLOAD_VERSION.toByte())
                    putShort(userOutput.size.toShort())
                    putShort(passwordOutput.size.toShort())
                    put(userOutput)
                    put(passwordOutput)
                }.array()
            } finally {
                userBytes?.fill(0)
                passwordBytes?.fill(0)
            }
        }

        private fun decodePayload(bytes: ByteArray): ForeverFunctionalAutologinCredentials {
            require(bytes.size in 10..MAX_PAYLOAD_BYTES)
            val input = ByteBuffer.wrap(bytes)
            val magic = ByteArray(PAYLOAD_MAGIC.size).also(input::get)
            require(magic.contentEquals(PAYLOAD_MAGIC) && input.get().toInt() == PAYLOAD_VERSION)
            val usernameLength = input.short.toInt() and 0xffff
            val passwordLength = input.short.toInt() and 0xffff
            require(usernameLength > 0 && passwordLength > 0)
            require(usernameLength + passwordLength == input.remaining())
            var username: CharArray? = null
            var password: CharArray? = null
            return try {
                username = decodeUtf8(input, usernameLength)
                password = decodeUtf8(input, passwordLength)
                ForeverFunctionalAutologinCredentials(requireNotNull(username), requireNotNull(password))
            } finally {
                username?.fill('\u0000')
                password?.fill('\u0000')
            }
        }

        private fun encodeUtf8(chars: CharArray): ByteArray {
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            var encoded: ByteBuffer? = null
            return try {
                encoded = encoder.encode(CharBuffer.wrap(chars))
                val output = requireNotNull(encoded)
                val result = ByteArray(output.remaining())
                output.get(result)
                result
            } finally {
                encoded?.let(::wipeByteBuffer)
            }
        }

        private fun decodeUtf8(input: ByteBuffer, length: Int): CharArray {
            val bytes = ByteArray(length)
            var decoded: CharBuffer? = null
            input.get(bytes)
            return try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoded = decoder.decode(ByteBuffer.wrap(bytes))
                val output = requireNotNull(decoded)
                val chars = CharArray(output.remaining())
                output.get(chars)
                chars
            } finally {
                bytes.fill(0)
                decoded?.let(::wipeCharBuffer)
            }
        }

        private fun wipeByteBuffer(buffer: ByteBuffer) {
            if (buffer.hasArray()) {
                buffer.array().fill(0)
            } else {
                val duplicate = buffer.duplicate()
                duplicate.clear()
                while (duplicate.hasRemaining()) duplicate.put(0)
            }
        }

        private fun wipeCharBuffer(buffer: CharBuffer) {
            if (buffer.hasArray()) {
                buffer.array().fill('\u0000')
            } else {
                val duplicate = buffer.duplicate()
                duplicate.clear()
                while (duplicate.hasRemaining()) duplicate.put('\u0000')
            }
        }
    }
}

/** Process lifetime cache. It retains only application contexts, never Activities. */
object ForeverFunctionalAutologinStores {
    private val stores = ConcurrentHashMap<String, ForeverFunctionalAutologinStore>()

    fun forApplication(context: Context): ForeverFunctionalAutologinStore {
        val applicationContext = context.applicationContext
        return stores.computeIfAbsent(applicationContext.packageName) {
            ForeverFunctionalAutologinStore(applicationContext)
        }
    }
}
