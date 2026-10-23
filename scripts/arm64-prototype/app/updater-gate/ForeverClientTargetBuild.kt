package app.gamenative

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

/** Immutable, APK-supplied admission for one reviewed Classic Beta client build. */
class ForeverClientTargetBuild private constructor(
    val product: String,
    val region: String,
    val platform: String,
    val architecture: String,
    val locale: String,
    val version: String,
    val buildId: Long,
    val launcherVersionCode: Long,
    val launcherVersionName: String,
    val artifactSubdir: String,
    val buildConfig: Config,
    val cdnConfig: Config,
    val catalog: Catalog,
    val executable: Executable,
    val serverVersionSource: String,
    val nativeValidation: NativeValidation,
    private val originalJson: ByteArray,
) {
    data class Config(val hash: String, val bytes: Long, val sha256: String)

    data class Catalog(
        val bytes: Long,
        val sha256: String,
        val gzipBytes: Long,
        val gzipSha256: String,
    )

    data class Executable(
        val path: String,
        val decodedBytes: Long,
        val contentKey: String,
        val expectedMachine: Int,
    )

    data class NativeValidation(val status: Status, val evidenceSha256: String?) {
        enum class Status { PENDING, VERIFIED }
    }

    val fingerprint: ForeverClientUpdateCheck.Fingerprint
        get() = ForeverClientUpdateCheck.Fingerprint(
            product = product,
            version = version,
            buildConfigHash = buildConfig.hash,
            cdnConfigHash = cdnConfig.hash,
            buildId = buildId,
        )

    /** Native activation is trusted only when this exact APK manifest carries valid evidence. */
    val nativeActivationValidated: Boolean
        get() = nativeValidation.status == NativeValidation.Status.VERIFIED &&
            nativeValidation.evidenceSha256?.matches(HEX64) == true

    internal fun originalJsonCopy(): ByteArray = originalJson.copyOf()

    companion object {
        const val ASSET_PATH = "forever/client-update/target.json"
        const val MAX_JSON_BYTES = 32 * 1024
        private val HEX32 = Regex("(?i)[0-9a-f]{32}")
        private val HEX64 = Regex("(?i)[0-9a-f]{64}")
        private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){3}")
        private const val PRODUCT = "wow_classic_beta"
        private const val EXPECTED_EXECUTABLE = "WowB-ARM64.exe"
        private const val EXPECTED_MACHINE = 43_620

        /** Loads only the immutable asset packaged in this APK; there is no network or override path. */
        fun load(context: Context): ForeverClientTargetBuild {
            val bytes = context.assets.open(ASSET_PATH).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= MAX_JSON_BYTES) { "client target asset exceeds its size bound" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return parse(bytes)
        }

        /** Strict parser used for APK data and bounded app-private admission history. */
        internal fun parse(raw: ByteArray): ForeverClientTargetBuild {
            require(raw.isNotEmpty() && raw.size <= MAX_JSON_BYTES) { "client target JSON is empty or exceeds its size bound" }
            require(raw.none { it == 0.toByte() }) { "client target JSON contains a NUL byte" }
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
            StrictJson(raw).validate()
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject
                ?: throw IllegalArgumentException("client target JSON root is not an object")
            require(tokener.nextClean() == '\u0000') { "client target JSON has trailing data" }

            require(root.keys().asSequence().toSet() == TOP_LEVEL_FIELDS) { "client target JSON fields do not match the schema" }
            require(root.requiredString("schema") == "wowforever.client-target") { "unsupported client target schema" }
            require(root.requiredLong("schema_version") == 1L) { "unsupported client target schema version" }

            val product = root.requiredString("product")
            val region = root.requiredString("region")
            val platform = root.requiredString("platform")
            val architecture = root.requiredString("architecture")
            val locale = root.requiredString("locale")
            val version = root.requiredString("version")
            val buildId = root.requiredLong("build_id")
            require(product == PRODUCT && region == "us" && platform == "Windows" &&
                architecture == "arm64" && locale == "enUS"
            ) { "client target is not the admitted Windows ARM64 enUS product" }
            require(VERSION.matches(version) && buildId > 0L && version.substringAfterLast('.').toLongOrNull() == buildId) {
                "client target version and BuildId do not match"
            }
            val launcherVersionCode = root.requiredLong("launcher_version_code")
            val launcherVersionName = root.requiredString("launcher_version_name")
            require(launcherVersionCode > 0L && launcherVersionName.length in 1..128 &&
                launcherVersionName.none { it.code < 0x20 || it.code == 0x7f }
            ) { "client target launcher identity is invalid" }

            val artifactSubdir = root.requiredString("artifact_subdir")
            val artifactParts = artifactSubdir.split('/')
            require(artifactParts.size == 2 && artifactParts[0] == "client-updater-packaged-catalog" &&
                artifactParts[1] == buildId.toString()
            ) { "client target artifact_subdir is not a bounded catalog-parent path" }

            val buildConfig = root.requiredObject("build_config").let { config(it, "build config") }
            val cdnConfig = root.requiredObject("cdn_config").let { config(it, "CDN config") }
            val catalogObject = root.requiredObject("catalog")
            require(catalogObject.keys().asSequence().toSet() == CATALOG_FIELDS) { "client target catalog fields do not match the schema" }
            val catalog = Catalog(
                bytes = catalogObject.requiredLong("bytes").also { require(it in 1..MAX_CATALOG_BYTES) },
                sha256 = catalogObject.requiredHex("sha256", HEX64, "catalog SHA-256"),
                gzipBytes = catalogObject.requiredLong("gzip_bytes").also { require(it in 1..MAX_COMPRESSED_CATALOG_BYTES) },
                gzipSha256 = catalogObject.requiredHex("gzip_sha256", HEX64, "compressed catalog SHA-256"),
            )
            val executableObject = root.requiredObject("executable")
            require(executableObject.keys().asSequence().toSet() == EXECUTABLE_FIELDS) { "client target executable fields do not match the schema" }
            val executable = Executable(
                path = executableObject.requiredString("path"),
                decodedBytes = executableObject.requiredLong("decoded_bytes"),
                contentKey = executableObject.requiredHex("content_key", HEX32, "executable content key"),
                expectedMachine = executableObject.requiredLong("expected_machine").also {
                    require(it == EXPECTED_MACHINE.toLong())
                }.toInt(),
            )
            require(executable.path == EXPECTED_EXECUTABLE && executable.decodedBytes in 1..MAX_DECODED_FILE_BYTES) {
                "client target executable identity or size is invalid"
            }

            val source = root.requiredString("server_version_source")
            require(source in setOf("user-confirmed", "not-confirmed")) { "client target server-version source is not admitted" }
            val nativeObject = root.requiredObject("native_validation")
            require(nativeObject.keys().asSequence().toSet() == NATIVE_FIELDS) { "client target native-validation fields do not match the schema" }
            val status = when (nativeObject.requiredString("status")) {
                "pending" -> NativeValidation.Status.PENDING
                "verified" -> NativeValidation.Status.VERIFIED
                else -> throw IllegalArgumentException("client target native-validation status is invalid")
            }
            val evidenceValue = nativeObject.get("evidence_sha256")
            val evidence = when {
                evidenceValue === JSONObject.NULL && status == NativeValidation.Status.PENDING -> null
                evidenceValue is String && status == NativeValidation.Status.VERIFIED && HEX64.matches(evidenceValue) ->
                    evidenceValue.lowercase(Locale.ROOT)
                else -> throw IllegalArgumentException("client target native evidence does not match its status")
            }

            return ForeverClientTargetBuild(
                product, region, platform, architecture, locale, version, buildId,
                launcherVersionCode, launcherVersionName, artifactSubdir, buildConfig,
                cdnConfig, catalog, executable, source, NativeValidation(status, evidence), raw.copyOf(),
            )
        }

        internal fun validateJson(raw: ByteArray) {
            StrictJson(raw).validate()
        }

        private fun config(value: JSONObject, label: String): Config {
            require(value.keys().asSequence().toSet() == CONFIG_FIELDS) { "client target $label fields do not match the schema" }
            return Config(
                hash = value.requiredHex("hash", HEX32, "$label hash"),
                bytes = value.requiredLong("bytes").also { require(it in 1..MAX_CONFIG_BYTES) },
                sha256 = value.requiredHex("sha256", HEX64, "$label SHA-256"),
            )
        }

        private fun JSONObject.requiredObject(name: String): JSONObject =
            get(name) as? JSONObject ?: throw IllegalArgumentException("client target field $name is not an object")

        private fun JSONObject.requiredString(name: String): String =
            get(name) as? String ?: throw IllegalArgumentException("client target field $name is not a string")

        private fun JSONObject.requiredLong(name: String): Long {
            val number = get(name) as? Number ?: throw IllegalArgumentException("client target field $name is not an integer")
            val lexicalInteger = number.toString()
            require(lexicalInteger.matches(Regex("-?(?:0|[1-9][0-9]*)"))) { "client target field $name is not an integer" }
            return lexicalInteger.toLongOrNull()
                ?: throw IllegalArgumentException("client target field $name is outside the integer range")
        }

        private fun JSONObject.requiredHex(name: String, pattern: Regex, description: String): String =
            requiredString(name).also { require(pattern.matches(it)) { "invalid client target $description" } }
                .lowercase(Locale.ROOT)

        private val TOP_LEVEL_FIELDS = setOf(
            "schema", "schema_version", "product", "region", "platform", "architecture", "locale", "version",
            "build_id", "launcher_version_code", "launcher_version_name", "artifact_subdir", "build_config",
            "cdn_config", "catalog", "executable", "server_version_source", "native_validation",
        )
        private val CONFIG_FIELDS = setOf("hash", "bytes", "sha256")
        private val CATALOG_FIELDS = setOf("bytes", "sha256", "gzip_bytes", "gzip_sha256")
        private val EXECUTABLE_FIELDS = setOf("path", "decoded_bytes", "content_key", "expected_machine")
        private val NATIVE_FIELDS = setOf("status", "evidence_sha256")
        private const val MAX_CONFIG_BYTES = 4L * 1024 * 1024
        private const val MAX_CATALOG_BYTES = 256L * 1024 * 1024
        private const val MAX_COMPRESSED_CATALOG_BYTES = 256L * 1024 * 1024
        private const val MAX_DECODED_FILE_BYTES = 512L * 1024 * 1024

        /** A tiny strict JSON grammar pass rejects duplicate keys before org.json can collapse them. */
        private class StrictJson(private val bytes: ByteArray) {
            private val source = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            private var offset = 0
            private var tokens = 0

            fun validate() {
                value(0)
                whitespace()
                require(offset == source.length) { "client target JSON has trailing data" }
            }

            private fun value(depth: Int) {
                require(depth <= 16 && ++tokens <= 8_192) { "client target JSON exceeds parser bounds" }
                whitespace()
                require(offset < source.length) { "client target JSON is truncated" }
                when (source[offset]) {
                    '{' -> objectValue(depth + 1)
                    '[' -> arrayValue(depth + 1)
                    '"' -> stringValue()
                    't' -> literal("true")
                    'f' -> literal("false")
                    'n' -> literal("null")
                    '-', in '0'..'9' -> numberValue()
                    else -> throw IllegalArgumentException("client target JSON contains an invalid value")
                }
            }

            private fun objectValue(depth: Int) {
                offset++
                whitespace()
                if (consume('}')) return
                val keys = HashSet<String>()
                while (true) {
                    whitespace()
                    require(offset < source.length && source[offset] == '"') { "client target JSON object key is invalid" }
                    val key = stringValue()
                    require(keys.add(key)) { "client target JSON contains a duplicate field" }
                    whitespace()
                    require(consume(':')) { "client target JSON object is missing a colon" }
                    value(depth)
                    whitespace()
                    if (consume('}')) return
                    require(consume(',')) { "client target JSON object is malformed" }
                }
            }

            private fun arrayValue(depth: Int) {
                offset++
                whitespace()
                if (consume(']')) return
                while (true) {
                    value(depth)
                    whitespace()
                    if (consume(']')) return
                    require(consume(',')) { "client target JSON array is malformed" }
                }
            }

            private fun stringValue(): String {
                val start = offset
                offset++
                while (offset < source.length) {
                    val character = source[offset++]
                    when {
                        character == '"' -> {
                            val encoded = source.substring(start, offset)
                            val tokener = JSONTokener(encoded)
                            val result = tokener.nextValue() as? String
                                ?: throw IllegalArgumentException("client target JSON string is invalid")
                            require(tokener.nextClean() == '\u0000') { "client target JSON string is malformed" }
                            return result
                        }
                        character == '\\' -> {
                            require(offset < source.length) { "client target JSON string escape is truncated" }
                            val escape = source[offset++]
                            if (escape == 'u') {
                                repeat(4) {
                                    require(offset < source.length && source[offset].digitToIntOrNull(16) != null) {
                                        "client target JSON unicode escape is malformed"
                                    }
                                    offset++
                                }
                            } else {
                                require(escape in "\"\\/bfnrt") { "client target JSON escape is invalid" }
                            }
                        }
                        character.code < 0x20 -> throw IllegalArgumentException("client target JSON string contains a control character")
                    }
                }
                throw IllegalArgumentException("client target JSON string is unterminated")
            }

            private fun numberValue() {
                val start = offset
                if (consume('-')) require(offset < source.length) { "client target JSON number is incomplete" }
                if (consume('0')) {
                    require(offset >= source.length || source[offset] !in '0'..'9') { "client target JSON number has a leading zero" }
                } else {
                    require(offset < source.length && source[offset] in '1'..'9') { "client target JSON number is invalid" }
                    while (offset < source.length && source[offset] in '0'..'9') offset++
                }
                if (consume('.')) {
                    val digits = offset
                    while (offset < source.length && source[offset] in '0'..'9') offset++
                    require(offset > digits) { "client target JSON fraction is invalid" }
                }
                if (consume('e') || consume('E')) {
                    if (!consume('+')) consume('-')
                    val digits = offset
                    while (offset < source.length && source[offset] in '0'..'9') offset++
                    require(offset > digits) { "client target JSON exponent is invalid" }
                }
                require(offset > start) { "client target JSON number is invalid" }
            }

            private fun literal(value: String) {
                require(source.regionMatches(offset, value, 0, value.length)) { "client target JSON literal is invalid" }
                offset += value.length
            }

            private fun whitespace() {
                while (offset < source.length && source[offset] in " \t\r\n") offset++
            }

            private fun consume(character: Char): Boolean {
                if (offset < source.length && source[offset] == character) {
                    offset++
                    return true
                }
                return false
            }
        }
    }
}
