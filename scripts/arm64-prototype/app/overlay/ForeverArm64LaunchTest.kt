package app.gamenative

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForeverArm64LaunchTest {
    @Test
    fun validAa64PeWithSpacesSelectsUnquotedTargetWithTheRealSplitter() {
        val fixture = fixture(machine = 0xaa64)

        val result = ForeverArm64Launch.validate(fixture.input)

        assertEquals("A:\\WowB-ARM64.exe", result.command)
        assertEquals(fixture.root.canonicalPath, result.canonicalCwd)
        assertEquals(fixture.record.decodedMd5, result.md5)
        val command = ForeverArm64Launch.wineCommand(result.command, fixture.input.requestedArgs)
        assertEquals(listOf("wine", "A:\\WowB-ARM64.exe", "-config", "BetaSuspendedTest.wtf", "-d3d11"),
            com.winlator.core.ProcessHelper.splitCommand(command).toList())
    }

    @Test
    fun formatterRejectsWrappersQuotedTargetsAndChangedFlags() {
        for (target in listOf("\"A:\\WowB-ARM64.exe\"", "explorer", "start.exe", "winhandler.exe", "B:\\WowB-ARM64.exe")) {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverArm64Launch.wineCommand(target, "-config BetaSuspendedTest.wtf -d3d11")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.wineCommand("A:\\WowB-ARM64.exe", "-config Wrong.wtf -d3d11")
        }
    }

    @Test
    fun desktopGraphicsAndUnmanagedBranchesRemainUpstream() {
        for (managed in listOf(false, true)) for (custom in listOf(false, true))
            for (desktop in listOf(false, true)) for (graphics in listOf(false, true)) {
                assertEquals(managed && custom && !desktop && !graphics,
                    ForeverArm64Launch.useDirectRoute(managed, custom, desktop, graphics))
            }
    }

    @Test
    fun neutralArchitecturesAndFakeArm64NameAreRejected() {
        for (machine in listOf(0x8664, 0x14c, 0xa641)) {
            assertThrows(IllegalArgumentException::class.java) { fixture(machine = machine).input.let(ForeverArm64Launch::validate) }
        }

        val fixture = fixture(machine = 0xaa64)
        val renamed = File(fixture.root, "not-really-ARM64.exe")
        fixture.exe.copyTo(renamed)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(
                verifiedFile = renamed,
                verifiedExecutableExtra = renamed.canonicalPath,
            ))
        }
    }

    @Test
    fun malformedMzOrPe32HeaderIsRejected() {
        val mz = fixture(machine = 0xaa64)
        mz.exe.writeBytes(ByteArray(512))
        assertThrows(IllegalArgumentException::class.java) { ForeverArm64Launch.validate(mz.input) }

        val pe32 = fixture(machine = 0xaa64)
        val bytes = pe32.exe.readBytes()
        ByteBuffer.wrap(bytes, 0x98, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x10b.toShort())
        pe32.exe.writeBytes(bytes)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(pe32.input.copy(
                catalogRecord = pe32.record.copy(decodedMd5 = md5),
            ))
        }
    }

    @Test
    fun pathsMustStayInsideTheRegisteredAContentsAndUseApprovedTarget() {
        val fixture = fixture(machine = 0xaa64)
        val outside = Files.createTempDirectory("forever-aa64-outside").toFile()
        val outsideExe = File(outside, "WowB-ARM64.exe").also { fixture.exe.copyTo(it) }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(
                verifiedFile = outsideExe,
                verifiedExecutableExtra = outsideExe.canonicalPath,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(executablePath = "../WowB-ARM64.exe"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(executablePath = "B:\\WowB-ARM64.exe"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(executablePath = "/tmp/WowB-ARM64.exe"))
        }
    }

    @Test
    fun externalExecutableSymlinkIsRejected() {
        val fixture = fixture(machine = 0xaa64)
        val outside = Files.createTempDirectory("forever-aa64-link-outside").toFile()
        val outsideExe = File(outside, "WowB-ARM64.exe").also { fixture.exe.copyTo(it) }
        Files.delete(fixture.exe.toPath())
        Files.createSymbolicLink(fixture.exe.toPath(), outsideExe.toPath())

        assertThrows(IllegalArgumentException::class.java) { ForeverArm64Launch.validate(fixture.input) }
    }

    @Test
    fun runIdentityAndExactFlagsAreFailClosed() {
        val fixture = fixture(machine = 0xaa64)
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(currentRunId = "RUN-other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(currentStatus = ForeverRunStatus.INTERRUPTED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(managed = false))
        }
        for (args in listOf(
            "-config BetaSuspendedTest.wtf -d3d11 -extra",
            "-config Wrong.wtf -d3d11",
            "-d3d11 -config BetaSuspendedTest.wtf",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverArm64Launch.validate(fixture.input.copy(requestedArgs = args))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(
                catalogRecord = fixture.record.copy(decodedMd5 = "00000000000000000000000000000000"),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.validate(fixture.input.copy(
                catalogRecord = fixture.record.copy(decodedSize = fixture.record.decodedSize + 1),
            ))
        }
    }

    @Test
    fun catalogMustHaveOneOfficialLooseExecutableRecord() {
        val record = JSONObject()
            .put("relative_path", "WowB-ARM64.exe")
            .put("decoded_md5", "0123456789abcdef0123456789abcdef")
            .put("decoded_size", 10)
        val duplicate = JSONObject(record.toString())
        val catalog = JSONObject().put("loose_files", JSONArray().put(record).put(duplicate))
        assertThrows(IllegalArgumentException::class.java) {
            ForeverArm64Launch.catalogRecordForTest(catalog)
        }
    }

    private data class Fixture(
        val root: File,
        val exe: File,
        val record: ForeverArm64Launch.CatalogExecutableRecord,
        val input: ForeverArm64Launch.ValidationInput,
    )

    private fun fixture(machine: Int): Fixture {
        val root = Files.createTempDirectory("forever aa64 managed root ").toFile()
        val exe = File(root, "WowB-ARM64.exe")
        val bytes = fakePe(machine)
        exe.writeBytes(bytes)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val record = ForeverArm64Launch.CatalogExecutableRecord("WowB-ARM64.exe", md5, bytes.size.toLong())
        val canonical = root.canonicalFile
        val input = ForeverArm64Launch.ValidationInput(
            managed = true,
            runId = "RUN-fixture",
            currentRunId = "RUN-fixture",
            currentStatus = ForeverRunStatus.ACTIVE,
            registeredGameFolder = canonical,
            gameFolder = canonical,
            aDriveFolders = listOf(canonical),
            executablePath = "WowB-ARM64.exe",
            verifiedExecutableExtra = File(canonical, "WowB-ARM64.exe").canonicalPath,
            requestedArgs = "-config BetaSuspendedTest.wtf -d3d11",
            verifiedFile = exe,
            cwd = canonical,
            catalogRecord = record,
        )
        return Fixture(root, exe, record, input)
    }

    private fun fakePe(machine: Int): ByteArray {
        val bytes = ByteArray(512) { index -> (index * 17).toByte() }
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x80)
        bytes[0x80] = 'P'.code.toByte()
        bytes[0x81] = 'E'.code.toByte()
        bytes[0x82] = 0
        bytes[0x83] = 0
        ByteBuffer.wrap(bytes, 0x84, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(machine.toShort())
        ByteBuffer.wrap(bytes, 0x98, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x20b.toShort())
        return bytes
    }
}
