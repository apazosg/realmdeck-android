package app.gamenative

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverCascTest {
    @Test
    fun buildInfoMatchesPinnedProducerSerialization() {
        val fields = JSONObject()
            .put("branch", "us")
            .put("build_key", "05215079e3905ef5922ae0b03ffefb73")
            .put("cdn_key", "9b3c456dbb837d133a026d380c7c13e9")
            .put("install_key", "8d7fa7d8544305bf0491e534e0b8e9aa")
            .put("cdn_path", "tpr/wow")
            .put("cdn_hosts", JSONArray().put("level3.blizzard.com").put("us.cdn.blizzard.com"))
            .put("version", "1.60.1.70009")
            .put("product", "wow_classic_beta")
            .put("platform", "Windows")
            .put("architecture", "arm64")
            .put("locale", "enUS")
            .put("region", "us")
        val bytes = ForeverCasc.buildInfo(fields)
        assertEquals(668, bytes.size)
        assertEquals("e43f5e18b8a1cf88caa7d9999811d8cc5526c9d51d5c5334d1ed916c7a906524", sha256Hex(bytes))
    }

    @Test
    fun materializedStoreMatchesIndependentV7LayoutAndChecksums() {
        val goldenFixture = System.getProperty("wowforever.cascGolden")?.let { JSONObject(File(it).readText()) }
        val root = goldenFixture?.let { File(it.getString("staging_root")).apply {
            check(!exists()) { "golden staging root must be new; regenerate the independent fixture" }
            check(mkdirs())
        } } ?: tempDir()
        val decoded = "casc-golden".toByteArray()
        val raw = blteRaw(decoded)
        val ekey = "0123456789abcdef0123456789abcdef"
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(root, ".build.info\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, file(root, "raw", raw))), { false })

        val bucket = bucket(ekey.hexBytes())
        val indexDir = File(root, "Data/data")
        for (index in 0 until 16) {
            val bytes = File(indexDir, "%02x%08x.idx".format(index, 1)).readBytes()
            assertEquals(0x30000, bytes.size)
            assertEquals(7, u16(bytes, 8))
            assertEquals(index, bytes[10].toInt() and 0xff)
            assertEquals(16, u32(bytes, 0))
            assertEquals(hashlittle(bytes.copyOfRange(8, 24), 0), u32(bytes, 4))
            val blockSize = u32(bytes, 0x20)
            var pc = 0
            var pb = 0
            var at = 0x28
            while (at < 0x28 + blockSize) {
                val entry = bytes.copyOfRange(at, at + 18)
                val hashes = hashlittle2(entry, pc, pb)
                pc = hashes.first
                pb = hashes.second
                at += 18
            }
            assertEquals(pc, u32(bytes, 0x24))
            if (index == bucket) {
                val entryAt = findEntry(bytes, ekey.hexBytes().copyOfRange(0, 9))
                assertEquals(480, u32be(bytes, entryAt + 10) and 0x3fffffff)
                assertEquals(raw.size + 30, u32(bytes, entryAt + 14))
            }
        }

        val archive = File(indexDir, "data.000")
        val segment = archive.readBytes().copyOfRange(0, 480)
        for (slot in 0 until 16) {
            val header = segment.copyOfRange(slot * 30, slot * 30 + 30)
            assertEquals(30, u32(header, 0x10))
            assertEquals(1, u16(header, 0x14))
            assertEquals(hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971), u32(header, 0x16))
            assertEquals(checksumB(header, slot * 30L), u32(header, 0x1a))
        }
        val objectOffset = 480
        val objectHeader = archive.readBytes().copyOfRange(objectOffset, objectOffset + 30)
        assertEquals(ekey.hexBytes().reversedArray().toList(), objectHeader.copyOfRange(0, 16).toList())
        assertEquals(raw.size + 30, u32(objectHeader, 0x10))
        assertEquals(0, u16(objectHeader, 0x14))
        assertEquals(hashlittle(objectHeader.copyOfRange(0, 0x16), 0x3D6BE971), u32(objectHeader, 0x16))
        assertEquals(checksumB(objectHeader, objectOffset.toLong()), u32(objectHeader, 0x1a))
        goldenFixture?.let { fixture ->
            val golden = fixture.getJSONObject("files")
            val expectedFiles = golden.keys().asSequence().toSet()
            assertEquals(indexDir.listFiles()!!.map { it.name }.toSet(), expectedFiles)
            expectedFiles.forEach { name -> assertEquals("independent cascette output: $name", golden.getString(name), sha256Hex(File(indexDir, name).readBytes())) }
        }
        delete(root)
    }

    @Test
    fun grownGenerationAndExpandedFileArePreservedAndStagingCanResume() {
        val root = tempDir()
        val decoded = "preserve".toByteArray()
        val raw = blteRaw(decoded)
        val ekey = "abcdef0123456789abcdef0123456789"
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        val rawFile = file(root, "raw", raw)
        ForeverCasc.materialize(root, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        val expanded = file(File(root, "Data"), "expanded-by-game.bin", byteArrayOf(1, 2, 3))
        val bucket = bucket(ekey.hexBytes())
        val first = File(root, "Data/data/%02x%08x.idx".format(bucket, 1))
        File(root, "Data/data/%02x%08x.idx".format(bucket, 2)).writeBytes(first.readBytes())
        ForeverCasc.verifyExisting(root, listOf(ForeverCasc.RuntimeObjectSpec(ekey, md5Hex(decoded), decoded.size.toLong())), { false })
        assertTrue(expanded.isFile)
        val outside = File.createTempFile("casc-outside-", ".bin").also { it.writeBytes(byteArrayOf(8)) }
        val verifyLink = File(root, ".forever-casc-verify.tmp")
        java.nio.file.Files.createSymbolicLink(verifyLink.toPath(), outside.toPath())
        org.junit.Assert.assertThrows(Exception::class.java) {
            ForeverCasc.verifyExisting(root, listOf(ForeverCasc.RuntimeObjectSpec(ekey, md5Hex(decoded), decoded.size.toLong())), { false })
        }
        assertEquals(listOf(8.toByte()), outside.readBytes().toList())
        verifyLink.delete()
        outside.delete()
        // The managed staging marker makes a retry safe even after Data.new or
        // a promoted staging Data directory survived an interrupted run.
        val retry = File(root, "retry-stage").also { it.mkdirs() }
        ForeverCasc.materialize(retry, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        assertTrue(File(retry, "Data/data").isDirectory)
        assertTrue(expanded.isFile)
        delete(root)
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "casc-test-${System.nanoTime()}").also { it.mkdirs() }
    private fun file(parent: File, name: String, bytes: ByteArray): File = File(parent, name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
    private fun delete(file: File) { file.deleteRecursively() }
    private fun blteRaw(value: ByteArray): ByteArray = byteArrayOf(0x42, 0x4c, 0x54, 0x45) + byteArrayOf(0, 0, 0, 0) + byteArrayOf('N'.code.toByte()) + value
    private fun md5Hex(value: ByteArray): String = MessageDigest.getInstance("MD5").digest(value).joinToString("") { "%02x".format(it) }
    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun bucket(key: ByteArray): Int { var value = 0; repeat(9) { value = value xor (key[it].toInt() and 0xff) }; return (((value ushr 4) xor value) and 0xf) }
    private fun findEntry(bytes: ByteArray, key: ByteArray): Int {
        val size = u32(bytes, 0x20)
        var at = 0x28
        while (at < 0x28 + size) { if (bytes.copyOfRange(at, at + 9).contentEquals(key)) return at; at += 18 }
        error("entry not found")
    }
    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)
    private fun u32be(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
    private fun rot(value: Int, shift: Int) = (value shl shift) or (value ushr (32 - shift))
    private fun mix(v: IntArray) { var a=v[0];var b=v[1];var c=v[2];a-=c;a=a xor rot(c,4);c+=b;b-=a;b=b xor rot(a,6);a+=c;c-=b;c=c xor rot(b,8);b+=a;a-=c;a=a xor rot(c,16);c+=b;b-=a;b=b xor rot(a,19);a+=c;c-=b;c=c xor rot(b,4);b+=a;v[0]=a;v[1]=b;v[2]=c }
    private fun finish(v: IntArray) { var a=v[0];var b=v[1];var c=v[2];c=c xor b;c-=rot(b,14);a=a xor c;a-=rot(c,11);b=b xor a;b-=rot(a,25);c=c xor b;c-=rot(b,16);a=a xor c;a-=rot(c,4);b=b xor a;b-=rot(a,14);c=c xor b;c-=rot(b,24);v[0]=a;v[1]=b;v[2]=c }
    private fun hashlittle(data: ByteArray, seed: Int): Int { var o=0;var l=data.size;val v=intArrayOf(-559038737+l+seed,-559038737+l+seed,-559038737+l+seed);while(l>12){word(v,0,data,o);word(v,1,data,o+4);word(v,2,data,o+8);mix(v);o+=12;l-=12};for(i in 0 until minOf(l,4))v[0]+=(data[o+i].toInt()and 255)shl(8*i);for(i in 4 until minOf(l,8))v[1]+=(data[o+i].toInt()and 255)shl(8*(i-4));for(i in 8 until l)v[2]+=(data[o+i].toInt()and 255)shl(8*(i-8));finish(v);return v[2] }
    private fun hashlittle2(data: ByteArray, pc: Int, pb: Int): Pair<Int,Int> { var o=0;var l=data.size;val v=intArrayOf(-559038737+l+pc,-559038737+l+pc,-559038737+l+pc+pb);while(l>12){word(v,0,data,o);word(v,1,data,o+4);word(v,2,data,o+8);mix(v);o+=12;l-=12};for(i in 0 until minOf(l,4))v[0]+=(data[o+i].toInt()and 255)shl(8*i);for(i in 4 until minOf(l,8))v[1]+=(data[o+i].toInt()and 255)shl(8*(i-4));for(i in 8 until l)v[2]+=(data[o+i].toInt()and 255)shl(8*(i-8));finish(v);return v[2] to v[1] }
    private fun word(v: IntArray, i: Int, d: ByteArray, at: Int) { v[i]+=(d[at].toInt()and 255)or((d[at+1].toInt()and 255)shl 8)or((d[at+2].toInt()and 255)shl 16)or(d[at+3].toInt()shl 24) }
    private val lut = intArrayOf(0x049396B8,0x72A82A9B.toInt(),0xEE626CCA.toInt(),0x9917754F.toInt(),0x15DE40B1,0xF5A8A9B6.toInt(),0x421EAC7E,0xA9D55C9A.toInt(),0x317FD40C,0x04FAF80D,0x3D6BE971,0x52933CFD,0x27F64B7D,0xC6F5C11B.toInt(),0xD5757E3A.toInt(),0x6C388745)
     private fun checksumB(header: ByteArray, offset: Long): Int { val a=IntArray(4);for(i in 0 until 0x1a){val s=(offset.toInt()+i)and 3;a[s]=a[s] xor (header[i].toInt()and 255)};val plus=((offset+30)and 0xffffffffL).toInt();val mask=lut[plus and 15] xor plus;val m=byteArrayOf(mask.toByte(),(mask ushr 8).toByte(),(mask ushr 16).toByte(),(mask ushr 24).toByte());val r=ByteArray(4);for(i in 0 until 4){val s=(offset.toInt()+0x1a+i)and 3;r[i]=(a[s] xor (m[s].toInt()and 255)).toByte()};return u32(r,0)}
}
