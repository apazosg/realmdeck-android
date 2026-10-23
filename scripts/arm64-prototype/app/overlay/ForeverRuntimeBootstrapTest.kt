package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverRuntimeBootstrapTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixtures = mutableListOf<File>()

    @After
    fun removeFixtures() {
        fixtures.forEach { it.deleteRecursively() }
    }

    @Test
    fun freshInvalidRootDownloadsThenInstallsAndValidatesCriticalFiles() {
        val fixture = fixture()
        val backend = FixtureBackend(fixture.root, fixture.evshim)
        backend.installAction = { installCompleteRoot(fixture.root) }

        ensure(ForeverRuntimeBootstrap(backend), backend)

        assertEquals(listOf("download", "install"), backend.calls)
        assertTrue(File(fixture.root, "usr/lib/libredirect-bionic-wx.so").isFile)
    }

    @Test
    fun readyRootWithMissingWxUsesInstallerAndNeverDirectCopiesIt() {
        val fixture = fixture()
        installCompleteRoot(fixture.root, includeWx = false)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply {
            installable = true
            installAction = {
                writeElf(File(fixture.root, "usr/lib/libredirect-bionic-wx.so"), AARCH64)
                File(fixture.root, "usr/lib/libredirect-bionic-wx.so").appendText("installer")
            }
        }

        ensure(ForeverRuntimeBootstrap(backend), backend)

        assertEquals(listOf("install"), backend.calls)
        assertTrue(File(fixture.root, "usr/lib/libredirect-bionic-wx.so").readText().endsWith("installer"))
    }

    @Test
    fun missingCriticalFileBlocksCompletion() {
        val fixture = fixture()
        installCompleteRoot(fixture.root, includeWx = false)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }

        val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)

        assertTrue(failure.message!!.contains("libredirect-bionic-wx.so"))
        assertFalse(backend.launchAfterEnsure)
    }

    @Test
    fun stockBionicImageWithoutVariantCommitsMarkerOnlyAfterCriticalValidation() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        File(fixture.root, ".winlator/.variant").delete()
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }
        ensure(ForeverRuntimeBootstrap(backend), backend)
        assertEquals(Container.BIONIC, ImageFs.find(fixture.root).variant)
    }

    @Test
    fun missingNativeComponentNeverCommitsAnAbsentVariantMarker() {
        val fixture = fixture()
        installCompleteRoot(fixture.root, includeWx = false)
        val marker = File(fixture.root, ".winlator/.variant").apply { delete() }
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }
        assertTrue(ensureFailure(ForeverRuntimeBootstrap(backend), backend).message!!.contains("libredirect-bionic-wx.so"))
        assertFalse(marker.exists())
        assertFalse(backend.launchAfterEnsure)
    }

    @Test
    fun explicitWrongVariantRemainsRejected() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        File(fixture.root, ".winlator/.variant").writeText(Container.GLIBC)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply {
            installable = true
            installAction = { installCompleteRoot(fixture.root) }
        }
        assertTrue(ensureFailure(ForeverRuntimeBootstrap(backend), backend).message!!.contains("does not match bionic"))
        assertTrue(backend.calls.isEmpty())
        assertEquals(Container.GLIBC, File(fixture.root, ".winlator/.variant").readText())
        assertFalse(backend.launchAfterEnsure)
    }

    @Test
    fun linkedVariantMarkerIsRejectedBeforeInstallerMutation() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        val outside = File(fixture.root.parentFile, "outside-variant-${System.nanoTime()}").apply { writeText("sentinel") }
        fixtures += outside
        val marker = File(fixture.root, ".winlator/.variant").apply { delete() }
        Files.createSymbolicLink(marker.toPath(), outside.toPath())
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }
        assertTrue(ensureFailure(ForeverRuntimeBootstrap(backend), backend).message!!.contains("state marker is a symlink"))
        assertTrue(backend.calls.isEmpty())
        assertEquals("sentinel", outside.readText())
    }

    @Test
    fun emptyStockDefaultProtonPlaceholdersAreNotUserDataConflicts() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        val defaultName = "proton-9.0-arm64ec"
        File(fixture.root, "opt/$defaultName").mkdirs()
        val shared = File(context.filesDir, "imagefs_shared/proton/$defaultName")
        val existed = shared.exists()
        shared.mkdirs()
        try {
            val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }
            ensure(ForeverRuntimeBootstrap(backend), backend)
            assertEquals(listOf("install"), backend.calls)
        } finally {
            if (!existed && shared.listFiles()?.isEmpty() == true) shared.delete()
        }
    }

    @Test
    fun badElfArchitectureBlocksCompletion() {
        val fixture = fixture()
        installCompleteRoot(fixture.root, elfMachine = X86_64)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }

        val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)

        assertTrue(failure.message!!.contains("AArch64 ELF"))
        assertFalse(backend.launchAfterEnsure)
    }

    @Test
    fun cancellationBeforeAndAfterInstallCannotReachLaunch() {
        val before = fixture()
        val beforeBackend = FixtureBackend(before.root, before.evshim).apply { cancelled = true }
        val beforeFailure = ensureFailure(ForeverRuntimeBootstrap(beforeBackend), beforeBackend)
        assertTrue(beforeFailure is CancellationException)
        assertTrue(beforeBackend.calls.isEmpty())
        assertFalse(beforeBackend.launchAfterEnsure)

        val after = fixture()
        val afterBackend = FixtureBackend(after.root, after.evshim).apply {
            installAction = {
                installCompleteRoot(after.root)
                cancelled = true
            }
        }
        val afterFailure = ensureFailure(ForeverRuntimeBootstrap(afterBackend), afterBackend)
        assertTrue(afterFailure is CancellationException)
        assertFalse(afterBackend.launchAfterEnsure)
    }

    @Test
    fun validationPreservesProfileAndSharedHomeBytes() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        val profile = File(fixture.root, "home/xuser/.wine/user.reg").apply {
            parentFile!!.mkdirs()
            writeText("profile-fixture")
        }
        val home = File(fixture.root, "home/xuser/forever.txt").apply { writeText("shared-home-fixture") }
        val profileBytes = profile.readBytes()
        val homeBytes = home.readBytes()
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }

        ensure(ForeverRuntimeBootstrap(backend), backend)

        assertArrayEquals(profileBytes, profile.readBytes())
        assertArrayEquals(homeBytes, home.readBytes())
    }

    @Test
    fun conflictingLegacyAndSharedHomeArePreservedWithoutInstallerCall() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        val shared = File(context.filesDir, "imagefs_shared/home")
        val created = !shared.exists()
        shared.mkdirs()
        if (created) fixtures += shared
        val marker = File(shared, "bootstrap-home-${System.nanoTime()}").apply { writeText("shared-profile") }
        fixtures += marker
        val legacy = File(fixture.root, "home/xuser/keep").apply { writeText("legacy-profile") }
        val backend = FixtureBackend(fixture.root, fixture.evshim)
        val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)
        assertTrue(failure.message!!.contains("legacy/shared home conflict"))
        assertTrue(backend.calls.isEmpty())
        assertEquals("shared-profile", marker.readText())
        assertEquals("legacy-profile", legacy.readText())
    }

    @Test
    fun conflictingLegacyAndSharedProtonArePreservedWithoutInstallerCall() {
        val fixture = fixture()
        val name = "proton-bootstrap-${System.nanoTime()}"
        val shared = File(context.filesDir, "imagefs_shared/proton/$name").apply { mkdirs() }
        fixtures += shared
        val sharedMarker = File(shared, "keep").apply { writeText("shared-runtime") }
        val legacy = File(fixture.root, "opt/$name").apply { mkdirs() }
        val legacyMarker = File(legacy, "keep").apply { writeText("legacy-runtime") }
        val backend = FixtureBackend(fixture.root, fixture.evshim)
        val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)
        assertTrue(failure.message!!.contains("legacy/shared Proton conflict"))
        assertTrue(backend.calls.isEmpty())
        assertEquals("shared-runtime", sharedMarker.readText())
        assertEquals("legacy-runtime", legacyMarker.readText())
    }

    @Test
    fun graphicsIcdIsNotRequiredBeforeItsStockProducerRuns() {
        val fixture = fixture()
        installCompleteRoot(fixture.root)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply { installable = true }
        ensure(ForeverRuntimeBootstrap(backend), backend)
        assertFalse(File(fixture.root, "usr/share/vulkan/icd.d/wrapper_icd.aarch64.json").exists())
        assertTrue(backend.launchAfterEnsure)
    }

    @Test
    fun outsideRootIsRejectedBeforeDownloadOrInstallerMutation() {
        val fixture = fixture()
        val outside = File(context.cacheDir, "outside-bootstrap-${System.nanoTime()}").apply { mkdirs() }
        fixtures += outside
        val link = File(fixture.root, "usr")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val sentinel = File(outside, "keep").apply { writeText("unchanged") }
        try {
            val backend = FixtureBackend(fixture.root, fixture.evshim)
            val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)
            assertTrue(failure.message!!.contains("escaped app-private files"))
            assertTrue(backend.calls.isEmpty())
            assertEquals("unchanged", sentinel.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
        }
    }

    @Test
    fun siblingAndSharedRootsAreRejectedBeforeAnyBackendOperation() {
        val fixture = fixture()
        val sibling = File(context.filesDir, "bootstrap-sibling-${System.nanoTime()}").apply { mkdirs() }
        fixtures += sibling
        val usrLink = File(fixture.root, "usr")
        Files.createSymbolicLink(usrLink.toPath(), sibling.toPath())
        val usrSentinel = File(sibling, "keep").apply { writeText("usr-unchanged") }
        try {
            val backend = FixtureBackend(fixture.root, fixture.evshim)
            val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)
            assertTrue(failure.message!!.contains("escaped its private root"))
            assertTrue(backend.calls.isEmpty())
            assertEquals("usr-unchanged", usrSentinel.readText())
        } finally {
            Files.deleteIfExists(usrLink.toPath())
        }

        val sharedRoot = File(context.filesDir, "imagefs_shared")
        assertFalse(sharedRoot.exists())
        val sharedSibling = File(context.filesDir, "bootstrap-shared-sibling-${System.nanoTime()}").apply { mkdirs() }
        fixtures += sharedSibling
        Files.createSymbolicLink(sharedRoot.toPath(), sharedSibling.toPath())
        val sharedSentinel = File(sharedSibling, "keep").apply { writeText("shared-unchanged") }
        try {
            val second = fixture()
            val backend = FixtureBackend(second.root, second.evshim)
            val failure = ensureFailure(ForeverRuntimeBootstrap(backend), backend)
            assertTrue(failure.message!!.contains("imagefs_shared is a symlink"))
            assertTrue(backend.calls.isEmpty())
            assertEquals("shared-unchanged", sharedSentinel.readText())
        } finally {
            Files.deleteIfExists(sharedRoot.toPath())
        }
    }

    @Test
    fun concurrentEnsureSharesOneOwnedDownloadAndInstall() {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondJoined = CountDownLatch(1)
        val secondChecks = java.util.concurrent.atomic.AtomicInteger()
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply {
            installEntered = entered
            installRelease = release
            installAction = { installCompleteRoot(fixture.root) }
        }
        val bootstrap = ForeverRuntimeBootstrap(backend)

        runBlocking {
            val first = async(Dispatchers.Default) {
                bootstrap.ensure(context, backend.container, {}, { false })
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) {
                bootstrap.ensure(context, backend.container, {}, {
                    // Initial cancellation check precedes ownedOperation; the
                    // second check proves this caller entered its shared wait.
                    if (secondChecks.incrementAndGet() >= 2) secondJoined.countDown()
                    false
                })
            }
            assertTrue(secondJoined.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            first.await()
            second.await()
        }

        assertEquals(listOf("download", "install"), backend.calls)
    }

    @Test
    fun cancelledCallerRetainsSynchronousInstallerOperationForRetry() {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FixtureBackend(fixture.root, fixture.evshim).apply {
            installable = true
            installEntered = entered
            installRelease = release
            installAction = { installCompleteRoot(fixture.root) }
        }
        val bootstrap = ForeverRuntimeBootstrap(backend, installTimeoutMs = 100)

        runBlocking {
            val first = async(Dispatchers.Default) {
                ensureFailureSuspending(bootstrap, backend) { backend.cancelled }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            backend.cancelled = true
            val cancelled = first.await()
            assertTrue(cancelled is CancellationException)
            backend.cancelled = false
            val timedOut = ensureFailure(bootstrap, backend)
            assertTrue(timedOut.message!!.contains("timed out"))
            assertEquals(listOf("install"), backend.calls)
            assertFalse(backend.launchAfterEnsure)
            release.countDown()
            ensure(bootstrap, backend)
        }

        assertEquals(listOf("install"), backend.calls)
    }

    private fun ensure(bootstrap: ForeverRuntimeBootstrap, backend: FixtureBackend) {
        runBlocking {
            bootstrap.ensure(context, backend.container, {}, { backend.cancelled })
        }
        backend.launchAfterEnsure = true
    }

    private fun ensureFailure(bootstrap: ForeverRuntimeBootstrap, backend: FixtureBackend): Throwable =
        runBlocking { ensureFailureSuspending(bootstrap, backend) { backend.cancelled } }

    private suspend fun ensureFailureSuspending(
        bootstrap: ForeverRuntimeBootstrap,
        backend: FixtureBackend,
        cancelled: () -> Boolean,
    ): Throwable {
        return try {
            bootstrap.ensure(context, backend.container, {}, cancelled)
            backend.launchAfterEnsure = true
            AssertionError("bootstrap unexpectedly succeeded")
        } catch (failure: Throwable) {
            failure
        }
    }

    private fun fixture(): Fixture {
        val root = File(context.filesDir, "bootstrap-fixture-${System.nanoTime()}").apply { mkdirs() }
        val native = File(root.parentFile, "bootstrap-evshim-${System.nanoTime()}.so").also { writeElf(it, AARCH64) }
        fixtures += root
        fixtures += native
        return Fixture(root, native)
    }

    private fun installCompleteRoot(root: File, includeWx: Boolean = true, elfMachine: Int = AARCH64) {
        File(root, ".winlator/.img_version").apply { parentFile!!.mkdirs(); writeText("31") }
        File(root, ".winlator/.variant").writeText(Container.BIONIC)
        writeElf(File(root, "usr/lib/libandroid-sysvshm.so"), elfMachine)
        if (includeWx) writeElf(File(root, "usr/lib/libredirect-bionic-wx.so"), elfMachine)
        File(root, "home/xuser").mkdirs()
    }

    private fun writeElf(file: File, machine: Int) {
        file.parentFile!!.mkdirs()
        val header = ByteArray(64)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2
        header[5] = 1
        header[18] = (machine and 0xff).toByte()
        header[19] = ((machine ushr 8) and 0xff).toByte()
        file.writeBytes(header)
    }

    private data class Fixture(val root: File, val evshim: File)

    private class FixtureBackend(private val root: File, private val evshimFile: File) : ForeverRuntimeBootstrap.Backend {
        val container = Container("bootstrap-fixture").apply { setContainerVariant(Container.BIONIC) }
        val calls = mutableListOf<String>()
        var installable = false
        @Volatile var cancelled = false
        var launchAfterEnsure = false
        var installAction: (() -> Unit)? = null
        var installEntered: CountDownLatch? = null
        var installRelease: CountDownLatch? = null

        override fun isImageFsInstallable(context: Context, variant: String): Boolean = installable

        override suspend fun downloadImageFs(context: Context, variant: String, progress: (Float) -> Unit) {
            calls += "download"
            progress(1f)
        }

        override fun installIfNeeded(
            context: Context,
            container: Container,
            progress: (Int) -> Unit,
        ): Future<Boolean> {
            calls += "install"
            installEntered?.countDown()
            installRelease?.await()
            installAction?.invoke()
            progress(100)
            return CompletableFuture.completedFuture(true)
        }

        override fun imageFs(context: Context): ImageFs = ImageFs.find(root)

        override fun evshim(context: Context): File = evshimFile
    }

    private companion object {
        const val AARCH64 = 183
        const val X86_64 = 62
    }
}
