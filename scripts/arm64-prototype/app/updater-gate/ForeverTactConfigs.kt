package app.gamenative

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/** Strict, hash-bound reader for the small official NGDP build/CDN config files. */
internal object ForeverTactConfigs {
    private const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
    private const val MAX_LINE_CHARS = 4 * 1024 * 1024
    private const val MAX_ENCODED_METADATA_BYTES = 256L * 1024 * 1024
    private const val MAX_DECODED_METADATA_BYTES = 512L * 1024 * 1024
    private const val MAX_RUNTIME_REFERENCES = 4_096
    private const val MAX_CDN_HASHES = 8_192
    private const val MAX_RELEVANT_CONFIG_FIELDS = MAX_RUNTIME_REFERENCES * 2 + 64
    private val HEX_MD5 = Regex("(?i)[0-9a-f]{32}")
    private val DECIMAL = Regex("[0-9]{1,19}")
    private val VFS_KEY = Regex("vfs-(root|[0-9]+)(-size)?")
    private val METADATA_ROLES = listOf("install", "download", "size", "encoding")
    private val BUILD_FIELDS = setOf("build-uid", "build-num", "root") +
        METADATA_ROLES.flatMap { listOf(it, "$it-size") }

    fun build(raw: ByteArray, published: ForeverClientUpdateCheck.Fingerprint): ForeverTactMetadata.BuildConfig {
        validateFingerprint(published)
        validateConfigBytes(raw, "build config")
        require(md5(raw).equals(published.buildConfigHash, ignoreCase = true)) {
            "build config MD5 does not match the published build-config fingerprint"
        }

        val fields = parseFields(raw, "build config") { key ->
            key in BUILD_FIELDS || VFS_KEY.matches(key) || key.startsWith("vfs-")
        }
        requireField(fields, "build-uid", "build config")
        require(fields.getValue("build-uid") == ForeverClientUpdateCheck.PRODUCT) {
            "build config product does not match ${ForeverClientUpdateCheck.PRODUCT}"
        }
        fields["build-num"]?.let { buildNum ->
            require(DECIMAL.matches(buildNum) && buildNum.toLongOrNull() == published.buildId) {
                "build config build-num does not match the published BuildId"
            }
        }

        val metadataObjects = LinkedHashMap<String, ForeverTactMetadata.ObjectReference>()
        METADATA_ROLES.forEach { role ->
            val value = requireField(fields, role, "$role metadata role")
            val (contentKey, encodingKey) = parseHashPair(value, "$role content/encoding keys")
            val (decodedSize, encodedSize) = parseSizePair(requireSizeField(fields, "$role-size", role), "$role decoded/encoded sizes")
            validateObjectSizes(encodedSize, decodedSize, role)
            metadataObjects[role] = ForeverTactMetadata.ObjectReference(
                role = role,
                contentKey = contentKey,
                encodingKey = encodingKey,
                encodedSize = encodedSize,
                decodedSize = decodedSize,
            )
        }

        val runtimeContents = ArrayList<ForeverTactMetadata.ContentReference>()
        val root = requireField(fields, "root", "runtime content")
        runtimeContents += ForeverTactMetadata.ContentReference("root", parseSingleHash(root, "root content key"))

        val vfsRoot = parseRuntimeReference(fields, "vfs-root", "vfs-root-size")
        runtimeContents += vfsRoot

        val vfsIndexes = fields.keys.mapNotNull { key ->
            val match = Regex("vfs-([0-9]+)").matchEntire(key) ?: return@mapNotNull null
            match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("VFS role index exceeds the supported range")
        }.sorted()
        require(vfsIndexes.size <= MAX_RUNTIME_REFERENCES - 2) { "build config has too many runtime content roles" }
        require(vfsIndexes == (1..vfsIndexes.size).toList()) {
            "build config VFS roles must be contiguous from vfs-1"
        }
        vfsIndexes.forEach { index ->
            runtimeContents += parseRuntimeReference(fields, "vfs-$index", "vfs-$index-size")
        }
        require(runtimeContents.size <= MAX_RUNTIME_REFERENCES) { "build config runtime content roles exceed the bound" }

        return ForeverTactMetadata.BuildConfig(
            fingerprint = published.copy(
                buildConfigHash = published.buildConfigHash.lowercase(Locale.ROOT),
                cdnConfigHash = published.cdnConfigHash.lowercase(Locale.ROOT),
            ),
            metadataObjects = metadataObjects,
            runtimeContents = runtimeContents,
            rawSha256 = sha256(raw),
        )
    }

    fun cdn(raw: ByteArray, published: ForeverClientUpdateCheck.Fingerprint): ForeverTactMetadata.CdnConfig {
        validateFingerprint(published)
        validateConfigBytes(raw, "CDN config")
        require(md5(raw).equals(published.cdnConfigHash, ignoreCase = true)) {
            "CDN config MD5 does not match the published CDN-config fingerprint"
        }
        val fields = parseFields(raw, "CDN config") { key ->
            key in setOf("archives", "builds", "file-index")
        }
        val archiveText = requireField(fields, "archives", "CDN archive metadata")
        val validatedArchives = parseHashList(archiveText, "CDN archive", requireAtLeastOne = true)
        require(validatedArchives.toSet().size == validatedArchives.size) { "CDN config contains duplicate archive hashes" }

        fields["builds"]?.takeIf(String::isNotBlank)?.let { buildText ->
            val hashes = parseHashList(buildText, "CDN build reference", requireAtLeastOne = true)
            require(hashes.any { it.equals(published.buildConfigHash, ignoreCase = true) }) {
                "CDN config builds list does not include the published build-config hash"
            }
        }
        val fileIndex = fields["file-index"]?.let { parseSingleHash(it, "CDN file-index hash") }
        return ForeverTactMetadata.CdnConfig(validatedArchives, fileIndex, sha256(raw))
    }

    private fun parseRuntimeReference(
        fields: Map<String, String>,
        role: String,
        sizeRole: String,
    ): ForeverTactMetadata.ContentReference {
        val (contentKey, _) = parseHashPair(requireField(fields, role, "$role runtime role"), "$role content/encoding keys")
        val (decodedSize, encodedSize) = parseSizePair(requireSizeField(fields, sizeRole, role), "$role decoded/encoded sizes")
        validateObjectSizes(encodedSize, decodedSize, role)
        return ForeverTactMetadata.ContentReference(role, contentKey, decodedSize)
    }

    private fun validateObjectSizes(encodedSize: Long, decodedSize: Long, role: String) {
        require(encodedSize in 1..MAX_ENCODED_METADATA_BYTES) {
            "$role encoded metadata size is outside the 1..$MAX_ENCODED_METADATA_BYTES byte bound"
        }
        require(decodedSize in 1..MAX_DECODED_METADATA_BYTES) {
            "$role decoded metadata size is outside the 1..$MAX_DECODED_METADATA_BYTES byte bound"
        }
    }

    private fun parseFields(raw: ByteArray, description: String, relevant: (String) -> Boolean): Map<String, String> {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
        require('\u0000' !in text) { "$description contains a NUL byte" }
        val fields = LinkedHashMap<String, String>()
        text.lineSequence().forEachIndexed { index, untrimmed ->
            val line = if (untrimmed.endsWith('\r')) untrimmed.dropLast(1) else untrimmed
            require('\r' !in line) { "$description contains an invalid line ending at line ${index + 1}" }
            require(line.length <= MAX_LINE_CHARS) { "$description line exceeds the parser bound" }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachIndexed

            val equals = line.indexOf('=')
            if (equals < 0) {
                val possibleKey = line.trim().takeWhile { !it.isWhitespace() }
                if (relevant(possibleKey)) throw IllegalArgumentException("$description field $possibleKey is truncated")
                return@forEachIndexed
            }
            val key = line.substring(0, equals).trim()
            if (!key.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,127}"))) {
                val candidate = key.takeWhile { it.isLetterOrDigit() || it == '-' }
                if (relevant(candidate)) throw IllegalArgumentException("$description field name is malformed: $key")
                return@forEachIndexed
            }
            if (!relevant(key)) return@forEachIndexed
            require(!fields.containsKey(key)) { "$description contains duplicate relevant field $key" }
            require(fields.size < MAX_RELEVANT_CONFIG_FIELDS) { "$description has too many relevant fields" }
            val value = line.substring(equals + 1).trim()
            require(value.isNotEmpty() && value.none { it.code < 0x20 || it.code == 0x7f }) {
                "$description relevant field $key is empty or contains a control character"
            }
            fields[key] = value
        }
        return fields
    }

    private fun validateConfigBytes(raw: ByteArray, description: String) {
        require(raw.isNotEmpty() && raw.size <= MAX_CONFIG_BYTES) {
            "$description byte count is outside the 1..$MAX_CONFIG_BYTES byte bound"
        }
    }

    private fun validateFingerprint(published: ForeverClientUpdateCheck.Fingerprint) {
        require(published.product == ForeverClientUpdateCheck.PRODUCT) { "published fingerprint product is not wow_classic_beta" }
        require(published.version.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))) { "published fingerprint version is malformed" }
        require(published.buildId != null && published.buildId > 0L &&
            published.version.substringAfterLast('.').toLongOrNull() == published.buildId
        ) { "published fingerprint has no complete version/BuildId binding" }
        require(HEX_MD5.matches(published.buildConfigHash) && HEX_MD5.matches(published.cdnConfigHash)) {
            "published fingerprint config hashes must be full 32-hex MD5 values"
        }
    }

    private fun requireField(fields: Map<String, String>, name: String, role: String): String =
        fields[name] ?: throw IllegalArgumentException("$role is missing required config field $name")

    private fun requireSizeField(fields: Map<String, String>, name: String, role: String): String =
        fields[name] ?: throw UnsupportedOperationException(
            "unsupported $role metadata reference: build config lacks direct $name decoded/encoded sizes; resolve it through encoding metadata before downloading",
        )

    private fun parseHashList(value: String, description: String, requireAtLeastOne: Boolean): List<String> {
        val result = ArrayList<String>()
        for (token in value.splitToSequence(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            require(result.size < MAX_CDN_HASHES) { "$description count exceeds the $MAX_CDN_HASHES full-hash bound" }
            result += parseSingleHash(token, "$description[${result.size}]")
        }
        require(!requireAtLeastOne || result.isNotEmpty()) { "$description list is empty" }
        return result
    }

    private fun parseSingleHash(value: String, description: String): String {
        require(HEX_MD5.matches(value)) { "$description must be one full 32-hex hash" }
        return value.lowercase(Locale.ROOT)
    }

    private fun parseHashPair(value: String, description: String): Pair<String, String> {
        val hashes = boundedTokens(value, 3)
        require(hashes.size == 2) { "$description must contain exactly h1 CKey and h2 EKey" }
        return parseSingleHash(hashes[0], "$description h1 CKey") to parseSingleHash(hashes[1], "$description h2 EKey")
    }

    private fun parseSizePair(value: String, description: String): Pair<Long, Long> {
        val values = boundedTokens(value, 3)
        if (values.size != 2 || values.any { !DECIMAL.matches(it) }) {
            throw UnsupportedOperationException(
                "unsupported $description format: expected explicit decoded-size and encoded-size decimal values",
            )
        }
        val decoded = values[0].toLongOrNull()
        val encoded = values[1].toLongOrNull()
        if (decoded == null || encoded == null) {
            throw UnsupportedOperationException("unsupported $description format: a size exceeds the supported integer range")
        }
        return decoded to encoded
    }

    private fun boundedTokens(value: String, limit: Int): List<String> {
        val result = ArrayList<String>(limit)
        for (token in value.splitToSequence(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            if (result.size == limit) break
            result += token
        }
        return result
    }

    private fun md5(bytes: ByteArray): String = digest("MD5", bytes)
    private fun sha256(bytes: ByteArray): String = digest("SHA-256", bytes)
    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
