package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForeverRuntimeEvidenceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun preflightReadsManagedNativeAndNarrowWinhandlerCandidatesOnly() {
        val files = temporary.newFolder("files")
        val native = temporary.newFolder("native")
        val context = FixtureContext(files, native)
        val imageFsUsr = File(files, "imagefs/usr").apply { check(mkdirs()) }
        val prefix = File(files, "prefix").apply { check(mkdirs()) }
        val helper = File(prefix, "system32/winhandler.exe").apply {
            parentFile!!.mkdirs()
            writeBytes(peHeader(0xaa64))
        }
        val redirect = File(native, "libredirect-bionic-wx.so").apply { writeBytes(elfHeader(183)) }
        val env = ForeverRuntimeSetup.declaredEnvironment(context.packageName, "run-1") + mapOf(
            "LD_PRELOAD" to redirect.absolutePath,
            "WINEPREFIX" to prefix.absolutePath,
            "WINELOADER" to redirect.absolutePath,
        )

        val report = ForeverRuntimeEvidence.inspect(
            context,
            "run-1",
            listOf(redirect.absolutePath, "-config", "BetaSuspendedTest.wtf", "-d3d11"),
            env,
        )

        assertTrue(imageFsUsr.isDirectory)
        assertEquals("PASS", report.getJSONObject("hostPackage").getString("status"))
        assertEquals("PASS", report.getJSONObject("imageFs").getString("status"))
        assertTrue(report.getJSONArray("nativeComponents").toString().contains("libredirect-bionic-wx.so"))
        val helpers = report.getJSONArray("helpers")
        val actual = findByPath(helpers, helper.canonicalPath)
        assertEquals("PASS", actual.getString("status"))
        assertEquals("ARM64", actual.getString("peMachine"))
        assertEquals("0xaa64", actual.getString("peMachineHex"))
        assertTrue(helpers.length() <= 12)
    }

    @Test
    fun systemLinkerWrapperUsesTheActualWineArgumentAndRealWinePrefix() {
        val files = temporary.newFolder("wrapped-files")
        val native = temporary.newFolder("wrapped-native")
        val context = FixtureContext(files, native)
        val wine = File(files, "imagefs_shared/proton/runtime/bin/wine").apply {
            parentFile!!.mkdirs(); writeBytes(elfHeader(183))
        }
        val prefix = File(files, "imagefs/home/xuser/.wine").apply { check(mkdirs()) }
        val helper = File(prefix, "drive_c/windows/system32/winhandler.exe").apply {
            parentFile!!.mkdirs(); writeBytes(peHeader(0x14c))
        }
        val env = ForeverRuntimeSetup.declaredEnvironment(context.packageName, "run-wrapper") + mapOf(
            "WINEPREFIX" to prefix.absolutePath,
            "PREFIX" to File(files, "imagefs/usr").absolutePath,
        )
        val report = ForeverRuntimeEvidence.inspect(context, "run-wrapper", listOf("/system/bin/linker64", wine.path, "explorer"), env)
        assertEquals(wine.canonicalPath, report.getJSONObject("wineExecutable").getString("path"))
        assertEquals("argv[1]", report.getJSONObject("wineExecutable").getString("source"))
        assertEquals("AA64", report.getJSONObject("wineExecutable").getJSONObject("header").getString("elfArch"))
        assertEquals("X86", findByPath(report.getJSONArray("helpers"), helper.canonicalPath).getString("peMachine"))
        assertTrue(report.getString("verificationBoundary").contains("no helper execution"))
    }

    @Test
    fun invalidPackageAndUnmanagedCandidatesRemainUnknownWithoutLeakingArguments() {
        val files = temporary.newFolder("files")
        val native = temporary.newFolder("native")
        val context = FixtureContext(files, native)
        val secret = "password=do-not-record"
        val report = ForeverRuntimeEvidence.inspect(
            context,
            "run-2",
            listOf("/outside/wine", secret),
            mapOf(
                "WINEMU_HOST_PKG" to "com.example/escape",
                "HOST_PKG" to "com.example/escape",
                "PREFIX" to "/outside/prefix",
                "LD_PRELOAD" to "/outside/lib.so",
                "SECRET" to secret,
            ),
        )

        assertEquals("UNKNOWN", report.getString("status"))
        assertEquals("UNKNOWN", report.getJSONObject("hostPackage").getString("status"))
        assertFalse(report.toString().contains(secret))
        assertFalse(report.toString().contains("SECRET"))
        assertEquals("UNKNOWN", report.getJSONObject("wineExecutable").getString("status"))
    }

    @Test
    fun peHeaderValidationDoesNotReadOrAcceptMalformedFixture() {
        val files = temporary.newFolder("files")
        val native = temporary.newFolder("native")
        val context = FixtureContext(files, native)
        val prefix = File(files, "prefix").apply { check(mkdirs()) }
        File(prefix, "system32/winhandler.exe").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))
        }
        val env = ForeverRuntimeSetup.declaredEnvironment(context.packageName, "run-3") +
            mapOf("WINEPREFIX" to prefix.absolutePath)

        val report = ForeverRuntimeEvidence.inspect(context, "run-3", listOf("wine"), env)
        val helper = findByPath(report.getJSONArray("helpers"), File(prefix, "system32/winhandler.exe").canonicalPath)
        assertEquals("UNKNOWN", helper.getString("status"))
        assertEquals("invalid-PE-header", helper.getString("reason"))
    }

    private fun findByPath(entries: JSONArray, path: String) : org.json.JSONObject {
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.optString("path") == path) return entry
        }
        fail("missing evidence path $path")
        error("unreachable")
    }

    private fun elfHeader(machine: Int): ByteArray = ByteArray(64).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[18] = (machine and 0xff).toByte()
        this[19] = ((machine ushr 8) and 0xff).toByte()
    }

    private fun peHeader(machine: Int): ByteArray = ByteArray(0x40 + 24).apply {
        this[0] = 'M'.code.toByte()
        this[1] = 'Z'.code.toByte()
        this[0x3c] = 0x40
        this[0x40] = 'P'.code.toByte()
        this[0x41] = 'E'.code.toByte()
        this[0x44] = (machine and 0xff).toByte()
        this[0x45] = ((machine ushr 8) and 0xff).toByte()
    }

    private class FixtureContext(private val files: File, native: File) : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        private val appInfo = ApplicationInfo().apply { nativeLibraryDir = native.absolutePath }

        override fun getFilesDir(): File = files
        override fun getPackageName(): String = "com.example.forever"
        override fun getApplicationInfo(): ApplicationInfo = appInfo
    }
}
