package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.envvars.EnvVars
import com.winlator.contents.ContentsManager
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class ForeverRuntimeSetupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installed = mutableListOf<File>()
    private lateinit var wineDir: File
    private lateinit var dxvkDir: File

    @Before
    fun installPinnedProfileFixtures() {
        wineDir = installProfile("proton-11.0-90624-arm64ec", """
            {"type":"Proton","versionName":"proton-11.0-90624-arm64ec","versionCode":1,
             "description":"Pinned Wine profile fixture","files":[],
             "wine":{"binPath":"bin","libPath":"lib","prefixPack":"prefixPack.txz"}}
        """.trimIndent())
        for (name in listOf("bin/wine", "bin/wineserver", "lib/wine/aarch64-unix/ntdll.so",
                "lib/wine/aarch64-windows/ntdll.dll", "prefixPack.txz")) {
            writeFile(wineDir, name)
        }
        check(wineDir.resolve("bin/wine").setExecutable(true))
        check(wineDir.resolve("bin/wineserver").setExecutable(true))
        dxvkDir = installProfile("dxvk-2.4.1-wow-aarch64-test", """
            {"type":"DXVK","versionName":"2.4.1-wow-aarch64-test","versionCode":1,
             "description":"Pinned DXVK profile fixture","files":[
             {"source":"system32/dxgi.dll","target":"${'$'}{system32}/dxgi.dll"},
             {"source":"system32/d3d11.dll","target":"${'$'}{system32}/d3d11.dll"}]}
        """.trimIndent())
        writeFile(dxvkDir, "system32/dxgi.dll")
        writeFile(dxvkDir, "system32/d3d11.dll")
    }

    @After
    fun removeOnlyTestInstallations() {
        installed.forEach { it.deleteRecursively() }
    }

    @Test
    fun installedWineWithEmptyCopyListIsReusedWithoutReimport() {
        val manager = ContentsManager(context)
        val parsed = requireNotNull(manager.readProfile(wineDir.resolve("profile.json")))
        assertTrue(parsed.fileList.isEmpty()) // Actual Proton WCP format, not a missing manifest.
        val before = wineDir.resolve("profile.json").readBytes()
        val progress = mutableListOf<String>()

        val profiles = ForeverRuntimeSetup.ensure(context, progress::add)

        assertEquals("Proton-proton-11.0-90624-arm64ec-1", ContentsManager.getEntryName(profiles.wine))
        assertEquals("DXVK-2.4.1-wow-aarch64-test-1", ContentsManager.getEntryName(profiles.dxvk))
        assertEquals(2, profiles.dxvk.fileList.size)
        assertArrayEquals(before, wineDir.resolve("profile.json").readBytes())
        assertFalse(File(context.cacheDir, "forever-proton.wcp").exists())
        assertFalse(File(context.cacheDir, "forever-dxvk.wcp").exists())
        assertTrue(progress.last().contains("DXVK"))
    }

    @Test
    fun missingWineComponentsStillBlockReuseWithoutDeletingProfile() {
        for (name in listOf("bin/wine", "bin/wineserver", "prefixPack.txz",
                "lib/wine/aarch64-unix/ntdll.so", "lib/wine/aarch64-windows/ntdll.dll")) {
            val file = wineDir.resolve(name)
            val before = file.readBytes()
            check(file.delete())
            assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
            assertTrue(wineDir.resolve("profile.json").isFile)
            writeFile(wineDir, name).writeBytes(before)
            if (name.startsWith("bin/")) check(file.setExecutable(true))
        }
        wineDir.resolve("lib").renameTo(wineDir.resolve("libraries-held"))
        val error = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(error.message!!.contains("binary/library directory"))
    }

    @Test
    fun nonExecutableWineIsNotConsideredInstalled() {
        check(wineDir.resolve("bin/wine").setExecutable(false, false))
        val error = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(error.message!!.contains("not executable"))
    }

    @Test
    fun dxvkStillRequiresCopyListAndEveryDeclaredDll() {
        val profile = dxvkDir.resolve("profile.json")
        val original = profile.readText()
        profile.writeText(JSONObject(original).put("files", JSONArray()).toString())
        val emptyError = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(emptyError.message!!.contains("no installed file manifest"))
        profile.writeText(original)
        check(dxvkDir.resolve("system32/d3d11.dll").delete())
        val missingError = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(missingError.message!!.contains("system32/d3d11.dll"))
    }

    @Test
    fun winePathsCannotEscapeInstalledProfile() {
        val profile = wineDir.resolve("profile.json")
        val original = profile.readText()
        val invalid = JSONObject(original)
        invalid.getJSONObject("wine").put("prefixPack", "../outside.txz")
        profile.writeText(invalid.toString())
        val error = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(error.message!!.contains("escaped profile"))
        profile.writeText(original)
        val prefix = wineDir.resolve("prefixPack.txz")
        check(prefix.delete())
        val outside = File(context.cacheDir, "outside-prefix.txz").apply { writeText("outside") }
        try {
            Files.createSymbolicLink(prefix.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        } finally {
            Files.deleteIfExists(prefix.toPath())
            outside.delete()
        }
    }

    @Test
    fun reusedProfileCannotSilentlyChangePinnedVersionCode() {
        val profile = wineDir.resolve("profile.json")
        profile.writeText(JSONObject(profile.readText()).put("versionCode", 2).toString())
        val error = assertThrows(IllegalStateException::class.java) { ForeverRuntimeSetup.ensure(context, {}) }
        assertTrue(error.message!!.contains("unexpected runtime version"))
    }

    @Test
    fun declaredRuntimeEnvironmentUsesBothHostPackageAliasesAndBoundedWineDebug() {
        val env = ForeverRuntimeSetup.declaredEnvironment("com.example.forever", "run-1")

        assertEquals("com.example.forever", env["WINEMU_HOST_PKG"])
        assertEquals(env["WINEMU_HOST_PKG"], env["HOST_PKG"])
        assertEquals("0", env["WINEESYNC"])
        assertEquals("run-1", env["WOWFOREVER_RUN_ID"])
        assertEquals("+timestamp,+pid,err+module,warn+module,trace+module,trace+loaddll", env["WINEDEBUG"])
        assertFalse(env.values.any { it.contains("snoop") || it.contains("+all") || it.contains("network") })

        val serialized = EnvVars().apply { env.forEach { (key, value) -> put(key, value) } }.toString()
        val restored = EnvVars(serialized)
        assertEquals(env["WINEMU_HOST_PKG"], restored["WINEMU_HOST_PKG"])
        assertEquals(env["HOST_PKG"], restored["HOST_PKG"])
        assertEquals(env["WINEDEBUG"], restored["WINEDEBUG"])
    }

    @Test
    fun finalEnvironmentOverridesUpstreamDebugWithoutDroppingPreloadsOrDisplay() {
        val preloads = "/owned/libandroid-sysvshm.so:/owned/libevshim.so:/owned/libredirect-bionic-wx.so"
        val env = EnvVars().apply {
            put("WINEDEBUG", "-all")
            put("DXVK_LOG_LEVEL", "none")
            put("LD_PRELOAD", preloads)
            put("DISPLAY", ":0")
            put("WINEPREFIX", "/owned/prefix")
            put("HODLL", "libwow64fex.dll")
        }
        ForeverRuntimeSetup.enforceManagedEnvironment(env, "com.example.forever", "run-final")
        assertEquals("+timestamp,+pid,err+module,warn+module,trace+module,trace+loaddll", env.get("WINEDEBUG"))
        assertEquals(preloads, env.get("LD_PRELOAD"))
        assertEquals("info", env.get("DXVK_LOG_LEVEL"))
        assertEquals("A:\\Logs", env.get("DXVK_LOG_PATH"))
        assertEquals(":0", env.get("DISPLAY"))
        assertEquals("/owned/prefix", env.get("WINEPREFIX"))
        assertEquals("libwow64fex.dll", env.get("HODLL"))
        assertEquals("com.example.forever", env.get("WINEMU_HOST_PKG"))
        assertEquals("run-final", env.get("WOWFOREVER_RUN_ID"))
    }

    @Test
    fun hostPackageRejectsInjectionAndPathSyntax() {
        for (invalid in listOf("com.example/escape", "com.example bad", "com.example;id", "../outside")) {
            assertFalse(ForeverRuntimeSetup.isAndroidPackageIdentifier(invalid))
            assertThrows(IllegalArgumentException::class.java) {
                ForeverRuntimeSetup.declaredEnvironment(invalid, "run-1")
            }
        }
    }

    private fun installProfile(directoryName: String, fallback: String): File {
        // When supplied, use the exact JSON already extracted from the pinned WCPs.
        val actualRoot = System.getProperty("wowforever.runtimeProfiles")
        val json = if (actualRoot != null) File(actualRoot, "$directoryName/profile.json").readText() else fallback
        val temporary = File(context.cacheDir, "fixture-$directoryName.json").apply { writeText(json) }
        val profile = requireNotNull(ContentsManager(context).readProfile(temporary))
        temporary.delete()
        return ContentsManager.getInstallDir(context, profile).apply {
            check(!exists()) { "test profile path already exists: $this" }
            check(mkdirs())
            installed += this
            resolve("profile.json").writeText(json)
        }
    }

    private fun writeFile(root: File, path: String): File = root.resolve(path).apply {
        requireNotNull(parentFile).mkdirs()
        writeText("installed-file-fixture")
    }
}
