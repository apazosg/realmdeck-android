package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
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
class ForeverNoOpenGLTest {
    @Test
    fun publicEnabledControlUsesOnlyPrivatePathAndNonManagedRemainsUnchanged() {
        assertTrue(ForeverLaunchDebug.SCOPED_NO_OPENGL)
        assertFalse(ForeverLaunchDebug.MEMORY_PATCHING_ENABLED)
        fixture().use { fixture ->
            val container = Container("actual-control").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverScopedNoOpenGL", "true")
                putExtra("foreverRunId", "RUN-control")
            }
            val env = EnvVars().apply {
                put("WOWFOREVER_RUN_ID", "RUN-control")
                put("LD_LIBRARY_PATH", "/old:/system/lib64")
                put("LD_PRELOAD", "/shm:/evshim:/wx")
            }
            ForeverNoOpenGL.applyManagedEnvironment(fixture.context, container, env)
            assertEquals("${ForeverNoOpenGL.prepare(fixture.context)}:/old:/system/lib64", env.get("LD_LIBRARY_PATH"))
            assertEquals("/shm:/evshim:/wx", env.get("LD_PRELOAD"))
            container.putExtra("foreverManaged", "false")
            val before = env.toString()
            ForeverNoOpenGL.applyManagedEnvironment(fixture.context, container, env)
            assertEquals(before, env.toString())
        }
    }

    @Test
    fun disabledPolicyIsNoOpEvenWithStaleOptInAndCannotReadAssets() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getAssets(): AssetManager = error("baseline must not access assets")
        }
        val container = Container("no-opengl-public-baseline").apply {
            putExtra("foreverManaged", "true")
            putExtra("foreverScopedNoOpenGL", "true")
            putExtra("foreverRunId", "run-baseline")
        }
        val env = EnvVars().apply {
            put("WOWFOREVER_RUN_ID", "run-baseline")
            put("LD_LIBRARY_PATH", "/existing:/path")
            put("WOWFOREVER_GL_CONTROL", "upstream-value")
        }
        val before = env.toString()
        ForeverNoOpenGL.applyForPolicy(context, container, env, enabled = false)
        assertEquals(before, env.toString())
    }

    @Test
    fun managedEnvironmentPrefixesOnlyPrivateLibraryAndPreservesRuntimeVariables() {
        fixture().use { fixture ->
            val container = Container("no-opengl-managed").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverScopedNoOpenGL", "true")
                putExtra("foreverRunId", "run-window-1")
            }
            val env = EnvVars().apply {
                put("WOWFOREVER_RUN_ID", "run-window-1")
                put("LD_LIBRARY_PATH", "/before:/after")
                put("LD_PRELOAD", "/owned/libevshim.so:/owned/libredirect-bionic-wx.so")
                put("HODLL", "libwow64fex.dll")
                put("VK_ICD_FILENAMES", "/system/etc/vulkan/icd.json")
                put("ANDROID_SYSVSHM_SERVER", "/owned/shm")
                put("DISPLAY", ":0")
            }

            ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
            val preparedDirectory = ForeverNoOpenGL.prepare(fixture.context)
            val expected = "${preparedDirectory.absolutePath}:/before:/after"
            assertEquals(expected, env.get("LD_LIBRARY_PATH"))
            assertEquals("scoped-no-opengl", env.get("WOWFOREVER_GL_CONTROL"))
            assertEquals("/owned/libevshim.so:/owned/libredirect-bionic-wx.so", env.get("LD_PRELOAD"))
            assertEquals("libwow64fex.dll", env.get("HODLL"))
            assertEquals("/system/etc/vulkan/icd.json", env.get("VK_ICD_FILENAMES"))
            assertEquals("/owned/shm", env.get("ANDROID_SYSVSHM_SERVER"))
            assertEquals(":0", env.get("DISPLAY"))

            // A second final merge is idempotent and does not duplicate our prefix.
            ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
            assertEquals(expected, env.get("LD_LIBRARY_PATH"))
            assertEquals(1, env.get("LD_LIBRARY_PATH")!!.split(':').count { it == preparedDirectory.absolutePath })
        }
    }

    @Test
    fun pathDeduplicationPreservesEmptyAndTrailingComponents() {
        fixture().use { fixture ->
            val container = Container("no-opengl-trailing").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverScopedNoOpenGL", "true")
                putExtra("foreverRunId", "run-trailing")
            }
            val env = EnvVars().apply { put("WOWFOREVER_RUN_ID", "run-trailing") }
            ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
            val prefix = ForeverNoOpenGL.prepare(fixture.context).absolutePath

            env.put("LD_LIBRARY_PATH", "$prefix:$prefix:/after::")
            ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
            assertEquals("$prefix:/after::", env.get("LD_LIBRARY_PATH"))
        }
    }

    @Test
    fun nonManagedAndExplicitOptOutAreByteForByteNoOps() {
        fixture().use { fixture ->
            for (managed in listOf("false", "true")) {
                val container = Container("no-opengl-optout-$managed").apply {
                    putExtra("foreverManaged", managed)
                    putExtra("foreverScopedNoOpenGL", "false")
                    putExtra("foreverRunId", "run-a")
                }
                val env = EnvVars().apply {
                    put("WOWFOREVER_RUN_ID", "run-a")
                    put("LD_LIBRARY_PATH", "/existing:/path")
                    put("LD_PRELOAD", "/owned/preload.so")
                }
                val before = env.toString()
                ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
                assertEquals(before, env.toString())
            }
        }
    }

    @Test
    fun badRunIdentityFailsClosedBeforeChangingEnvironment() {
        fixture().use { fixture ->
            val container = Container("no-opengl-bad-run").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverScopedNoOpenGL", "true")
                putExtra("foreverRunId", "run-a")
            }
            val env = EnvVars().apply {
                put("WOWFOREVER_RUN_ID", "run-b")
                put("LD_LIBRARY_PATH", "/existing")
            }
            val before = env.toString()
            assertThrows<IllegalArgumentException> {
                ForeverNoOpenGL.applyForPolicy(fixture.context, container, env, enabled = true)
            }
            assertEquals(before, env.toString())
        }
    }

    @Test
    fun malformedHashElfAndSymlinkAreRejectedAndExistingUnknownBytesAreNotReplaced() {
        fixture().use { fixture ->
            val prepared = ForeverNoOpenGL.prepare(fixture.context)
            val library = prepared.resolve("libGL.so.1")
            val original = library.readBytes()
            assertArrayEquals(original, ForeverNoOpenGL.prepare(fixture.context).resolve("libGL.so.1").readBytes())
            library.writeText("unknown-file")
            assertThrows<IllegalArgumentException> { ForeverNoOpenGL.prepare(fixture.context) }
            assertEquals("unknown-file", library.readText())
        }

        fixture(badElf = true).use { fixture ->
            assertThrows<IllegalArgumentException> { ForeverNoOpenGL.prepare(fixture.context) }
        }
        fixture(badHash = true).use { fixture ->
            assertThrows<IllegalArgumentException> { ForeverNoOpenGL.prepare(fixture.context) }
        }
        fixture(selfConsistentDifferentHash = true).use { fixture ->
            assertThrows<IllegalArgumentException> { ForeverNoOpenGL.prepare(fixture.context) }
        }
        fixture().use { fixture ->
            val hash = fixture.hash
            val root = fixture.context.filesDir.resolve("forever/controls/no-opengl")
            root.mkdirs()
            val outside = Files.createTempDirectory("no-opengl-outside").toFile()
            try {
                Files.createSymbolicLink(root.resolve(hash).toPath(), outside.toPath())
                assertThrows<IllegalArgumentException> { ForeverNoOpenGL.prepare(fixture.context) }
            } finally {
                root.resolve(hash).delete()
                outside.deleteRecursively()
            }
        }
    }

    @Test
    fun boundedReaderHandlesZeroReadsAndRejectsOneByteOverLimit() {
        val fragmented = object : InputStream() {
            private val bytes = byteArrayOf(1, 2, 3)
            private var position = 0
            private var returnedZero = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                if (position == bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }

            override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), readBoundedForTest(fragmented, 3))
        assertThrows<IllegalArgumentException> {
            readBoundedForTest(java.io.ByteArrayInputStream(ByteArray(65 * 1024 + 1)), 64 * 1024)
        }
    }

    private fun fixture(
        badElf: Boolean = false,
        badHash: Boolean = false,
        selfConsistentDifferentHash: Boolean = false,
    ): Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val sandbox = Files.createTempDirectory(base.cacheDir.toPath(), "no-opengl-files").toFile()
        val bytes = loadAssetBounded(base, ForeverNoOpenGL.ASSET_LIBRARY, 1944)
        assertEquals(1944, bytes.size)
        val manifest = JSONObject(
            String(loadAssetBounded(base, ForeverNoOpenGL.ASSET_MANIFEST, 4 * 1024), Charsets.UTF_8),
        )
        if (badElf) bytes[16] = 2 // Retain the pinned manifest: hash or ELF validation must reject it.
        if (selfConsistentDifferentHash) {
            bytes[100] = (bytes[100].toInt() xor 1).toByte()
            manifest.put("sha256", sha256(bytes))
        }
        if (badHash) manifest.put("sha256", "0".repeat(64))
        val hash = manifest.getString("sha256")
        val zip = Files.createTempFile(base.cacheDir.toPath(), "no-opengl-assets", ".zip").toFile()
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("assets/${ForeverNoOpenGL.ASSET_LIBRARY}"))
            output.write(bytes)
            output.closeEntry()
            output.putNextEntry(ZipEntry("assets/${ForeverNoOpenGL.ASSET_MANIFEST}"))
            output.write(manifest.toString().toByteArray())
            output.closeEntry()
        }
        val assets = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply { isAccessible = true }
        check((addAssetPath.invoke(assets, zip.absolutePath) as Int) != 0)
        val context = object : ContextWrapper(base) {
            override fun getAssets(): AssetManager = assets
            override fun getFilesDir(): File = sandbox
        }
        return Fixture(context, sandbox, zip, assets, hash)
    }

    private fun loadAssetBounded(context: Context, path: String, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.assets.open(path).use { input ->
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val requested = minOf(buffer.size, maximum - total + 1)
                val count = input.read(buffer, 0, requested)
                if (count < 0) break
                require(count > 0 && count <= maximum - total) { "fixture asset exceeds bound" }
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val context: Context,
        val sandbox: File,
        val zip: File,
        val assets: AssetManager,
        val hash: String,
    ) : AutoCloseable {
        override fun close() {
            assets.close()
            sandbox.deleteRecursively()
            zip.delete()
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("unexpected exception: ${error.javaClass}", error is T)
            return
        }
        assertFalse("expected ${T::class.java.simpleName}", true)
    }

    private fun readBoundedForTest(input: InputStream, maximum: Int): ByteArray {
        val method = ForeverNoOpenGL::class.java.getDeclaredMethod(
            "readBounded", InputStream::class.java, Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        return try {
            method.invoke(ForeverNoOpenGL, input, maximum) as ByteArray
        } catch (error: InvocationTargetException) {
            throw (error.cause ?: error)
        }
    }
}
