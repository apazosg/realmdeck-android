package app.gamenative

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

/** One bounded, validated asset reader for provisioning and direct launch. */
object ForeverClientCatalog {
    const val ASSET = "forever/client-catalog.json"
    const val MAX_BYTES = 8_388_608
    private const val BUFFER_BYTES = 16 * 1024

    fun load(context: Context): JSONObject = context.assets.open(ASSET).use(::load)

    internal fun load(input: InputStream): JSONObject {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            // At the exact limit, probe only one byte to distinguish EOF from
            // excess. No available()/declared length or unbounded readBytes().
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(output.size() < MAX_BYTES) { "client catalog exceeds bounded asset read ($MAX_BYTES bytes)" }
                output.write(single)
            } else {
                require(count <= MAX_BYTES - output.size()) { "client catalog exceeds bounded asset read ($MAX_BYTES bytes)" }
                output.write(buffer, 0, count)
            }
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
        return JSONObject(text).also(ForeverClientProvisioner::validateCatalog)
    }
}
