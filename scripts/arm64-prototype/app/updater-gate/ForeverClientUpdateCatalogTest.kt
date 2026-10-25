package app.gamenative

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverClientUpdateCatalogTest {
    @Test
    fun archiveOrderStreamsPhysicalOrderAndReusesOnlyTheExactCatalogMembership() {
        Fixture().use { fixture ->
            val authorityHash = sha256(fixture.database)
            ForeverClientUpdateCatalog(fixture.database, authorityHash, fixture.database.length()).use { catalog ->
                val directory = File(fixture.root, "archive-order").also { check(it.mkdir()) }
                ForeverClientArchiveOrder.open(catalog, directory) { false }.use { order ->
                    assertEquals(2L, order.objectCount)
                    assertTrue(order.fileBytes in 1..ForeverClientArchiveOrder.MAX_BYTES)
                    assertEquals(listOf(Fixture.RANGE_EKEY, Fixture.DIRECT_EKEY),
                        order.withObjects({ false }) { it.map { record -> record.ekey }.toList() })
                    assertEquals(0, order.ordinalOf(Fixture.RANGE_EKEY))
                    assertEquals(1, order.ordinalOf(Fixture.DIRECT_EKEY))
                    assertThrows(NoSuchElementException::class.java) { order.ordinalOf("ff".repeat(16)) }
                    assertEquals(Fixture.RANGE_EKEY, order.withObjects({ false }) { it.first().ekey })
                }
                val published = directory.listFiles()!!.single { it.extension == "sqlite3" }
                val digest = sha256(published)
                ForeverClientArchiveOrder.open(catalog, directory) { false }.use { order ->
                    assertEquals(2, order.withObjects({ false }) { it.count() })
                }
                assertEquals(digest, sha256(published))
                assertEquals(authorityHash, sha256(fixture.database))
            }
        }
    }

    @Test
    fun archiveOrderBatchingPreservesAuthoritativeRecordsAndClosesOnEarlyReturn() {
        Fixture(extraRangeObjects = 257).use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                val directory = File(fixture.root, "archive-order").also { check(it.mkdir()) }
                val expected = catalog.withObjects({ false }) { it.toList() }.sortedWith(
                    compareBy<ForeverClientUpdateCatalog.ObjectRecord>(
                        { if (it.sourceKind == "range") 0 else 1 },
                        { it.sourceHash },
                        { it.sourceOffset },
                        { it.ekey },
                    ),
                )
                ForeverClientArchiveOrder.open(catalog, directory) { false }.use { order ->
                    assertEquals(259, expected.size)
                    assertEquals(expected, order.withObjects({ false }) { it.toList() })
                    assertEquals(expected.first(), order.withObjects({ false }) { it.first() })
                    assertEquals(expected.first().ekey, catalog.objectByKey(expected.first().ekey).ekey)
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        order.withObjects({ ++checks > 3 }) { it.toList() }
                    }
                    assertEquals(expected, order.withObjects({ false }) { it.toList() })
                }
                assertThrows(IllegalArgumentException::class.java) {
                    catalog.objectsByKeys(listOf(Fixture.RANGE_EKEY, Fixture.RANGE_EKEY)) { false }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    catalog.objectsByKeys(List(ForeverClientUpdateCatalog.MAX_OBJECT_KEYS_PER_QUERY + 1) { Fixture.RANGE_EKEY }) { false }
                }
                assertThrows(IllegalStateException::class.java) {
                    catalog.objectsByKeys(listOf("ff".repeat(16))) { false }
                }
                var checks = 0
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    catalog.objectsByKeys(listOf(Fixture.RANGE_EKEY, Fixture.DIRECT_EKEY)) { ++checks > 1 }
                }
                assertEquals(Fixture.DIRECT_EKEY, catalog.objectByKey(Fixture.DIRECT_EKEY).ekey)
            }
        }
    }

    @Test
    fun archiveOrderRejectsForeignKeysOrSchemaEvenWithMatchingCountAndFingerprint() {
        Fixture().use { fixture ->
            val authorityHash = sha256(fixture.database)
            ForeverClientUpdateCatalog(fixture.database, authorityHash, fixture.database.length()).use { catalog ->
                val directory = File(fixture.root, "archive-order").also { check(it.mkdir()) }
                ForeverClientArchiveOrder.open(catalog, directory) { false }.close()
                val published = directory.listFiles()!!.single { it.extension == "sqlite3" }
                SQLiteDatabase.openDatabase(published.absolutePath, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.execSQL("UPDATE schedule SET ekey=X'${"ff".repeat(16)}' WHERE ordinal=0")
                }
                val failure = assertThrows(IllegalStateException::class.java) {
                    ForeverClientArchiveOrder.open(catalog, directory) { false }.close()
                }
                assertTrue(failure.message.orEmpty().contains("membership"))
                SQLiteDatabase.openDatabase(published.absolutePath, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.execSQL("UPDATE schedule SET ekey=X'${Fixture.RANGE_EKEY}' WHERE ordinal=0")
                    db.execSQL("CREATE TABLE sqlitex_extra(value BLOB)")
                }
                val schemaFailure = assertThrows(IllegalStateException::class.java) {
                    ForeverClientArchiveOrder.open(catalog, directory) { false }.close()
                }
                assertTrue(schemaFailure.message.orEmpty().contains("schema"))
                assertEquals(authorityHash, sha256(fixture.database))
            }
        }
    }

    @Test
    fun archiveOrderCancellationNeverPublishesAnIncompleteHint() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                val directory = File(fixture.root, "archive-order").also { check(it.mkdir()) }
                var checks = 0
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    ForeverClientArchiveOrder.open(catalog, directory) { ++checks >= 3 }.close()
                }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                ForeverClientArchiveOrder.open(catalog, directory) { false }.use { order ->
                    assertEquals(2, order.withObjects({ false }) { it.count() })
                }
            }
        }
    }

    @Test
    fun archiveOrderRejectsLinkedDirectoriesWithoutWritingThroughThem() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                val outside = File(fixture.root, "outside").also { check(it.mkdir()) }
                val linked = File(fixture.root, "linked-order")
                Files.createSymbolicLink(linked.toPath(), outside.toPath())
                assertThrows(IllegalArgumentException::class.java) {
                    ForeverClientArchiveOrder.open(catalog, linked) { false }.close()
                }
                assertTrue(outside.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun readsAdmittedFingerprintAndStreamsBoundedObjectRows() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                assertEquals(sha256(fixture.database), catalog.verifiedSha256)
                val summary = catalog.summary()
                assertEquals("1.60.1.70425", summary.version)
                assertEquals(ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH, summary.buildConfigHash)
                assertEquals(ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH, summary.cdnConfigHash)
                assertEquals(2L, summary.objectCount)
                assertEquals(222L, summary.totalEncodedBytes)
                assertEquals(1, summary.looseCount)
                assertEquals("\"wowforever.arm64-client-catalog.sqlite\"", catalog.metadata("schema"))

                val byKey = catalog.objectByKey(Fixture.DIRECT_EKEY)
                assertEquals(Fixture.DIRECT_CKEY, byKey.contentKey)
                assertEquals("direct", byKey.sourceKind)
                assertEquals(Fixture.RANGE_EKEY, catalog.objectByKey(Fixture.RANGE_EKEY).ekey)
                assertThrows(NoSuchElementException::class.java) { catalog.objectByKey("ff".repeat(16)) }
                assertThrows(IllegalArgumentException::class.java) { catalog.objectByKey("x' OR 1=1 --") }

                val loose = catalog.looseFiles().single()
                assertEquals("Interface/patch.mpq", loose.path)
                assertEquals(Fixture.DIRECT_EKEY, loose.objectRecord.ekey)

                var streamed = 0
                val early = catalog.withObjects({ false }) { records -> records.first().also { streamed++ } }
                assertEquals(1, streamed)
                assertEquals(32, early.ekey.length)
                assertEquals(early.ekey, catalog.withObjects({ false }) { records ->
                    val first = records.first()
                    catalog.objectByKey(first.ekey).ekey
                })
                // The first callback returned with an unconsumed cursor. A second query
                // proves withObjects closed it in its finally block.
                assertEquals(2L, catalog.withObjects({ false }) { it.count().toLong() })
            }
        }
    }

    @Test
    fun rejectsWrongChecksumFingerprintAndUnsafeLoosePaths() {
        Fixture().use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, "0".repeat(64), fixture.database.length())
            }
        }
        Fixture(buildConfigHash = "0".repeat(32)).use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length())
            }
        }
        listOf("../outside.bin", "WTF/Config.wtf", "wtf/betasuspendedtest.wtf", "/absolute.bin", "C:/outside.bin", "bad\\path").forEach { path ->
            Fixture(loosePaths = listOf(path)).use { fixture ->
                assertThrows(IllegalArgumentException::class.java) {
                    ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length())
                }
            }
        }
        Fixture(loosePaths = listOf("Interface/A.dat", "interface/a.DAT")).use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length())
            }
        }
        Fixture(looseCkeyMismatch = true).use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length())
            }
        }
    }

    @Test
    fun externalTargetAdmissionIsDataDrivenAndBindsDatabaseIdentityAndDigest() {
        Fixture(
            buildConfigHash = "a1".repeat(16),
            cdnConfigHash = "b2".repeat(16),
            buildId = 70700,
            version = "1.60.1.70700",
        ).use { fixture ->
            val target = targetBuild(70700, "a1".repeat(16), "b2".repeat(16), fixture.database)
            ForeverClientUpdateCatalog(
                fixture.database,
                target.catalog.sha256,
                target.catalog.bytes,
                target,
            ).use { catalog ->
                assertEquals("1.60.1.70700", catalog.summary().version)
                assertEquals("a1".repeat(16), catalog.summary().buildConfigHash)
                assertEquals("b2".repeat(16), catalog.summary().cdnConfigHash)
            }

            val wrongConfigs = targetBuild(70700, "c3".repeat(16), "b2".repeat(16), fixture.database)
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, target.catalog.sha256, target.catalog.bytes, wrongConfigs)
            }
            val wrongVersion = targetBuild(70701, "a1".repeat(16), "b2".repeat(16), fixture.database)
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, target.catalog.sha256, target.catalog.bytes, wrongVersion)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(fixture.database, "0".repeat(64), target.catalog.bytes, target)
            }
        }
    }

    @Test
    fun rejectsSymbolicCatalogAndRefusesImportOverwriteOrBadDigest() {
        Fixture().use { fixture ->
            val outside = File(fixture.root, "catalog-outside.sqlite").also { fixture.database.copyTo(it) }
            val link = File(fixture.root, "linked.sqlite")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(link, sha256(outside), outside.length())
            }
        }

        val root = Files.createTempDirectory("client-catalog-import-test-").toFile()
        try {
            val bytes = "synthetic compressed catalog bytes".toByteArray()
            val compressed = gzip(bytes)
            val destination = File(root, "client-catalog.sqlite3")
            val imported = ForeverClientUpdateCatalog.importGzip(
                ByteArrayInputStream(compressed), destination, bytes.size.toLong(), sha256(bytes), { false },
                expectedCompressedSize = compressed.size.toLong(), compressedSha256 = sha256(compressed),
            )
            assertEquals(bytes.toList(), imported.readBytes().toList())
            assertThrows(IllegalStateException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed), destination, bytes.size.toLong(), sha256(bytes), { false },
                )
            }

            val badDestination = File(root, "bad.sqlite3")
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed), badDestination, bytes.size.toLong(), "0".repeat(64), { false },
                )
            }
            assertFalse(badDestination.exists())
            val badCompressedFingerprint = File(root, "bad-compressed.sqlite3")
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed),
                    badCompressedFingerprint,
                    bytes.size.toLong(),
                    sha256(bytes),
                    { false },
                    expectedCompressedSize = compressed.size.toLong(),
                    compressedSha256 = "0".repeat(64),
                )
            }
            assertFalse(badCompressedFingerprint.exists())
            val tooLarge = File(root, "too-large.sqlite3")
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed), tooLarge, bytes.size.toLong() - 1L, sha256(bytes), { false },
                )
            }
            assertFalse(tooLarge.exists())

            val sentinel = File(root, "sentinel.bin").also { it.writeText("not replaceable") }
            val linkedDestination = File(root, "linked.sqlite3")
            Files.createSymbolicLink(linkedDestination.toPath(), sentinel.toPath())
            assertThrows(IllegalStateException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed), linkedDestination, bytes.size.toLong(), sha256(bytes), { false },
                )
            }
            assertEquals("not replaceable", sentinel.readText())

            val cancelledDestination = File(root, "cancelled.sqlite3")
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverClientUpdateCatalog.importGzip(
                    ByteArrayInputStream(compressed), cancelledDestination, bytes.size.toLong(), sha256(bytes), { true },
                )
            }
            assertFalse(cancelledDestination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionalReal70425AdmissionCatalogPassesFullReadOnlyValidation() {
        val path = System.getProperty("wowforever.clientCatalog")
        assumeTrue("set wowforever.clientCatalog to the external audited SQLite artifact", path != null)
        val file = File(requireNotNull(path))
        ForeverClientUpdateCatalog(
            file,
            ForeverClientUpdateCatalog.PINNED_70425_CATALOG_SHA256,
            ForeverClientUpdateCatalog.PINNED_70425_CATALOG_SIZE,
        ).use { catalog ->
            assertEquals("1.60.1.70425", catalog.summary().version)
            assertEquals(1_144_978L, catalog.summary().objectCount)
            assertEquals(36, catalog.summary().looseCount)
        }
    }

    @Test
    fun directRowsEnforceEncodedAndDecodedTransferBoundsAtTheBoundary() {
        assertFixtureAdmitted(Fixture(directEncodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES))
        assertFixtureRejected(Fixture(directEncodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES + 1))
        assertFixtureAdmitted(Fixture(directDecodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES))
        assertFixtureRejected(Fixture(directDecodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES + 1))
    }

    @Test
    fun runtimeRoleCountsAndMetadataBlteModesAreProtocolDriven() {
        Fixture(vfsRoleIndexes = emptyList(), metadataBlteFlags = 16, metadataBlteModes = listOf("Z")).use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                val roles = JSONArray(catalog.metadata("required_runtime_roles"))
                assertEquals(6, roles.length())
                assertEquals(2L, catalog.summary().objectCount)
            }
        }
        Fixture(vfsRoleIndexes = listOf(1, 3)).use { fixture ->
            assertFixtureRejected(fixture)
        }
        Fixture(metadataBlteModes = listOf("N", "N")).use { fixture ->
            assertFixtureRejected(fixture)
        }
        Fixture(metadataBlteFlags = 17).use { fixture ->
            assertFixtureRejected(fixture)
        }
    }

    @Test
    fun rangeRowsEnforceEncodedAndDecodedTransferBoundsAtTheBoundary() {
        assertFixtureAdmitted(Fixture(rangeEncodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES))
        assertFixtureRejected(Fixture(rangeEncodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES + 1))
        assertFixtureAdmitted(Fixture(rangeDecodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES))
        assertFixtureRejected(Fixture(rangeDecodedSize = ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES + 1))
    }

    @Test
    fun sizeBoundsMatchStreamedSizesAndAreRequeriedOnEveryCall() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                var streamedCount = 0L
                var streamedMinEncoded = Long.MAX_VALUE
                var streamedMaxEncoded = 0L
                var streamedMinDecoded = Long.MAX_VALUE
                var streamedMaxDecoded = 0L
                catalog.withObjects({ false }) { records ->
                    records.forEach { record ->
                        streamedCount++
                        streamedMinEncoded = minOf(streamedMinEncoded, record.encodedSize)
                        streamedMaxEncoded = maxOf(streamedMaxEncoded, record.encodedSize)
                        streamedMinDecoded = minOf(streamedMinDecoded, record.decodedSize)
                        streamedMaxDecoded = maxOf(streamedMaxDecoded, record.decodedSize)
                    }
                }
                val first = catalog.objectSizeBounds { false }
                assertEquals(streamedCount, first.objectCount)
                assertEquals(streamedMinEncoded, first.minEncodedSize)
                assertEquals(streamedMaxEncoded, first.maxEncodedSize)
                assertEquals(streamedMinDecoded, first.minDecodedSize)
                assertEquals(streamedMaxDecoded, first.maxDecodedSize)
                assertEquals(2L, first.objectCount)
                // A second call re-queries instead of returning a cached maximum.
                val second = catalog.objectSizeBounds { false }
                assertEquals(first, second)
            }
        }
    }

    @Test
    fun sizeBoundsRejectCountChangeAfterAdmission() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                assertEquals(2L, catalog.objectSizeBounds { false }.objectCount)
                // A valid row inserted after admission changes the count: the
                // next aggregate must observe it and fail closed, never reuse
                // the admitted summary.
                mutateObjects(
                    fixture.database,
                    "INSERT INTO objects VALUES (X'${"33".repeat(16)}', X'${"dd".repeat(16)}', " +
                        "50, 7, 'range', X'${"44".repeat(16)}', 9, 50)",
                )
                assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
            }
        }
    }

    @Test
    fun sizeBoundsRejectOutOfBoundsMaximaChangedAfterAdmission() {
        Fixture(rangeEncodedSize = 99, rangeDecodedSize = 8).use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                assertEquals(123L, catalog.objectSizeBounds { false }.maxEncodedSize)
                mutateObjects(
                    fixture.database,
                    "UPDATE objects SET encoded_size=${ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES + 1}, " +
                        "source_length=${ForeverClientUpdateCatalog.MAX_SUPPORTED_ENCODED_OBJECT_BYTES + 1} " +
                        "WHERE ekey=X'${Fixture.RANGE_EKEY}'",
                )
                assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
            }
        }
        Fixture(rangeEncodedSize = 99, rangeDecodedSize = 8).use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                assertEquals(12L, catalog.objectSizeBounds { false }.maxDecodedSize)
                mutateObjects(
                    fixture.database,
                    "UPDATE objects SET decoded_size=${ForeverClientUpdateCatalog.MAX_SUPPORTED_DECODED_OBJECT_BYTES + 1} " +
                        "WHERE ekey=X'${Fixture.RANGE_EKEY}'",
                )
                assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
            }
        }
    }

    @Test
    fun sizeBoundsRejectNonIntegerAggregateResultChangedAfterAdmission() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                catalog.objectSizeBounds { false }
                // TEXT sorts above every INTEGER, so it surfaces as the MAX()
                // result and the declared-type check rejects it. This pins the
                // aggregate-result boundary only: a non-extremal interior
                // non-integer stays covered by the row validators.
                mutateObjects(
                    fixture.database,
                    "UPDATE objects SET encoded_size='oversized', source_length='oversized' " +
                        "WHERE ekey=X'${Fixture.RANGE_EKEY}'",
                )
                assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
            }
        }
    }

    @Test
    fun sizeBoundsRejectNullAggregateWhenObjectsVanishAfterAdmission() {
        Fixture().use { fixture ->
            ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length()).use { catalog ->
                catalog.objectSizeBounds { false }
                mutateObjects(fixture.database, "DELETE FROM loose")
                mutateObjects(fixture.database, "DELETE FROM objects")
                assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
            }
        }
    }

    @Test
    fun sizeBoundsHonorCancelBeforeAndAfterTheQueryAndClosedReader() {
        Fixture().use { fixture ->
            val catalog = ForeverClientUpdateCatalog(fixture.database, sha256(fixture.database), fixture.database.length())
            try {
                assertThrows(java.util.concurrent.CancellationException::class.java) { catalog.objectSizeBounds { true } }
                var calls = 0
                assertThrows(java.util.concurrent.CancellationException::class.java) { catalog.objectSizeBounds { ++calls > 1 } }
            } finally {
                catalog.close()
            }
            assertThrows(IllegalStateException::class.java) { catalog.objectSizeBounds { false } }
        }
    }

    private fun mutateObjects(database: File, sql: String) {
        val writable = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            writable.execSQL(sql)
        } finally {
            writable.close()
        }
    }

    private fun assertFixtureAdmitted(fixture: Fixture) {
        fixture.use {
            ForeverClientUpdateCatalog(it.database, sha256(it.database), it.database.length()).use { catalog ->
                assertEquals(2L, catalog.summary().objectCount)
            }
        }
    }

    private fun assertFixtureRejected(fixture: Fixture) {
        fixture.use {
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateCatalog(it.database, sha256(it.database), it.database.length())
            }
        }
    }

    private class Fixture(
        extraRangeObjects: Int = 0,
        loosePaths: List<String> = listOf("Interface/patch.mpq"),
        looseCkeyMismatch: Boolean = false,
        buildConfigHash: String = ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH,
        cdnConfigHash: String = ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH,
        buildId: Int = 70425,
        version: String = "1.60.1.70425",
        directEncodedSize: Long = 123,
        directDecodedSize: Long = 12,
        rangeEncodedSize: Long = 99,
        rangeDecodedSize: Long = 8,
        vfsRoleIndexes: List<Int> = (1..582).toList(),
        metadataBlteFlags: Int = 15,
        metadataBlteModes: List<String> = listOf("N", "Z"),
    ) : AutoCloseable {
        val root = Files.createTempDirectory("update-catalog-fixture-").toFile()
        val database = File(root, "catalog.sqlite3")

        init {
            val db = SQLiteDatabase.openOrCreateDatabase(database, null)
            try {
                db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE objects (ekey BLOB PRIMARY KEY CHECK(length(ekey)=16), ckey BLOB NOT NULL CHECK(length(ckey)=16), " +
                        "encoded_size INTEGER NOT NULL CHECK(encoded_size>0), decoded_size INTEGER NOT NULL CHECK(decoded_size>=0), " +
                        "source_kind TEXT NOT NULL CHECK(source_kind IN ('direct','range')), source_hash BLOB NOT NULL CHECK(length(source_hash)=16), " +
                        "source_offset INTEGER NOT NULL CHECK(source_offset>=0), source_length INTEGER NOT NULL CHECK(source_length=encoded_size), " +
                        "CHECK((source_kind='direct' AND source_hash=ekey AND source_offset=0) OR source_kind='range')) WITHOUT ROWID",
                )
                db.execSQL(
                    "CREATE TABLE loose (path TEXT PRIMARY KEY, ekey BLOB NOT NULL CHECK(length(ekey)=16), " +
                        "ckey BLOB NOT NULL CHECK(length(ckey)=16), encoded_size INTEGER NOT NULL CHECK(encoded_size>0), " +
                        "decoded_size INTEGER NOT NULL CHECK(decoded_size>=0), FOREIGN KEY(ekey) REFERENCES objects(ekey)) WITHOUT ROWID",
                )
                insertObject(db, DIRECT_EKEY, DIRECT_CKEY, directEncodedSize, directDecodedSize, "direct", DIRECT_EKEY, 0)
                insertObject(db, RANGE_EKEY, RANGE_CKEY, rangeEncodedSize, rangeDecodedSize, "range", "33".repeat(16), 50)
                repeat(extraRangeObjects) { index ->
                    val ekey = (index + 0x100).toString(16).padStart(32, '0')
                    insertObject(db, ekey, "ee".repeat(16), 1, 1, "range", "44".repeat(16), index.toLong() + 100)
                }
                loosePaths.forEachIndexed { index, path ->
                    db.insertOrThrow("loose", null, ContentValues().apply {
                        put("path", path)
                        put("ekey", if (index == 0) DIRECT_EKEY.decodeHex() else RANGE_EKEY.decodeHex())
                        put("ckey", if (index == 0) (if (looseCkeyMismatch) "cc".repeat(16) else DIRECT_CKEY).decodeHex() else RANGE_CKEY.decodeHex())
                        put("encoded_size", if (index == 0) directEncodedSize else rangeEncodedSize)
                        put("decoded_size", if (index == 0) directDecodedSize else rangeDecodedSize)
                    })
                }
                metadataValues(
                    loosePaths.size, buildConfigHash,
                    cdnConfigHash, buildId, version,
                    directEncodedSize, directDecodedSize, rangeEncodedSize, rangeDecodedSize,
                    vfsRoleIndexes, metadataBlteFlags, metadataBlteModes,
                ).forEach { (key, value) ->
                    db.insertOrThrow("metadata", null, ContentValues().apply { put("key", key); put("value", value) })
                }
                // Android's writable SQLite helper creates a locale table; the
                // reviewed producer schema is the three-table Python SQLite form.
                db.execSQL("DROP TABLE IF EXISTS android_metadata")
            } finally {
                db.close()
            }
        }

        override fun close() {
            root.deleteRecursively()
        }

        companion object {
            val DIRECT_EKEY = "11".repeat(16)
            val DIRECT_CKEY = "aa".repeat(16)
            val RANGE_EKEY = "22".repeat(16)
            val RANGE_CKEY = "bb".repeat(16)

            private fun insertObject(
                db: SQLiteDatabase,
                ekey: String,
                ckey: String,
                encoded: Long,
                decoded: Long,
                source: String,
                sourceHash: String,
                offset: Long,
            ) {
                db.insertOrThrow("objects", null, ContentValues().apply {
                    put("ekey", ekey.decodeHex())
                    put("ckey", ckey.decodeHex())
                    put("encoded_size", encoded)
                    put("decoded_size", decoded)
                    put("source_kind", source)
                    put("source_hash", sourceHash.decodeHex())
                    put("source_offset", offset)
                    put("source_length", encoded)
                })
            }

            private fun metadataValues(
                looseCount: Int,
                buildConfigHash: String,
                cdnConfigHash: String,
                buildId: Int,
                version: String,
                directEncodedSize: Long,
                directDecodedSize: Long,
                rangeEncodedSize: Long,
                rangeDecodedSize: Long,
                vfsRoleIndexes: List<Int>,
                metadataBlteFlags: Int,
                metadataBlteModes: List<String>,
            ): Map<String, String> {
                fun json(value: Any): String = when (value) {
                    is String -> JSONObject.quote(value)
                    is JSONArray, is JSONObject -> value.toString()
                    else -> value.toString()
                }
                val roles = (listOf("download", "encoding", "install", "root", "size") +
                    vfsRoleIndexes.map { "vfs-$it" } + "vfs-root")
                val runtimeFingerprints = JSONObject()
                roles.forEachIndexed { index, role ->
                    val direct = index % 2 == 0
                    runtimeFingerprints.put(role, JSONObject()
                        .put("ekey", if (direct) DIRECT_EKEY else RANGE_EKEY)
                        .put("ckey", if (direct) DIRECT_CKEY else RANGE_CKEY)
                        .put("encoded_size", if (direct) directEncodedSize else rangeEncodedSize)
                        .put("decoded_size", if (direct) directDecodedSize else rangeDecodedSize)
                        .put("source_kind", if (direct) "direct" else "range"))
                }
                val metadataObjects = JSONObject()
                listOf("download", "encoding", "install", "size").forEach { role ->
                    metadataObjects.put(role, JSONObject()
                        .put("role", role)
                        .put("blte_flags", metadataBlteFlags)
                        .put("blte_modes", JSONArray(metadataBlteModes))
                        .put("encoding_key", DIRECT_EKEY)
                        .put("content_key", DIRECT_CKEY)
                        .put("decoded_md5", DIRECT_CKEY)
                        .put("declared_encoded_size", directEncodedSize)
                        .put("decoded_size", directDecodedSize)
                        .put("decoded_sha256", "d".repeat(64))
                        .put("raw_sha256", "e".repeat(64)))
                }
                return mapOf(
                    "schema" to json("wowforever.arm64-client-catalog.sqlite"),
                    "schema_version" to json(1),
                    "product" to json("wow_classic_beta"),
                    "region" to json("us"),
                    "build_id" to json(buildId),
                    "version" to json(version),
                    "platform" to json("Windows"),
                    "architecture" to json("arm64"),
                    "locale" to json("enUS"),
                    "build_config_hash" to json(buildConfigHash),
                    "cdn_config_hash" to json(cdnConfigHash),
                    "official_versions_sha256" to json("f".repeat(64)),
                    "selection_query" to json("Windows,arm64,enUS"),
                    "install_manifest_sha256" to json("a".repeat(64)),
                    "download_manifest_sha256" to json("b".repeat(64)),
                    "size_manifest_sha256" to json("c".repeat(64)),
                    "encoding_manifest_sha256" to json("d".repeat(64)),
                    "metadata_objects" to json(metadataObjects),
                    "required_runtime_roles" to json(JSONArray(roles)),
                    "runtime_role_fingerprints" to json(runtimeFingerprints),
                    "cdn_index_count" to json(1),
                    "cdn_index_sha256_manifest" to json("1".repeat(64)),
                    "cdn_file_index_hash" to json("2".repeat(32)),
                    "download_selected_entry_count" to json(1),
                    "download_selected_unique_ekey_count" to json(1),
                    "download_selected_unique_encoded_bytes" to json(directEncodedSize),
                    "size_selected_entry_count" to json(1),
                    "size_selected_estimated_bytes" to json(directEncodedSize),
                    "selected_loose_file_count" to json(looseCount),
                    "source_unresolved_count" to json(0),
                    "payloads_acquired_or_verified" to json(false),
                    "source_boundary" to json("source bytes are not claimed acquired"),
                    "server_compatibility" to json(JSONObject().put("build_id", buildId).put("login_or_gameplay_verified", false)),
                )
            }
        }
    }

    companion object {
        private fun targetBuild(buildId: Int, buildHash: String, cdnHash: String, database: File): ForeverClientTargetBuild {
            val bytes = database.readBytes()
            return ForeverClientTargetBuild.parse(JSONObject()
                .put("schema", "wowforever.client-target")
                .put("schema_version", 1)
                .put("product", "wow_classic_beta")
                .put("region", "us")
                .put("platform", "Windows")
                .put("architecture", "arm64")
                .put("locale", "enUS")
                .put("version", "1.60.1.$buildId")
                .put("build_id", buildId)
                .put("launcher_version_code", 80)
                .put("launcher_version_name", "catalog-test")
                .put("artifact_subdir", "client-updater-packaged-catalog/$buildId")
                .put("build_config", JSONObject().put("hash", buildHash).put("bytes", 1).put("sha256", "11".repeat(32)))
                .put("cdn_config", JSONObject().put("hash", cdnHash).put("bytes", 1).put("sha256", "22".repeat(32)))
                .put("catalog", JSONObject()
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes))
                    .put("gzip_bytes", 1)
                    .put("gzip_sha256", "33".repeat(32)))
                .put("executable", JSONObject()
                    .put("path", "WowB-ARM64.exe")
                    .put("decoded_bytes", 1)
                    .put("content_key", "44".repeat(16))
                    .put("expected_machine", 43_620))
                .put("server_version_source", "user-confirmed")
                .put("native_validation", JSONObject().put("status", "pending").put("evidence_sha256", JSONObject.NULL))
                .toString().toByteArray(Charsets.UTF_8))
        }

        private fun String.decodeHex(): ByteArray = ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
        }.toByteArray()
    }
}
