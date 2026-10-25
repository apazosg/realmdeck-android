package app.gamenative

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Disposable ordering hint, never object authority or download progress.
 * The caller owns the directory exclusively under its I/O lease and must reserve
 * MAX_BYTES before creating it/opening this index. Published caches are read-only
 * and rebound to the full catalog.
 */
internal class ForeverClientArchiveOrder private constructor(
    private val catalog: ForeverClientUpdateCatalog,
    private val database: SQLiteDatabase,
    val fileBytes: Long,
) : AutoCloseable {
    val objectCount: Long = catalog.summary().objectCount

    fun ordinalOf(ekey: String): Int {
        val key = ekey.lowercase(Locale.ROOT)
        require(KEY.matches(key)) { "invalid archive-order encoding key" }
        database.rawQuery("SELECT ordinal FROM schedule WHERE ekey=X'$key'", null).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("archive order has no admitted encoding key")
            val ordinal = ordinal(cursor, objectCount)
            check(!cursor.moveToNext()) { "archive order contains duplicate keys" }
            return ordinal
        }
    }

    fun <T> withObjects(cancelled: () -> Boolean, block: (Sequence<ForeverClientUpdateCatalog.ObjectRecord>) -> T): T {
        val started = System.nanoTime()
        var rows = 0L
        var blocks = 0L
        var scheduleNanos = 0L
        var lookupNanos = 0L
        var consumerNanos = 0L
        var consumerStarted = 0L
        var consumerActive = false
        var outcome = "early"
        try {
            active(cancelled)
            database.rawQuery("SELECT ordinal, ekey FROM schedule ORDER BY ordinal", null).use { cursor ->
                return block(sequence {
                    var expected = 0L
                    while (true) {
                        val keys = ArrayList<String>(ForeverClientUpdateCatalog.MAX_OBJECT_KEYS_PER_QUERY)
                        val scheduleStarted = System.nanoTime()
                        while (keys.size < ForeverClientUpdateCatalog.MAX_OBJECT_KEYS_PER_QUERY && cursor.moveToNext()) {
                            active(cancelled)
                            check(ordinal(cursor, objectCount).toLong() == expected++) { "archive order has an ordinal gap" }
                            keys += key(cursor, 1)
                        }
                        scheduleNanos += System.nanoTime() - scheduleStarted
                        if (keys.isEmpty()) break
                        rows += keys.size
                        blocks++
                        // The hint supplies only EKeys; all object fields come from the
                        // authenticated catalog, returned in this block's input order.
                        val lookupStarted = System.nanoTime()
                        val records = catalog.objectsByKeys(keys, cancelled)
                        lookupNanos += System.nanoTime() - lookupStarted
                        consumerStarted = System.nanoTime()
                        consumerActive = true
                        try {
                            yieldAll(records)
                        } finally {
                            consumerNanos += System.nanoTime() - consumerStarted
                            consumerActive = false
                        }
                    }
                    check(expected == objectCount) { "archive order object count changed" }
                    outcome = "complete"
                })
            }
        } catch (failure: CancellationException) {
            outcome = "cancelled"
            throw failure
        } catch (failure: Throwable) {
            outcome = "failure"
            throw failure
        } finally {
            if (consumerActive) consumerNanos += System.nanoTime() - consumerStarted
            runCatching {
                STREAM_LOG.info(
                    "archive order stream outcome=$outcome rows=$rows blocks=$blocks " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                        "scheduleMs=${scheduleNanos / 1_000_000} lookupMs=${lookupNanos / 1_000_000} " +
                        "consumerMs=${consumerNanos / 1_000_000}",
                )
            }
        }
    }

    override fun close() = database.close()

    companion object {
        const val MAX_BYTES = 256L shl 20
        const val CACHE_NAME = "order.sqlite3"
        private val KEY = Regex("[0-9a-f]{32}")
        private val STREAM_LOG = Logger.getLogger("ForeverClientUpdate")
        private const val SCHEDULE = "CREATE TABLE schedule(ordinal INTEGER PRIMARY KEY, ekey BLOB NOT NULL UNIQUE CHECK(length(ekey)=16))"
        private const val METADATA = "CREATE TABLE metadata(catalog_sha256 TEXT NOT NULL, objects INTEGER NOT NULL)"

        fun open(catalog: ForeverClientUpdateCatalog, directory: File, cancelled: () -> Boolean): ForeverClientArchiveOrder {
            active(cancelled)
            val parent = directory.toPath().toAbsolutePath().normalize()
            safe(parent)
            require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "archive-order directory is unavailable" }
            val count = catalog.summary().objectCount
            require(count in 1..Int.MAX_VALUE.toLong()) { "archive-order count is outside ordinal bounds" }
            val output = parent.resolve(CACHE_NAME)
            safe(output)
            var rebuild = false
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output)) {
                    "archive-order file is unsafe"
                }
                require(Files.size(output) in 1..MAX_BYTES) { "archive-order file exceeds its reserved bound" }
                val identity = SQLiteDatabase.openDatabase(
                    output.toString(),
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { db -> cacheIdentity(db) }
                if (identity.catalogSha256 != catalog.verifiedSha256) {
                    // This private ordering hint has no object authority. Remove
                    // its one old slot durably before making the replacement so
                    // old and new catalog indexes never coexist on disk.
                    Files.delete(output)
                    forceDirectory(parent)
                    rebuild = true
                } else {
                    check(identity.objectCount == count) { "archive order count differs from its catalog identity" }
                }
            }
            if (rebuild || !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                val temporary = Files.createTempFile(parent, ".archive-order-", ".sqlite3")
                try {
                    build(catalog, temporary, cancelled)
                    active(cancelled)
                    FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
                    ForeverClientUpdateCatalog.publishVerifiedPrivateFile(output, temporary)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            safe(output)
            require(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && Files.size(output) in 1..MAX_BYTES) {
                "archive-order file is unsafe or exceeds its reserved bound"
            }
            val db = SQLiteDatabase.openDatabase(output.toString(), null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                boundCache(db)
                validate(catalog, db, cancelled)
                return ForeverClientArchiveOrder(catalog, db, Files.size(output))
            } catch (failure: Throwable) {
                db.close()
                throw failure
            }
        }

        private fun build(catalog: ForeverClientUpdateCatalog, path: Path, cancelled: () -> Boolean) {
            SQLiteDatabase.openDatabase(path.toString(), null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.execSQL("PRAGMA page_size=4096")
                db.rawQuery("PRAGMA max_page_count=65536", null).use {
                    check(it.moveToFirst() && it.getLong(0) == 65536L) { "archive-order page bound unavailable" }
                }
                boundCache(db)
                // This is an unpublished, rebuildable hint, not a checkpoint.
                db.rawQuery("PRAGMA journal_mode=OFF", null).use {
                    check(it.moveToFirst() && it.getString(0).equals("off", true)) { "archive-order journal mode unavailable" }
                }
                db.rawQuery("PRAGMA page_size", null).use {
                    check(it.moveToFirst() && it.getLong(0) == 4096L) { "archive-order page size exceeds the reserved bound" }
                }
                db.execSQL("CREATE TABLE pending(ekey BLOB PRIMARY KEY, kind INTEGER NOT NULL, archive BLOB NOT NULL, offset INTEGER NOT NULL) WITHOUT ROWID")
                // Insert into an existing B-tree: no whole-catalog SQL sorter.
                db.execSQL("CREATE INDEX physical ON pending(kind,archive,offset,ekey)")
                db.execSQL(SCHEDULE)
                db.execSQL(METADATA)
                var inserted = 0L
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT INTO pending VALUES(?,?,?,?)").use { statement ->
                        catalog.withObjects(cancelled) { rows ->
                            rows.forEach { record ->
                                active(cancelled)
                                statement.bindBlob(1, bytes(record.ekey))
                                statement.bindLong(2, if (record.sourceKind == "range") 0 else 1)
                                statement.bindBlob(3, bytes(record.sourceHash))
                                statement.bindLong(4, record.sourceOffset)
                                statement.executeInsert()
                                inserted++
                            }
                        }
                    }
                    check(inserted == catalog.summary().objectCount) { "archive-order source count changed" }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                val query = "SELECT ekey FROM pending INDEXED BY physical ORDER BY kind,archive,offset,ekey"
                db.rawQuery("EXPLAIN QUERY PLAN $query", null).use { plan ->
                    while (plan.moveToNext()) check(!plan.getString(3).contains("TEMP B-TREE", true)) {
                        "archive ordering requires an unreserved sorter"
                    }
                }
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT INTO schedule VALUES(?,?)").use { statement ->
                        db.rawQuery(query, null).use { rows ->
                            var ordinal = 0L
                            while (rows.moveToNext()) {
                                active(cancelled)
                                statement.bindLong(1, ordinal++)
                                statement.bindBlob(2, rows.getBlob(0))
                                statement.executeInsert()
                            }
                            check(ordinal == inserted) { "archive-order schedule is incomplete" }
                        }
                    }
                    db.execSQL("DROP TABLE pending")
                    db.execSQL("INSERT INTO metadata VALUES(?,?)", arrayOf(catalog.verifiedSha256, inserted))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            check(Files.size(path) <= MAX_BYTES) { "archive-order file exceeded its reserved bound" }
        }

        private fun validate(catalog: ForeverClientUpdateCatalog, db: SQLiteDatabase, cancelled: () -> Boolean) {
            val identity = cacheIdentity(db)
            val count = catalog.summary().objectCount
            check(identity.catalogSha256 == catalog.verifiedSha256 && identity.objectCount == count) {
                "archive order belongs to another catalog or is incomplete"
            }
            // Sorted unique EKeys must equal the authoritative catalog, not merely
            // have the same count. Dense unique ordinals need only a bounded bitset.
            val seen = BitSet(count.toInt())
            db.rawQuery("SELECT ordinal,ekey FROM schedule INDEXED BY sqlite_autoindex_schedule_1 ORDER BY ekey", null).use { hint ->
                catalog.withObjects(cancelled) { records ->
                    var matched = 0L
                    records.forEach { record ->
                        active(cancelled)
                        check(hint.moveToNext() && key(hint, 1) == record.ekey) { "archive-order membership differs from the catalog" }
                        val ordinal = ordinal(hint, count)
                        check(!seen[ordinal]) { "archive order repeats an ordinal" }
                        seen.set(ordinal)
                        matched++
                    }
                    check(matched == count && !hint.moveToNext()) { "archive-order membership is incomplete" }
                }
            }
        }

        private data class CacheIdentity(val catalogSha256: String, val objectCount: Long)

        /** Validate the directory-independent identity before deciding a cache is stale. */
        private fun cacheIdentity(db: SQLiteDatabase): CacheIdentity {
            val expected = mapOf("schedule" to SCHEDULE, "metadata" to METADATA)
            var tables = 0
            db.rawQuery("SELECT name,type,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'", null).use { schema ->
                while (schema.moveToNext()) {
                    check(schema.getString(1) == "table" && expected[schema.getString(0)] == schema.getString(2)) {
                        "archive-order schema is unrecognized"
                    }
                    tables++
                }
            }
            check(tables == expected.size) { "archive-order schema is incomplete" }
            var catalogSha256: String? = null
            var objectCount = -1L
            db.rawQuery("SELECT catalog_sha256,objects FROM metadata", null).use { metadata ->
                check(metadata.moveToFirst()) { "archive-order metadata identity is missing" }
                check(metadata.getType(0) == Cursor.FIELD_TYPE_STRING &&
                    metadata.getType(1) == Cursor.FIELD_TYPE_INTEGER) {
                    "archive-order metadata identity has invalid field types"
                }
                catalogSha256 = metadata.getString(0)
                objectCount = metadata.getLong(1)
                check(!metadata.moveToNext()) { "archive-order metadata identity has multiple rows" }
            }
            val hash = requireNotNull(catalogSha256)
            check(hash.matches(Regex("[0-9a-f]{64}")) && objectCount in 1..Int.MAX_VALUE.toLong()) {
                "archive-order metadata identity is outside its bounds"
            }
            return CacheIdentity(hash, objectCount)
        }

        private fun forceDirectory(path: Path) {
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
            } catch (failure: Exception) {
                throw IllegalStateException("archive-order cache retirement could not be synchronized", failure)
            }
        }

        private fun ordinal(cursor: Cursor, count: Long): Int {
            check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) in 0 until count) {
                "archive-order ordinal is invalid"
            }
            return cursor.getInt(0)
        }

        private fun boundCache(db: SQLiteDatabase) {
            // Some Android read-only handles reject even connection-local tuning.
            // Accept their default only after proving it is no larger.
            runCatching { db.execSQL("PRAGMA cache_size=-2048") }
            val pages = db.rawQuery("PRAGMA cache_size", null).use {
                check(it.moveToFirst()) { "archive-order cache bound unavailable" }; it.getLong(0)
            }
            val size = if (pages < 0) Math.multiplyExact(Math.negateExact(pages), 1024L) else {
                val pageSize = db.rawQuery("PRAGMA page_size", null).use {
                    check(it.moveToFirst()) { "archive-order page size unavailable" }; it.getLong(0)
                }
                Math.multiplyExact(pages, pageSize)
            }
            check(size in 0..(2L shl 20)) { "archive-order page cache exceeds its bound" }
        }

        private fun key(cursor: Cursor, column: Int): String {
            check(cursor.getType(column) == Cursor.FIELD_TYPE_BLOB) { "archive-order key is not a blob" }
            val value = cursor.getBlob(column)
            check(value.size == 16) { "archive-order key has an invalid size" }
            val hex = "0123456789abcdef"
            return buildString(32) { value.forEach { append(hex[(it.toInt() ushr 4) and 15]); append(hex[it.toInt() and 15]) } }
        }

        private fun bytes(key: String): ByteArray = ByteArray(16) {
            ((key[it * 2].digitToInt(16) shl 4) or key[it * 2 + 1].digitToInt(16)).toByte()
        }

        private fun safe(path: Path) {
            var current = path.root ?: throw IllegalArgumentException("archive-order path is not absolute")
            for (part in path) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "archive-order path contains a symbolic link" }
            }
        }

        private fun active(cancelled: () -> Boolean) {
            if (cancelled()) throw CancellationException("archive ordering cancelled")
        }
    }
}
