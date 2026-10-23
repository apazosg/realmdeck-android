package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowProcess
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowLinux
import android.system.StructStat

/** Robolectric's stock stat reports file type only; model real POSIX mode bits. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false)
class PanvkWinePosixShadow : ShadowLinux() {
    @Implementation
    override fun stat(path: String): StructStat {
        val value = super.stat(path)
        val mode = (Files.getAttribute(File(path).toPath(), "unix:mode") as Number).toInt()
        val links = (Files.getAttribute(File(path).toPath(), "unix:nlink") as Number).toLong()
        val uid = (Files.getAttribute(File(path).toPath(), "unix:uid") as Number).toInt()
        return StructStat(value.st_dev, value.st_ino, mode, links,
            uid, value.st_gid, value.st_rdev, value.st_size,
            value.st_atime, value.st_mtime, value.st_ctime, value.st_blksize, value.st_blocks)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [PanvkWinePosixShadow::class])
class ForeverPanvkWineTest {
    @Before
    fun useConsistentFakeKernelUidForPrivateFileFixtures() {
        val fixture = Files.createTempDirectory("panvk-wine-kernel-uid")
        try {
            // Os.stat and the Robolectric Process shadow must agree on the
            // owner used by the 0600 production guard.
            ShadowProcess.setUid(android.system.Os.stat(fixture.toString()).st_uid)
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @After
    fun restoreShadowProcessUid() {
        ShadowProcess.reset()
    }

    @Test
    fun disabledNonManagedNonProbeAndNonOptedInRoutesDoNotReadAssetsOrChangeEnv() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = assetFailingContext(base)
        val cases = listOf(
            Container("non-managed").apply { putExtra("foreverManaged", "false") },
            Container("non-probe").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverLaunchKind", "wow_client")
            },
            Container("not-opted-in").apply {
                putExtra("foreverManaged", "true")
                putExtra("foreverLaunchKind", ForeverPanvkWine.PROFILE)
                putExtra("foreverPanvkWineProbe", "false")
            },
        )
        cases.forEach { container ->
            val env = EnvVars().apply { put("WOWFOREVER_RUN_ID", "baseline") }
            val before = env.toString()
            ForeverPanvkWine.applyForPolicy(context, container, env, enabled = true)
            assertEquals(before, env.toString())
        }

        val disabledEnv = EnvVars().apply { put("existing", "value") }
        val disabledBefore = disabledEnv.toString()
        ForeverPanvkWine.applyForPolicy(context, cases.last(), disabledEnv, enabled = false)
        assertEquals(disabledBefore, disabledEnv.toString())
    }

    @Test
    fun packageAndExecutableMismatchFailBeforeAnyEnvironmentMutation() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(assetFailingContext(base)) {
            override fun getPackageName(): String = "com.wowforever.arm64probe.other"
        }
        val container = managedProbe("RUN-scope").apply {
            setExecutablePath("not-the-graphics-probe.exe")
        }
        val env = EnvVars().apply {
            put("WOWFOREVER_RUN_ID", "RUN-scope")
            put("LD_PRELOAD", "/owned/libevshim.so")
        }
        val before = env.toString()
        assertThrows {
            ForeverPanvkWine.applyForPolicy(context, container, env, enabled = true)
        }
        assertEquals(before, env.toString())
    }

    @Test
    fun frozenScopeNoGlAndSourceMismatchesFailBeforeAssetAccess() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = fixedDebugContext(assetFailingContext(base))
        val env = EnvVars().apply { put("WOWFOREVER_RUN_ID", "RUN-scope") }
        val mutations: List<(Container) -> Unit> = listOf(
            { it.putExtra("foreverScopedNoOpenGL", "false") },
            { it.setContainerVariant("glibc") },
            { it.setDXWrapper("dxvk-other") },
            { it.putExtra("foreverVerifiedExe", "/outside/GraphicsProbe-ARM64.exe") },
        )
        mutations.forEach { mutate ->
            val container = completeProbeProfile("RUN-scope")
            mutate(container)
            val before = env.toString()
            assertThrows { ForeverPanvkWine.applyForPolicy(context, container, env, enabled = true) }
            assertEquals(before, env.toString())
        }
    }

    @Test
    fun finalDeltaChangesOnlyTheThreeAdrenotoolsKeys() {
        val env = EnvVars().apply {
            put("WINEESYNC", "0")
            put("WOWFOREVER_RUN_ID", "RUN-env")
            put("VK_ICD_FILENAMES", "/owned/wrapper_icd.aarch64.json")
            put("LD_PRELOAD", "/owned/shm:/owned/evshim:/owned/wx")
            put("HODLL", "libwow64fex.dll")
            put("DISPLAY", ":0")
            put("PATH", "/owned/wine:/owned/bin")
            put("LD_LIBRARY_PATH", "/owned/lib:/system/lib64")
            put("ANDROID_SYSVSHM_SERVER", "/owned/shm/socket")
            put("EVSHIM_BASE_PATH", "/owned/evshim")
            put("REDIRECT_EXEC__PROC_SELF_EXE", "/owned/redirect")
            put("WINEPREFIX", "/owned/prefix")
            put("WINELOADER", "/owned/wine")
            put("WINEPATH", "A:\\owned")
            put("PULSE_SERVER", "/owned/pulse")
        }
        val before = env.toString()
        ForeverPanvkWine.applySelectionEnvironment(env, ForeverPanvkWine.EnvironmentSelection(
            driverPath = "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wine/RUN-env/",
            driverName = ForeverPanvkWine.FILE_NAME,
            hooksPath = "/data/user/0/com.wowforever.arm64probe/files/imagefs/usr/lib",
            assetSha256 = ForeverPanvkWine.APPROVED_SHA256,
            wrapperSha256 = ForeverPanvkWine.EXPECTED_WRAPPER_SHA256,
        ))
        val after = EnvVars(before)
        after.put("ADRENOTOOLS_DRIVER_PATH", env.get("ADRENOTOOLS_DRIVER_PATH"))
        after.put("ADRENOTOOLS_DRIVER_NAME", env.get("ADRENOTOOLS_DRIVER_NAME"))
        after.put("ADRENOTOOLS_HOOKS_PATH", env.get("ADRENOTOOLS_HOOKS_PATH"))
        assertEquals(after.toString(), env.toString())
        assertTrue(env.get("ADRENOTOOLS_DRIVER_PATH").endsWith("/"))
        assertEquals(ForeverPanvkWine.FILE_NAME, env.get("ADRENOTOOLS_DRIVER_NAME"))
        assertEquals("/owned/wrapper_icd.aarch64.json", env.get("VK_ICD_FILENAMES"))
        assertEquals("/owned/shm:/owned/evshim:/owned/wx", env.get("LD_PRELOAD"))
        assertFalse(env.has("REDIRECT_DIR"))
    }

    @Test
    fun pinnedAssetIsStreamedBoundedAndVerifiedThenReusedExactly() {
        val fixture = fixture()
        try {
            val first = ForeverPanvkWine.installFixture(fixture.root, fixture.bytes, fixture.pin)
            val bytesBefore = first.readBytes()
            val second = ForeverPanvkWine.installFixture(fixture.root, fixture.bytes, fixture.pin)
            assertEquals(first.canonicalFile, second.canonicalFile)
            assertArrayEquals(bytesBefore, second.readBytes())
            assertEquals(0x180, mode(first))
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun unknownRacedTargetAndSymlinkEscapeAreRejectedWithoutReplacementOrDeletion() {
        val fixture = fixture()
        try {
            val target = fixture.root.resolve("forever/controls/panvk-wine/fixture-run/${ForeverPanvkWine.FILE_NAME}")
            target.parentFile.mkdirs()
            val unknown = ByteArray(fixture.bytes.size) { 7 }
            target.writeBytes(unknown)
            assertThrows {
                ForeverPanvkWine.installFixture(fixture.root, fixture.bytes, fixture.pin)
            }
            assertArrayEquals(unknown, target.readBytes())

            target.delete()
            val outside = fixture.parent.resolve("outside.so").also { it.writeBytes(fixture.bytes) }
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            assertThrows {
                ForeverPanvkWine.installFixture(fixture.root, fixture.bytes, fixture.pin)
            }
            assertTrue(Files.isSymbolicLink(target.toPath()))
            assertArrayEquals(fixture.bytes, outside.readBytes())

            target.delete()
            val run = target.parentFile
            run.deleteRecursively()
            Files.createSymbolicLink(run.toPath(), fixture.parent.resolve("outside-dir").apply { mkdirs() }.toPath())
            assertThrows {
                ForeverPanvkWine.installFixture(fixture.root, fixture.bytes, fixture.pin)
            }
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun shortAndZeroReadsDoNotBypassLengthOrElfChecks() {
        val fixture = fixture()
        try {
            val fragmented = object : InputStream() {
                private var index = 0
                private var returnedZero = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!returnedZero) {
                        returnedZero = true
                        return 0
                    }
                    if (index == fixture.bytes.size) return -1
                    buffer[offset] = fixture.bytes[index++]
                    return 1
                }
                override fun read(): Int = if (index == fixture.bytes.size) -1 else fixture.bytes[index++].toInt() and 0xff
            }
            assertTrue(ForeverPanvkWine.installFixtureStream(fixture.root, fragmented, fixture.pin).isFile)
        } finally {
            fixture.parent.deleteRecursively()
        }

        val bad = fixture()
        try {
            val badHash = bad.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            assertThrows {
                ForeverPanvkWine.installFixture(bad.root, badHash, bad.pin)
            }
            val badHeader = ByteArray(bad.bytes.size) { 3 }
            val badHeaderHash = sha256(badHeader)
            assertThrows {
                ForeverPanvkWine.installFixture(bad.root, badHeader, ForeverPanvkWine.AssetPin(badHeaderHash, badHeader.size.toLong()))
            }
        } finally {
            bad.parent.deleteRecursively()
        }
    }

    @Test
    fun cancelledAndStaleStoreStatesAreNotActiveForTheSelection() {
        val parent = Files.createTempDirectory("panvk-wine-store").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(parent)
            val started = store.begin()
            assertTrue(ForeverPanvkWine.activeRunMatches(store.readCurrent(), started.runId))
            store.finish(started.runId, ForeverRunStatus.CANCELLED, "STOP")
            assertFalse(ForeverPanvkWine.activeRunMatches(store.readCurrent(), started.runId))
            assertFalse(ForeverPanvkWine.activeRunMatches(store.readCurrent(), "RUN-stale"))
            val env = EnvVars().apply { put("WOWFOREVER_RUN_ID", started.runId) }
            assertThrows {
                ForeverPanvkWine.commitSelection(store, started.runId, env, ForeverPanvkWine.EnvironmentSelection(
                    "/owned/panvk/", ForeverPanvkWine.FILE_NAME, "/owned/hooks/",
                    ForeverPanvkWine.APPROVED_SHA256, ForeverPanvkWine.EXPECTED_WRAPPER_SHA256,
                ))
            }
            assertFalse(env.has("ADRENOTOOLS_DRIVER_PATH"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun finalSelectionJournalAndEnvironmentCommitShareTheStoreMonitor() {
        val parent = Files.createTempDirectory("panvk-wine-transaction").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(parent)
            val run = store.begin()
            val env = EnvVars().apply { put("WOWFOREVER_RUN_ID", run.runId); put("LD_PRELOAD", "/owned/wx") }
            ForeverPanvkWine.commitSelection(store, run.runId, env, ForeverPanvkWine.EnvironmentSelection(
                driverPath = "/owned/panvk-wine/${run.runId}/",
                driverName = ForeverPanvkWine.FILE_NAME,
                hooksPath = "/owned/imagefs/usr/lib",
                assetSha256 = ForeverPanvkWine.APPROVED_SHA256,
                wrapperSha256 = ForeverPanvkWine.EXPECTED_WRAPPER_SHA256,
            ))
            assertEquals(ForeverPanvkWine.FILE_NAME, env.get("ADRENOTOOLS_DRIVER_NAME"))
            assertEquals("/owned/wx", env.get("LD_PRELOAD"))
            val journal = store.outputDirectory(run.runId).parentFile.resolve("${run.runId}-diagnostics.jsonl").readText()
            assertTrue(journal.contains("panvk_wine_selection"))
            assertFalse(journal.contains("mappingProof"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun verifiedBuiltinCompilerIsCommittedWithTheThreeExistingKeysAndRawWineOverrides() {
        val parent = Files.createTempDirectory("panvk-wine-compiler-transaction").toFile()
        try {
            val store = ForeverRunStoreRegistry.forDirectory(parent)
            val run = store.begin()
            val env = EnvVars().apply {
                put("WOWFOREVER_RUN_ID", run.runId)
                put("WINEDLLOVERRIDES", "dxgi=n;d3d11=n,b;d3dcompiler_47=n;foo=unknown")
                put("LD_PRELOAD", "/owned/wx")
            }
            ForeverPanvkWine.commitSelection(
                store,
                run.runId,
                env,
                ForeverPanvkWine.EnvironmentSelection(
                    "/owned/panvk-wine/${run.runId}/",
                    ForeverPanvkWine.FILE_NAME,
                    "/owned/imagefs/usr/lib",
                    ForeverPanvkWine.APPROVED_SHA256,
                    ForeverPanvkWine.EXPECTED_WRAPPER_SHA256,
                ),
                ForeverBuiltinShaderCompiler.Selection(
                    "/owned/imagefs_shared/proton/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/d3dcompiler_47.dll",
                    ForeverBuiltinShaderCompiler.APPROVED_SHA256,
                    ForeverBuiltinShaderCompiler.APPROVED_SIZE,
                ),
            )
            assertEquals("dxgi=n;d3d11=n,b;foo=unknown;d3dcompiler_47=b", env.get("WINEDLLOVERRIDES"))
            assertEquals("/owned/wx", env.get("LD_PRELOAD"))
            val journal = store.outputDirectory(run.runId).parentFile.resolve("${run.runId}-diagnostics.jsonl").readText()
            assertTrue(journal.contains("builtin_shader_compiler_selection"))
            assertTrue(journal.contains(ForeverBuiltinShaderCompiler.APPROVED_SHA256))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun packagedPinnedAssetIsReadThroughTheRealAssetManagerWhenPresent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.wowforever.arm64probe", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.DEBUG)
        assertEquals("com.wowforever.arm64probe", context.packageName)
        val input = context.assets.open(ForeverPanvkWine.ASSET_PATH)
        input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(count > 0)
                digest.update(buffer, 0, count)
                total += count
                assertTrue(total <= ForeverPanvkWine.APPROVED_SIZE)
            }
            assertEquals(ForeverPanvkWine.APPROVED_SIZE, total)
            assertEquals(ForeverPanvkWine.APPROVED_SHA256, digest.digest().joinToString("") { b -> "%02x".format(b) })
        }
    }

    private data class Fixture(val parent: File, val root: File, val bytes: ByteArray, val pin: ForeverPanvkWine.AssetPin)

    private fun fixture(): Fixture {
        val parent = Files.createTempDirectory("panvk wine fixture").toFile()
        val root = parent.resolve("files").also { assertTrue(it.mkdirs()) }
        val bytes = fakeElf()
        return Fixture(parent, root, bytes, ForeverPanvkWine.AssetPin(sha256(bytes), bytes.size.toLong()))
    }

    private fun fakeElf(): ByteArray {
        val bytes = ByteArray(512) { index -> (index * 17).toByte() }
        bytes[0] = 0x7f.toByte()
        bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2.toByte(); bytes[5] = 1.toByte()
        ByteBuffer.wrap(bytes, 16, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(3.toShort())
        ByteBuffer.wrap(bytes, 18, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(183.toShort())
        return bytes
    }

    private fun mode(file: File): Int = android.system.Os.stat(file.absolutePath).st_mode and 0x1ff

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun managedProbe(runId: String): Container = Container("probe-$runId").apply {
        putExtra("foreverManaged", "true")
        putExtra("foreverLaunchKind", ForeverPanvkWine.PROFILE)
        putExtra("foreverPanvkWineProbe", "true")
        putExtra("foreverRunId", runId)
        setExecutablePath(ForeverGraphicsProbe.FILE_NAME)
    }

    private fun completeProbeProfile(runId: String): Container = managedProbe(runId).apply {
        putExtra("foreverScopedNoOpenGL", "true")
        setContainerVariant(Container.BIONIC)
        setGraphicsDriver("wrapper")
        setGraphicsDriverVersion("System")
        setDisplayRenderer("vulkan")
        setWineVersion("Proton-proton-11.0-90624-arm64ec-1")
        setDXWrapper("dxvk-2.4.1-wow-aarch64-test")
        setDXWrapperConfig("version=2.4.1-wow-aarch64-test,framerate=0,maxDeviceMemory=0,async=0,asyncCache=0")
        setExecArgs(ForeverGraphicsProbe.exactArgs(runId))
        putExtra("foreverGraphicsProbeArgs", ForeverGraphicsProbe.exactArgs(runId))
        putExtra("foreverGraphicsProbeLog", ForeverGraphicsProbe.logPath(runId))
        putExtra("foreverVerifiedExe", "/outside/GraphicsProbe-ARM64.exe")
    }

    private fun fixedDebugContext(base: Context): Context = object : ContextWrapper(base) {
        override fun getPackageName(): String = "com.wowforever.arm64probe"
        override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
            flags = flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        }
    }

    private fun assetFailingContext(base: Context): Context = object : ContextWrapper(base) {
        override fun getAssets(): AssetManager = error("PanVK policy gate must not read APK assets")
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
