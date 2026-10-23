package app.gamenative

import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForeverBlteTest {
    @Test
    fun zeroHeaderRawAndZlibDecodeWithExactFileLength() {
        val raw = "zero-header".toByteArray()
        val rawFile = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('N'.code.toByte()) + raw)
        ForeverBlte.verify(rawFile, raw.size.toLong(), md5(raw))

        val compressed = zlib(raw)
        val zFile = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('Z'.code.toByte()) + compressed)
        val output = File.createTempFile("blte-z-", ".out")
        ForeverBlte.decode(zFile, output, raw.size.toLong(), md5(raw))
        assertArrayEquals(raw, output.readBytes())
        rawFile.delete()
        zFile.delete()
        output.delete()
    }

    @Test
    fun extendedChunkChecksumsAreVerified() {
        val raw = "extended-chunk".toByteArray()
        val compressed = zlib(raw)
        val payload = byteArrayOf('Z'.code.toByte()) + compressed
        val header = u32(52) + byteArrayOf(0x10, 0, 0, 1) +
            u32(payload.size) + u32(raw.size) + md5Bytes(payload) + md5Bytes(raw)
        val file = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + header + payload)
        ForeverBlte.verify(file, raw.size.toLong(), md5(raw))
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) {
            ForeverBlte.verify(file, raw.size.toLong(), md5(raw))
        }
        file.delete()
    }

    @Test
    fun malformedZlibAndInflationBombAreRejectedBeforeOutputCommit() {
        val raw = ByteArray(1024) { 7 }
        val compressed = zlib(raw)
        val bomb = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('Z'.code.toByte()) + compressed)
        assertThrows(IllegalArgumentException::class.java) { ForeverBlte.verify(bomb, 1, md5(ByteArray(1))) }

        val truncated = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('Z'.code.toByte(), 0x78))
        assertThrows(IllegalArgumentException::class.java) { ForeverBlte.verify(truncated, 10, "0".repeat(32)) }
        val encrypted = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('E'.code.toByte(), 1))
        val unsupported = assertThrows(IllegalArgumentException::class.java) { ForeverBlte.verify(encrypted, 1, "0".repeat(32)) }
        org.junit.Assert.assertTrue(unsupported.message.orEmpty().contains("unsupported BLTE mode"))
        bomb.delete()
        truncated.delete()
        encrypted.delete()
    }

    @Test
    fun decodeRejectsSymlinkOutputWithoutWritingOutsideManagedPath() {
        val raw = "nofollow".toByteArray()
        val input = fixture(byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32(0) + byteArrayOf('N'.code.toByte()) + raw)
        val root = File.createTempFile("blte-link-", "").also { it.delete(); it.mkdirs() }
        val outside = File.createTempFile("blte-outside-", ".bin").also { it.writeBytes(byteArrayOf(9)) }
        val link = File(root, "output")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(Exception::class.java) { ForeverBlte.decode(input, link, raw.size.toLong(), md5(raw)) }
        assertArrayEquals(byteArrayOf(9), outside.readBytes())
        input.delete()
        outside.delete()
        root.deleteRecursively()
    }

    private fun fixture(bytes: ByteArray): File = File.createTempFile("blte-", ".bin").also { it.writeBytes(bytes) }
    private fun u32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
    private fun zlib(input: ByteArray): ByteArray = Deflater().let { deflater ->
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArray(4096)
        val size = deflater.deflate(output)
        deflater.end()
        output.copyOf(size)
    }
}
