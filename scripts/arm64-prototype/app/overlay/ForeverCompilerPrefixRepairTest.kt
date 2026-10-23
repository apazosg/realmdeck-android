package app.gamenative

import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [PanvkWinePosixShadow::class])
class ForeverCompilerPrefixRepairTest {
    @Before
    fun useRealFixtureUid() {
        val fixture = Files.createTempDirectory("compiler-prefix-uid")
        try {
            ShadowProcess.setUid(Os.stat(fixture.toString()).st_uid)
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @After
    fun restoreUid() {
        ShadowProcess.reset()
    }

    @Test
    fun freshBootstrapUsesOnlyThePinnedTargetAndKeepsOtherDllsUntouched() {
        val f = fixture()
        try {
            val beforeOther = f.other.readBytes()
            val receipt = repair(f)
            assertFalse(receipt.reused)
            assertEquals(f.originalHash, receipt.originalSha256)
            assertEquals(f.installedHash, receipt.installedSha256)
            assertEquals(f.aa64.readBytes().toList(), f.target.readBytes().toList())
            assertArrayEquals(beforeOther, f.other.readBytes())
            ForeverCompilerPrefixRepair.verifyBeforeSpawnForTests(f.root, currentRun(f), f.prefix)
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun knownAa64TargetIsReusedWithoutCreatingAReplacementBackup() {
        val f = fixture(targetBytes = fBytes(machine = AA64))
        try {
            val beforeOther = f.other.readBytes()
            val receipt = repair(f)
            assertTrue(receipt.reused)
            assertEquals("", receipt.backupPath)
            assertArrayEquals(beforeOther, f.other.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun completedFixtureReceiptUsesTheSameSyntheticPinsOnRetry() {
        val f = fixture()
        try {
            val run = ForeverRunStoreRegistry.forDirectory(f.root).begin().runId
            val first = repairAt(f, f.prefix, f.target, runId = run)
            val second = repairAt(f, f.prefix, f.target, runId = run)
            assertEquals(first.transactionPath, second.transactionPath)
            assertEquals(first.installedSha256, second.installedSha256)
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun preSpawnVerificationOfAMissingReceiptDoesNotCreateControlDirectories() {
        val f = fixture()
        try {
            assertFails { ForeverCompilerPrefixRepair.verifyBeforeSpawnForTests(f.root, "RUN-missing", f.prefix) }
            assertFalse(f.root.resolve("forever").exists())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun unknownTargetIsStoppedAndDoesNotTouchTheSentinel() {
        val f = fixture(targetBytes = ByteArray(1024) { 7 })
        try {
            val before = f.target.readBytes()
            assertFails { repair(f) }
            assertArrayEquals(before, f.target.readBytes())
            assertArrayEquals(f.otherBytes, f.other.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun unknownSourceIsStoppedBeforeAnyTargetMutation() {
        val f = fixture()
        try {
            val before = f.target.readBytes()
            f.source.writeBytes(ByteArray(1024) { 8 })
            assertFails { repair(f) }
            assertArrayEquals(before, f.target.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun sourceTargetAndAncestorSymlinksAreRejected() {
        val f = fixture()
        try {
            val outside = f.parent.resolve("outside.dll").also { it.writeBytes(f.aa64.readBytes()) }
            f.source.delete()
            Files.createSymbolicLink(f.source.toPath(), outside.toPath())
            assertFails { repair(f) }
            f.source.delete(); f.source.writeBytes(f.aa64.readBytes())
            f.target.delete(); Files.createSymbolicLink(f.target.toPath(), outside.toPath())
            assertFails { repair(f) }
            f.target.delete(); f.system32.deleteRecursively()
            Files.createSymbolicLink(f.system32.toPath(), f.parent.resolve("outside-system32").apply { mkdirs() }.toPath())
            assertFails { repair(f) }
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun cancellationAndIdentityRaceHappenBeforeAtomicMove() {
        val f = fixture()
        try {
            val before = f.target.readBytes()
            assertFailsContaining("active pre-launch RUN") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(beforeCommit = {
                    ForeverRunStoreRegistry.forDirectory(f.root).finish(currentRun(f), ForeverRunStatus.CANCELLED, "fixture_cancel")
                }))
            }
            assertArrayEquals(before, f.target.readBytes())
            val g = fixture()
            try {
                val originalModified = Files.getLastModifiedTime(g.target.toPath()).toMillis()
                assertFailsContaining("target changed before atomic commit") {
                    repair(g, ForeverCompilerPrefixRepair.TestHooks(beforeCommit = {
                        Files.setLastModifiedTime(g.target.toPath(), java.nio.file.attribute.FileTime.fromMillis(originalModified + 10_000))
                    }))
                }
                assertArrayEquals(g.originalBytes, g.target.readBytes())
            } finally { g.parent.deleteRecursively() }
            val h = fixture()
            try {
                assertFailsContaining("cancelled") {
                    repair(h, ForeverCompilerPrefixRepair.TestHooks(beforeCommit = {
                        Thread.currentThread().interrupt()
                    }))
                }
                assertArrayEquals(h.originalBytes, h.target.readBytes())
            } finally {
                Thread.interrupted()
                h.parent.deleteRecursively()
            }
        } finally {
            Thread.interrupted()
            f.parent.deleteRecursively()
        }
    }

    @Test
    fun partialWriteAndUnsupportedAtomicMoveLeaveTargetUnchanged() {
        val f = fixture()
        try {
            val before = f.target.readBytes()
            assertFails { repair(f, ForeverCompilerPrefixRepair.TestHooks(failAfterBytes = 17)) }
            assertArrayEquals(before, f.target.readBytes())
            val g = fixture()
            try {
                assertFailsContaining("atomic compiler replacement is unsupported") {
                    repair(g, ForeverCompilerPrefixRepair.TestHooks(move = { source, target ->
                        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture")
                    }))
                }
                assertArrayEquals(g.originalBytes, g.target.readBytes())
            } finally { g.parent.deleteRecursively() }
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun fsyncFailureLeavesTheOriginalTargetAndRecoveryArtifacts() {
        val f = fixture()
        try {
            val before = f.target.readBytes()
            assertFailsContaining("injected fsync failure") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(failFsync = true))
            }
            assertArrayEquals(before, f.target.readBytes())
            val runDirectory = f.root.resolve("forever/controls/compiler-prefix/${currentRun(f)}")
            assertTrue(runDirectory.listFiles().orEmpty().any { it.name.startsWith("backup-") })
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun rollbackRequiresQuiescenceAndRestoresOnlyTheKnownOriginal() {
        val f = fixture()
        try {
            val receipt = repair(f)
            assertFalse(receipt.reused)
            val rolled = ForeverCompilerPrefixRepair.rollbackForTests(f.root, currentRun(f))
            assertEquals(f.originalHash, rolled.installedSha256)
            assertArrayEquals(f.originalBytes, f.target.readBytes())
            assertArrayEquals(f.otherBytes, f.other.readBytes())
            assertFails { ForeverCompilerPrefixRepair.rollbackForTests(f.root, currentRun(f)) }
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun rollbackBlocksAnUnknownAa64Target() {
        val f = fixture()
        try {
            repair(f)
            f.target.writeBytes(ByteArray(f.aa64.length().toInt()) { 3 })
            assertFails { ForeverCompilerPrefixRepair.rollbackForTests(f.root, currentRun(f)) }
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun rollbackRevalidatesTheTargetInsideItsStoreMonitor() {
        val f = fixture()
        try {
            repair(f)
            val originalModified = Files.getLastModifiedTime(f.target.toPath()).toMillis()
            assertFailsContaining("target changed before rollback commit") {
                ForeverCompilerPrefixRepair.rollbackForTests(f.root, currentRun(f), ForeverCompilerPrefixRepair.TestHooks(
                    beforeCommit = {
                        Files.setLastModifiedTime(f.target.toPath(), java.nio.file.attribute.FileTime.fromMillis(originalModified + 10_000))
                    },
                ))
            }
            assertArrayEquals(f.aa64.readBytes(), f.target.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun sourceGrowthIsRejectedWithoutChangingTheTarget() {
        val f = fixture()
        try {
            val before = f.target.readBytes()
            assertFailsContaining("compiler file identity") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(duringSourceCopy = {
                    f.source.appendBytes(byteArrayOf(4))
                }))
            }
            assertArrayEquals(before, f.target.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun onlyTheApprovedImageFsAndSharedHomeAliasesResolveToTheSamePrefix() {
        val imageFs = fixture()
        try {
            val imageFsHome = imageFs.root.resolve("imagefs/home/xuser-fixture")
            imageFsHome.parentFile.mkdirs()
            Files.createSymbolicLink(imageFsHome.toPath(), imageFs.root.resolve("imagefs_shared/home/xuser-fixture").toPath())
            repairAt(imageFs, imageFsHome.resolve(".wine"), imageFsHome.resolve(".wine/drive_c/windows/system32/d3dcompiler_47.dll"))
        } finally { imageFs.parent.deleteRecursively() }

        val shared = fixture()
        try {
            val sharedHome = shared.root.resolve("imagefs/home/xuser")
            sharedHome.parentFile.mkdirs()
            Files.createSymbolicLink(sharedHome.toPath(), shared.root.resolve("imagefs_shared/home/xuser-fixture").toPath())
            repairAt(shared, sharedHome.resolve(".wine"), sharedHome.resolve(".wine/drive_c/windows/system32/d3dcompiler_47.dll"))
        } finally { shared.parent.deleteRecursively() }
    }

    @Test
    fun postCommitReceiptFailureCanBeRecoveredWithoutReplacingTheInstalledInode() {
        val f = fixture()
        try {
            assertFailsContaining("injected post-commit receipt failure") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(failReceiptAfterCommit = true))
            }
            assertArrayEquals(f.aa64.readBytes(), f.target.readBytes())
            val runId = currentRun(f)
            val keyBefore = Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            val receipt = repairAt(f, f.prefix, f.target, runId = runId)
            assertArrayEquals(f.originalBytes, File(receipt.backupPath).readBytes())
            assertEquals(keyBefore, Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            ForeverCompilerPrefixRepair.verifyBeforeSpawnForTests(f.root, runId, f.prefix)
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun stoppingAndCancelledSpawnGateCannotCommitTheRepair() {
        val f = fixture()
        try {
            assertFailsContaining("active pre-launch RUN") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(beforeCommit = {
                    val store = ForeverRunStoreRegistry.forDirectory(f.root)
                    store.update(currentRun(f)) { it.copy(stage = ForeverStage.STOPPING) }
                }))
            }
            assertArrayEquals(f.originalBytes, f.target.readBytes())
            val g = fixture()
            try {
                assertFailsContaining("active pre-launch RUN") {
                    repair(g, ForeverCompilerPrefixRepair.TestHooks(beforeCommit = {
                        ForeverProcessDiagnostics.cancelExpected(currentRun(g))
                    }))
                }
                assertArrayEquals(g.originalBytes, g.target.readBytes())
            } finally { g.parent.deleteRecursively() }
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun interruptedRollbackReceiptIsRecoveredWithoutTouchingTheRestoredInode() {
        val f = fixture()
        try {
            repair(f)
            val runId = currentRun(f)
            assertFailsContaining("injected rollback receipt failure") {
                ForeverCompilerPrefixRepair.rollbackForTests(f.root, runId,
                    ForeverCompilerPrefixRepair.TestHooks(failReceiptAfterCommit = true))
            }
            assertArrayEquals(f.originalBytes, f.target.readBytes())
            val before = Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            ForeverCompilerPrefixRepair.rollbackForTests(f.root, runId)
            assertEquals(before, Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertArrayEquals(f.otherBytes, f.other.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun hardlinkedSourceCannotEnterTheRepairTransaction() {
        val f = fixture()
        try {
            Files.createLink(f.parent.resolve("linked-source.dll").toPath(), f.source.toPath())
            assertFailsContaining("unexpectedly hardlinked") { repair(f) }
            assertArrayEquals(f.originalBytes, f.target.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun interruptedReuseReceiptIsCompletedWithoutBackupOrReplacement() {
        val f = fixture(targetBytes = fBytes(machine = AA64))
        try {
            assertFailsContaining("injected reused receipt failure") {
                repair(f, ForeverCompilerPrefixRepair.TestHooks(failReceiptAfterCommit = true))
            }
            val before = Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            val receipt = repairAt(f, f.prefix, f.target, runId = currentRun(f))
            assertTrue(receipt.reused)
            assertEquals("", receipt.backupPath)
            assertEquals(before, Files.readAttributes(f.target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        } finally { f.parent.deleteRecursively() }
    }

    @Test
    fun cancellationMutationIsSerializedWithTheDurableLaunchMonitor() {
        val f = fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        var thread: Thread? = null
        try {
            val store = ForeverRunStoreRegistry.forDirectory(f.root)
            val runId = store.begin().runId
            synchronized(store) {
                thread = Thread {
                    entered.countDown()
                    ForeverProcessDiagnostics.cancelExpectedUnderStore(runId, store)
                    cancelled.countDown()
                }.also { it.start() }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(cancelled.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertFalse(ForeverProcessDiagnostics.isLaunchCancelled(runId))
            }
            assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(ForeverProcessDiagnostics.isLaunchCancelled(runId))
        } finally {
            thread?.join(2000)
            f.parent.deleteRecursively()
        }
    }

    @Test
    fun androidDataRootSpellingDoesNotBroadenTheOwnedPrefixBoundary() {
        val owner = File("/data/user/0/com.wowforever.arm64probe/files")
        val relative = "imagefs/home/xuser/.wine"
        assertEquals("${owner.path}/$relative", ForeverCompilerPrefixRepair.normalizedOwnedAppAlias(owner,
            File("/data/data/com.wowforever.arm64probe/files/$relative")))
        listOf("/data/data/com.other.app/files/$relative", "/data/user/10/com.wowforever.arm64probe/files/$relative",
            "/data/data/com.wowforever.arm64probe/files-other/$relative").forEach { path ->
            assertEquals(path, ForeverCompilerPrefixRepair.normalizedOwnedAppAlias(owner, File(path)))
        }
    }

    @Test
    fun rollbackIsBlockedAfterARecordedLaunchAttempt() {
        val f = fixture()
        try {
            repair(f)
            val store = ForeverRunStoreRegistry.forDirectory(f.root)
            store.markLaunchAttempt(currentRun(f))
            assertFails { ForeverCompilerPrefixRepair.rollbackForTests(f.root, currentRun(f)) }
            assertArrayEquals(f.aa64.readBytes(), f.target.readBytes())
        } finally { f.parent.deleteRecursively() }
    }

    private data class Fixture(
        val parent: File,
        val root: File,
        val prefix: File,
        val system32: File,
        val source: File,
        val target: File,
        val aa64: File,
        val other: File,
        val runId: String,
        val originalBytes: ByteArray,
        val otherBytes: ByteArray,
        val originalHash: String,
        val installedHash: String,
    )

    private fun fixture(targetBytes: ByteArray = fBytes(machine = X64)): Fixture {
        val parent = Files.createTempDirectory("compiler-prefix-fixture").toFile()
        val root = parent.resolve("files").also { it.mkdirs() }
        val prefix = root.resolve("imagefs_shared/home/xuser-fixture/.wine").also { it.mkdirs() }
        val system32 = prefix.resolve("drive_c/windows/system32").also { it.mkdirs() }
        val source = root.resolve("imagefs_shared/proton/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/d3dcompiler_47.dll")
            .also { it.parentFile.mkdirs(); it.writeBytes(fBytes(machine = AA64)) }
        val target = system32.resolve("d3dcompiler_47.dll").also { it.writeBytes(targetBytes) }
        val aa64 = parent.resolve("aa64.dll").also { it.writeBytes(fBytes(machine = AA64)) }
        val otherBytes = ByteArray(333) { 41 }
        val other = system32.resolve("d3dcompiler_other.dll").also { it.writeBytes(otherBytes) }
        Files.setPosixFilePermissions(target.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"))
        val originalBytes = fBytes(machine = X64)
        return Fixture(parent, root, prefix, system32, source, target, aa64, other, "RUN-fixture",
            originalBytes, otherBytes, sha(originalBytes), sha(fBytes(machine = AA64)))
    }

    private fun repair(f: Fixture, hooks: ForeverCompilerPrefixRepair.TestHooks = ForeverCompilerPrefixRepair.TestHooks()): ForeverCompilerPrefixRepair.Receipt {
        val store = ForeverRunStoreRegistry.forDirectory(f.root)
        val runId = store.begin().runId
        return repairAt(f, f.prefix, f.target, hooks, runId)
    }

    private fun repairAt(
        f: Fixture,
        prefix: File,
        target: File,
        hooks: ForeverCompilerPrefixRepair.TestHooks = ForeverCompilerPrefixRepair.TestHooks(),
        runId: String? = null,
    ): ForeverCompilerPrefixRepair.Receipt {
        val store = ForeverRunStoreRegistry.forDirectory(f.root)
        val actualRun = runId ?: store.begin().runId
        return ForeverCompilerPrefixRepair.repairForTests(f.root, prefix, f.source, target, actualRun,
            ForeverCompilerPrefixRepair.TestPins(
                ForeverBuiltinShaderCompiler.CompilerPin(sha(fBytes(machine = AA64)), f.source.length()),
                ForeverBuiltinShaderCompiler.CompilerPin(f.originalHash, f.originalBytes.size.toLong()),
                ForeverBuiltinShaderCompiler.CompilerPin(sha(fBytes(machine = AA64)), f.source.length()),
            ), hooks)
    }

    private fun currentRun(f: Fixture): String =
        requireNotNull(ForeverRunStoreRegistry.forDirectory(f.root).readCurrent()).runId

    private fun assertFails(block: () -> Unit) {
        try { block() } catch (_: Throwable) { return }
        throw AssertionError("expected repair failure")
    }

    private fun assertFailsContaining(expected: String, block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            assertTrue("expected '$expected', got '${failure.message}'", failure.message?.contains(expected) == true)
            return
        }
        throw AssertionError("expected repair failure containing '$expected'")
    }

    private fun fBytes(machine: Int): ByteArray {
        val bytes = ByteArray(1024) { (it * 13).toByte() }
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(64)
        bytes[64] = 'P'.code.toByte(); bytes[65] = 'E'.code.toByte(); bytes[66] = 0; bytes[67] = 0
        ByteBuffer.wrap(bytes, 68, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(machine.toShort())
        ByteBuffer.wrap(bytes, 88, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x20b.toShort())
        return bytes
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val X64 = 0x8664
        private const val AA64 = 0xaa64
    }
}
