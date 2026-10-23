package app.gamenative

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverCatalogAssetTest {
    @Test
    fun realPackagedCatalogIsAcceptedByTheDirectLaunchReader() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = context.assets.open("forever/client-catalog.json").use { it.readBytes() }
        assertTrue("the regression must exercise the real catalog above 2 MiB", bytes.size > 2 * 1024 * 1024)
        System.getProperty("wowforever.catalog")?.let { path ->
            assertTrue("packaged test asset differs from the audited input", bytes.contentEquals(File(path).readBytes()))
        }
        val reader = ForeverArm64Launch::class.java.getDeclaredMethod("catalogRecordFromOfficialAsset", Context::class.java)
        reader.isAccessible = true
        val record = reader.invoke(ForeverArm64Launch, context) as ForeverArm64Launch.CatalogExecutableRecord
        assertEquals("WowB-ARM64.exe", record.relativePath)
        assertEquals("f8d91fd1b3751e77973035dccbde40e7", record.decodedMd5)
        assertEquals(94_286_544L, record.decodedSize)
    }

    @Test
    fun provisioningAndDirectLaunchUseTheSameRealAssetAndBounds() {
        val context = RuntimeEnvironment.getApplication()
        val catalog = ForeverClientCatalog.load(context)
        assertEquals(748_157_712L, ForeverClientProvisioner.plan(context).downloadBytes)
        val record = ForeverArm64Launch.catalogRecordForTest(catalog)
        assertEquals("f8d91fd1b3751e77973035dccbde40e7", record.decodedMd5)
        assertEquals(94_286_544L, record.decodedSize)
    }

    @Test
    fun exactBoundIsAcceptedButOneExtraByteIsRejectedBeforeParsing() {
        val bytes = realAsset()
        val atLimit = ByteArray(ForeverClientCatalog.MAX_BYTES) { ' '.code.toByte() }
        bytes.copyInto(atLimit)
        assertEquals("wowforever.arm64-client-catalog", ForeverClientCatalog.load(ByteArrayInputStream(atLimit)).getString("schema"))
        val excess = CountingInput(ByteArrayInputStream(atLimit + ' '.code.toByte()))
        val error = assertThrows(IllegalArgumentException::class.java) { ForeverClientCatalog.load(excess) }
        assertTrue(error.message.orEmpty().contains("exceeds bounded asset read"))
        assertEquals(ForeverClientCatalog.MAX_BYTES.toLong() + 1, excess.bytesRead)
    }

    @Test
    fun availableAndShortOrZeroReadsDoNotDetermineCatalogSize() {
        val source = ByteArrayInputStream(realAsset())
        var zeroNext = true
        val fragmented = object : InputStream() {
            override fun available(): Int = 0
            override fun read(): Int = source.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                zeroNext = !zeroNext
                return if (zeroNext) 0 else source.read(target, offset, minOf(length, 37))
            }
        }
        assertEquals("1.60.1.70009", ForeverClientCatalog.load(fragmented).getJSONObject("provenance").getString("version"))
    }

    @Test
    fun malformedUtf8JsonAndWrongPinsRemainRejected() {
        assertThrows(CharacterCodingException::class.java) {
            ForeverClientCatalog.load(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(org.json.JSONException::class.java) { ForeverClientCatalog.load(ByteArrayInputStream("{".toByteArray())) }
        val altered = org.json.JSONObject(realAsset().toString(Charsets.UTF_8))
        altered.getJSONObject("provenance").put("version", "unapproved")
        assertThrows(IllegalArgumentException::class.java) { ForeverClientCatalog.load(ByteArrayInputStream(altered.toString().toByteArray())) }
    }

    private fun realAsset(): ByteArray = RuntimeEnvironment.getApplication().assets
        .open(ForeverClientCatalog.ASSET).use { it.readBytes() }

    private class CountingInput(private val source: InputStream) : InputStream() {
        var bytesRead = 0L
        override fun read(): Int = source.read().also { if (it >= 0) bytesRead++ }
        override fun read(target: ByteArray, offset: Int, length: Int): Int = source.read(target, offset, length)
            .also { if (it > 0) bytesRead += it }
    }
}
