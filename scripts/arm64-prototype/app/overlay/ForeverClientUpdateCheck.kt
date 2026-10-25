package app.gamenative

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/** Read-only check of Blizzard's public Ribbit versions metadata. This does not download client data. */
object ForeverClientUpdateCheck {
    const val PRODUCT = "wow_classic_beta"
    const val REGION = "us"
    const val OFFICIAL_VERSIONS_URL =
        "https://us.version.battle.net/v2/products/wow_classic_beta/versions"
    const val MAX_METADATA_BYTES = 1_048_576

    private const val MAX_LINE_CHARS = 16_384
    private const val MAX_FIELD_CHARS = 1_024
    private const val MAX_COLUMNS = 32
    private const val MAX_ROWS = 128
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private const val PINNED_VERSION = "1.60.1.70009"
    private const val PINNED_BUILD_ID = 70009L
    private const val PINNED_BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
    private const val PINNED_CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"

    /** Fields supplied by a verified local install/catalog. A missing BuildId is not a complete identity. */
    data class Fingerprint(
        val product: String,
        val version: String,
        val buildConfigHash: String,
        val cdnConfigHash: String,
        val buildId: Long? = null,
    )

    /** Optional manifest-derived architecture evidence, bound to the exact product/version/build config. */
    data class ArchitectureEvidence(
        val product: String,
        val version: String,
        val buildConfigHash: String,
        val architecture: String,
    )

    fun interface MetadataSource {
        /** Returns only the raw `versions` table. Implementations must honor cancellation and the byte bound. */
        fun fetchVersions(cancelled: () -> Boolean): ByteArray
    }

    enum class Status {
        METADATA_MATCH,
        CANDIDATE_NOT_ARM64_CONFIRMED,
        ARM64_CONFIRMED_COMPATIBILITY_PENDING,
        APPROVED_AVAILABLE,
        INSTALLED_IDENTITY_UNKNOWN,
        METADATA_UNAVAILABLE,
    }

    data class CheckResult(
        val status: Status,
        val message: String,
        val published: Fingerprint? = null,
    )

    data class PublishedBuild(val fingerprint: Fingerprint, val metadataSha256: String)

    /** Obtain a fresh official fingerprint together with the digest of its exact response. */
    fun published(cancelled: () -> Boolean = { false }): PublishedBuild {
        checkCancelled(cancelled)
        val raw = OfficialMetadataSource.fetchVersions(cancelled)
        checkCancelled(cancelled)
        val fingerprint = parseVersions(raw)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PublishedBuild(fingerprint, digest)
    }

    /** Blocking network check: call from a worker thread, never from Android's UI thread. */
    fun check(
        installed: Fingerprint?,
        metadataSource: MetadataSource = OfficialMetadataSource,
        architectureEvidence: ArchitectureEvidence? = null,
        cancelled: () -> Boolean = { false },
        admittedFingerprint: Fingerprint? = null,
    ): CheckResult {
        val published = try {
            checkCancelled(cancelled)
            val bytes = metadataSource.fetchVersions(cancelled)
            checkCancelled(cancelled)
            parseVersions(bytes)
        } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (_: Exception) {
            return CheckResult(
                Status.METADATA_UNAVAILABLE,
                "No se pudo verificar la metadata oficial; estado desconocido.",
            )
        }

        if (!isCompleteFingerprint(installed)) {
            return CheckResult(
                Status.INSTALLED_IDENTITY_UNKNOWN,
                "No se pudo verificar la huella instalada; estado desconocido.",
                published,
            )
        }

        val local = requireNotNull(installed)
        if (sameFingerprint(local, published)) {
            return CheckResult(
                Status.METADATA_MATCH,
                "Los metadatos publicados coinciden; no se han detectado novedades.",
                published,
            )
        }

        if (isPinnedExperimentalBuild(published)) {
            return CheckResult(
                Status.APPROVED_AVAILABLE,
                "Build experimental 1.60.1.70009 admitido disponible; no acredita acceso a Forever. No se descargó.",
                published,
            )
        }

        if (isSameCompleteFingerprint(admittedFingerprint, published) && confirmsArm64(architectureEvidence, published)) {
            return CheckResult(
                Status.APPROVED_AVAILABLE,
                "Versión ${published.version} disponible para ARM64 y admitida para el launcher según confirmación del usuario; el arranque y Login propios siguen pendientes. No se descargó.",
                published,
            )
        }

        if (confirmsArm64(architectureEvidence, published)) {
            return CheckResult(
                Status.ARM64_CONFIRMED_COMPATIBILITY_PENDING,
                "ARM64 confirmado para el candidato; compatibilidad con Forever pendiente. No se descargó.",
                published,
            )
        }

        return CheckResult(
            Status.CANDIDATE_NOT_ARM64_CONFIRMED,
            "Hay un candidato publicado; arquitectura ARM64 no confirmada. No se descargó.",
            published,
        )
    }

    /** Strictly parses the official pipe-separated `versions` metadata; no ordering is inferred from BuildId. */
    internal fun parseVersions(raw: ByteArray): Fingerprint {
        require(raw.isNotEmpty()) { "empty versions metadata" }
        require(raw.size <= MAX_METADATA_BYTES) { "versions metadata exceeds the size limit" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
        require(text.endsWith('\n')) { "versions metadata is truncated" }
        require('\u0000' !in text) { "versions metadata contains a NUL byte" }

        val lines = text.dropLast(1).split('\n')
        var headers: List<String>? = null
        var headerIndexes: Map<String, Int>? = null
        var hex16Indexes: List<Int> = emptyList()
        var rowCount = 0
        val seenRegions = HashSet<String>()
        var usFingerprint: Fingerprint? = null

        for (rawLine in lines) {
            val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
            require('\r' !in line) { "invalid line ending" }
            require(line.length <= MAX_LINE_CHARS) { "versions metadata line exceeds the limit" }
            if (line.isBlank()) continue
            val trimmed = line.trim()
            val lower = trimmed.lowercase(Locale.ROOT)
            require(!lower.startsWith("error") && !lower.startsWith("# error") && !lower.startsWith("## error")) {
                "versions endpoint returned an error"
            }
            if (trimmed.startsWith("##")) continue

            if (headers == null) {
                val rawHeaders = line.split('|')
                require(rawHeaders.size in 1..MAX_COLUMNS) { "invalid versions header width" }
                val names = rawHeaders.map(::headerName)
                val normalized = names.map { it.lowercase(Locale.ROOT) }
                require(normalized.toSet().size == normalized.size) { "duplicate versions header" }
                val required = listOf("region", "buildconfig", "cdnconfig", "buildid", "versionsname")
                require(required.all { it in normalized }) { "versions header is missing required fields" }
                headers = names
                headerIndexes = normalized.withIndex().associate { it.value to it.index }
                hex16Indexes = rawHeaders.mapIndexedNotNull { index, rawHeader ->
                    val type = rawHeader.trim().substringAfter('!', missingDelimiterValue = "")
                    if (type.matches(Regex("(?i)HEX:16"))) index else null
                }
                continue
            }

            rowCount++
            require(rowCount <= MAX_ROWS) { "versions metadata has too many rows" }
            val values = line.split('|')
            require(values.size == headers.size) { "invalid versions row width" }
            require(values.all { it.length <= MAX_FIELD_CHARS }) { "versions metadata field exceeds the limit" }
            require(values.none { it.trim().startsWith("error", ignoreCase = true) }) {
                "versions endpoint returned an error row"
            }
            hex16Indexes.forEach { index ->
                val hash = values[index].trim()
                require(hash.isEmpty() || hash.matches(Regex("(?i)[0-9a-f]{32}"))) {
                    "invalid hexadecimal metadata field"
                }
            }
            val indexes = requireNotNull(headerIndexes)
            fun value(name: String): String = values[requireNotNull(indexes[name])].trim()

            val region = value("region")
            require(region.matches(Regex("[A-Za-z]{2}"))) { "invalid metadata region" }
            require(seenRegions.add(region.lowercase(Locale.ROOT))) { "duplicate metadata region" }
            if (!region.equals(REGION, ignoreCase = true)) continue
            require(region == REGION) { "unexpected casing for the US region" }

            val version = value("versionsname")
            require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))) { "invalid stable product version" }
            val buildConfig = value("buildconfig")
            val cdnConfig = value("cdnconfig")
            require(buildConfig.matches(Regex("(?i)[0-9a-f]{32}"))) { "invalid build config hash" }
            require(cdnConfig.matches(Regex("(?i)[0-9a-f]{32}"))) { "invalid CDN config hash" }
            val buildIdText = value("buildid")
            require(buildIdText.matches(Regex("[0-9]{1,19}"))) { "invalid BuildId" }
            val buildId = buildIdText.toLongOrNull()
            require(buildId != null && buildId > 0L) { "invalid numeric BuildId" }
            usFingerprint = Fingerprint(PRODUCT, version, buildConfig.lowercase(Locale.ROOT), cdnConfig.lowercase(Locale.ROOT), buildId)
        }

        require(headers != null) { "versions header is missing" }
        require(usFingerprint != null) { "US region is missing from versions metadata" }
        return requireNotNull(usFingerprint)
    }

    private fun headerName(header: String): String {
        require(header.length <= MAX_FIELD_CHARS) { "versions header field exceeds the limit" }
        val token = header.trim()
        val marker = token.indexOf('!')
        val name = if (marker >= 0) {
            require(token.indexOf('!', marker + 1) < 0) { "invalid typed versions header" }
            val type = token.substring(marker + 1)
            require(type.matches(Regex("(?i)[a-z]+:[0-9]+"))) { "invalid typed versions header" }
            token.substring(0, marker)
        } else {
            token
        }
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "invalid versions header name" }
        return name
    }

    private fun isCompleteFingerprint(fingerprint: Fingerprint?): Boolean =
        fingerprint != null && fingerprint.product == PRODUCT &&
            fingerprint.version.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) &&
            fingerprint.buildConfigHash.matches(Regex("(?i)[0-9a-f]{32}")) &&
            fingerprint.cdnConfigHash.matches(Regex("(?i)[0-9a-f]{32}")) &&
            fingerprint.buildId != null && fingerprint.buildId > 0L

    private fun sameFingerprint(left: Fingerprint, right: Fingerprint): Boolean =
        left.product == right.product && left.version == right.version && left.buildId == right.buildId &&
            left.buildConfigHash.equals(right.buildConfigHash, ignoreCase = true) &&
            left.cdnConfigHash.equals(right.cdnConfigHash, ignoreCase = true)

    private fun isSameCompleteFingerprint(left: Fingerprint?, right: Fingerprint): Boolean =
        left != null && isCompleteFingerprint(left) && isCompleteFingerprint(right) && sameFingerprint(left, right)

    private fun isPinnedExperimentalBuild(fingerprint: Fingerprint): Boolean =
        fingerprint.product == PRODUCT && fingerprint.version == PINNED_VERSION &&
            fingerprint.buildId == PINNED_BUILD_ID &&
            fingerprint.buildConfigHash.equals(PINNED_BUILD_CONFIG, ignoreCase = true) &&
            fingerprint.cdnConfigHash.equals(PINNED_CDN_CONFIG, ignoreCase = true)

    private fun confirmsArm64(evidence: ArchitectureEvidence?, fingerprint: Fingerprint): Boolean =
        evidence != null && evidence.architecture == "arm64" && evidence.product == fingerprint.product &&
            evidence.version == fingerprint.version &&
            evidence.buildConfigHash.equals(fingerprint.buildConfigHash, ignoreCase = true)

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client update check cancelled")
    }

    private object OfficialMetadataSource : MetadataSource {
        override fun fetchVersions(cancelled: () -> Boolean): ByteArray {
            val url = URL(OFFICIAL_VERSIONS_URL)
            require(url.protocol == "https" && url.host == "us.version.battle.net" &&
                url.port in setOf(-1, 443) && url.userInfo == null && url.query == null && url.ref == null &&
                url.path == "/v2/products/wow_classic_beta/versions") {
                "refusing a non-official metadata endpoint"
            }
            checkCancelled(cancelled)
            val connection = (ForeverWifiDownloads.open(url) as? HttpsURLConnection)
                ?: throw IllegalStateException("metadata endpoint is not HTTPS")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                checkCancelled(cancelled)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("official metadata request returned HTTP $status")
                }
                if (connection.contentLengthLong > MAX_METADATA_BYTES) {
                    throw IllegalStateException("official versions metadata exceeds the size limit")
                }
                return connection.inputStream.use { input -> readBounded(input, cancelled) }
            } finally {
                connection.disconnect()
            }
        }

        private fun readBounded(input: java.io.InputStream, cancelled: () -> Boolean): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_METADATA_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    output.write(single)
                } else {
                    output.write(buffer, 0, count)
                }
                if (output.size() > MAX_METADATA_BYTES) {
                    throw IllegalStateException("official versions metadata exceeds the size limit")
                }
            }
            return output.toByteArray()
        }
    }
}
