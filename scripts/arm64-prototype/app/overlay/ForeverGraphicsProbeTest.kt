package app.gamenative

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverGraphicsProbeTest {
    @Test
    fun publicArtifactPinHasOneExplicitBoundedAuthority() {
        val pin = ForeverGraphicsProbe.publicPin()
        assertEquals("80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de", pin.sha256)
        assertEquals(96768L, pin.size)
    }

    @Test
    fun knownFixtureInstallsOnceAndReturnsExactProbeContract() {
        val fixture = fixture()
        try {
            val installed = ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            val second = ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            assertEquals("GraphicsProbeReadback-ARM64.exe", installed.file.name)
            assertEquals(fixture.pin.sha256, installed.sha256)
            assertEquals(fixture.bytes.size.toLong(), installed.size)
            assertEquals(ForeverGraphicsProbe.PE_MACHINE, installed.machine)
            assertEquals(installed.file.canonicalPath, second.file.canonicalPath)
            assertEquals(ForeverGraphicsProbe.exactArgs("RUN-probe"), "--log A:\\Logs\\RUN-probe-graphics-probe.jsonl --run-id RUN-probe")
            assertEquals(listOf("wine", "A:\\GraphicsProbeReadback-ARM64.exe", "--log", "A:\\Logs\\RUN-probe-graphics-probe.jsonl", "--run-id", "RUN-probe"),
                ForeverGraphicsProbe.wineCommand(ForeverGraphicsProbe.WINDOWS_TARGET, ForeverGraphicsProbe.exactArgs("RUN-probe")).split(" "))
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun newCompilerLogProbeDoesNotOverwriteTheLegacyProbeTarget() {
        val fixture = fixture()
        try {
            val legacy = File(fixture.root, "GraphicsProbe-ARM64.exe")
            val legacyBytes = byteArrayOf(1, 9, 8, 9, 6)
            legacy.writeBytes(legacyBytes)
            ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            assertArrayEquals(legacyBytes, legacy.readBytes())
            assertTrue(File(fixture.root, ForeverGraphicsProbe.FILE_NAME).isFile)
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun unknownOrSymlinkedDestinationIsNeverOverwritten() {
        val fixture = fixture()
        try {
            val target = File(fixture.root, ForeverGraphicsProbe.FILE_NAME)
            target.writeBytes(ByteArray(fixture.bytes.size))
            assertThrows(IllegalArgumentException::class.java) {
                ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            }
            target.delete()
            val outside = File(fixture.parent, "outside.exe").also { it.writeBytes(fixture.bytes) }
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            }
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun validatorKeepsRunRootAndArgumentGatesSeparateFromTheOfficialCatalog() {
        val fixture = fixture()
        try {
            val verified = ForeverGraphicsProbe.installFixture(fixture.root, fixture.manifest, fixture.bytes, fixture.pin)
            val base = ForeverGraphicsProbe.ValidationInput(
                managed = true,
                runId = "RUN-probe",
                currentRunId = "RUN-probe",
                currentStatus = ForeverRunStatus.ACTIVE,
                registeredGameFolder = fixture.root,
                gameFolder = fixture.root,
                aDriveFolders = listOf(fixture.root),
                executablePath = ForeverGraphicsProbe.FILE_NAME,
                verifiedExecutableExtra = verified.file.canonicalPath,
                verifiedFile = verified.file,
                cwd = fixture.root,
                requestedArgs = ForeverGraphicsProbe.exactArgs("RUN-probe"),
                expectedSha256 = fixture.pin.sha256,
                expectedSize = fixture.pin.size,
                actualSha256 = fixture.pin.sha256,
                actualSize = fixture.pin.size,
                peMachine = ForeverGraphicsProbe.PE_MACHINE,
                peOptionalMagic = ForeverGraphicsProbe.PE_OPTIONAL_MAGIC,
            )
            assertEquals(ForeverGraphicsProbe.WINDOWS_TARGET, ForeverGraphicsProbe.validate(base).command)
            for (bad in listOf(
                base.copy(currentRunId = "RUN-other"),
                base.copy(currentStatus = ForeverRunStatus.CANCELLED),
                base.copy(verifiedExecutableExtra = File(fixture.root, "other.exe").canonicalPath),
                base.copy(requestedArgs = "--run-id RUN-probe --log A:\\Logs\\RUN-probe-graphics-probe.jsonl"),
                base.copy(executablePath = "unknown.exe"),
            )) {
                assertThrows(IllegalArgumentException::class.java) { ForeverGraphicsProbe.validate(bad) }
            }
        } finally {
            fixture.parent.deleteRecursively()
        }
    }

    @Test
    fun rootVetoCoversOnlyTheTwoFixedExecutablesWhenProbeIsFrozen() {
        assertTrue(ForeverSpawnGate.matchesManagedRoot("wine A:\\WowB-ARM64.exe", null, allowMissingEnv = true))
        assertTrue(ForeverSpawnGate.matchesManagedRoot("wine A:\\GraphicsProbeReadback-ARM64.exe", null, allowMissingEnv = true))
        assertFalse(ForeverSpawnGate.matchesManagedRoot("wine A:\\arbitrary.exe", null, allowMissingEnv = true))
        assertFalse(ForeverSpawnGate.matches("RUN-probe", ForeverGraphicsProbe.FILE_NAME, "wine A:\\GraphicsProbeReadback-ARM64.exe",
            arrayOf("WOWFOREVER_RUN_ID=RUN-probe", "WOWFOREVER_RUN_ID=RUN-other")))
    }

    @Test
    fun actualDesktopSplitterReceivesTargetAndArgumentsExactlyOnce() {
        val command = "wine explorer /desktop=shell,1280x720 " + ForeverGraphicsProbe.WINDOWS_TARGET + " " + ForeverGraphicsProbe.exactArgs("RUN-probe")
        assertEquals(listOf("wine", "explorer", "/desktop=shell,1280x720", "A:\\GraphicsProbeReadback-ARM64.exe", "--log", "A:\\Logs\\RUN-probe-graphics-probe.jsonl", "--run-id", "RUN-probe"),
            com.winlator.core.ProcessHelper.splitCommand(command).toList())
        val wine = File("/owned/proton/bin/wine")
        val final = "/system/bin/linker64 ${wine.absolutePath} " + command.removePrefix("wine ")
        assertTrue(ForeverSpawnGate.probeCommandMatches(final, "RUN-probe", wine))
        assertFalse(ForeverSpawnGate.probeCommandMatches(final + " unexpected", "RUN-probe", wine))
        assertFalse(ForeverSpawnGate.probeCommandMatches(final.replace("RUN-probe", "RUN-other"), "RUN-probe", wine))
        assertFalse(ForeverSpawnGate.probeCommandMatches("/other.exe " + final, "RUN-probe", wine))
    }

    @Test
    fun logsAreCreatedForFreshProbeAndSymlinkLogsAreRejected() {
        val fixture = fixture()
        try {
            val logs = ForeverGraphicsProbe.ensureLogsDirectory(fixture.root)
            assertTrue(logs.isDirectory)
            assertTrue(logs.delete())
            val outside = File(fixture.parent, "outside-logs").also { it.mkdir() }
            Files.createSymbolicLink(logs.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { ForeverGraphicsProbe.ensureLogsDirectory(fixture.root) }
        } finally { fixture.parent.deleteRecursively() }
    }

    private data class Fixture(val parent: File, val root: File, val bytes: ByteArray, val pin: ForeverGraphicsProbe.ProbePin, val manifest: String)

    private fun fixture(): Fixture {
        val parent = Files.createTempDirectory("forever graphics probe ").toFile()
        val root = File(parent, "WoW Forever").also { assertTrue(it.mkdirs()) }
        val bytes = fakePe()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val pin = ForeverGraphicsProbe.ProbePin(hash, bytes.size.toLong())
        val manifest = "{\"schema\":1,\"file\":\"GraphicsProbeReadback-ARM64.exe\",\"size\":${bytes.size},\"sha256\":\"$hash\",\"machine\":${ForeverGraphicsProbe.PE_MACHINE}}"
        return Fixture(parent, root, bytes, pin, manifest)
    }

    private fun fakePe(): ByteArray {
        val bytes = ByteArray(512) { index -> (index * 13).toByte() }
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x80)
        bytes[0x80] = 'P'.code.toByte(); bytes[0x81] = 'E'.code.toByte()
        bytes[0x82] = 0; bytes[0x83] = 0
        ByteBuffer.wrap(bytes, 0x84, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(ForeverGraphicsProbe.PE_MACHINE.toShort())
        ByteBuffer.wrap(bytes, 0x98, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(ForeverGraphicsProbe.PE_OPTIONAL_MAGIC.toShort())
        return bytes
    }
}
