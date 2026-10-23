package app.gamenative

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Read-only consumer for an externally generated, APK-admitted SQLite catalog. */
class ForeverClientUpdateCatalog @JvmOverloads constructor(
    file: File,
    expectedSha256: String,
    expectedSize: Long,
    private val target: ForeverClientTargetBuild? = null,
) : AutoCloseable {
    data class ObjectRecord(
        val ekey: String,
        val contentKey: String,
        val encodedSize: Long,
        val decodedSize: Long,
        val sourceKind: String,
        val sourceHash: String,
        val sourceOffset: Long,
        val sourceLength: Long,
    )

    data class LooseFile(val path: String, val objectRecord: ObjectRecord)

    data class Summary(
        val version: String,
        val buildConfigHash: String,
        val cdnConfigHash: String,
        val objectCount: Long,
        val totalEncodedBytes: Long,
        val looseCount: Int,
    )

    /**
     * Fresh one-row aggregate of admitted object sizes. It is re-queried on
     * every call and never a cached maximum.
     */
    internal data class ObjectSizeBounds(
        val objectCount: Long,
        val minEncodedSize: Long,
        val maxEncodedSize: Long,
        val minDecodedSize: Long,
        val maxDecodedSize: Long,
    )

    private val database: SQLiteDatabase
    private val metadataValues: Map<String, String>
    private val catalogSummary: Summary
    private var closed = false
    val verifiedSha256: String

    init {
        require(expectedSha256.matches(Regex("(?i)[0-9a-f]{64}"))) { "invalid client catalog SHA-256" }
        require(expectedSize in 1..MAX_CATALOG_BYTES) { "client catalog size is outside the import bound" }
        if (target != null) {
            require(expectedSha256.equals(target.catalog.sha256, true) && expectedSize == target.catalog.bytes) {
                "client catalog file authority does not match the APK target admission"
            }
        }
        validateReadOnlyFile(file, expectedSize, expectedSha256)
        verifiedSha256 = expectedSha256.lowercase(Locale.ROOT)

        val opened = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (failure: Exception) {
            throw IllegalArgumentException("client catalog is not a readable SQLite database", failure)
        }
        database = opened
        try {
            // This setting is connection-local and bounds SQLite's page cache. Do not
            // request a journal, recovery, or any persistent pragma in this read-only DB.
            try {
                database.execSQL("PRAGMA cache_size=-2048")
            } catch (_: Exception) {
                // Some Android SQLite builds reject cache tuning on a read-only handle.
            }
            validateSchema()
            metadataValues = readMetadata()
            validateFingerprint()
            catalogSummary = validateRowsAndSummarize()
            validateMetadataBindings(catalogSummary)
            validateLooseRows(catalogSummary.looseCount)
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    fun summary(): Summary {
        ensureOpen()
        return catalogSummary
    }

    /** Returns the producer's raw JSON serialization for a known catalog metadata key. */
    fun metadata(name: String): String {
        ensureOpen()
        require(name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "invalid client catalog metadata key" }
        return metadataValues[name] ?: throw IllegalArgumentException("client catalog metadata key is absent")
    }

    /** Primary-key BLOB lookup. The validated hex is embedded as a SQLite blob literal. */
    fun objectByKey(ekey: String): ObjectRecord {
        ensureOpen()
        val normalized = normalizeHex(ekey, "encoding key")
        val cursor = database.rawQuery(
            "SELECT ekey, ckey, encoded_size, decoded_size, source_kind, source_hash, source_offset, source_length " +
                "FROM objects WHERE ekey=X'$normalized'",
            null,
        )
        cursor.use {
            if (!it.moveToFirst()) throw NoSuchElementException("client catalog has no object for the requested encoding key")
            val record = readObjectRecord(it)
            if (it.moveToNext()) throw IllegalStateException("client catalog primary-key lookup returned duplicate objects")
            return record
        }
    }

    /** The reviewed client build has only a small loose-file set; refuse growth beyond the bound. */
    fun looseFiles(): List<LooseFile> {
        ensureOpen()
        val cursor = database.rawQuery(
            "SELECT l.path, l.ekey, l.ckey, l.encoded_size, l.decoded_size, " +
                "o.ekey, o.ckey, o.encoded_size, o.decoded_size, o.source_kind, o.source_hash, o.source_offset, o.source_length " +
                "FROM loose l JOIN objects o ON o.ekey=l.ekey ORDER BY l.path",
            null,
        )
        cursor.use {
            val result = ArrayList<LooseFile>(catalogSummary.looseCount)
            val paths = HashSet<String>(catalogSummary.looseCount)
            while (it.moveToNext()) {
                if (result.size >= MAX_LOOSE_FILES) throw IllegalStateException("client catalog loose-file count exceeds its bound")
                val path = validateLoosePath(it.getString(0))
                if (!paths.add(path.lowercase(Locale.ROOT))) {
                    throw IllegalStateException("client catalog loose paths contain a case-insensitive alias")
                }
                result += LooseFile(path, readBoundLooseRecord(it, 1, 5))
            }
            if (result.size != catalogSummary.looseCount) throw IllegalStateException("client catalog loose-file count changed while reading")
            return result
        }
    }

    /**
     * Streams all encoded objects in primary-key order. The cursor is closed even
     * when the caller returns early, throws, or stops iterating the Sequence.
     */
    fun <T> withObjects(cancelled: () -> Boolean, block: (Sequence<ObjectRecord>) -> T): T {
        ensureOpen()
        checkCancelled(cancelled)
        val cursor = database.rawQuery(
            "SELECT ekey, ckey, encoded_size, decoded_size, source_kind, source_hash, source_offset, source_length " +
                "FROM objects ORDER BY ekey",
            null,
        )
        try {
            val sequence = sequence {
                var checked = 0
                while (cursor.moveToNext()) {
                    if ((checked++ and 0x3ff) == 0) checkCancelled(cancelled)
                    yield(readObjectRecord(cursor))
                }
            }
            return block(sequence)
        } finally {
            cursor.close()
        }
    }

    /**
     * Returns a fresh COUNT/MIN/MAX over the objects table without
     * materializing rows or converting keys. The full row, key, source-binding
     * and metadata validation performed at open time and in the caller's main
     * scan is unchanged: this aggregate never substitutes for it and fails
     * closed on any null, non-integer aggregate result, count or bound
     * mismatch. The declared-type check below covers the aggregate result
     * types only; full interior-row typing stays with the open-time and
     * main-scan row validation.
     */
    internal fun objectSizeBounds(cancelled: () -> Boolean): ObjectSizeBounds {
        ensureOpen()
        checkCancelled(cancelled)
        val startedNanos = System.nanoTime()
        val cursor = database.rawQuery(
            "SELECT COUNT(*), MIN(encoded_size), MAX(encoded_size), MIN(decoded_size), MAX(decoded_size) FROM objects",
            null,
        )
        try {
            if (!cursor.moveToFirst()) throw IllegalStateException("client catalog size aggregate returned no row")
            for (index in 0..4) {
                if (cursor.isNull(index)) throw IllegalStateException("client catalog size aggregate has a null component")
                // Aggregate result types only: a non-extremal interior value
                // that MIN/MAX never surfaces stays the row validators' job.
                if (cursor.getType(index) != Cursor.FIELD_TYPE_INTEGER) {
                    throw IllegalStateException("client catalog size aggregate is not an integer row")
                }
            }
            val count = cursor.getLong(0)
            val minEncoded = cursor.getLong(1)
            val maxEncoded = cursor.getLong(2)
            val minDecoded = cursor.getLong(3)
            val maxDecoded = cursor.getLong(4)
            if (count != catalogSummary.objectCount) {
                throw IllegalStateException("client catalog object count changed while reading")
            }
            if (minEncoded !in 1..MAX_SUPPORTED_ENCODED_OBJECT_BYTES ||
                maxEncoded !in 1..MAX_SUPPORTED_ENCODED_OBJECT_BYTES ||
                minEncoded > maxEncoded
            ) {
                throw IllegalStateException("client catalog encoded-size aggregate exceeds its admitted bound")
            }
            if (minDecoded !in 0..MAX_SUPPORTED_DECODED_OBJECT_BYTES ||
                maxDecoded !in 0..MAX_SUPPORTED_DECODED_OBJECT_BYTES ||
                minDecoded > maxDecoded
            ) {
                throw IllegalStateException("client catalog decoded-size aggregate exceeds its admitted bound")
            }
            ensureOpen()
            checkCancelled(cancelled)
            val queryMs = (System.nanoTime() - startedNanos) / 1_000_000
            SIZE_BOUNDS_LOG.info(
                "client catalog size bounds objects=$count maxEncoded=$maxEncoded maxDecoded=$maxDecoded " +
                    "queryMs=$queryMs catalog=${verifiedSha256.take(16)}",
            )
            return ObjectSizeBounds(count, minEncoded, maxEncoded, minDecoded, maxDecoded)
        } finally {
            cursor.close()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            database.close()
        }
    }

    private fun validateSchema() {
        val quickCheck = database.rawQuery("PRAGMA quick_check(1)", null)
        quickCheck.use {
            if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) {
                throw IllegalArgumentException("client catalog SQLite quick_check failed")
            }
        }

        val tables = HashSet<String>()
        database.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (tables.size >= 8) throw IllegalArgumentException("client catalog has too many tables")
                val name = cursor.getString(0)
                tables += name
                val sql = cursor.getString(1)?.uppercase(Locale.ROOT).orEmpty()
                when (name) {
                    "objects", "loose" -> if (!sql.contains("WITHOUT ROWID")) {
                        throw IllegalArgumentException("client catalog table is not in the admitted WITHOUT ROWID layout")
                    }
                    "metadata" -> Unit
                    else -> throw IllegalArgumentException("client catalog has an unexpected table")
                }
            }
        }
        if (tables != EXPECTED_TABLES) throw IllegalArgumentException("client catalog schema tables do not match the admitted layout")
        if (scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('index','view','trigger') AND name NOT LIKE 'sqlite_%'") != 0L) {
            throw IllegalArgumentException("client catalog has an unexpected index, view, or trigger")
        }
        validateColumns("metadata", listOf("key" to "TEXT", "value" to "TEXT"), primaryKey = "key")
        validateTableConstraints("metadata", listOf("KEYTEXTPRIMARYKEY", "VALUETEXTNOTNULL"))
        validateColumns(
            "objects",
            listOf(
                "ekey" to "BLOB", "ckey" to "BLOB", "encoded_size" to "INTEGER", "decoded_size" to "INTEGER",
                "source_kind" to "TEXT", "source_hash" to "BLOB", "source_offset" to "INTEGER", "source_length" to "INTEGER",
            ),
            primaryKey = "ekey",
        )
        validateColumns(
            "loose",
            listOf("path" to "TEXT", "ekey" to "BLOB", "ckey" to "BLOB", "encoded_size" to "INTEGER", "decoded_size" to "INTEGER"),
            primaryKey = "path",
        )
        validateTableConstraints("objects", listOf(
            "CHECK(LENGTH(EKEY)=16)", "CHECK(LENGTH(CKEY)=16)", "CHECK(ENCODED_SIZE>0)", "CHECK(DECODED_SIZE>=0)",
            "CHECK(SOURCE_KINDIN('DIRECT','RANGE'))", "CHECK(LENGTH(SOURCE_HASH)=16)", "CHECK(SOURCE_OFFSET>=0)",
            "CHECK(SOURCE_LENGTH=ENCODED_SIZE)", "SOURCE_HASH=EKEY", "SOURCE_OFFSET=0", "SOURCE_KIND='RANGE'",
        ))
        validateTableConstraints("loose", listOf(
            "CHECK(LENGTH(EKEY)=16)", "CHECK(LENGTH(CKEY)=16)", "CHECK(ENCODED_SIZE>0)", "CHECK(DECODED_SIZE>=0)",
            "FOREIGNKEY(EKEY)REFERENCESOBJECTS(EKEY)",
        ))
        if (tableSql("metadata").contains("WITHOUTROWID")) throw IllegalArgumentException("client catalog metadata table has an unexpected row layout")
    }

    private fun validateColumns(table: String, expected: List<Pair<String, String>>, primaryKey: String) {
        val columns = ArrayList<Triple<String, String, Int>>(expected.size)
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (columns.size >= expected.size) throw IllegalArgumentException("client catalog table has unexpected columns")
                columns += Triple(cursor.getString(1), cursor.getString(2).uppercase(Locale.ROOT), cursor.getInt(5))
            }
        }
        if (columns.map { it.first to it.second } != expected || columns.count { it.first == primaryKey && it.third == 1 } != 1) {
            throw IllegalArgumentException("client catalog table columns do not match the admitted layout")
        }
    }

    private fun validateTableConstraints(table: String, requiredFragments: List<String>) {
        val sql = tableSql(table)
        if (requiredFragments.any { it !in sql }) throw IllegalArgumentException("client catalog table constraints do not match the admitted schema")
    }

    private fun tableSql(table: String): String = database.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(table),
    ).use { cursor ->
        if (!cursor.moveToFirst()) throw IllegalArgumentException("client catalog table definition is missing")
        cursor.getString(0).uppercase(Locale.ROOT).replace(Regex("\\s+"), "")
    }

    private fun readMetadata(): Map<String, String> {
        val count = scalarLong("SELECT COUNT(*) FROM metadata")
        if (count != EXPECTED_METADATA_KEYS.size.toLong()) throw IllegalArgumentException("client catalog metadata row count is invalid")
        val result = LinkedHashMap<String, String>(count.toInt())
        var totalChars = 0L
        database.rawQuery("SELECT key, value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: throw IllegalArgumentException("client catalog metadata key is null")
                val value = cursor.getString(1) ?: throw IllegalArgumentException("client catalog metadata value is null")
                if (key !in EXPECTED_METADATA_KEYS || value.length > MAX_METADATA_VALUE_CHARS) {
                    throw IllegalArgumentException("client catalog contains an unsupported metadata field")
                }
                totalChars = Math.addExact(totalChars, value.length.toLong())
                if (totalChars > MAX_METADATA_TOTAL_CHARS) throw IllegalArgumentException("client catalog metadata exceeds the in-memory bound")
                parseJson(value)
                if (result.put(key, value) != null) throw IllegalArgumentException("client catalog has duplicate metadata keys")
            }
        }
        if (result.keys != EXPECTED_METADATA_KEYS) throw IllegalArgumentException("client catalog metadata fields are incomplete")
        return result
    }

    private fun validateFingerprint() {
        require(metadataString("schema") == "wowforever.arm64-client-catalog.sqlite") { "unsupported client catalog schema" }
        require(metadataLong("schema_version") == 1L) { "unsupported client catalog schema version" }
        val product = metadataString("product")
        val region = metadataString("region")
        val buildId = metadataLong("build_id")
        val version = metadataString("version")
        val platform = metadataString("platform")
        val architecture = metadataString("architecture")
        val locale = metadataString("locale")
        val buildConfigHash = normalizeHex(metadataString("build_config_hash"), "build-config hash")
        val cdnConfigHash = normalizeHex(metadataString("cdn_config_hash"), "CDN-config hash")
        require(product == "wow_classic_beta" && region == "us") { "client catalog product or region is not admitted" }
        require(platform == "Windows" && architecture == "arm64" && locale == "enUS") {
            "client catalog is not a Windows ARM64 enUS build"
        }
        require(buildId > 0L && version.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) &&
            version.substringAfterLast('.').toLongOrNull() == buildId
        ) {
            "client catalog version and BuildId do not match"
        }
        if (target != null) {
            require(product == target.product && region == target.region && platform == target.platform &&
                architecture == target.architecture && locale == target.locale && version == target.version &&
                buildId == target.buildId && buildConfigHash == target.buildConfig.hash && cdnConfigHash == target.cdnConfig.hash
            ) { "client catalog identity does not match its APK target admission" }
        } else {
            require(product == "wow_classic_beta" && region == "us" && platform == "Windows" &&
                architecture == "arm64" && locale == "enUS" && version == PINNED_VERSION && buildId == PINNED_BUILD_ID &&
                buildConfigHash == PINNED_BUILD_CONFIG_HASH && cdnConfigHash == PINNED_CDN_CONFIG_HASH
            ) { "client catalog fingerprint is not pinned by this APK" }
        }
        require(metadataLong("source_unresolved_count") == 0L) { "client catalog has unresolved object sources" }
        require(!metadataBoolean("payloads_acquired_or_verified")) {
            "client catalog source metadata must not claim unverified payload acquisition"
        }
        require(metadataLong("selected_loose_file_count") in 1..MAX_LOOSE_FILES.toLong()) { "client catalog loose-file metadata count is invalid" }
        require(metadataLong("download_selected_unique_ekey_count") in 1..MAX_OBJECTS.toLong()) { "client catalog selected-object metadata count is invalid" }
        require(metadataLong("size_selected_entry_count") == metadataLong("download_selected_unique_ekey_count")) {
            "client catalog selected manifest counts disagree"
        }
        listOf("build_config_hash", "cdn_config_hash").forEach { normalizeHex(metadataString(it), it) }
        val fileIndexValue = parseJson(metadataValues.getValue("cdn_file_index_hash"))
        val fileIndexHash = when {
            fileIndexValue === JSONObject.NULL -> null
            fileIndexValue is String -> normalizeHex(fileIndexValue, "CDN file-index hash")
            else -> throw IllegalArgumentException("client catalog CDN file-index hash is malformed")
        }
        if (fileIndexHash == null && scalarLong("SELECT COUNT(*) FROM objects WHERE source_kind='direct'") != 0L) {
            throw IllegalArgumentException("client catalog has direct object sources without a configured file index")
        }
        val officialVersionsDigest = parseJson(metadataValues.getValue("official_versions_sha256"))
        require(officialVersionsDigest === JSONObject.NULL ||
            (officialVersionsDigest is String && officialVersionsDigest.matches(Regex("(?i)[0-9a-f]{64}")))
        ) { "invalid official versions response fingerprint" }
        listOf(
            "install_manifest_sha256", "download_manifest_sha256", "size_manifest_sha256",
            "encoding_manifest_sha256", "cdn_index_sha256_manifest",
        ).forEach { require(metadataString(it).matches(Regex("(?i)[0-9a-f]{64}"))) { "invalid client catalog fingerprint field" } }
    }

    private fun validateRowsAndSummarize(): Summary {
        val count = scalarLong("SELECT COUNT(*) FROM objects")
        if (count !in 1..MAX_OBJECTS.toLong()) throw IllegalArgumentException("client catalog object count exceeds its bound")
        val looseCount = scalarLong("SELECT COUNT(*) FROM loose")
        if (looseCount !in 1..MAX_LOOSE_FILES.toLong() || looseCount != metadataLong("selected_loose_file_count")) {
            throw IllegalArgumentException("client catalog loose-file count is invalid")
        }

        var scanned = 0L
        var totalEncoded = 0L
        database.rawQuery(
            "SELECT ekey, ckey, encoded_size, decoded_size, source_kind, source_hash, source_offset, source_length FROM objects",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                readObjectRecord(cursor)
                scanned++
                if (scanned > MAX_OBJECTS) throw IllegalArgumentException("client catalog object count exceeds its bound")
                totalEncoded = Math.addExact(totalEncoded, cursor.getLong(2))
            }
        }
        if (scanned != count) throw IllegalArgumentException("client catalog object count changed while reading")
        val selectedCount = metadataLong("download_selected_unique_ekey_count")
        if (selectedCount > count) throw IllegalArgumentException("client catalog selected-object count exceeds its object table")
        return Summary(
            version = metadataString("version"),
            buildConfigHash = metadataString("build_config_hash"),
            cdnConfigHash = metadataString("cdn_config_hash"),
            objectCount = scanned,
            totalEncodedBytes = totalEncoded,
            looseCount = looseCount.toInt(),
        )
    }

    private fun validateMetadataBindings(summary: Summary) {
        val roles = parseJson(metadata("required_runtime_roles")) as? JSONArray
            ?: throw IllegalArgumentException("client catalog runtime roles are malformed")
        if (roles.length() !in MIN_RUNTIME_ROLE_COUNT..MAX_RUNTIME_ROLE_COUNT) {
            throw IllegalArgumentException("client catalog runtime role count is invalid")
        }
        val roleNames = HashSet<String>(roles.length())
        val vfsIndexes = HashSet<Int>()
        repeat(roles.length()) { index ->
            val role = roles.getString(index)
            if (!roleNames.add(role) || !(role in BASE_RUNTIME_ROLES || VFS_ROLE.matches(role))) {
                throw IllegalArgumentException("client catalog runtime role list is invalid")
            }
            if (role.startsWith("vfs-") && role != "vfs-root") {
                val number = role.removePrefix("vfs-").toIntOrNull()
                    ?: throw IllegalArgumentException("client catalog VFS runtime role index is invalid")
                if (!vfsIndexes.add(number)) throw IllegalArgumentException("client catalog VFS runtime roles contain duplicates")
            }
        }
        if (!roleNames.containsAll(BASE_RUNTIME_ROLES) || !roleNames.contains("vfs-root") ||
            vfsIndexes.sorted() != (1..vfsIndexes.size).toList()
        ) {
            throw IllegalArgumentException("client catalog runtime role list is incomplete")
        }

        val roleMap = parseJson(metadata("runtime_role_fingerprints")) as? JSONObject
            ?: throw IllegalArgumentException("client catalog runtime role fingerprints are malformed")
        if (roleMap.length() != roleNames.size || roleMap.keys().asSequence().toSet() != roleNames) {
            throw IllegalArgumentException("client catalog runtime role fingerprints do not match the admitted roles")
        }
        roleNames.forEach { role ->
            val fingerprint = roleMap.getJSONObject(role)
            val record = objectByKey(fingerprint.getString("ekey"))
            if (fingerprint.getString("ckey").equals(record.contentKey, true).not() ||
                fingerprint.getLong("encoded_size") != record.encodedSize ||
                fingerprint.getLong("decoded_size") != record.decodedSize ||
                fingerprint.getString("source_kind") != record.sourceKind
            ) throw IllegalArgumentException("client catalog runtime role fingerprint does not match its object")
        }

        val metadataObjects = parseJson(metadata("metadata_objects")) as? JSONObject
            ?: throw IllegalArgumentException("client catalog metadata-object fingerprints are malformed")
        if (metadataObjects.keys().asSequence().toSet() != METADATA_OBJECT_ROLES) {
            throw IllegalArgumentException("client catalog metadata-object roles are incomplete")
        }
        METADATA_OBJECT_ROLES.forEach { role ->
            val details = metadataObjects.getJSONObject(role)
            val flags = details.optInt("blte_flags", -1)
            val modes = details.optJSONArray("blte_modes")
                ?: throw IllegalArgumentException("client catalog metadata object BLTE modes are missing")
            val modeNames = ArrayList<String>(modes.length())
            repeat(modes.length()) { index -> modeNames += modes.getString(index) }
            if (details.getString("role") != role || flags !in SUPPORTED_BLTE_FLAGS ||
                modeNames.isEmpty() || modeNames.size != modeNames.toSet().size || modeNames.any { it !in SUPPORTED_BLTE_MODES }
            ) throw IllegalArgumentException("client catalog metadata object format is not admitted")
            val record = objectByKey(details.getString("encoding_key"))
            if (!details.getString("content_key").equals(record.contentKey, true) ||
                !details.getString("decoded_md5").equals(record.contentKey, true) ||
                details.getLong("declared_encoded_size") != record.encodedSize ||
                details.getLong("decoded_size") != record.decodedSize ||
                !details.getString("decoded_sha256").matches(Regex("(?i)[0-9a-f]{64}")) ||
                !details.getString("raw_sha256").matches(Regex("(?i)[0-9a-f]{64}"))
            ) throw IllegalArgumentException("client catalog metadata object does not match its encoded object")
        }
        if (metadataLong("selected_loose_file_count") != summary.looseCount.toLong()) {
            throw IllegalArgumentException("client catalog loose-file fingerprint does not match its table")
        }
    }

    private fun validateLooseRows(expectedCount: Int) {
        val paths = HashSet<String>(expectedCount)
        var count = 0
        database.rawQuery(
            "SELECT l.path, l.ekey, l.ckey, l.encoded_size, l.decoded_size, " +
                "o.ekey, o.ckey, o.encoded_size, o.decoded_size, o.source_kind, o.source_hash, o.source_offset, o.source_length " +
                "FROM loose l LEFT JOIN objects o ON o.ekey=l.ekey ORDER BY l.path",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (count++ >= MAX_LOOSE_FILES) throw IllegalArgumentException("client catalog loose-file count exceeds its bound")
                val path = validateLoosePath(cursor.getString(0))
                if (!paths.add(path.lowercase(Locale.ROOT))) {
                    throw IllegalArgumentException("client catalog loose paths contain a case-insensitive alias")
                }
                readBoundLooseRecord(cursor, 1, 5)
            }
        }
        if (count != expectedCount) throw IllegalArgumentException("client catalog loose-file table count changed while reading")
    }

    private fun readBoundLooseRecord(cursor: Cursor, looseStart: Int, objectStart: Int): ObjectRecord {
        val looseEkey = cursor.getBlob(looseStart) ?: throw IllegalArgumentException("client catalog loose EKey is null")
        val looseCkey = cursor.getBlob(looseStart + 1) ?: throw IllegalArgumentException("client catalog loose CKey is null")
        val looseEncodedSize = cursor.getLong(looseStart + 2)
        val looseDecodedSize = cursor.getLong(looseStart + 3)
        if (looseEkey.size != 16 || looseCkey.size != 16 || looseEncodedSize <= 0 || looseDecodedSize < 0) {
            throw IllegalArgumentException("client catalog loose row violates admitted key/size constraints")
        }
        val record = readObjectRecord(cursor, objectStart)
        if (looseEkey.hex() != record.ekey || looseCkey.hex() != record.contentKey ||
            looseEncodedSize != record.encodedSize || looseDecodedSize != record.decodedSize
        ) throw IllegalArgumentException("client catalog loose-file binding does not match its object")
        return record
    }

    private fun readObjectRecord(cursor: Cursor, start: Int = 0): ObjectRecord {
        val ekeyBytes = cursor.getBlob(start) ?: throw IllegalArgumentException("client catalog object EKey is null")
        val contentBytes = cursor.getBlob(start + 1) ?: throw IllegalArgumentException("client catalog object CKey is null")
        val sourceHashBytes = cursor.getBlob(start + 5) ?: throw IllegalArgumentException("client catalog source hash is null")
        if (ekeyBytes.size != 16 || contentBytes.size != 16 || sourceHashBytes.size != 16) {
            throw IllegalArgumentException("client catalog object key or source hash has the wrong length")
        }
        val ekey = ekeyBytes.hex()
        val contentKey = contentBytes.hex()
        val encodedSize = cursor.getLong(start + 2)
        val decodedSize = cursor.getLong(start + 3)
        val sourceKind = cursor.getString(start + 4)
        val sourceHash = sourceHashBytes.hex()
        val sourceOffset = cursor.getLong(start + 6)
        val sourceLength = cursor.getLong(start + 7)
        if (encodedSize !in 1..MAX_SUPPORTED_ENCODED_OBJECT_BYTES ||
            decodedSize !in 0..MAX_SUPPORTED_DECODED_OBJECT_BYTES ||
            sourceOffset < 0 || sourceLength != encodedSize ||
            sourceKind !in setOf("direct", "range")
        ) throw IllegalArgumentException("client catalog object row exceeds supported encoded/decoded size or source constraints")
        try {
            Math.addExact(sourceOffset, sourceLength)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("client catalog object source range overflows", failure)
        }
        if (sourceKind == "direct" && (sourceHash != ekey || sourceOffset != 0L)) {
            throw IllegalArgumentException("client catalog object source binding is invalid")
        }
        return ObjectRecord(ekey, contentKey, encodedSize, decodedSize, sourceKind, sourceHash, sourceOffset, sourceLength)
    }

    private fun scalarLong(sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        if (!cursor.moveToFirst()) throw IllegalArgumentException("client catalog count query returned no row")
        cursor.getLong(0)
    }

    private fun metadataString(name: String): String = parseJson(metadataValues.getValue(name)) as? String
        ?: throw IllegalArgumentException("client catalog metadata $name is not a string")

    private fun metadataLong(name: String): Long {
        val value = parseJson(metadataValues.getValue(name))
        val number = value as? Number ?: throw IllegalArgumentException("client catalog metadata $name is not numeric")
        val result = number.toLong()
        if (number.toDouble() != result.toDouble()) throw IllegalArgumentException("client catalog metadata $name is not an integer")
        return result
    }

    private fun metadataBoolean(name: String): Boolean = parseJson(metadataValues.getValue(name)) as? Boolean
        ?: throw IllegalArgumentException("client catalog metadata $name is not boolean")

    private fun ensureOpen() {
        check(!closed) { "client catalog is closed" }
    }

    private fun validateLoosePath(value: String?): String {
        require(!value.isNullOrEmpty() && value.length <= MAX_PATH_CHARS && '\u0000' !in value) { "client catalog loose path is invalid" }
        require('\\' !in value && !value.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(value)) {
            "client catalog loose path is absolute or ambiguous"
        }
        val parts = value.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." } &&
            parts.none { part -> part.any { it.code < 0x20 || it.code == 0x7f } }
        ) { "client catalog loose path contains traversal or a control character" }
        val normalized = value.lowercase(Locale.ROOT)
        require(normalized != "wtf/config.wtf" && normalized != "wtf/betasuspendedtest.wtf") {
            "client catalog loose path targets protected user configuration"
        }
        return value
    }

    private fun validateReadOnlyFile(file: File, expectedSize: Long, expectedHash: String) {
        val path = file.toPath().toAbsolutePath().normalize()
        var component = path.root
        for (part in path) {
            component = component.resolve(part)
            if (Files.isSymbolicLink(component)) throw IllegalArgumentException("client catalog path contains a symbolic link")
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expectedSize) {
            throw IllegalArgumentException("client catalog is not a regular file of the declared size")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var consumed = 0L
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                consumed = Math.addExact(consumed, count.toLong())
                if (consumed > expectedSize) throw IllegalArgumentException("client catalog grew while verifying its checksum")
                digest.update(buffer, 0, count)
            }
        }
        if (consumed != expectedSize || !digest.digest().hex().equals(expectedHash, true) || Files.size(path) != expectedSize) {
            throw IllegalArgumentException("client catalog SHA-256 or declared size does not match")
        }
    }

    private fun parseJson(value: String): Any {
        val tokener = JSONTokener(value)
        val parsed = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "client catalog metadata JSON has trailing data" }
        return parsed
    }

    private fun normalizeHex(value: String, description: String): String {
        require(value.matches(Regex("(?i)[0-9a-f]{32}"))) { "invalid client catalog $description" }
        return value.lowercase(Locale.ROOT)
    }

    private fun ByteArray.hex(): String {
        val digits = "0123456789abcdef"
        val output = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 255
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 15]
        }
        return String(output)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw java.util.concurrent.CancellationException("client catalog scan cancelled")
    }

    companion object {
        // Same recorder tag as the CASC writer and updater diagnostics, so the
        // existing `logcat -s ForeverClientUpdate:I` capture keeps this line.
        private val SIZE_BOUNDS_LOG = Logger.getLogger("ForeverClientUpdate")
        /** Kept for existing low-level test/engine callers; production admission comes from TargetBuild. */
        const val PINNED_70425_CATALOG_SHA256 = "8327794ea6867113dec55b07b4f3755c70fe808a67502eb12242ccbf742a69fc"
        const val PINNED_70425_CATALOG_SIZE = 99_647_488L
        const val PINNED_VERSION = "1.60.1.70425"
        const val PINNED_BUILD_ID = 70_425L
        const val PINNED_BUILD_CONFIG_HASH = "ea9c525eef74f1d682c00a8ef39a5d2b"
        const val PINNED_CDN_CONFIG_HASH = "fcab7fec2ccc6b8158b56b6938ec4551"
        const val MAX_SUPPORTED_ENCODED_OBJECT_BYTES = 1_073_741_314L
        const val MAX_SUPPORTED_DECODED_OBJECT_BYTES = 536_870_912L

        private const val MAX_CATALOG_BYTES = 256L * 1024 * 1024
        private const val MAX_OBJECTS = 2_000_000L
        private const val MAX_LOOSE_FILES = 4_096
        private const val MAX_PATH_CHARS = 4_096
        private const val MAX_METADATA_VALUE_CHARS = 2 * 1024 * 1024
        private const val MAX_METADATA_TOTAL_CHARS = 8L * 1024 * 1024
        private const val MIN_RUNTIME_ROLE_COUNT = 6
        private const val MAX_RUNTIME_ROLE_COUNT = 4_096
        private const val BUFFER_SIZE = 64 * 1024
        private val SUPPORTED_BLTE_FLAGS = setOf(0x0f, 0x10)
        private val SUPPORTED_BLTE_MODES = setOf("N", "Z")
        private val EXPECTED_TABLES = setOf("metadata", "objects", "loose")
        private val BASE_RUNTIME_ROLES = setOf("download", "encoding", "install", "root", "size")
        private val VFS_ROLE = Regex("vfs-(?:root|[1-9][0-9]*)")
        private val METADATA_OBJECT_ROLES = setOf("download", "encoding", "install", "size")
        private val EXPECTED_METADATA_KEYS = setOf(
            "architecture", "build_config_hash", "build_id", "cdn_config_hash", "cdn_file_index_hash",
            "cdn_index_count", "cdn_index_sha256_manifest", "download_manifest_sha256",
            "download_selected_entry_count", "download_selected_unique_ekey_count",
            "download_selected_unique_encoded_bytes", "encoding_manifest_sha256", "install_manifest_sha256",
            "locale", "metadata_objects", "official_versions_sha256", "payloads_acquired_or_verified", "platform",
            "product", "region", "required_runtime_roles", "runtime_role_fingerprints", "schema", "schema_version",
            "selected_loose_file_count", "selection_query", "server_compatibility", "size_manifest_sha256",
            "size_selected_entry_count", "size_selected_estimated_bytes", "source_boundary", "source_unresolved_count", "version",
        )

        /** Stream a gzip catalog into a fresh sibling and atomically publish only a verified file. */
        fun importGzip(
            input: InputStream,
            destination: File,
            expectedSize: Long,
            sha256: String,
            cancelled: () -> Boolean,
            expectedCompressedSize: Long? = null,
            compressedSha256: String? = null,
        ): File {
            require(expectedSize in 1..MAX_CATALOG_BYTES) { "compressed client catalog expands beyond the bounded import size" }
            require(sha256.matches(Regex("(?i)[0-9a-f]{64}"))) { "invalid imported client catalog SHA-256" }
            require((expectedCompressedSize == null) == (compressedSha256 == null)) { "compressed catalog fingerprint is incomplete" }
            if (expectedCompressedSize != null) {
                require(expectedCompressedSize in 1..MAX_CATALOG_BYTES &&
                    requireNotNull(compressedSha256).matches(Regex("(?i)[0-9a-f]{64}"))
                ) { "invalid compressed client catalog fingerprint" }
            }
            val destinationPath = destination.toPath().toAbsolutePath().normalize()
            val parent = destinationPath.parent ?: throw IllegalArgumentException("catalog destination has no parent directory")
            rejectSymlinkPath(parent)
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("catalog destination parent is not a directory")
            rejectSymlinkPath(destinationPath)
            if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("catalog destination already exists")

            val temporary = Files.createTempFile(parent, ".${destination.name.take(32)}-", ".catalog-part")
            var published = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val compressedDigest = expectedCompressedSize?.let { MessageDigest.getInstance("SHA-256") }
                var compressedConsumed = 0L
                val compressedInput = if (expectedCompressedSize == null) input else object : FilterInputStream(input) {
                    override fun read(): Int {
                        val value = super.read()
                        if (value >= 0) {
                            compressedConsumed = Math.addExact(compressedConsumed, 1L)
                            if (compressedConsumed > expectedCompressedSize) throw IllegalArgumentException("compressed client catalog exceeds its declared size")
                            compressedDigest!!.update(value.toByte())
                        }
                        return value
                    }

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(bytes, offset, length)
                        if (count > 0) {
                            compressedConsumed = Math.addExact(compressedConsumed, count.toLong())
                            if (compressedConsumed > expectedCompressedSize) throw IllegalArgumentException("compressed client catalog exceeds its declared size")
                            compressedDigest!!.update(bytes, offset, count)
                        }
                        return count
                    }
                }
                var total = 0L
                val channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                )
                try {
                    val output = Channels.newOutputStream(channel)
                    BufferedInputStream(GZIPInputStream(compressedInput, BUFFER_SIZE), BUFFER_SIZE).use { source ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (cancelled()) throw java.util.concurrent.CancellationException("client catalog import cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total = Math.addExact(total, count.toLong())
                            if (total > expectedSize || total > MAX_CATALOG_BYTES) {
                                throw IllegalArgumentException("decompressed client catalog exceeds its declared bound")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                    channel.force(true)
                } finally {
                    channel.close()
                }
                if (total != expectedSize || !digest.digest().hex().equals(sha256, true)) {
                    throw IllegalArgumentException("imported client catalog size or SHA-256 mismatch")
                }
                if (expectedCompressedSize != null &&
                    (compressedConsumed != expectedCompressedSize ||
                        !compressedDigest!!.digest().hex().equals(compressedSha256, true))
                ) throw IllegalArgumentException("compressed client catalog size or SHA-256 mismatch")
                if (cancelled()) throw java.util.concurrent.CancellationException("client catalog import cancelled")
                rejectSymlinkPath(destinationPath)
                try {
                    // Serialize app-private publishers and publish verified bytes
                    // with an atomic same-directory rename, without a hard-link requirement.
                    publishVerifiedPrivateFile(destinationPath, temporary)
                } catch (failure: UnsupportedOperationException) {
                    throw IllegalStateException("filesystem cannot atomically publish a fresh catalog without replacement", failure)
                } catch (failure: java.nio.file.FileSystemException) {
                    if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalStateException("catalog destination appeared during import; refusing replacement", failure)
                    }
                    throw IllegalStateException("filesystem cannot atomically publish a fresh catalog", failure)
                }
                published = true
                Files.deleteIfExists(temporary)
                return destinationPath.toFile()
            } finally {
                if (!published) Files.deleteIfExists(temporary)
            }
        }

        internal fun availableBytes(path: java.nio.file.Path): Long = path.toFile().usableSpace

        internal fun sameFilesystem(first: java.nio.file.Path, second: java.nio.file.Path): Boolean =
            if (System.getProperty("java.vm.name") == "Dalvik") {
                android.system.Os.stat(first.toString()).st_dev == android.system.Os.stat(second.toString()).st_dev
            } else Files.getFileStore(first) == Files.getFileStore(second)

        internal fun sharedCapacity(first: java.nio.file.Path, second: java.nio.file.Path): Boolean {
            if (sameFilesystem(first, second)) return true
            if (System.getProperty("java.vm.name") != "Dalvik") return false
            // Emulated external storage shares /data's physical capacity even
            // though its FUSE mount has a different device number.
            val a = first.toFile().canonicalPath
            val b = second.toFile().canonicalPath
            return (a.startsWith("/data/") && b.startsWith("/storage/") && android.os.Environment.isExternalStorageEmulated(second.toFile())) ||
                (b.startsWith("/data/") && a.startsWith("/storage/") && android.os.Environment.isExternalStorageEmulated(first.toFile()))
        }

        private val privatePublicationMonitor = Any()

        /** Only for the app-private verified cache, never client-volume activation. */
        internal fun publishVerifiedPrivateFile(destination: java.nio.file.Path, source: java.nio.file.Path) {
            require(destination.parent == source.parent) { "private publication must stay on one directory/filesystem" }
            synchronized(privatePublicationMonitor) {
                val lockPath = destination.parent.resolve(".forever-private-publish.lock")
                rejectSymlinkPath(lockPath)
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalStateException("private publication lock is not a regular file")
                }
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use {
                        rejectSymlinkPath(destination)
                        rejectSymlinkPath(source)
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            throw java.nio.file.FileAlreadyExistsException(destination.toString())
                        }
                        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "private publication source is not regular" }
                        Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    }
                }
            }
        }

        private fun rejectSymlinkPath(path: java.nio.file.Path) {
            var current = path.root
            for (part in path) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) throw IllegalStateException("catalog import path contains a symbolic link")
            }
        }

        private fun ByteArray.hex(): String {
            val digits = "0123456789abcdef"
            val output = CharArray(size * 2)
            for (index in indices) {
                val value = this[index].toInt() and 255
                output[index * 2] = digits[value ushr 4]
                output[index * 2 + 1] = digits[value and 15]
            }
            return String(output)
        }
    }
}
