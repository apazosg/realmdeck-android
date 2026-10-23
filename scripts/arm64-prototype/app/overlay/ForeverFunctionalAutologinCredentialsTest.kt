package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalAutologinCredentialsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vaultRoot().deleteRecursively()
    }

    @After
    fun tearDown() {
        vaultRoot().deleteRecursively()
    }

    @Test
    fun holderOwnsCopiesBorrowsWipedValuesAndCloses() {
        val username = "synthetic-user".toCharArray()
        val password = "synthetic-password".toCharArray()
        val credentials = ForeverFunctionalAutologinCredentials(username, password)
        username.fill('x')
        password.fill('x')

        var observed: List<String>? = null
        credentials.useValues { user, pass -> observed = listOf(String(user), String(pass)) }
        assertEquals(listOf("synthetic-user", "synthetic-password"), observed)
        var borrowedUser: CharArray? = null
        var borrowedPassword: CharArray? = null
        assertThrows(IllegalArgumentException::class.java) {
            credentials.useValues { user, pass ->
                borrowedUser = user
                borrowedPassword = pass
                throw IllegalArgumentException("synthetic callback failure")
            }
        }
        assertTrue(borrowedUser!!.all { it == '\u0000' })
        assertTrue(borrowedPassword!!.all { it == '\u0000' })
        assertTrue(credentials.toString().contains("REDACTED"))
        assertFalse(credentials.toString().contains("synthetic"))

        val copy = credentials.copyForUse()
        credentials.close()
        assertThrows(IllegalStateException::class.java) { credentials.copyForUse() }
        copy.close()
    }

    @Test
    fun controlsNewlinesAndOversizedValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ForeverFunctionalAutologinCredentials("user\n".toCharArray(), "password".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverFunctionalAutologinCredentials(CharArray(129) { 'u' }, "password".toCharArray())
        }
    }

    @Test
    fun persistenceIsEncryptedUsesFreshIvAndBindsApplicationAad() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        val first = payloadFile().readBytes()
        store.save(credentials, remember = true)
        val second = payloadFile().readBytes()

        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.UTF_8).contains("synthetic-password"))
        assertEquals(2, backend.aads.size)
        assertTrue(String(backend.aads.last(), Charsets.UTF_8).contains(context.packageName))
        assertThrows(Exception::class.java) {
            backend.decrypt(requireNotNull(backend.lastValue), "wrong-aad".toByteArray())
        }
        credentials.close()
    }

    @Test
    fun encryptedValueSurvivesFreshStoreButNotProcessMemory() {
        val backend = JceFakeBackend()
        val firstStore = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        firstStore.save(credentials, remember = true)
        credentials.close()

        val freshStore = ForeverFunctionalAutologinStore(context, backend)
        assertFalse(freshStore.settingsSummary().sessionAvailable)
        val restored = freshStore.acquire()
        requireNotNull(restored).useValues { user, pass ->
            assertEquals("synthetic-user", String(user))
            assertEquals("synthetic-password", String(pass))
        }
    }

    @Test
    fun bitFlipRequiresReentryAndRemovesCiphertextAndKey() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()
        val bytes = payloadFile().readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        payloadFile().writeBytes(bytes)

        val corruptStore = ForeverFunctionalAutologinStore(context, backend)
        assertNull(corruptStore.acquire())
        assertFalse(payloadFile().exists())
        assertFalse(backend.hasKey())
        assertTrue(corruptStore.settingsSummary().requiresReentry)
    }

    @Test
    fun missingKeySummaryRequiresReentryAndClearsCachedCredentialsBeforeAcquire() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        credentials().use { store.save(it, remember = true) }
        backend.deleteKey()

        val summary = store.settingsSummary()
        assertTrue(summary.requiresReentry)
        assertFalse(summary.remembered)
        assertFalse(summary.sessionAvailable)
        assertFalse(payloadFile().exists())
        assertNull(store.acquire())
    }

    @Test
    fun missingKeyAndOversizedEnvelopeHaveNoPlaintextFallback() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()

        val missingKeyStore = ForeverFunctionalAutologinStore(context, JceFakeBackend())
        assertNull(missingKeyStore.acquire())
        assertTrue(missingKeyStore.settingsSummary().requiresReentry)

        val secondBackend = JceFakeBackend()
        val secondStore = ForeverFunctionalAutologinStore(context, secondBackend)
        val secondCredentials = credentials()
        secondStore.save(secondCredentials, remember = true)
        secondCredentials.close()
        payloadFile().writeBytes(ByteArray(5 * 1024) { 7 })
        assertNull(ForeverFunctionalAutologinStore(context, secondBackend).acquire())
        assertFalse(payloadFile().exists())
    }

    @Test
    fun staleOwnedTemporaryIsRemovedButUnknownCollisionFailsClosed() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()
        val temporary = File(vaultRoot(), ".credentials.bin.tmp")
        temporary.writeBytes(ByteArray(32) { 4 })

        val freshStore = ForeverFunctionalAutologinStore(context, backend)
        requireNotNull(freshStore.acquire()).close()
        assertFalse(temporary.exists())

        File(vaultRoot(), "unknown-collision").writeBytes(ByteArray(1) { 5 })
        val blockedStore = ForeverFunctionalAutologinStore(context, backend)
        assertNull(blockedStore.acquire())
        assertFalse(backend.hasKey())
        assertTrue(File(vaultRoot(), "unknown-collision").exists())
    }

    @Test
    fun symlinkedPayloadIsNotFollowedAndKeyDeleteIsStillAttempted() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()
        val outside = File(context.cacheDir, "autologin-outside-fixture").also { it.writeBytes(byteArrayOf(9)) }
        java.nio.file.Files.delete(payloadFile().toPath())
        java.nio.file.Files.createSymbolicLink(payloadFile().toPath(), outside.toPath())

        assertThrows(ForeverFunctionalAutologinStoreFailure::class.java) { store.forget() }
        assertEquals(1, backend.deleteCalls)
        assertFalse(backend.hasKey())
        assertTrue(outside.exists())
        assertFalse(store.settingsSummary().sessionAvailable)
        assertTrue(store.settingsSummary().requiresReentry)
    }

    @Test
    fun malformedPasswordEncodingClearsPartialDecodeAndRequiresReentry() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()
        backend.plaintextOverride = byteArrayOf(
            'F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte(),
            1, 0, 1, 0, 1, 'u'.code.toByte(), 0xc3.toByte(),
        )

        val freshStore = ForeverFunctionalAutologinStore(context, backend)
        assertNull(freshStore.acquire())
        assertFalse(payloadFile().exists())
        assertFalse(backend.hasKey())
        assertTrue(freshStore.settingsSummary().requiresReentry)
    }

    @Test
    fun orphanedKeyWithoutPayloadRequiresReentryAndIsCleared() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        credentials.close()
        java.nio.file.Files.delete(payloadFile().toPath())

        val freshStore = ForeverFunctionalAutologinStore(context, backend)
        assertNull(freshStore.acquire())
        assertFalse(backend.hasKey())
        assertTrue(freshStore.settingsSummary().requiresReentry)
    }

    @Test
    fun failedForgetRemainsDurablyFailClosedUntilExplicitNewSave() {
        val backend = DeleteFailingBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        credentials().use { store.save(it, remember = true) }
        File(vaultRoot(), "unknown-collision").writeBytes(ByteArray(1) { 6 })

        assertThrows(ForeverFunctionalAutologinStoreFailure::class.java) { store.forget() }
        assertTrue(payloadFile().exists())
        assertTrue(backend.hasKey())
        assertEquals(1, backend.deleteCalls)

        val failedSummary = store.settingsSummary()
        assertTrue(failedSummary.requiresReentry)
        assertFalse(failedSummary.remembered)
        assertFalse(failedSummary.sessionAvailable)

        File(vaultRoot(), "unknown-collision").delete()
        val healedSummary = store.settingsSummary()
        assertTrue(healedSummary.requiresReentry)
        assertFalse(healedSummary.remembered)
        assertFalse(healedSummary.sessionAvailable)
        assertNull(store.acquire())

        credentials().use { store.save(it, remember = true) }
        assertFalse(store.settingsSummary().requiresReentry)
        assertTrue(store.settingsSummary().remembered)
    }

    @Test
    fun rememberOffClearsPersistedStateBeforePublishingMemorySession() {
        val backend = JceFakeBackend()
        val store = ForeverFunctionalAutologinStore(context, backend)
        val credentials = credentials()
        store.save(credentials, remember = true)
        store.save(credentials, remember = false)

        assertFalse(payloadFile().exists())
        assertFalse(backend.hasKey())
        assertTrue(store.settingsSummary().sessionAvailable)
        val acquired = requireNotNull(store.acquire())
        acquired.close()

        store.forget()
        assertFalse(store.settingsSummary().sessionAvailable)
        assertEquals(ForeverFunctionalAutologinStatus.EMPTY, store.settingsSummary().status)
        credentials.close()
    }

    private fun credentials() =
        ForeverFunctionalAutologinCredentials(
            "synthetic-user".toCharArray(),
            "synthetic-password".toCharArray(),
        )

    private fun vaultRoot() = File(context.noBackupFilesDir, "forever-autologin")
    private fun payloadFile() = File(vaultRoot(), "credentials.bin")

    private class JceFakeBackend : ForeverFunctionalAutologinCryptoBackend {
        private var key: SecretKeySpec? = null
        private val random = SecureRandom()
        val aads = mutableListOf<ByteArray>()
        var lastValue: ForeverFunctionalAutologinEncryptedValue? = null
        var plaintextOverride: ByteArray? = null
        var deleteCalls = 0

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ForeverFunctionalAutologinEncryptedValue {
            val activeKey = key ?: SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES").also { key = it }
            val iv = ByteArray(ForeverFunctionalAutologinStore.IV_BYTES).also(random::nextBytes)
            aads += aad.copyOf()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return ForeverFunctionalAutologinEncryptedValue(iv, cipher.doFinal(plaintext)).also {
                lastValue = ForeverFunctionalAutologinEncryptedValue(it.iv.copyOf(), it.ciphertextAndTag.copyOf())
            }
        }

        override fun decrypt(value: ForeverFunctionalAutologinEncryptedValue, aad: ByteArray): ByteArray {
            val activeKey = key ?: throw IllegalStateException("missing fake key")
            aads += aad.copyOf()
            plaintextOverride?.let { return it.copyOf() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, activeKey, GCMParameterSpec(128, value.iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(value.ciphertextAndTag)
        }

        override fun deleteKey() {
            deleteCalls++
            key = null
        }

        override fun hasKey(): Boolean = key != null
    }

    private class DeleteFailingBackend : ForeverFunctionalAutologinCryptoBackend {
        private val delegate = JceFakeBackend()
        var deleteCalls = 0

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ForeverFunctionalAutologinEncryptedValue =
            delegate.encrypt(plaintext, aad)

        override fun decrypt(value: ForeverFunctionalAutologinEncryptedValue, aad: ByteArray): ByteArray =
            delegate.decrypt(value, aad)

        override fun deleteKey() {
            deleteCalls++
            throw IllegalStateException("synthetic delete failure")
        }

        override fun hasKey(): Boolean = delegate.hasKey()
    }
}
