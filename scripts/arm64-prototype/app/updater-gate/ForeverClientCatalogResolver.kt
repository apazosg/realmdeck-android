package app.gamenative

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Builds the updater's local SQLite object catalog from authenticated TACT metadata. */
internal class ForeverClientCatalogResolver @JvmOverloads constructor(
    private val workRoot: File,
    private val sourceFactory: ((ForeverClientUpdateCheck.Fingerprint, File) -> Source)? = null,
) {
    /** Narrow seam for offline fixture and preloaded-cache tests. Production uses official HTTPS. */
    interface Source {
        fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File
        fun decoded(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File
        fun raw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File
        fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File
    }

    data class Result(
        val catalogFile: File,
        val catalogSha256: String,
        val catalogBytes: Long,
        val buildConfigFile: File,
        val cdnConfigFile: File,
        val target: ForeverClientTargetBuild,
    )

    internal enum class PublicationBoundary { AFTER_STAGED_DIRECTORY_SYNC, AFTER_FINAL_PARENT_SYNC }

    internal class SimulatedProcessDeath : RuntimeException("simulated resolver process death")

    /** Test-only crash seam; production leaves it unset. */
    internal var publicationFaultForTests: ((PublicationBoundary) -> Unit)? = null

    private data class ConfigFile(
        val file: File,
        val raw: ByteArray,
        val sha256: String,
    )

    private data class MetadataEvidence(
        val role: String,
        val rawSha256: String,
        val decodedSha256: String,
        val blteFlags: Int,
        val blteModes: List<String>,
        val rawArtifact: String,
    )

    private data class RuntimeEncodingReference(val ekey: String, val encodedSize: Long)
    private data class IndexEvidence(
        val hash: String,
        val role: String,
        val sizeBytes: Long,
        val sha256: String,
        val entryCount: Long,
        val requiredMatches: Long,
    )
    private data class GzipEvidence(val bytes: Long, val sha256: String)

    fun resolve(
        published: ForeverClientUpdateCheck.Fingerprint,
        serverVersionConfirmed: Boolean,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
    ): Result = resolve(published, serverVersionConfirmed, cancelled, null, progress)

    /** Use only a digest computed by the caller from the raw official versions response. */
    fun resolve(
        published: ForeverClientUpdateCheck.Fingerprint,
        serverVersionConfirmed: Boolean,
        cancelled: () -> Boolean,
        officialVersionsSha256: String?,
        progress: (String, Long, Long) -> Unit,
    ): Result {
        checkCancelled(cancelled)
        validateFingerprint(published)
        val officialVersionsDigest = officialVersionsSha256?.let { digest ->
            require(digest.matches(HEX64)) { "official versions response SHA-256 is malformed" }
            digest.lowercase(Locale.ROOT)
        }
        val root = safeWorkRoot()
        return withWorkRootLock(root, cancelled) {
            resolveLocked(published, officialVersionsDigest, cancelled, progress, root)
        }
    }

    private fun resolveLocked(
        published: ForeverClientUpdateCheck.Fingerprint,
        officialVersionsDigest: String?,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
        root: File,
    ): Result {
        checkCancelled(cancelled)
        val key = cacheKey(published)
        val metadataCacheParent = childDirectory(root, "metadata-cache")
        val metadataCache = File(metadataCacheParent, key)
        val source = sourceFactory?.invoke(published, metadataCache) ?: ProductionSource(metadataCache)

        val buildFile = verifiedConfig(source.config(published.buildConfigHash, cancelled) {
            progress("build-config", it, 0L)
        }, published.buildConfigHash, "build config", cancelled)
        val cdnFile = verifiedConfig(source.config(published.cdnConfigHash, cancelled) {
            progress("cdn-config", it, 0L)
        }, published.cdnConfigHash, "CDN config", cancelled)
        val build = ForeverTactConfigs.build(buildFile.raw, published)
        val cdn = ForeverTactConfigs.cdn(cdnFile.raw, published)
        val runtimeEncodingReferences = parseRuntimeEncodingReferences(buildFile.raw, build)

        val catalogParent = childDirectory(root, "catalogs")
        val finalDirectory = File(catalogParent, key)
        if (Files.exists(finalDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return reuseCompletedCatalog(
                finalDirectory, published, buildFile, cdnFile, cancelled, progress,
            )
        }

        val temporaryDirectory = Files.createTempDirectory(root.toPath(), ".resolver-$key-").toFile()
        try {
            val decodedEvidence = LinkedHashMap<String, MetadataEvidence>(METADATA_ROLES.size)
            val decodedFiles = LinkedHashMap<String, File>(METADATA_ROLES.size)
            for (role in METADATA_ROLES) {
                checkCancelled(cancelled)
                val reference = build.metadataObjects[role]
                    ?: throw IllegalArgumentException("build config has no $role metadata reference")
                val rawFile = source.raw(reference, cancelled) { value ->
                    progress("raw-$role", value, reference.encodedSize)
                }
                val decodedFile = source.decoded(reference, cancelled) { value ->
                    progress("decoded-$role", value, reference.decodedSize ?: 0L)
                }
                decodedEvidence[role] = verifyMetadataObject(
                    reference, rawFile, decodedFile, temporaryDirectory, cancelled,
                )
                decodedFiles[role] = decodedFile
            }

            val workFile = File(temporaryDirectory, "work.sqlite3")
            val work = openWorkDatabase(workFile)
            val installRecords = try {
                importManifests(
                    work,
                    decodedFiles.getValue("install"),
                    decodedFiles.getValue("download"),
                    decodedFiles.getValue("size"),
                    decodedFiles.getValue("encoding"),
                    build,
                    runtimeEncodingReferences,
                    cancelled,
                    progress,
                )
            } catch (failure: Throwable) {
                work.close()
                throw failure
            }

            try {
                addRequiredCandidates(work, build, runtimeEncodingReferences, installRecords, decodedEvidence)
                val neededCount = createNeededSet(work)
                if (neededCount !in 1..MAX_CATALOG_OBJECTS) {
                    throw IllegalArgumentException("required catalog object count is outside its bound: $neededCount")
                }
                val indexes = scanIndexes(work, cdn, source, cancelled, progress, neededCount)
                validateDownloadSources(work)
                val chosen = chooseCandidates(work, installRecords, cancelled)
                val objectCount = scalarLong(work, "SELECT COUNT(*) FROM objects_work")
                val looseCount = scalarLong(work, "SELECT COUNT(*) FROM loose_chosen")
                if (objectCount !in 1..MAX_CATALOG_OBJECTS || looseCount !in 1..MAX_LOOSE_FILES) {
                    throw IllegalArgumentException("resolved catalog row counts are outside their bounds")
                }
                val unresolved = scalarLong(
                    work,
                    "SELECT COUNT(*) FROM objects_work w LEFT JOIN sources s ON s.ekey=w.ekey " +
                        "WHERE s.ekey IS NULL OR s.encoded_size!=w.encoded_size",
                )
                require(unresolved == 0L) { "$unresolved required catalog objects have unresolved CDN sources" }

                val metadata = catalogMetadata(
                    published, officialVersionsDigest, build, cdn, buildFile, cdnFile, decodedEvidence,
                    installRecords, chosen, indexes, work,
                )
                val catalogFile = File(temporaryDirectory, "client-catalog.sqlite3")
                writeFinalCatalog(work, catalogFile, metadata, cancelled, progress)
                work.close()

                val catalogSha = sha256File(catalogFile, cancelled)
                val catalogBytes = catalogFile.length()
                require(catalogBytes in 1..MAX_CATALOG_BYTES) { "generated client catalog exceeds its size bound" }
                val gzip = gzipEvidence(catalogFile, File(temporaryDirectory, "client-catalog.sqlite3.gz"), cancelled)
                val target = makeTarget(published, buildFile, cdnFile, catalogBytes, catalogSha, gzip, installRecords.executable)
                ForeverClientUpdateCatalog(catalogFile, catalogSha, catalogBytes, target).use { verified ->
                    require(verified.verifiedSha256 == catalogSha) { "generated catalog SHA-256 was not retained by the read-only validator" }
                    require(verified.summary().objectCount == objectCount && verified.summary().looseCount == looseCount.toInt()) {
                        "generated catalog counts changed during read-only validation"
                    }
                }
                Files.deleteIfExists(File(temporaryDirectory, "client-catalog.sqlite3.gz").toPath())
                workFile.delete()
                ensureConfigStable(buildFile, published.buildConfigHash, "build config", cancelled)
                ensureConfigStable(cdnFile, published.cdnConfigHash, "CDN config", cancelled)

                publishCompletedCatalog(
                    catalogParent, finalDirectory, catalogFile, target,
                )
                progress("catalog-ready", catalogBytes, catalogBytes)
                return Result(
                    File(finalDirectory, "client-catalog.sqlite3"),
                    catalogSha,
                    catalogBytes,
                    buildFile.file,
                    cdnFile.file,
                    target,
                )
            } finally {
                if (!work.isOpen) Unit
                else work.close()
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private data class InstallSelection(
        val records: List<ForeverTactMetadata.InstallRecord>,
        val executable: ForeverTactMetadata.InstallRecord,
    )

    private data class CandidateSelection(
        val requiredRoles: List<String>,
        val runtimeFingerprints: JSONObject,
        val looseCount: Int,
    )

    private fun importManifests(
        db: SQLiteDatabase,
        install: File,
        download: File,
        size: File,
        encoding: File,
        build: ForeverTactMetadata.BuildConfig,
        runtimeEncodingReferences: Map<String, RuntimeEncodingReference>,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
    ): InstallSelection {
        validateSelectionTags(install, ManifestKind.INSTALL)
        validateSelectionTags(download, ManifestKind.DOWNLOAD)
        validateSelectionTags(size, ManifestKind.SIZE)
        createWorkTables(db)
        fun reportBatch(stage: String, rows: Long) {
            if (rows == 1L || rows % SQL_BATCH_ROWS == 0L) progress(stage, rows, 0L)
        }

        var executable: ForeverTactMetadata.InstallRecord? = null
        val installBatch = SqlBatch<ForeverTactMetadata.InstallRecord>(db) { statement, record ->
            statement.bindString(1, record.path)
            statement.bindBlob(2, record.contentKey.hexBytes())
            statement.bindLong(3, record.decodedSize)
            statement.executeInsert()
            if (record.path.equals(EXPECTED_EXECUTABLE, ignoreCase = true)) {
                if (executable != null) throw IllegalArgumentException("install manifest contains duplicate ARM64 executable entries")
                executable = record
            }
        }
        val installCount = ForeverTactManifests.install(install, cancelled) { record ->
            val lowerPath = record.path.lowercase(Locale.ROOT)
            require(lowerPath != "wtf/config.wtf" && lowerPath != "wtf/betasuspendedtest.wtf") {
                "selected install manifest targets protected user configuration"
            }
            installBatch.add(record)
            progress("parse-install", installBatch.totalRows, 0L)
        }
        installBatch.finish()
        require(installCount in 1..MAX_LOOSE_FILES.toLong()) { "selected install manifest has no bounded loose-file set" }
        require(installBatch.totalRows == installCount) { "install-manifest callback count disagrees with its streaming statistics" }
        val selectedExecutable = executable ?: throw IllegalArgumentException("Windows/arm64/enUS install selection lacks WowB-ARM64.exe")

        val downloadBatch = SqlBatch<ForeverTactMetadata.DownloadRecord>(db) { statement, record ->
            statement.bindBlob(1, record.encodingKey.hexBytes())
            statement.bindLong(2, record.encodedSize)
            statement.executeInsert()
        }
        progress("parse-download", 0L, 0L)
        val downloadStats = ForeverTactManifests.download(download, cancelled) { record ->
            downloadBatch.add(record)
            reportBatch("parse-download", downloadBatch.totalRows)
        }
        downloadBatch.finish()
        progress("parse-download", downloadBatch.totalRows, 0L)
        require(downloadBatch.totalRows == downloadStats.selectedEntries) {
            "download-manifest callback count disagrees with its streaming statistics"
        }

        var sizeBatchIndex = 0L
        val sizeBatch = SqlBatch<ForeverTactMetadata.SizeRecord>(db) { statement, record ->
            statement.bindLong(1, sizeBatchIndex++)
            statement.bindBlob(2, record.encodingKeyPrefix.hexBytes())
            statement.bindLong(3, record.encodedSize)
            statement.executeInsert()
        }
        progress("parse-size", 0L, 0L)
        val sizeStats = ForeverTactManifests.size(size, cancelled) { record ->
            sizeBatch.add(record)
            reportBatch("parse-size", sizeBatch.totalRows)
        }
        sizeBatch.finish()
        progress("parse-size", sizeBatch.totalRows, 0L)
        require(sizeBatch.totalRows == sizeStats.selectedEntries) {
            "size-manifest callback count disagrees with its streaming statistics"
        }
        require(scalarLong(db, "SELECT COUNT(*) FROM size_estimates") == sizeStats.selectedEntries &&
            scalarLong(db, "SELECT COALESCE(SUM(estimated_size),0) FROM size_estimates") == sizeStats.selectedEncodedBytes
        ) { "size-manifest selected records disagree with their streaming statistics" }

        val mappingBatch = SqlBatch<ForeverTactMetadata.ContentMapping>(db) { statement, record ->
            statement.bindBlob(1, record.encodingKey.hexBytes())
            statement.bindBlob(2, record.contentKey.hexBytes())
            statement.bindLong(3, record.decodedSize)
            statement.executeInsert()
        }
        val encodingBatch = SqlBatch<ForeverTactMetadata.EncodingRecord>(db) { statement, record ->
            statement.bindBlob(1, record.encodingKey.hexBytes())
            statement.bindLong(2, record.encodedSize)
            statement.executeInsert()
        }
        progress("parse-encoding-content", 0L, 0L)
        val encodingStats = ForeverTactManifests.encoding(
            encoding,
            cancelled,
            { record -> mappingBatch.add(record); reportBatch("parse-encoding-content", mappingBatch.totalRows) },
            { record -> encodingBatch.add(record); reportBatch("parse-encoding", encodingBatch.totalRows) },
        )
        mappingBatch.finish()
        encodingBatch.finish()
        require(mappingBatch.totalRows == encodingStats.contentMappings && encodingBatch.totalRows == encodingStats.encodingEntries) {
            "encoding-manifest callback counts disagree with their streaming statistics"
        }
        require(scalarLong(db, "SELECT COUNT(*) FROM encoding_ckeys") == encodingStats.contentMappings &&
            scalarLong(db, "SELECT COUNT(*) FROM encoding_ekeys") == encodingStats.encodingEntries
        ) { "encoding manifest contains a duplicate mapping or EKey record" }
        db.execSQL("CREATE INDEX encoding_ckeys_content_idx ON encoding_ckeys(ckey,decoded_size,ekey)")

        require(downloadStats.selectedEntries > 0L && sizeStats.selectedEntries > 0L) {
            "Windows/arm64/enUS download or size selection is empty"
        }
        require(encodingStats.contentMappings > 0L && encodingStats.encodingEntries > 0L) {
            "encoding manifest contains no full CKey/EKey mapping closure"
        }
        require(runtimeEncodingReferences.keys.containsAll(build.runtimeContents.map { it.role }.filter { it.startsWith("vfs-") && it != "vfs-root" })) {
            "build config runtime references and parsed VFS EKeys disagree"
        }
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('download_selected_entries',${downloadStats.selectedEntries})")
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('download_selected_bytes',${downloadStats.selectedEncodedBytes})")
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('size_selected_entries',${sizeStats.selectedEntries})")
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('size_selected_bytes',${sizeStats.selectedEncodedBytes})")
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('encoding_content_mappings',${encodingStats.contentMappings})")
        db.execSQL("INSERT INTO manifest_stats(name,value) VALUES('encoding_entries',${encodingStats.encodingEntries})")
        progress("manifests-parsed", encodingStats.contentMappings, encodingStats.contentMappings)

        val records = ArrayList<ForeverTactMetadata.InstallRecord>(installCount.toInt())
        db.rawQuery("SELECT path,ckey,decoded_size FROM install_records ORDER BY path", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled(cancelled)
                records += ForeverTactMetadata.InstallRecord(cursor.getString(0), cursor.getBlob(1).hex(), cursor.getLong(2))
            }
        }
        require(records.size == installCount.toInt()) { "selected install records changed after parsing" }
        return InstallSelection(records, selectedExecutable)
    }

    private enum class ManifestKind { INSTALL, DOWNLOAD, SIZE }

    private fun validateSelectionTags(file: File, kind: ManifestKind) {
        DataInputStream(FileInputStream(file)).use { input ->
            val magic = ByteArray(2)
            input.readFully(magic)
            val expected = when (kind) {
                ManifestKind.INSTALL -> "IN"
                ManifestKind.DOWNLOAD -> "DL"
                ManifestKind.SIZE -> "DS"
            }
            require(magic.contentEquals(expected.toByteArray(Charsets.US_ASCII))) { "$kind manifest magic mismatch" }
            val version = input.readUnsignedByte()
            val keySize = input.readUnsignedByte()
            val entryCount: Long
            val tagCount: Int
            when (kind) {
                ManifestKind.INSTALL -> {
                    tagCount = input.readUnsignedShort()
                    entryCount = readU32(input)
                    require(version in 1..2 && keySize == 16) { "unsupported install selection header" }
                }
                ManifestKind.DOWNLOAD -> {
                    val checksum = input.readUnsignedByte()
                    entryCount = readU32(input)
                    tagCount = input.readUnsignedShort()
                    require(version in 1..3 && keySize == 16 && checksum in 0..1) { "unsupported download selection header" }
                    val flagSize = if (version >= 2) input.readUnsignedByte() else 0
                    require(flagSize == 0) { "download manifest entry flags are not supported" }
                    if (version >= 3) {
                        input.readUnsignedByte()
                        repeat(3) { require(input.readUnsignedByte() == 0) { "download V3 reserved header is nonzero" } }
                    }
                    val entryBytes = Math.multiplyExact(entryCount, 16L + 5L + 1L + (if (checksum == 1) 4L else 0L))
                    input.skipFully(entryBytes)
                }
                ManifestKind.SIZE -> {
                    entryCount = readU32(input)
                    tagCount = input.readUnsignedShort()
                    readU40(input)
                    require(version in 1..2 && keySize == 9) { "unsupported size selection header" }
                }
            }
            require(entryCount in 0..MAX_MANIFEST_ENTRIES && tagCount in 0..MAX_TAGS) { "$kind tag or entry count exceeds the bound" }
            val bitmapBytes = (entryCount + 7L) / 8L
            var names = HashSet<String>()
            val selectedNames = HashMap<String, Int>(3)
            repeat(tagCount) { index ->
                val name = readCString(input, MAX_TAG_NAME_BYTES, "$kind tag $index")
                val type = input.readUnsignedShort()
                if (kind == ManifestKind.SIZE && type in setOf(0, 0xffff)) {
                    // Size V1/V2 may terminate its tag list with a type marker. The selected
                    // install tags are checked among the preceding records; parser validates EOF.
                    return@use validateRequestedTags(kind, selectedNames)
                }
                require(name.isNotEmpty() && names.add(name)) { "$kind manifest has duplicate or empty tag names" }
                if (name in REQUESTED_TAGS) {
                    require(selectedNames.put(name, type) == null) { "$kind manifest contains duplicate $name selection tags" }
                }
                input.skipFully(bitmapBytes)
            }
            validateRequestedTags(kind, selectedNames)
        }
    }

    private fun validateRequestedTags(kind: ManifestKind, selected: Map<String, Int>) {
        require(selected.keys == REQUESTED_TAGS) { "$kind manifest must contain exactly Windows, arm64, and enUS selection tags" }
        require(selected.values.toSet().size == REQUESTED_TAGS.size) {
            "$kind Windows/arm64/enUS selection tags must use independent tag groups"
        }
    }

    private fun parseRuntimeEncodingReferences(raw: ByteArray, build: ForeverTactMetadata.BuildConfig): Map<String, RuntimeEncodingReference> {
        val relevant = Regex("vfs-(?:root|[1-9][0-9]*)(?:-size)?")
        val fields = LinkedHashMap<String, String>()
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString().lineSequence().forEach { sourceLine ->
            val line = sourceLine.removeSuffix("\r")
            val equals = line.indexOf('=')
            if (equals <= 0) return@forEach
            val name = line.substring(0, equals).trim()
            if (relevant.matches(name)) fields[name] = line.substring(equals + 1).trim()
        }
        val expectedRoles = build.runtimeContents.map { it.role }.filter { it.startsWith("vfs-") }.toSet()
        require(fields.keys.filter { !it.endsWith("-size") }.toSet() == expectedRoles) {
            "hash-bound build config VFS references disagree with the parsed runtime role set"
        }
        val result = LinkedHashMap<String, RuntimeEncodingReference>()
        for (role in expectedRoles) {
            val hashes = fields[role].orEmpty().split(Regex("\\s+")).filter(String::isNotEmpty)
            require(hashes.size == 2 && hashes.all { HEX32.matches(it) }) { "build config $role reference is malformed" }
            val sizes = fields["$role-size"].orEmpty().split(Regex("\\s+")).filter(String::isNotEmpty)
            require(sizes.size == 2 && sizes.all { it.matches(DECIMAL) }) { "build config $role sizes are malformed" }
            val decodedSize = sizes[0].toLongOrNull()
            val encodedSize = sizes[1].toLongOrNull()
            require(decodedSize != null && encodedSize != null && decodedSize > 0 && encodedSize > 0) {
                "build config $role sizes exceed the supported integer range"
            }
            val parsed = build.runtimeContents.single { it.role == role }
            require(parsed.contentKey.equals(hashes[0], true) && parsed.decodedSize == decodedSize) {
                "hash-bound build config $role CKey/decoded-size binding changed during parsing"
            }
            result[role] = RuntimeEncodingReference(hashes[1].lowercase(Locale.ROOT), encodedSize)
        }
        return result
    }

    private fun addRequiredCandidates(
        db: SQLiteDatabase,
        build: ForeverTactMetadata.BuildConfig,
        runtimeRefs: Map<String, RuntimeEncodingReference>,
        install: InstallSelection,
        metadata: Map<String, MetadataEvidence>,
    ) {
        for (runtime in build.runtimeContents) {
            val explicit = runtimeRefs[runtime.role]
            val candidateFilter = buildString {
                append("c.ckey=X'${runtime.contentKey}'")
                runtime.decodedSize?.let { append(" AND c.decoded_size=$it") }
                explicit?.let { append(" AND c.ekey=X'${it.ekey}' AND e.encoded_size=${it.encodedSize}") }
            }
            val possibleCandidates = scalarLong(
                db,
                "SELECT COUNT(*) FROM encoding_ckeys c JOIN encoding_ekeys e ON e.ekey=c.ekey WHERE $candidateFilter",
            )
            require(possibleCandidates in 1..64) {
                "runtime role ${runtime.role} has no encoding candidate or exceeds 64 alternatives"
            }
            val sql = buildString {
                append("INSERT OR IGNORE INTO candidates(role,owner,ekey,ckey,encoded_size,decoded_size) ")
                append("SELECT ?, '', c.ekey,c.ckey,e.encoded_size,c.decoded_size FROM encoding_ckeys c ")
                append("JOIN encoding_ekeys e ON e.ekey=c.ekey WHERE c.ckey=?")
                runtime.decodedSize?.let { append(" AND c.decoded_size=?") }
                explicit?.let { append(" AND c.ekey=? AND e.encoded_size=?") }
            }
            db.compileStatement(sql).use { statement ->
                statement.bindString(1, runtime.role)
                statement.bindBlob(2, runtime.contentKey.hexBytes())
                var index = 3
                runtime.decodedSize?.let { statement.bindLong(index++, it) }
                explicit?.let {
                    statement.bindBlob(index++, it.ekey.hexBytes())
                    statement.bindLong(index, it.encodedSize)
                }
                statement.executeInsert()
            }
            val added = scalarLong(db, "SELECT COUNT(*) FROM candidates WHERE role=${sqlQuote(runtime.role)} AND owner=''")
            val decodedSizes = scalarLong(
                db,
                "SELECT COUNT(DISTINCT decoded_size) FROM candidates WHERE role=${sqlQuote(runtime.role)} AND owner=''",
            )
            require(added in 1..64 && decodedSizes == 1L) {
                "runtime role ${runtime.role} has no unique-size encoding candidate set or exceeds 64 alternatives"
            }
        }

        for (role in METADATA_ROLES) {
            val reference = build.metadataObjects.getValue(role)
            val evidence = metadata.getValue(role)
            val mapped = db.rawQuery(
                "SELECT c.ckey,c.decoded_size,e.encoded_size FROM encoding_ckeys c " +
                    "LEFT JOIN encoding_ekeys e ON e.ekey=c.ekey WHERE c.ekey=X'${reference.encodingKey}'",
                null,
            )
            mapped.use { cursor ->
                while (cursor.moveToNext()) {
                    require(cursor.getBlob(0).hex().equals(reference.contentKey, true) &&
                        cursor.getLong(1) == requireNotNull(reference.decodedSize) &&
                        (cursor.isNull(2) || cursor.getLong(2) == reference.encodedSize)
                    ) { "$role config and encoding-page metadata bindings disagree" }
                }
            }
            val ekeyRows = db.rawQuery(
                "SELECT encoded_size FROM encoding_ekeys WHERE ekey=X'${reference.encodingKey}'",
                null,
            )
            ekeyRows.use { cursor ->
                if (cursor.moveToFirst()) require(cursor.getLong(0) == reference.encodedSize) {
                    "$role config EKey size disagrees with the encoding manifest"
                }
            }
            db.execSQL(
                "INSERT INTO candidates(role,owner,ekey,ckey,encoded_size,decoded_size) VALUES(" +
                    "${sqlQuote(role)},'',X'${reference.encodingKey}',X'${reference.contentKey}'," +
                    "${reference.encodedSize},${requireNotNull(reference.decodedSize)})",
            )
            require(evidence.role == role) { "metadata verification role changed" }
        }

        val missingLoose = scalarLong(
            db,
            "SELECT COUNT(*) FROM install_records i WHERE NOT EXISTS (" +
                "SELECT 1 FROM encoding_ckeys c JOIN encoding_ekeys e ON e.ekey=c.ekey " +
                "WHERE c.ckey=i.ckey AND c.decoded_size=i.decoded_size)",
        )
        require(missingLoose == 0L) { "$missingLoose selected install files have no matching encoding EKey" }
        val looseCount = scalarLong(db, "SELECT COUNT(*) FROM install_records")
        require(looseCount == install.records.size.toLong()) { "selected install records changed" }
        val statsRows = db.rawQuery("SELECT value FROM manifest_stats WHERE name='download_selected_entries'", null)
        val downloadEntries = statsRows.use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(downloadEntries > 0L) { "download selection did not produce any full EKeys" }

        db.execSQL(
            "INSERT INTO loose_candidates(path,ekey,ckey,encoded_size,decoded_size) " +
                "SELECT i.path,c.ekey,c.ckey,e.encoded_size,c.decoded_size FROM install_records i " +
                "JOIN encoding_ckeys c ON c.ckey=i.ckey AND c.decoded_size=i.decoded_size " +
                "JOIN encoding_ekeys e ON e.ekey=c.ekey",
        )
    }

    private fun createNeededSet(db: SQLiteDatabase): Long {
        val conflictingDownloadSizes = scalarLong(
            db,
            "SELECT COUNT(*) FROM (SELECT ekey FROM download_entries GROUP BY ekey HAVING COUNT(*)>1)",
        )
        require(conflictingDownloadSizes == 0L) { "selected download EKeys have conflicting encoded sizes" }
        val missingOrWrongDownloadSize = scalarLong(
            db,
            "SELECT COUNT(*) FROM download_entries d LEFT JOIN encoding_ekeys e ON e.ekey=d.ekey " +
                "WHERE e.ekey IS NULL OR e.encoded_size!=d.encoded_size",
        )
        require(missingOrWrongDownloadSize == 0L) { "$missingOrWrongDownloadSize selected download EKeys are missing or have conflicting encoding sizes" }
        val unmappedDownload = scalarLong(
            db,
            "SELECT COUNT(*) FROM download_entries d LEFT JOIN encoding_ckeys c ON c.ekey=d.ekey WHERE c.ekey IS NULL",
        )
        require(unmappedDownload == 0L) { "$unmappedDownload selected download EKeys have no full CKey mapping" }
        val ambiguousDownload = scalarLong(
            db,
            "SELECT COUNT(*) FROM (SELECT d.ekey FROM download_entries d JOIN encoding_ckeys c ON c.ekey=d.ekey " +
                "GROUP BY d.ekey HAVING COUNT(DISTINCT c.ckey)>1)",
        )
        require(ambiguousDownload == 0L) { "$ambiguousDownload selected download EKeys map to multiple CKeys" }
        db.execSQL(
            "INSERT INTO objects_work(ekey,ckey,encoded_size,decoded_size) " +
                "SELECT d.ekey,MIN(c.ckey),d.encoded_size,MIN(c.decoded_size) FROM download_entries d " +
                "JOIN encoding_ckeys c ON c.ekey=d.ekey GROUP BY d.ekey",
        )

        val conflictingCandidateSizes = scalarLong(
            db,
            "SELECT COUNT(*) FROM (SELECT ekey FROM (" +
                "SELECT ekey,encoded_size FROM objects_work UNION ALL " +
                "SELECT ekey,encoded_size FROM candidates UNION ALL " +
                "SELECT ekey,encoded_size FROM loose_candidates) GROUP BY ekey HAVING MIN(encoded_size)!=MAX(encoded_size))",
        )
        require(conflictingCandidateSizes == 0L) { "$conflictingCandidateSizes required EKeys have conflicting encoded sizes" }
        db.execSQL("INSERT INTO needed(ekey,encoded_size) SELECT ekey,encoded_size FROM objects_work")
        db.execSQL("INSERT OR IGNORE INTO needed(ekey,encoded_size) SELECT ekey,encoded_size FROM candidates")
        db.execSQL("INSERT OR IGNORE INTO needed(ekey,encoded_size) SELECT ekey,encoded_size FROM loose_candidates")
        val count = scalarLong(db, "SELECT COUNT(*) FROM needed")
        require(count in 1..MAX_CATALOG_OBJECTS) { "required unique EKey count is outside its bound" }
        return count
    }

    private fun scanIndexes(
        db: SQLiteDatabase,
        cdn: ForeverTactMetadata.CdnConfig,
        source: Source,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
        neededCount: Long,
    ): List<IndexEvidence> {
        val bloom = EKeyBloom(neededCount)
        db.rawQuery("SELECT ekey FROM needed", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled(cancelled)
                bloom.add(cursor.getBlob(0))
            }
        }
        val fileIndex = cdn.fileIndex
        val indexHashes = LinkedHashSet<String>()
        cdn.archives.forEach { indexHashes += it.lowercase(Locale.ROOT) }
        fileIndex?.let { indexHashes += it.lowercase(Locale.ROOT) }
        require(indexHashes.isNotEmpty() && indexHashes.size <= MAX_CDN_INDEX_COUNT) {
            "CDN index count is outside the supported bound"
        }
        val results = ArrayList<IndexEvidence>(indexHashes.size)
        var indexNumber = 0L
        var totalIndexBytes = 0L
        for (hash in indexHashes) {
            checkCancelled(cancelled)
            progress("cdn-indexes", indexNumber, indexHashes.size.toLong())
            val indexFile = source.index(hash, cancelled) { value ->
                progress("cdn-index-$indexNumber", value, 0L)
            }
            validateRegularFile(indexFile, "CDN index")
            val indexBytes = indexFile.length()
            require(indexBytes in 1..MAX_CDN_INDEX_BYTES) { "CDN index is empty or exceeds its byte bound" }
            val rawSha = sha256File(indexFile, cancelled)
            val matched = longArrayOf(0L)
            val batch = ArrayList<ForeverTactMetadata.SourceRecord>(SQL_BATCH_ROWS)
            val stats = ForeverTactCdnIndex.parse(indexFile, hash, fileIndex, cancelled) { record ->
                if (bloom.mightContain(record.encodingKey.hexBytes())) {
                    batch += record
                    if (batch.size == SQL_BATCH_ROWS) {
                        storeSourceBatch(db, batch, cancelled, matched)
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty()) storeSourceBatch(db, batch, cancelled, matched)
            require(indexFile.length() == indexBytes && sha256File(indexFile, cancelled) == rawSha) {
                "official CDN index changed while its structure and sources were being verified"
            }
            val role = if (stats.sourceKind == "direct") "file-index" else "archive-index"
            results += IndexEvidence(hash, role, indexBytes, rawSha, stats.entries, matched[0])
            totalIndexBytes = Math.addExact(totalIndexBytes, indexBytes)
            require(totalIndexBytes <= MAX_TOTAL_INDEX_BYTES) { "total official CDN index bytes exceed the supported bound" }
            indexNumber++
            progress("cdn-indexes", indexNumber, indexHashes.size.toLong())
        }
        val missingDownloadSources = scalarLong(
            db,
            "SELECT COUNT(*) FROM download_entries d LEFT JOIN sources s ON s.ekey=d.ekey " +
                "WHERE s.ekey IS NULL OR s.encoded_size!=d.encoded_size",
        )
        require(missingDownloadSources == 0L) { "$missingDownloadSources selected download EKeys have no official CDN index source" }
        results.sortBy { it.hash }
        return results
    }

    private fun storeSourceBatch(
        db: SQLiteDatabase,
        rows: List<ForeverTactMetadata.SourceRecord>,
        cancelled: () -> Boolean,
        matched: LongArray,
    ) {
        val neededQuery = db.compileStatement("SELECT encoded_size FROM needed WHERE ekey=?")
        val rangeInsert = db.compileStatement(
            "INSERT OR IGNORE INTO sources(ekey,encoded_size,source_kind,source_hash,source_offset) VALUES(?,?,?, ?,?)",
        )
        val directInsert = db.compileStatement(
            "INSERT OR IGNORE INTO sources(ekey,encoded_size,source_kind,source_hash,source_offset) VALUES(?,?,'direct',?,0)",
        )
        val directUpgrade = db.compileStatement(
            "UPDATE sources SET source_kind='direct',source_hash=?,source_offset=0 WHERE ekey=? AND source_kind='range'",
        )
        db.beginTransaction()
        try {
            for (record in rows) {
                checkCancelled(cancelled)
                val ekey = record.encodingKey
                val ekeyBytes = ekey.hexBytes()
                val needed = try {
                    neededQuery.bindBlob(1, ekeyBytes)
                    neededQuery.simpleQueryForLong()
                } catch (_: android.database.sqlite.SQLiteDoneException) {
                    null
                } finally {
                    neededQuery.clearBindings()
                } ?: continue
                require(needed == record.encodedSize) {
                    "official CDN index encoded size disagrees with encoding metadata for EKey $ekey"
                }
                val direct = record.sourceKind == "direct"
                val sourceHash = if (direct) ekey else record.sourceHash
                require(record.sourceKind in setOf("direct", "range") && sourceHash.matches(HEX32)) {
                    "official CDN index source kind/hash is invalid"
                }
                require(record.sourceOffset >= 0L && record.sourceOffset + record.encodedSize <= UINT32_RANGE_END) {
                    "official CDN source range exceeds the CDN uint32 offset boundary"
                }
                if (direct) require(record.sourceOffset == 0L && sourceHash == ekey) {
                    "file-index direct source is not bound to its full EKey"
                }
                if (direct) {
                    directInsert.bindBlob(1, ekeyBytes)
                    directInsert.bindLong(2, record.encodedSize)
                    directInsert.bindBlob(3, sourceHash.hexBytes())
                    directInsert.executeInsert()
                    directInsert.clearBindings()
                    directUpgrade.bindBlob(1, sourceHash.hexBytes())
                    directUpgrade.bindBlob(2, ekeyBytes)
                    directUpgrade.executeUpdateDelete()
                    directUpgrade.clearBindings()
                } else {
                    rangeInsert.bindBlob(1, ekeyBytes)
                    rangeInsert.bindLong(2, record.encodedSize)
                    rangeInsert.bindString(3, "range")
                    rangeInsert.bindBlob(4, sourceHash.hexBytes())
                    rangeInsert.bindLong(5, record.sourceOffset)
                    rangeInsert.executeInsert()
                    rangeInsert.clearBindings()
                }
                matched[0] = Math.addExact(matched[0], 1L)
            }
            db.setTransactionSuccessful()
        } finally {
            neededQuery.close()
            rangeInsert.close()
            directInsert.close()
            directUpgrade.close()
            db.endTransaction()
        }
    }

    private fun validateDownloadSources(db: SQLiteDatabase) {
        val missing = scalarLong(
            db,
            "SELECT COUNT(*) FROM objects_work w LEFT JOIN sources s ON s.ekey=w.ekey " +
                "WHERE s.ekey IS NULL OR s.encoded_size!=w.encoded_size",
        )
        require(missing == 0L) { "$missing selected download objects have no valid CDN source" }
    }

    private fun chooseCandidates(
        db: SQLiteDatabase,
        install: InstallSelection,
        cancelled: () -> Boolean,
    ): CandidateSelection {
        val runtimeFingerprints = JSONObject()
        val requiredRoles = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT role,owner FROM candidates ORDER BY role,owner", null).use { roles ->
            while (roles.moveToNext()) {
                checkCancelled(cancelled)
                val role = roles.getString(0)
                val owner = roles.getString(1)
                val selected = db.rawQuery(
                    "SELECT c.ekey,c.ckey,c.encoded_size,c.decoded_size,s.source_kind " +
                        "FROM candidates c JOIN sources s ON s.ekey=c.ekey AND s.encoded_size=c.encoded_size " +
                        "WHERE c.role=${sqlQuote(role)} AND c.owner=${sqlQuote(owner)} " +
                        "ORDER BY CASE WHEN EXISTS(SELECT 1 FROM download_entries d WHERE d.ekey=c.ekey) THEN 0 ELSE 1 END,c.ekey LIMIT 1",
                    null,
                )
                val choice = selected.use { cursor ->
                    if (cursor.moveToFirst()) CandidateRow(
                        cursor.getBlob(0), cursor.getBlob(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4),
                    ) else null
                } ?: throw IllegalArgumentException("required runtime role has no resolved CDN source: $role${if (owner.isEmpty()) "" else ":$owner"}")
                val ckeyCount = scalarLong(
                    db,
                    "SELECT COUNT(DISTINCT ckey) FROM encoding_ckeys WHERE ekey=X'${choice.ekey.hex()}'",
                )
                require(ckeyCount == 1L || (ckeyCount == 0L && role in METADATA_ROLES)) {
                    "runtime EKey ${choice.ekey.hex()} maps to $ckeyCount CKeys"
                }
                upsertObject(db, choice)
                runtimeFingerprints.put(role, JSONObject()
                    .put("owner", owner)
                    .put("ekey", choice.ekey.hex())
                    .put("ckey", choice.ckey.hex())
                    .put("encoded_size", choice.encodedSize)
                    .put("decoded_size", choice.decodedSize)
                    .put("source_kind", choice.sourceKind))
                requiredRoles += role
            }
        }

        val chosenLoose = SqlBatch<LooseChoice>(db) { statement, choice ->
            statement.bindString(1, choice.path)
            statement.bindBlob(2, choice.ekey)
            statement.bindBlob(3, choice.ckey)
            statement.bindLong(4, choice.encodedSize)
            statement.bindLong(5, choice.decodedSize)
            statement.executeInsert()
        }
        db.rawQuery("SELECT DISTINCT path FROM loose_candidates ORDER BY path", null).use { paths ->
            while (paths.moveToNext()) {
                checkCancelled(cancelled)
                val path = paths.getString(0)
                val selected = db.rawQuery(
                    "SELECT l.ekey,l.ckey,l.encoded_size,l.decoded_size FROM loose_candidates l " +
                        "JOIN sources s ON s.ekey=l.ekey AND s.encoded_size=l.encoded_size " +
                        "WHERE l.path=${sqlQuote(path)} " +
                        "ORDER BY CASE WHEN EXISTS(SELECT 1 FROM download_entries d WHERE d.ekey=l.ekey) THEN 0 ELSE 1 END,l.ekey LIMIT 1",
                    null,
                )
                val choice = selected.use { cursor ->
                    if (cursor.moveToFirst()) LooseChoice(
                        path, cursor.getBlob(0), cursor.getBlob(1), cursor.getLong(2), cursor.getLong(3),
                    ) else null
                } ?: throw IllegalArgumentException("selected loose file has no resolved CDN source: $path")
                val collision = scalarLong(db, "SELECT COUNT(DISTINCT ckey) FROM encoding_ckeys WHERE ekey=X'${choice.ekey.hex()}'")
                require(collision == 1L) { "loose-file EKey ${choice.ekey.hex()} maps to $collision CKeys" }
                upsertObject(db, CandidateRow(choice.ekey, choice.ckey, choice.encodedSize, choice.decodedSize, ""))
                chosenLoose.add(choice)
            }
        }
        chosenLoose.finish()
        val looseCount = scalarLong(db, "SELECT COUNT(*) FROM loose_chosen")
        require(looseCount == install.records.size.toLong()) { "selected loose-file source count disagrees with install selection" }
        requiredRoles.sort()
        require(requiredRoles.size in 6..MAX_RUNTIME_ROLE_COUNT) {
            "resolved runtime role count exceeds the catalog protocol bound"
        }
        return CandidateSelection(requiredRoles, runtimeFingerprints, looseCount.toInt())
    }

    private data class CandidateRow(
        val ekey: ByteArray,
        val ckey: ByteArray,
        val encodedSize: Long,
        val decodedSize: Long,
        val sourceKind: String,
    )
    private data class LooseChoice(
        val path: String,
        val ekey: ByteArray,
        val ckey: ByteArray,
        val encodedSize: Long,
        val decodedSize: Long,
    )

    private fun upsertObject(db: SQLiteDatabase, row: CandidateRow) {
        val key = row.ekey.hex()
        val existing = db.rawQuery(
            "SELECT ckey,encoded_size,decoded_size FROM objects_work WHERE ekey=X'$key'",
            null,
        )
        val found = existing.use { cursor ->
            if (cursor.moveToFirst()) Triple(cursor.getBlob(0), cursor.getLong(1), cursor.getLong(2)) else null
        }
        if (found != null) {
            require(found.first.contentEquals(row.ckey) && found.second == row.encodedSize && found.third == row.decodedSize) {
                "full EKey $key has conflicting CKey/size bindings"
            }
            return
        }
        db.execSQL(
            "INSERT INTO objects_work(ekey,ckey,encoded_size,decoded_size) VALUES(" +
                "X'$key',X'${row.ckey.hex()}',${row.encodedSize},${row.decodedSize})",
        )
    }

    private fun catalogMetadata(
        published: ForeverClientUpdateCheck.Fingerprint,
        officialVersionsSha256: String?,
        build: ForeverTactMetadata.BuildConfig,
        cdn: ForeverTactMetadata.CdnConfig,
        buildFile: ConfigFile,
        cdnFile: ConfigFile,
        decoded: Map<String, MetadataEvidence>,
        install: InstallSelection,
        chosen: CandidateSelection,
        indexes: List<IndexEvidence>,
        db: SQLiteDatabase,
    ): Map<String, Any> {
        val metadataObjects = JSONObject()
        METADATA_ROLES.forEach { role ->
            val reference = build.metadataObjects.getValue(role)
            val evidence = decoded.getValue(role)
            metadataObjects.put(role, JSONObject()
                .put("role", role)
                .put("content_key", reference.contentKey)
                .put("encoding_key", reference.encodingKey)
                .put("declared_encoded_size", reference.encodedSize)
                .put("decoded_size", requireNotNull(reference.decodedSize))
                .put("decoded_md5", reference.contentKey)
                .put("decoded_sha256", evidence.decodedSha256)
                .put("blte_flags", evidence.blteFlags)
                .put("blte_modes", JSONArray(evidence.blteModes))
                .put("raw_artifact", evidence.rawArtifact)
                .put("raw_sha256", evidence.rawSha256))
        }
        val indexFingerprint = MessageDigest.getInstance("SHA-256")
        indexes.sortedBy { it.hash }.forEach { index ->
            indexFingerprint.update("${index.hash}\u0000${index.role}\u0000${index.sizeBytes}\u0000${index.sha256}\n".toByteArray(Charsets.UTF_8))
        }
        val roles = JSONArray(chosen.requiredRoles)
        val selectedDownloadUnique = scalarLong(db, "SELECT COUNT(*) FROM download_entries")
        val selectedDownloadBytes = scalarLong(db, "SELECT COALESCE(SUM(encoded_size),0) FROM download_entries")
        val downloadSelectedEntries = stat(db, "download_selected_entries")
        val sizeSelectedEntries = stat(db, "size_selected_entries")
        require(selectedDownloadUnique in 1..MAX_CATALOG_OBJECTS) { "selected download EKey count is outside its bound" }
        val unresolved = scalarLong(
            db,
            "SELECT COUNT(*) FROM objects_work w LEFT JOIN sources s ON s.ekey=w.ekey " +
                "WHERE s.ekey IS NULL OR s.encoded_size!=w.encoded_size",
        )
        require(unresolved == 0L) { "catalog contains unresolved source records" }
        require(chosen.looseCount == install.records.size) { "loose-file count changed during catalog metadata generation" }
        return linkedMapOf(
            "schema" to "wowforever.arm64-client-catalog.sqlite",
            "schema_version" to 1,
            "product" to published.product,
            "region" to ForeverClientUpdateCheck.REGION,
            "build_id" to requireNotNull(published.buildId),
            "version" to published.version,
            "platform" to PLATFORM,
            "architecture" to ARCHITECTURE,
            "locale" to LOCALE,
            "build_config_hash" to published.buildConfigHash.lowercase(Locale.ROOT),
            "cdn_config_hash" to published.cdnConfigHash.lowercase(Locale.ROOT),
            // The caller may provide the raw response digest from the official versions check.
            // Absence remains explicit; never derive a fake feed digest from the fingerprint.
            "official_versions_sha256" to (officialVersionsSha256 ?: JSONObject.NULL),
            "selection_query" to SELECTION_QUERY,
            "install_manifest_sha256" to decoded.getValue("install").decodedSha256,
            "download_manifest_sha256" to decoded.getValue("download").decodedSha256,
            "size_manifest_sha256" to decoded.getValue("size").decodedSha256,
            "encoding_manifest_sha256" to decoded.getValue("encoding").decodedSha256,
            "metadata_objects" to metadataObjects,
            "required_runtime_roles" to roles,
            "runtime_role_fingerprints" to chosen.runtimeFingerprints,
            "cdn_index_count" to indexes.size,
            "cdn_index_sha256_manifest" to indexFingerprint.digest().hex(),
            "cdn_file_index_hash" to (cdn.fileIndex ?: JSONObject.NULL),
            "download_selected_entry_count" to downloadSelectedEntries,
            "download_selected_unique_ekey_count" to selectedDownloadUnique,
            "download_selected_unique_encoded_bytes" to selectedDownloadBytes,
            "size_selected_entry_count" to sizeSelectedEntries,
            "size_selected_estimated_bytes" to stat(db, "size_selected_bytes"),
            "selected_loose_file_count" to chosen.looseCount,
            "source_unresolved_count" to unresolved,
            "payloads_acquired_or_verified" to false,
            "source_boundary" to (
                "Official parsed fingerprint, hash-bound configs/manifests, and validated CDN indexes only; " +
                    "payload objects and archives were not fetched. " +
                    if (officialVersionsSha256 == null) "No raw versions-response digest was supplied by the caller." else
                        "official_versions_sha256 was supplied by the caller from the raw official versions response."
            ),
            "server_compatibility" to JSONObject()
                .put("server", "ClassicForever")
                .put("build_id", requireNotNull(published.buildId))
                .put("status", "server compatibility not independently confirmed")
                .put("login_or_gameplay_verified", false),
        ).also {
            require(buildFile.raw.isNotEmpty() && cdnFile.raw.isNotEmpty())
        }
    }

    private fun writeFinalCatalog(
        spool: SQLiteDatabase,
        destination: File,
        metadata: Map<String, Any>,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
    ) {
        checkCancelled(cancelled)
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "refusing to overwrite a staged catalog" }
        val output = SQLiteDatabase.openOrCreateDatabase(destination, null)
        try {
            require(setPragma(output, "PRAGMA cache_size=-8192").toLongOrNull() == -8_192L) {
                "SQLite catalog page cache could not be bounded to 8 MiB"
            }
            require(setPragma(output, "PRAGMA temp_store=FILE").toLongOrNull() == 1L) { "SQLite catalog temporary storage is not file-backed" }
            require(setPragma(output, "PRAGMA journal_mode=OFF").equals("off", true)) { "SQLite catalog journal mode is not disabled" }
            require(setPragma(output, "PRAGMA synchronous=OFF").toLongOrNull() == 0L) { "SQLite catalog synchronous mode is not disabled" }
            require(setPragma(output, "PRAGMA foreign_keys=ON").toLongOrNull() == 1L) { "SQLite catalog foreign-key checks could not be enabled" }
            require(setPragma(output, "PRAGMA max_page_count=${MAX_CATALOG_BYTES / SQLITE_PAGE_BYTES}").toLongOrNull()?.let {
                it in 1L..(MAX_CATALOG_BYTES / SQLITE_PAGE_BYTES)
            } == true) { "SQLite catalog page-count bound could not be enforced" }
            output.execSQL("DROP TABLE IF EXISTS android_metadata")
            output.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            output.execSQL(
                "CREATE TABLE objects (" +
                    "ekey BLOB PRIMARY KEY CHECK(length(ekey)=16)," +
                    "ckey BLOB NOT NULL CHECK(length(ckey)=16)," +
                    "encoded_size INTEGER NOT NULL CHECK(encoded_size>0)," +
                    "decoded_size INTEGER NOT NULL CHECK(decoded_size>=0)," +
                    "source_kind TEXT NOT NULL CHECK(source_kind IN ('direct','range'))," +
                    "source_hash BLOB NOT NULL CHECK(length(source_hash)=16)," +
                    "source_offset INTEGER NOT NULL CHECK(source_offset>=0)," +
                    "source_length INTEGER NOT NULL CHECK(source_length=encoded_size)," +
                    "CHECK((source_kind='direct' AND source_hash=ekey AND source_offset=0) OR source_kind='range')" +
                    ") WITHOUT ROWID",
            )
            output.execSQL(
                "CREATE TABLE loose (" +
                    "path TEXT PRIMARY KEY," +
                    "ekey BLOB NOT NULL CHECK(length(ekey)=16)," +
                    "ckey BLOB NOT NULL CHECK(length(ckey)=16)," +
                    "encoded_size INTEGER NOT NULL CHECK(encoded_size>0)," +
                    "decoded_size INTEGER NOT NULL CHECK(decoded_size>=0)," +
                    "FOREIGN KEY(ekey) REFERENCES objects(ekey)" +
                    ") WITHOUT ROWID",
            )
            val metadataBatch = SqlBatch<Pair<String, Any>>(output) { statement, row ->
                statement.bindString(1, row.first)
                statement.bindString(2, jsonValue(row.second))
                statement.executeInsert()
            }
            metadata.toSortedMap().forEach { (key, value) -> metadataBatch.add(key to value) }
            metadataBatch.finish()

            val expectedObjects = scalarLong(spool, "SELECT COUNT(*) FROM objects_work")
            var objectRows = 0L
            val objectRowsBatch = SqlBatch<CatalogObject>(output) { statement, row ->
                statement.bindBlob(1, row.ekey)
                statement.bindBlob(2, row.ckey)
                statement.bindLong(3, row.encodedSize)
                statement.bindLong(4, row.decodedSize)
                statement.bindString(5, row.sourceKind)
                statement.bindBlob(6, row.sourceHash)
                statement.bindLong(7, row.sourceOffset)
                statement.bindLong(8, row.encodedSize)
                statement.executeInsert()
            }
            spool.rawQuery(
                "SELECT w.ekey,w.ckey,w.encoded_size,w.decoded_size,s.source_kind,s.source_hash,s.source_offset " +
                    "FROM objects_work w JOIN sources s ON s.ekey=w.ekey AND s.encoded_size=w.encoded_size ORDER BY w.ekey",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    checkCancelled(cancelled)
                    val ekey = cursor.getBlob(0)
                    val ckey = cursor.getBlob(1)
                    val encoded = cursor.getLong(2)
                    val decoded = cursor.getLong(3)
                    val sourceKind = cursor.getString(4)
                    val sourceHash = cursor.getBlob(5)
                    val sourceOffset = cursor.getLong(6)
                    require(ekey.size == 16 && ckey.size == 16 && sourceHash.size == 16 && encoded in 1..MAX_ENCODED_OBJECT_BYTES &&
                        decoded in 0..MAX_DECODED_OBJECT_BYTES && sourceKind in setOf("direct", "range") &&
                        sourceOffset >= 0 && sourceOffset + encoded <= UINT32_RANGE_END
                    ) { "resolved catalog object exceeds its full-key, source, or transfer bounds" }
                    if (sourceKind == "direct") require(sourceHash.contentEquals(ekey) && sourceOffset == 0L) {
                        "direct catalog source is not bound to its EKey"
                    }
                    objectRowsBatch.add(CatalogObject(ekey, ckey, encoded, decoded, sourceKind, sourceHash, sourceOffset))
                    objectRows++
                    if ((objectRows % SQL_BATCH_ROWS) == 0L) progress("catalog-objects", objectRows, expectedObjects)
                }
            }
            objectRowsBatch.finish()
            require(objectRows == expectedObjects) { "catalog object export lost rows: $objectRows!=$expectedObjects" }

            var looseRows = 0L
            val looseBatch = SqlBatch<LooseChoice>(output, "INSERT INTO loose(path,ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?,?)") { statement, row ->
                statement.bindString(1, row.path)
                statement.bindBlob(2, row.ekey)
                statement.bindBlob(3, row.ckey)
                statement.bindLong(4, row.encodedSize)
                statement.bindLong(5, row.decodedSize)
                statement.executeInsert()
            }
            spool.rawQuery("SELECT path,ekey,ckey,encoded_size,decoded_size FROM loose_chosen ORDER BY path", null).use { cursor ->
                while (cursor.moveToNext()) {
                    checkCancelled(cancelled)
                    looseBatch.add(LooseChoice(
                        cursor.getString(0), cursor.getBlob(1), cursor.getBlob(2), cursor.getLong(3), cursor.getLong(4),
                    ))
                    looseRows++
                }
            }
            looseBatch.finish()
            require(looseRows in 1..MAX_LOOSE_FILES.toLong()) { "catalog loose export count is outside its bound" }
            output.close()
            val size = destination.length()
            require(size in 1..MAX_CATALOG_BYTES) { "generated SQLite catalog exceeds its byte bound" }
            FileChannel.open(destination.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            progress("catalog-objects", objectRows, expectedObjects)
        } catch (failure: Throwable) {
            output.close()
            throw failure
        }
    }

    private data class CatalogObject(
        val ekey: ByteArray,
        val ckey: ByteArray,
        val encodedSize: Long,
        val decodedSize: Long,
        val sourceKind: String,
        val sourceHash: ByteArray,
        val sourceOffset: Long,
    )

    private fun makeTarget(
        published: ForeverClientUpdateCheck.Fingerprint,
        build: ConfigFile,
        cdn: ConfigFile,
        catalogBytes: Long,
        catalogSha: String,
        gzip: GzipEvidence,
        executable: ForeverTactMetadata.InstallRecord,
    ): ForeverClientTargetBuild {
        val json = JSONObject()
            .put("schema", "wowforever.client-target")
            .put("schema_version", 1)
            .put("product", published.product)
            .put("region", ForeverClientUpdateCheck.REGION)
            .put("platform", PLATFORM)
            .put("architecture", ARCHITECTURE)
            .put("locale", LOCALE)
            .put("version", published.version)
            .put("build_id", requireNotNull(published.buildId))
            .put("launcher_version_code", DYNAMIC_LAUNCHER_VERSION_CODE)
            .put("launcher_version_name", "dynamic-metadata")
            .put("artifact_subdir", "client-updater-packaged-catalog/${published.buildId}")
            .put("build_config", JSONObject()
                .put("hash", published.buildConfigHash.lowercase(Locale.ROOT))
                .put("bytes", build.raw.size)
                .put("sha256", build.sha256))
            .put("cdn_config", JSONObject()
                .put("hash", published.cdnConfigHash.lowercase(Locale.ROOT))
                .put("bytes", cdn.raw.size)
                .put("sha256", cdn.sha256))
            .put("catalog", JSONObject()
                .put("bytes", catalogBytes)
                .put("sha256", catalogSha)
                .put("gzip_bytes", gzip.bytes)
                .put("gzip_sha256", gzip.sha256))
            .put("executable", JSONObject()
                .put("path", executable.path)
                .put("decoded_bytes", executable.decodedSize)
                .put("content_key", executable.contentKey)
                .put("expected_machine", EXPECTED_ARM64_MACHINE))
            .put("server_version_source", "not-confirmed")
            .put("native_validation", JSONObject()
                .put("status", "pending")
                .put("evidence_sha256", JSONObject.NULL))
        return ForeverClientTargetBuild.parse(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun reuseCompletedCatalog(
        directory: File,
        published: ForeverClientUpdateCheck.Fingerprint,
        build: ConfigFile,
        cdn: ConfigFile,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
    ): Result {
        validateDirectory(directory, "completed catalog cache")
        val targetFile = File(directory, TARGET_RECEIPT_NAME)
        val catalogFile = File(directory, CATALOG_FILE_NAME)
        validateRegularFile(targetFile, "catalog target receipt")
        validateRegularFile(catalogFile, "completed catalog")
        require(directory.list()?.toSet() == setOf(TARGET_RECEIPT_NAME, CATALOG_FILE_NAME)) {
            "completed catalog cache has unexpected children"
        }
        val targetBytes = readBounded(targetFile, ForeverClientTargetBuild.MAX_JSON_BYTES.toLong(), cancelled)
        val target = ForeverClientTargetBuild.parse(targetBytes)
        require(target.fingerprint == published.copy(
            buildConfigHash = published.buildConfigHash.lowercase(Locale.ROOT),
            cdnConfigHash = published.cdnConfigHash.lowercase(Locale.ROOT),
        )) { "completed catalog receipt does not match the published build fingerprint" }
        require(target.serverVersionSource in setOf("user-confirmed", "not-confirmed") &&
            target.nativeValidation.status == ForeverClientTargetBuild.NativeValidation.Status.PENDING
        ) { "completed catalog receipt has incompatible server/native evidence" }
        require(target.buildConfig.bytes == build.raw.size.toLong() && target.buildConfig.sha256 == build.sha256 &&
            target.cdnConfig.bytes == cdn.raw.size.toLong() && target.cdnConfig.sha256 == cdn.sha256
        ) { "completed catalog receipt does not match the verified current configs" }
        val gzipPath = File(workRoot, ".catalog-cache-check-${UUID.randomUUID()}.gz")
        try {
            val gzip = gzipEvidence(catalogFile, gzipPath, cancelled)
            require(gzip.bytes == target.catalog.gzipBytes && gzip.sha256 == target.catalog.gzipSha256) {
                "completed catalog gzip fingerprint does not match the generated catalog bytes"
            }
        } finally {
            Files.deleteIfExists(gzipPath.toPath())
        }
        ForeverClientUpdateCatalog(catalogFile, target.catalog.sha256, target.catalog.bytes, target).use { catalog ->
            val summary = catalog.summary()
            require(summary.version == published.version && summary.buildConfigHash == published.buildConfigHash.lowercase(Locale.ROOT) &&
                summary.cdnConfigHash == published.cdnConfigHash.lowercase(Locale.ROOT)
            ) { "cached catalog identity does not match the published fingerprint" }
            val executable = catalog.looseFiles().singleOrNull { it.path.equals(EXPECTED_EXECUTABLE, true) }
                ?: throw IllegalArgumentException("cached catalog lacks the selected ARM64 executable entry")
            require(executable.objectRecord.contentKey == target.executable.contentKey &&
                executable.objectRecord.decodedSize == target.executable.decodedBytes
            ) { "cached catalog executable does not match its target receipt" }
            ensureConfigStable(build, published.buildConfigHash, "build config", cancelled)
            ensureConfigStable(cdn, published.cdnConfigHash, "CDN config", cancelled)
            progress("catalog-cache-hit", summary.objectCount, summary.objectCount)
            return Result(
                catalogFile,
                catalog.verifiedSha256,
                target.catalog.bytes,
                build.file,
                cdn.file,
                target,
            )
        }
    }

    private fun publishCompletedCatalog(
        parent: File,
        destination: File,
        stagedCatalog: File,
        target: ForeverClientTargetBuild,
    ) {
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "catalog destination appeared during generation" }
        val publicationStage = Files.createTempDirectory(parent.toPath(), ".publish-${destination.name}-").toFile()
        var renamed = false
        try {
            val stagedFinalCatalog = File(publicationStage, CATALOG_FILE_NAME).toPath()
            Files.move(stagedCatalog.toPath(), stagedFinalCatalog, StandardCopyOption.ATOMIC_MOVE)
            forceFile(stagedFinalCatalog)
            val stagedReceipt = File(publicationStage, TARGET_RECEIPT_NAME)
            writeNewFile(stagedReceipt, target.originalJsonCopy())
            require(publicationStage.list()?.toSet() == setOf(CATALOG_FILE_NAME, TARGET_RECEIPT_NAME)) {
                "catalog publication stage contains unexpected children"
            }
            forceDirectory(publicationStage.toPath())
            publicationFaultForTests?.invoke(PublicationBoundary.AFTER_STAGED_DIRECTORY_SYNC)

            require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "catalog destination appeared during publication; refusing to replace it"
            }
            try {
                Files.move(publicationStage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: FileAlreadyExistsException) {
                throw IllegalStateException("catalog destination appeared during atomic publication; refusing to replace it", failure)
            }
            renamed = true
            forceDirectory(parent.toPath())
            publicationFaultForTests?.invoke(PublicationBoundary.AFTER_FINAL_PARENT_SYNC)
        } catch (failure: Throwable) {
            if (!renamed && failure !is SimulatedProcessDeath) publicationStage.deleteRecursively()
            if (failure is UnsupportedOperationException || failure is AtomicMoveNotSupportedException) {
                throw IllegalStateException("filesystem cannot atomically publish a complete catalog directory", failure)
            }
            throw failure
        }
    }

    private fun <T> withWorkRootLock(root: File, cancelled: () -> Boolean, block: () -> T): T {
        val canonicalRoot = root.canonicalFile.toPath().normalize()
        val jvmLock = JVM_ROOT_LOCKS.computeIfAbsent(canonicalRoot.toString()) { ReentrantLock() }
        var acquired = false
        while (!acquired) {
            checkCancelled(cancelled)
            try {
                acquired = jvmLock.tryLock(100, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("client catalog resolution was interrupted while waiting for its work-root lock")
            }
        }
        try {
            val lockPath = canonicalRoot.resolve(RESOLVER_LOCK_FILE)
            rejectSymlinkPath(lockPath)
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                require(Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(lockPath)) {
                    "catalog resolver lock is not a regular non-symlink file"
                }
                var fileLock: FileLock? = null
                while (fileLock == null) {
                    checkCancelled(cancelled)
                    try {
                        fileLock = channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        // Another resolver in this process may own a lock through a different channel.
                    }
                    if (fileLock == null) {
                        try {
                            Thread.sleep(50L)
                        } catch (failure: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw CancellationException("client catalog resolution was interrupted while waiting for its OS lock")
                        }
                    }
                }
                try {
                    return block()
                } finally {
                    fileLock.release()
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    private fun verifyMetadataObject(
        reference: ForeverTactMetadata.ObjectReference,
        rawFile: File,
        decodedFile: File,
        temporaryDirectory: File,
        cancelled: () -> Boolean,
    ): MetadataEvidence {
        require(reference.role in METADATA_ROLES && reference.decodedSize != null) {
            "metadata role reference is missing its CKey/size binding"
        }
        validateRegularFile(rawFile, "raw ${reference.role} metadata")
        require(rawFile.length() == reference.encodedSize && rawFile.length() in 1..MAX_RAW_METADATA_BYTES) {
            "raw ${reference.role} metadata encoded size disagrees with its hash-bound config"
        }
        validateEKey(rawFile, reference.encodingKey, cancelled)
        val rawSha = sha256File(rawFile, cancelled)

        validateRegularFile(decodedFile, "decoded ${reference.role} metadata")
        require(decodedFile.length() == reference.decodedSize && decodedFile.length() in 1..MAX_DECODED_METADATA_BYTES) {
            "decoded ${reference.role} metadata size disagrees with its hash-bound config"
        }
        val decodedMd5 = digestFile(decodedFile, "MD5", cancelled)
        require(decodedMd5.equals(reference.contentKey, true)) { "decoded ${reference.role} metadata CKey mismatch" }
        val decodedSha = sha256File(decodedFile, cancelled)

        val independentlyDecoded = File(temporaryDirectory, "verify-${reference.role}-${UUID.randomUUID()}.decoded")
        try {
            ForeverBlte.decode(rawFile, independentlyDecoded, reference.decodedSize, reference.contentKey, cancelled)
            require(independentlyDecoded.length() == decodedFile.length() &&
                sha256File(independentlyDecoded, cancelled) == decodedSha
            ) { "source-provided decoded ${reference.role} metadata does not match its raw BLTE object" }
        } finally {
            Files.deleteIfExists(independentlyDecoded.toPath())
        }
        require(rawFile.length() == reference.encodedSize &&
            validateEKeyDigest(rawFile, reference.encodingKey, cancelled).equals(reference.encodingKey, true) &&
            sha256File(rawFile, cancelled) == rawSha
        ) {
            "raw ${reference.role} metadata changed while it was independently decoded"
        }
        require(decodedFile.length() == reference.decodedSize &&
            digestFile(decodedFile, "MD5", cancelled).equals(reference.contentKey, true) &&
            sha256File(decodedFile, cancelled) == decodedSha
        ) { "decoded ${reference.role} metadata changed while it was independently verified" }
        val blte = inspectBlte(rawFile, cancelled)
        require(blte.flags in SUPPORTED_BLTE_FLAGS && blte.modes.isNotEmpty() &&
            blte.modes.size == blte.modes.toSet().size && blte.modes.all { it in SUPPORTED_BLTE_MODES }
        ) { "${reference.role} metadata uses an unsupported BLTE header or mode" }
        return MetadataEvidence(
            reference.role,
            rawSha,
            decodedSha,
            blte.flags,
            blte.modes,
            "metadata/${reference.role}-${reference.encodingKey}.blte",
        )
    }

    private data class BlteEvidence(val flags: Int, val modes: List<String>)

    private fun inspectBlte(file: File, cancelled: () -> Boolean): BlteEvidence = RandomAccessFile(file, "r").use { input ->
        checkCancelled(cancelled)
        require(input.length() >= 9L) { "metadata BLTE object is too short for a supported chunked header" }
        val magic = ByteArray(4)
        input.readFully(magic)
        require(magic.contentEquals(BLTE_MAGIC)) { "metadata BLTE object is not in the supported chunked BLTE format" }
        val headerSize = readU32(input)
        require(headerSize > 0L) { "metadata BLTE single-chunk format is not admitted for closure metadata" }
        require(headerSize in 12..MAX_BLTE_HEADER_BYTES && headerSize <= input.length()) { "metadata BLTE header is outside its bound" }
        val flags = input.readUnsignedByte()
        require(flags in SUPPORTED_BLTE_FLAGS) { "metadata BLTE header flags are unsupported" }
        val count = readU24(input)
        require(count in 1..MAX_BLTE_CHUNKS) { "metadata BLTE chunk count is outside its bound" }
        val descriptorSize = if (flags == 0x10) 40 else 24
        require(headerSize == 12L + count.toLong() * descriptorSize) { "metadata BLTE header length/count mismatch" }
        val encodedSizes = IntArray(count)
        repeat(count) { index ->
            checkCancelled(cancelled)
            val encoded = readU32(input)
            val decoded = readU32(input)
            require(encoded in 1..MAX_RAW_METADATA_BYTES && decoded in 0..MAX_DECODED_METADATA_BYTES) {
                "metadata BLTE chunk size exceeds its bound"
            }
            input.skipBytes(16 + if (flags == 0x10) 16 else 0)
            encodedSizes[index] = encoded.toInt()
        }
        require(input.filePointer == headerSize) { "metadata BLTE descriptor table did not consume the declared header" }
        val modes = java.util.TreeSet<String>()
        var encodedPayload = 0L
        repeat(count) { index ->
            checkCancelled(cancelled)
            val chunkBytes = encodedSizes[index]
            require(chunkBytes > 0 && input.filePointer <= input.length() - chunkBytes) { "metadata BLTE chunk exceeds its encoded object" }
            val mode = input.readUnsignedByte().toChar().toString()
            modes += mode
            input.seek(Math.addExact(input.filePointer, chunkBytes.toLong() - 1L))
            encodedPayload = Math.addExact(encodedPayload, chunkBytes.toLong())
        }
        require(input.filePointer == input.length() && encodedPayload == input.length() - headerSize) {
            "metadata BLTE chunks do not consume the exact encoded object"
        }
        BlteEvidence(flags, modes.toList())
    }

    private fun validateEKey(file: File, expectedEkey: String, cancelled: () -> Boolean) {
        require(validateEKeyDigest(file, expectedEkey, cancelled).equals(expectedEkey, true)) {
            "raw metadata EKey mismatch"
        }
    }

    private fun validateEKeyDigest(file: File, expectedEkey: String, cancelled: () -> Boolean): String {
        val size = file.length()
        require(size in 1..MAX_RAW_METADATA_BYTES) { "raw metadata size exceeds the EKey hashing bound" }
        val digest = MessageDigest.getInstance("MD5")
        RandomAccessFile(file, "r").use { input ->
            val prefix = ByteArray(8)
            val prefixLength = input.read(prefix)
            if (prefixLength >= 4 && prefix.copyOfRange(0, 4).contentEquals(BLTE_MAGIC)) {
                require(prefixLength == 8) { "truncated BLTE header before EKey verification" }
                val headerSize = u32be(prefix, 4)
                if (headerSize > 0L) {
                    require(headerSize in 12..MAX_BLTE_HEADER_BYTES && headerSize <= size) {
                        "BLTE EKey header exceeds its size bound"
                    }
                    input.seek(0)
                    digestRange(input, digest, headerSize, cancelled)
                } else {
                    input.seek(0)
                    digestRange(input, digest, size, cancelled)
                }
            } else {
                input.seek(0)
                digestRange(input, digest, size, cancelled)
            }
        }
        return digest.digest().hex()
    }

    private fun verifiedConfig(file: File, hash: String, label: String, cancelled: () -> Boolean): ConfigFile {
        validateRegularFile(file, label)
        val raw = readBounded(file, MAX_CONFIG_BYTES, cancelled)
        require(digestBytes("MD5", raw).equals(hash, true)) { "$label MD5 does not match the published config hash" }
        return ConfigFile(file, raw, digestBytes("SHA-256", raw))
    }

    private fun ensureConfigStable(config: ConfigFile, hash: String, label: String, cancelled: () -> Boolean) {
        validateRegularFile(config.file, label)
        require(config.file.length() == config.raw.size.toLong() &&
            digestFile(config.file, "MD5", cancelled).equals(hash, true) &&
            sha256File(config.file, cancelled) == config.sha256
        ) { "$label changed after its official MD5 and SHA-256 bindings were verified" }
    }

    private fun readBounded(file: File, limit: Long, cancelled: () -> Boolean): ByteArray {
        val before = file.length()
        require(before in 1..limit && before <= Int.MAX_VALUE) { "metadata file is empty or exceeds its read bound" }
        val result = ByteArrayOutputStream(before.toInt())
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(result.size().toLong() + count <= limit) { "metadata file grew beyond its read bound" }
                result.write(buffer, 0, count)
            }
        }
        require(result.size().toLong() == before && file.length() == before) { "metadata file changed while reading" }
        return result.toByteArray()
    }

    private fun gzipEvidence(input: File, destination: File, cancelled: () -> Boolean): GzipEvidence {
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "gzip staging file already exists" }
        var bytes = 0L
        input.inputStream().buffered(IO_BUFFER_BYTES).use { source ->
            FileChannel.open(
                destination.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val sink = java.nio.channels.Channels.newOutputStream(channel)
                val gzip = GZIPOutputStream(sink, IO_BUFFER_BYTES)
                try {
                    val buffer = ByteArray(IO_BUFFER_BYTES)
                    while (true) {
                        checkCancelled(cancelled)
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        gzip.write(buffer, 0, count)
                        bytes = Math.addExact(bytes, count.toLong())
                    }
                    gzip.finish()
                    gzip.flush()
                    channel.force(true)
                } finally {
                    gzip.close()
                }
            }
        }
        require(bytes == input.length()) { "catalog changed while generating its gzip fingerprint" }
        return GzipEvidence(destination.length(), sha256File(destination, cancelled))
    }

    private fun openWorkDatabase(file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            require(setPragma(db, "PRAGMA cache_size=-8192").toLongOrNull() == -8_192L) {
                "SQLite spool page cache could not be bounded to 8 MiB"
            }
            require(setPragma(db, "PRAGMA temp_store=FILE").toLongOrNull() == 1L) { "SQLite spool temporary storage is not file-backed" }
            require(setPragma(db, "PRAGMA journal_mode=OFF").equals("off", true)) { "SQLite spool journal mode is not disabled" }
            require(setPragma(db, "PRAGMA synchronous=OFF").toLongOrNull() == 0L) { "SQLite spool synchronous mode is not disabled" }
            require(setPragma(db, "PRAGMA foreign_keys=ON").toLongOrNull() == 1L) { "SQLite spool foreign-key checks could not be enabled" }
            require(setPragma(db, "PRAGMA max_page_count=${MAX_WORK_DATABASE_BYTES / SQLITE_PAGE_BYTES}").toLongOrNull()?.let {
                it in 1L..(MAX_WORK_DATABASE_BYTES / SQLITE_PAGE_BYTES)
            } == true) { "SQLite spool page-count bound could not be enforced" }
            db.execSQL("CREATE TABLE encoding_ckeys(ekey BLOB NOT NULL,ckey BLOB NOT NULL,decoded_size INTEGER NOT NULL,PRIMARY KEY(ekey,ckey)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE encoding_ekeys(ekey BLOB PRIMARY KEY,encoded_size INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE download_entries(ekey BLOB NOT NULL,encoded_size INTEGER NOT NULL,PRIMARY KEY(ekey,encoded_size)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE size_estimates(entry_index INTEGER PRIMARY KEY,ekey_prefix BLOB NOT NULL,estimated_size INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE install_records(path TEXT PRIMARY KEY,ckey BLOB NOT NULL,decoded_size INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE manifest_stats(name TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE candidates(role TEXT NOT NULL,owner TEXT NOT NULL,ekey BLOB NOT NULL,ckey BLOB NOT NULL,encoded_size INTEGER NOT NULL,decoded_size INTEGER NOT NULL,PRIMARY KEY(role,owner,ekey)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE loose_candidates(path TEXT NOT NULL,ekey BLOB NOT NULL,ckey BLOB NOT NULL,encoded_size INTEGER NOT NULL,decoded_size INTEGER NOT NULL,PRIMARY KEY(path,ekey)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE loose_chosen(path TEXT PRIMARY KEY,ekey BLOB NOT NULL,ckey BLOB NOT NULL,encoded_size INTEGER NOT NULL,decoded_size INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE objects_work(ekey BLOB PRIMARY KEY,ckey BLOB NOT NULL,encoded_size INTEGER NOT NULL,decoded_size INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE needed(ekey BLOB PRIMARY KEY,encoded_size INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE sources(ekey BLOB PRIMARY KEY,encoded_size INTEGER NOT NULL,source_kind TEXT NOT NULL,source_hash BLOB NOT NULL,source_offset INTEGER NOT NULL) WITHOUT ROWID")
            return db
        } catch (failure: Throwable) {
            db.close()
            throw failure
        }
    }

    private fun createWorkTables(db: SQLiteDatabase) {
        // Work tables are created when the database is opened; this guard catches accidental reuse.
        require(scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='encoding_ckeys'") == 1L) {
            "catalog spool schema is not initialized"
        }
    }

    private inner class SqlBatch<T>(
        private val db: SQLiteDatabase,
        private val sqlOverride: String? = null,
        private val bind: (SQLiteStatement, T) -> Unit,
    ) {
        private val rows = ArrayList<T>(SQL_BATCH_ROWS)
        var totalRows: Long = 0L
            private set

        fun add(row: T) {
            rows += row
            totalRows = Math.addExact(totalRows, 1L)
            if (rows.size == SQL_BATCH_ROWS) flush()
        }

        fun finish() {
            if (rows.isNotEmpty()) flush()
        }

        private fun flush() {
            val sql = sqlOverride ?: when (rows.firstOrNull()) {
                is ForeverTactMetadata.InstallRecord -> "INSERT INTO install_records(path,ckey,decoded_size) VALUES(?,?,?)"
                is ForeverTactMetadata.DownloadRecord -> "INSERT OR IGNORE INTO download_entries(ekey,encoded_size) VALUES(?,?)"
                is ForeverTactMetadata.SizeRecord -> "INSERT INTO size_estimates(entry_index,ekey_prefix,estimated_size) VALUES(?,?,?)"
                is ForeverTactMetadata.ContentMapping -> "INSERT INTO encoding_ckeys(ekey,ckey,decoded_size) VALUES(?,?,?)"
                is ForeverTactMetadata.EncodingRecord -> "INSERT INTO encoding_ekeys(ekey,encoded_size) VALUES(?,?)"
                is LooseChoice -> "INSERT INTO loose_chosen(path,ekey,ckey,encoded_size,decoded_size) VALUES(?,?,?,?,?)"
                is CatalogObject -> "INSERT INTO objects(ekey,ckey,encoded_size,decoded_size,source_kind,source_hash,source_offset,source_length) VALUES(?,?,?,?,?,?,?,?)"
                is Pair<*, *> -> "INSERT INTO metadata(key,value) VALUES(?,?)"
                else -> throw IllegalStateException("unsupported catalog SQL batch type")
            }
            db.beginTransaction()
            try {
                db.compileStatement(sql).use { statement -> rows.forEach { bind(statement, it) } }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                rows.clear()
            }
        }
    }

    private class EKeyBloom(count: Long) {
        private val bitCount = maxOf(64L, count * 20L)
        private val bits = ByteArray(((bitCount + 7L) / 8L).toInt())

        fun add(key: ByteArray) = positions(key).forEach { position ->
            val byte = position ushr 3
            bits[byte] = (bits[byte].toInt() or (1 shl (position and 7))).toByte()
        }

        fun mightContain(key: ByteArray): Boolean = positions(key).all { position ->
            (bits[position ushr 3].toInt() and (1 shl (position and 7))) != 0
        }

        private fun positions(key: ByteArray): IntArray {
            require(key.size == 16)
            var first = 0L
            var step = 0L
            repeat(8) { index ->
                first = (first shl 8) or (key[index].toLong() and 0xffL)
                step = (step shl 8) or (key[index + 8].toLong() and 0xffL)
            }
            step = step or 1L
            val firstMod = java.lang.Long.remainderUnsigned(first, bitCount)
            val stepMod = java.lang.Long.remainderUnsigned(step, bitCount)
            return IntArray(BLOOM_HASHES) { index -> ((firstMod + index.toLong() * stepMod) % bitCount).toInt() }
        }
    }

    private inner class ProductionSource(cacheRoot: File) : Source {
        private val transport = ForeverClientMetadataTransport(cacheRoot)
        override fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
            transport.config(hash, cancelled, progress)
        override fun decoded(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
            transport.decoded(reference, cancelled, progress)
        override fun raw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
            transport.objectRaw(reference, cancelled, progress)
        override fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
            transport.index(hash, cancelled, progress)
    }

    private fun childDirectory(parent: File, name: String): File {
        val path = File(parent, name).toPath()
        require(!Files.isSymbolicLink(path)) { "resolver-owned directory is a symlink: $path" }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "resolver-owned path is not a directory: $path" }
        return path.toFile()
    }

    private fun safeWorkRoot(): File {
        val path = workRoot.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "catalog resolver workRoot must be an existing, non-symlink app-private directory outside the client root"
        }
        rejectSymlinkPath(path)
        return path.toFile()
    }

    private fun validateDirectory(file: File, description: String) {
        val path = file.toPath().toAbsolutePath().normalize()
        rejectSymlinkPath(path)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "$description is not a regular directory" }
    }

    private fun validateRegularFile(file: File, description: String) {
        val path = file.toPath().toAbsolutePath().normalize()
        rejectSymlinkPath(path)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "$description is not a regular non-symlink file"
        }
    }

    private fun rejectSymlinkPath(path: java.nio.file.Path) {
        var component = path.root ?: throw IllegalArgumentException("resolver file path is not absolute")
        for (part in path) {
            component = component.resolve(part)
            if (Files.isSymbolicLink(component)) throw IllegalArgumentException("resolver path contains a symbolic link")
        }
    }

    private fun writeNewFile(file: File, bytes: ByteArray) {
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "resolver output already exists: ${file.name}" }
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val input = ByteBuffer.wrap(bytes)
            while (input.hasRemaining()) {
                val written = channel.write(input)
                if (written <= 0) throw IOException("could not persist catalog target receipt")
            }
            channel.force(true)
        }
    }

    private fun forceFile(path: java.nio.file.Path) {
        FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun forceDirectory(path: java.nio.file.Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (failure: Exception) {
            throw IllegalStateException("filesystem cannot durably publish resolver output", failure)
        }
    }

    private fun validateFingerprint(fingerprint: ForeverClientUpdateCheck.Fingerprint) {
        require(fingerprint.product == ForeverClientUpdateCheck.PRODUCT && fingerprint.version.matches(VERSION_RE) &&
            fingerprint.buildId != null && fingerprint.buildId > 0L &&
            fingerprint.version.substringAfterLast('.').toLongOrNull() == fingerprint.buildId &&
            HEX32.matches(fingerprint.buildConfigHash) && HEX32.matches(fingerprint.cdnConfigHash)
        ) { "published product fingerprint is incomplete or malformed" }
    }

    private fun cacheKey(fingerprint: ForeverClientUpdateCheck.Fingerprint): String =
        "${requireNotNull(fingerprint.buildId)}-${fingerprint.version}-" +
            "${fingerprint.buildConfigHash.lowercase(Locale.ROOT)}-${fingerprint.cdnConfigHash.lowercase(Locale.ROOT)}"

    private fun stat(db: SQLiteDatabase, name: String): Long = scalarLong(db, "SELECT value FROM manifest_stats WHERE name=${sqlQuote(name)}")

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { cursor ->
        require(cursor.moveToFirst()) { "catalog scalar query returned no row" }
        cursor.getLong(0)
    }

    private fun setPragma(db: SQLiteDatabase, sql: String): String {
        val name = sql.substringAfter("PRAGMA", missingDelimiterValue = "").substringBefore('=').trim()
        require(name.matches(Regex("[a-z_]+"))) { "invalid resolver SQLite pragma" }
        try {
            db.execSQL(sql)
        } catch (_: Exception) {
            // Some SQLiteDatabase versions reject a PRAGMA assignment through execSQL;
            // executing the same bounded assignment as a query is the portable fallback.
            db.rawQuery(sql, null).use { cursor -> while (cursor.moveToNext()) Unit }
        }
        return db.rawQuery("PRAGMA $name", null).use { cursor ->
        require(cursor.moveToFirst()) { "SQLite pragma returned no value: $sql" }
        cursor.getString(0)
        }
    }

    private fun jsonValue(value: Any): String = when (value) {
        is String -> JSONObject.quote(value)
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun sqlQuote(value: String): String = "'${value.replace("'", "''")}'"

    private fun digestRange(input: RandomAccessFile, digest: MessageDigest, length: Long, cancelled: () -> Boolean) {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            checkCancelled(cancelled)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("metadata file truncated while hashing")
            if (count == 0) continue
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }

    private fun digestFile(file: File, algorithm: String, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun sha256File(file: File, cancelled: () -> Boolean): String = digestFile(file, "SHA-256", cancelled)
    private fun digestBytes(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes).hex()
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client catalog resolution cancelled")
    }

    private fun SQLiteStatement.use(block: (SQLiteStatement) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun DataInputStream.skipFully(count: Long) {
        require(count >= 0)
        var remaining = count
        val buffer = ByteArray(IO_BUFFER_BYTES)
        while (remaining > 0L) {
            val skipped = skip(minOf(remaining, buffer.size.toLong()))
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) throw IOException("truncated TACT manifest while skipping records")
                if (read > 0) remaining -= read
            }
        }
    }

    private fun readCString(input: DataInputStream, limit: Int, context: String): String {
        val output = ByteArrayOutputStream(minOf(limit, 128))
        while (true) {
            val value = input.read()
            if (value < 0) throw IOException("truncated $context")
            if (value == 0) break
            require(output.size() < limit) { "$context exceeds its bounded length" }
            output.write(value)
        }
        val bytes = output.toByteArray()
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun readU32(input: DataInputStream): Long =
        ((input.readUnsignedByte().toLong() shl 24) or (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 8) or input.readUnsignedByte().toLong())

    private fun readU40(input: DataInputStream): Long {
        var result = 0L
        repeat(5) { result = (result shl 8) or input.readUnsignedByte().toLong() }
        return result
    }

    private fun readU24(input: RandomAccessFile): Int =
        (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()

    private fun readU32(input: RandomAccessFile): Long =
        (input.readUnsignedByte().toLong() shl 24) or (input.readUnsignedByte().toLong() shl 16) or
            (input.readUnsignedByte().toLong() shl 8) or input.readUnsignedByte().toLong()

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or (bytes[offset + 3].toLong() and 0xffL)

    private fun ByteArray.hex(): String = buildString(size * 2) {
        val alphabet = "0123456789abcdef"
        for (byte in this@hex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }

    private fun String.hexBytes(): ByteArray = run {
        require(length in 2..32 && length % 2 == 0 && matches(Regex("(?i)[0-9a-f]+"))) { "invalid TACT key or prefix" }
        ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private const val SQL_BATCH_ROWS = 2_048
        private const val BLOOM_HASHES = 14
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val SQLITE_PAGE_BYTES = 4_096L
        private const val MAX_WORK_DATABASE_BYTES = 768L * 1024 * 1024
        private const val MAX_CATALOG_BYTES = 256L * 1024 * 1024
        private const val MAX_CATALOG_OBJECTS = 2_000_000L
        private const val MAX_LOOSE_FILES = 4_096L
        private const val MAX_RUNTIME_ROLE_COUNT = 4_096
        private const val MAX_CDN_INDEX_COUNT = 8_192
        private const val MAX_CDN_INDEX_BYTES = 16L * 1024 * 1024
        private const val MAX_TOTAL_INDEX_BYTES = 512L * 1024 * 1024
        private const val MAX_RAW_METADATA_BYTES = 256L * 1024 * 1024
        private const val MAX_DECODED_METADATA_BYTES = 256L * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 4L * 1024 * 1024
        private const val MAX_MANIFEST_ENTRIES = 5_000_000L
        private const val MAX_TAGS = 4_096
        private const val MAX_TAG_NAME_BYTES = 4_096
        private const val MAX_BLTE_HEADER_BYTES = 16L * 1024 * 1024
        private const val MAX_BLTE_CHUNKS = 8_192
        private const val UINT32_RANGE_END = 1L shl 32
        private const val MAX_ENCODED_OBJECT_BYTES = 1_073_741_314L
        private const val MAX_DECODED_OBJECT_BYTES = 536_870_912L
        private const val DYNAMIC_LAUNCHER_VERSION_CODE = 1L
        private const val EXPECTED_ARM64_MACHINE = 43_620
        private const val CATALOG_FILE_NAME = "client-catalog.sqlite3"
        private const val TARGET_RECEIPT_NAME = "target.json"
        private const val RESOLVER_LOCK_FILE = ".catalog-resolver.lock"
        private const val PLATFORM = "Windows"
        private const val ARCHITECTURE = "arm64"
        private const val LOCALE = "enUS"
        private const val EXPECTED_EXECUTABLE = "WowB-ARM64.exe"
        private const val SELECTION_QUERY = "Windows,arm64,enUS"
        private val METADATA_ROLES = listOf("install", "download", "size", "encoding")
        private val REQUESTED_TAGS = setOf("Windows", "arm64", "enUS")
        private val SUPPORTED_BLTE_FLAGS = setOf(0x0f, 0x10)
        private val SUPPORTED_BLTE_MODES = setOf("N", "Z")
        private val HEX32 = Regex("(?i)[0-9a-f]{32}")
        private val HEX64 = Regex("(?i)[0-9a-f]{64}")
        private val VERSION_RE = Regex("[0-9]+(?:\\.[0-9]+){3}")
        private val DECIMAL = Regex("[0-9]{1,19}")
        private val BLTE_MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())
        private val JVM_ROOT_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}
