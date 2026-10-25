package app.gamenative

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverClientCatalogResolverTest {
    @Test
    fun oneResolverBuildsDifferentFingerprintsAndReusesOnlyVerifiedCompletedOutput() {
        val root = Files.createTempDirectory("catalog-resolver-two-builds-").toFile()
        try {
            val first = SyntheticGraph(File(root, "fixture-a"), 70_425, 11)
            val second = SyntheticGraph(File(root, "fixture-b"), 70_700, 29)
            val graphs = mapOf(70_425L to first, 70_700L to second)
            val resolver = ForeverClientCatalogResolver(File(root, "private-work").apply { mkdirs() }) { fingerprint, _ ->
                GraphSource(graphs.getValue(requireNotNull(fingerprint.buildId)))
            }

            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(first.fingerprint, false, { false }) { _, _, _ -> }
            }
            assertEquals("unconfirmed target must not acquire configs", 0, first.sourceCalls.config)

            val firstResult = resolver.resolve(first.fingerprint, true, { false }) { _, _, _ -> }
            val secondResult = resolver.resolve(second.fingerprint, true, { false }) { _, _, _ -> }
            assertNotEquals(firstResult.catalogSha256, secondResult.catalogSha256)
            assertNotEquals(firstResult.catalogFile.canonicalPath, secondResult.catalogFile.canonicalPath)
            assertEquals("1.60.1.70425", firstResult.target.version)
            assertEquals("1.60.1.70700", secondResult.target.version)
            assertEquals(ForeverClientTargetBuild.NativeValidation.Status.PENDING, firstResult.target.nativeValidation.status)
            assertFalse(firstResult.target.nativeActivationValidated)

            ForeverClientUpdateCatalog(
                firstResult.catalogFile,
                firstResult.catalogSha256,
                firstResult.catalogBytes,
                firstResult.target,
            ).use { catalog ->
                assertEquals(5L, catalog.summary().objectCount)
                assertEquals(1, catalog.summary().looseCount)
                assertEquals(firstResult.catalogSha256, catalog.verifiedSha256)
                assertEquals("2", catalog.metadata("download_selected_entry_count"))
                assertEquals("1", catalog.metadata("download_selected_unique_ekey_count"))
                assertEquals("1", catalog.metadata("size_selected_entry_count"))
                assertEquals("null", catalog.metadata("official_versions_sha256"))
                val roles = JSONArray(catalog.metadata("required_runtime_roles"))
                assertEquals(listOf("download", "encoding", "install", "root", "size", "vfs-1", "vfs-root"),
                    (0 until roles.length()).map { roles.getString(it) })
                assertEquals("direct", catalog.objectByKey(first.payloadEkey).sourceKind)
            }

            val rawCallsBeforeCacheHit = first.sourceCalls.raw + first.sourceCalls.decoded + first.sourceCalls.index
            val repeated = resolver.resolve(first.fingerprint, true, { false }) { _, _, _ -> }
            assertEquals(firstResult.catalogSha256, repeated.catalogSha256)
            assertEquals(rawCallsBeforeCacheHit, first.sourceCalls.raw + first.sourceCalls.decoded + first.sourceCalls.index)
            assertEquals(4, first.sourceCalls.config)
            assertTrue("no payload bytes are requested through the metadata Source", first.sourceCalls.raw == 4)
            assertTrue("completed catalog and receipt are published together", File(repeated.catalogFile.parentFile, "target.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparationReportsManifestAndIndexPhases() {
        val root = Files.createTempDirectory("catalog-resolver-progress-").toFile()
        try {
            val graph = SyntheticGraph(File(root, "fixture"), 70_425, 11)
            val resolver = ForeverClientCatalogResolver(File(root, "work").apply { mkdirs() }) { _, _ -> GraphSource(graph) }
            val phases = HashSet<String>()
            resolver.resolve(graph.fingerprint, true, { false }) { stage, _, _ -> phases += stage }
            assertTrue(phases.containsAll(listOf("parse-download", "parse-size", "parse-encoding-content", "parse-encoding", "cdn-indexes")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongConfigRawMetadataAndTruncatedIndexWithoutPublishingCatalog() {
        val root = Files.createTempDirectory("catalog-resolver-invalid-").toFile()
        try {
            val graph = SyntheticGraph(File(root, "fixture"), 70_425, 3)
            val wrongConfigWork = File(root, "wrong-config-work").apply { mkdirs() }
            val wrongFingerprint = graph.fingerprint.copy(buildConfigHash = "0".repeat(32))
            val wrongConfigResolver = ForeverClientCatalogResolver(wrongConfigWork) { _, _ ->
                object : GraphSource(graph) {
                    override fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
                        calls.config++
                        return graph.configs.getValue(graph.fingerprint.buildConfigHash).also { progress(it.length()) }
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                wrongConfigResolver.resolve(wrongFingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(wrongConfigWork, "catalogs").list()?.isEmpty() ?: true)

            val badRawWork = File(root, "bad-raw-work").apply { mkdirs() }
            val badRawResolver = ForeverClientCatalogResolver(badRawWork) { _, _ ->
                object : GraphSource(graph) {
                    override fun raw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
                        val original = super.raw(reference, cancelled, progress)
                        if (reference.role != "download") return original
                        return File(root, "truncated-download.raw").also { it.writeBytes(original.readBytes().copyOf(original.length().toInt() - 1)) }
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                badRawResolver.resolve(graph.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(badRawWork, "catalogs").list()?.isEmpty() ?: true)

            val truncatedIndexWork = File(root, "truncated-index-work").apply { mkdirs() }
            val truncatedIndexResolver = ForeverClientCatalogResolver(truncatedIndexWork) { _, _ ->
                object : GraphSource(graph) {
                    override fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
                        File(root, "truncated.index").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                truncatedIndexResolver.resolve(graph.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(truncatedIndexWork, "catalogs").list()?.isEmpty() ?: true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongMetadataReferenceAndMissingArm64TagWhileKeepingVerifiedRawInputs() {
        val root = Files.createTempDirectory("catalog-resolver-selection-errors-").toFile()
        try {
            val wrongCkey = SyntheticGraph(
                File(root, "wrong-reference"), 70_425, 53, overrideInstallMetadataCkey = "0".repeat(32),
            )
            val wrongWork = File(root, "wrong-work").apply { mkdirs() }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientCatalogResolver(wrongWork) { _, _ -> GraphSource(wrongCkey) }
                    .resolve(wrongCkey.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(wrongWork, "catalogs").list()?.isEmpty() ?: true)
            wrongCkey.raw.values.forEach { assertTrue("verified fake-source raw metadata is never deleted", it.isFile) }

            val noArm64 = SyntheticGraph(File(root, "no-arm64"), 70_700, 61, includeArm64InstallTag = false)
            val noArm64Work = File(root, "no-arm64-work").apply { mkdirs() }
            val before = noArm64.raw.mapValues { sha256File(it.value) }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientCatalogResolver(noArm64Work) { _, _ -> GraphSource(noArm64) }
                    .resolve(noArm64.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(noArm64Work, "catalogs").list()?.isEmpty() ?: true)
            noArm64.raw.forEach { (role, file) ->
                assertEquals("parser failure preserves verified raw metadata for $role", before.getValue(role), sha256File(file))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsIndexCountAndByteBudgetBeforePublishingOrFollowingPayloadReferences() {
        val root = Files.createTempDirectory("catalog-resolver-bounds-").toFile()
        try {
            val manyIndexes = SyntheticGraph(File(root, "many-indexes"), 70_425, 7, archiveCount = 8_192)
            val countWork = File(root, "count-work").apply { mkdirs() }
            val countSource = GraphSource(manyIndexes)
            val countResolver = ForeverClientCatalogResolver(countWork) { _, _ -> countSource }
            assertThrows(IllegalArgumentException::class.java) {
                countResolver.resolve(manyIndexes.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertEquals("the 8,192-archive plus file-index graph is rejected before any index request", 0, countSource.calls.index)
            assertTrue(File(countWork, "catalogs").list()?.isEmpty() ?: true)

            val oversized = SyntheticGraph(File(root, "oversized-index"), 70_700, 17)
            val oversizedWork = File(root, "oversized-work").apply { mkdirs() }
            val oversizedResolver = ForeverClientCatalogResolver(oversizedWork) { _, _ ->
                object : GraphSource(oversized) {
                    override fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
                        val path = File(root, "oversized.index")
                        RandomAccessFile(path, "rw").use { it.setLength(16L * 1024 * 1024 + 1L) }
                        return path
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                oversizedResolver.resolve(oversized.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertTrue(File(oversizedWork, "catalogs").list()?.isEmpty() ?: true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsBeforeAnySourceInvocation() {
        val root = Files.createTempDirectory("catalog-resolver-cancelled-").toFile()
        try {
            val graph = SyntheticGraph(File(root, "fixture"), 70_425, 21)
            val source = GraphSource(graph)
            val resolver = ForeverClientCatalogResolver(File(root, "work").apply { mkdirs() }) { _, _ -> source }
            assertThrows(CancellationException::class.java) {
                resolver.resolve(graph.fingerprint, true, { true }) { _, _, _ -> }
            }
            assertEquals(0, source.calls.config + source.calls.raw + source.calls.decoded + source.calls.index)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun suppliedVersionsDigestIsStoredAndNewRegionalRowsDoNotInvalidateSameFingerprintCache() {
        val root = Files.createTempDirectory("catalog-resolver-feed-provenance-").toFile()
        try {
            val graph = SyntheticGraph(File(root, "fixture"), 70_425, 31)
            val firstFeed = versionsResponse(graph.fingerprint, includeEu = false)
            assertEquals(graph.fingerprint, ForeverClientUpdateCheck.parseVersions(firstFeed))
            val firstDigest = sha256(firstFeed)
            val changedFeed = versionsResponse(graph.fingerprint, includeEu = true)
            assertEquals(graph.fingerprint, ForeverClientUpdateCheck.parseVersions(changedFeed))
            val changedDigest = sha256(changedFeed)
            assertNotEquals(firstDigest, changedDigest)

            val source = GraphSource(graph)
            val work = File(root, "work").apply { mkdirs() }
            val resolver = ForeverClientCatalogResolver(work) { _, _ -> source }
            val first = resolver.resolve(graph.fingerprint, true, { false }, firstDigest) { _, _, _ -> }
            ForeverClientUpdateCatalog(first.catalogFile, first.catalogSha256, first.catalogBytes, first.target).use { catalog ->
                assertEquals(firstDigest, catalog.metadata("official_versions_sha256").trim('"'))
            }
            val metadataCallsBeforeReuse = source.calls.raw + source.calls.decoded + source.calls.index
            val reused = resolver.resolve(graph.fingerprint, true, { false }, changedDigest) { _, _, _ -> }
            assertEquals(first.catalogSha256, reused.catalogSha256)
            assertEquals(metadataCallsBeforeReuse, source.calls.raw + source.calls.decoded + source.calls.index)
            ForeverClientUpdateCatalog(reused.catalogFile, reused.catalogSha256, reused.catalogBytes, reused.target).use { catalog ->
                assertEquals("a completed catalog retains its original source proof", firstDigest, catalog.metadata("official_versions_sha256").trim('"'))
            }

            val beforeInvalidDigest = source.calls.config
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(graph.fingerprint, true, { false }, "bad-digest") { _, _, _ -> }
            }
            assertEquals(beforeInvalidDigest, source.calls.config)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveOnlyCdnProducesBoundRangeSourcesWithoutInventingAFileIndexHash() {
        val root = Files.createTempDirectory("catalog-resolver-archive-only-").toFile()
        try {
            val graph = SyntheticGraph(File(root, "fixture"), 70_701, 41, includeFileIndex = false)
            val work = File(root, "work").apply { mkdirs() }
            val result = ForeverClientCatalogResolver(work) { _, _ -> GraphSource(graph) }
                .resolve(graph.fingerprint, true, { false }) { _, _, _ -> }
            ForeverClientUpdateCatalog(result.catalogFile, result.catalogSha256, result.catalogBytes, result.target).use { catalog ->
                assertEquals("null", catalog.metadata("cdn_file_index_hash"))
                assertEquals("range", catalog.objectByKey(graph.payloadEkey).sourceKind)
                assertEquals(graph.indexHash, catalog.objectByKey(graph.payloadEkey).sourceHash)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publicationCrashBeforeRenameLeavesNoFinalAndCrashAfterRenameLeavesReusableCompleteDirectory() {
        val root = Files.createTempDirectory("catalog-resolver-publication-crash-").toFile()
        try {
            val beforeGraph = SyntheticGraph(File(root, "before-fixture"), 70_702, 73)
            val beforeWork = File(root, "before-work").apply { mkdirs() }
            val beforeResolver = ForeverClientCatalogResolver(beforeWork) { _, _ -> GraphSource(beforeGraph) }
            beforeResolver.publicationFaultForTests = { boundary ->
                if (boundary == ForeverClientCatalogResolver.PublicationBoundary.AFTER_STAGED_DIRECTORY_SYNC) {
                    throw ForeverClientCatalogResolver.SimulatedProcessDeath()
                }
            }
            assertThrows(ForeverClientCatalogResolver.SimulatedProcessDeath::class.java) {
                beforeResolver.resolve(beforeGraph.fingerprint, true, { false }) { _, _, _ -> }
            }
            val beforeFinal = completedDirectory(beforeWork, beforeGraph.fingerprint)
            assertFalse("pre-rename interruption must not expose a partial final directory", beforeFinal.exists())
            val staged = File(beforeWork, "catalogs").listFiles().single { it.name.startsWith(".publish-${beforeFinal.name}-") }
            assertEquals(setOf("client-catalog.sqlite3", "target.json"), staged.list()?.toSet())

            beforeResolver.publicationFaultForTests = null
            val recovered = beforeResolver.resolve(beforeGraph.fingerprint, true, { false }) { _, _, _ -> }
            assertEquals(beforeFinal.canonicalFile, recovered.catalogFile.parentFile.canonicalFile)
            ForeverClientUpdateCatalog(recovered.catalogFile, recovered.catalogSha256, recovered.catalogBytes, recovered.target).use {
                assertEquals(5L, it.summary().objectCount)
            }

            val afterGraph = SyntheticGraph(File(root, "after-fixture"), 70_703, 79)
            val afterWork = File(root, "after-work").apply { mkdirs() }
            val afterResolver = ForeverClientCatalogResolver(afterWork) { _, _ -> GraphSource(afterGraph) }
            afterResolver.publicationFaultForTests = { boundary ->
                if (boundary == ForeverClientCatalogResolver.PublicationBoundary.AFTER_FINAL_PARENT_SYNC) {
                    throw ForeverClientCatalogResolver.SimulatedProcessDeath()
                }
            }
            assertThrows(ForeverClientCatalogResolver.SimulatedProcessDeath::class.java) {
                afterResolver.resolve(afterGraph.fingerprint, true, { false }) { _, _, _ -> }
            }
            val afterFinal = completedDirectory(afterWork, afterGraph.fingerprint)
            assertEquals(setOf("client-catalog.sqlite3", "target.json"), afterFinal.list()?.toSet())
            afterResolver.publicationFaultForTests = null
            val rawCalls = afterGraph.sourceCalls.raw + afterGraph.sourceCalls.decoded + afterGraph.sourceCalls.index
            val reused = afterResolver.resolve(afterGraph.fingerprint, true, { false }) { _, _, _ -> }
            assertEquals(rawCalls, afterGraph.sourceCalls.raw + afterGraph.sourceCalls.decoded + afterGraph.sourceCalls.index)
            assertEquals(afterFinal.canonicalFile, reused.catalogFile.parentFile.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foreignFinalDirectoriesArePreservedAndTwoResolverInstancesSerializeOneWorkRoot() {
        val root = Files.createTempDirectory("catalog-resolver-publication-foreign-").toFile()
        try {
            val foreignGraph = SyntheticGraph(File(root, "foreign-fixture"), 70_704, 83)
            val foreignWork = File(root, "foreign-work").apply { mkdirs() }
            val foreignFinal = completedDirectory(foreignWork, foreignGraph.fingerprint)
            val marker = File(foreignFinal, "foreign-owner.marker")
            assertTrue(marker.parentFile.mkdirs())
            marker.writeText("preserve foreign final directory")
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientCatalogResolver(foreignWork) { _, _ -> GraphSource(foreignGraph) }
                    .resolve(foreignGraph.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertEquals("preserve foreign final directory", marker.readText())
            assertEquals(setOf("foreign-owner.marker"), foreignFinal.list()?.toSet())

            val symlinkGraph = SyntheticGraph(File(root, "symlink-fixture"), 70_707, 101)
            val symlinkWork = File(root, "symlink-work").apply { mkdirs() }
            val sentinel = File(root, "lock-sentinel").also { it.writeText("keep") }
            Files.createSymbolicLink(File(symlinkWork, ".catalog-resolver.lock").toPath(), sentinel.toPath())
            val symlinkSource = GraphSource(symlinkGraph)
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientCatalogResolver(symlinkWork) { _, _ -> symlinkSource }
                    .resolve(symlinkGraph.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertEquals("keep", sentinel.readText())
            assertEquals(0, symlinkSource.calls.config)

            val racedGraph = SyntheticGraph(File(root, "raced-fixture"), 70_705, 89)
            val racedWork = File(root, "raced-work").apply { mkdirs() }
            val racedFinal = completedDirectory(racedWork, racedGraph.fingerprint)
            val racedMarker = File(racedFinal, "foreign-owner.marker")
            val racedResolver = ForeverClientCatalogResolver(racedWork) { _, _ -> GraphSource(racedGraph) }
            racedResolver.publicationFaultForTests = { boundary ->
                if (boundary == ForeverClientCatalogResolver.PublicationBoundary.AFTER_STAGED_DIRECTORY_SYNC) {
                    assertTrue(racedFinal.mkdirs())
                    racedMarker.writeText("foreign directory appeared before rename")
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                racedResolver.resolve(racedGraph.fingerprint, true, { false }) { _, _, _ -> }
            }
            assertEquals("foreign directory appeared before rename", racedMarker.readText())
            assertEquals(setOf("foreign-owner.marker"), racedFinal.list()?.toSet())

            val graph = SyntheticGraph(File(root, "concurrent-fixture"), 70_706, 97)
            val sharedWork = File(root, "concurrent-work").apply { mkdirs() }
            val resolvers = listOf(
                ForeverClientCatalogResolver(sharedWork) { _, _ -> GraphSource(graph) },
                ForeverClientCatalogResolver(sharedWork) { _, _ -> GraphSource(graph) },
            )
            val ready = CountDownLatch(resolvers.size)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(resolvers.size)
            try {
                val futures = resolvers.map { resolver ->
                    executor.submit<ForeverClientCatalogResolver.Result> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        resolver.resolve(graph.fingerprint, true, { false }) { _, _, _ -> }
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val first = futures[0].get(3, TimeUnit.MINUTES)
                val second = futures[1].get(3, TimeUnit.MINUTES)
                assertEquals(first.catalogSha256, second.catalogSha256)
                assertEquals(first.catalogFile.parentFile.canonicalFile, second.catalogFile.parentFile.canonicalFile)
                assertEquals(setOf("client-catalog.sqlite3", "target.json"), first.catalogFile.parentFile.list()?.toSet())
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Full offline parity test. Supply a read-only host output tree and host consumer DB:
     * `wowforever.tact70425MetadataRoot`, `wowforever.tact70425HostCatalog`, and
     * optionally `wowforever.tact70425CdnCacheRoot` (the read-only cascette `data` root).
     * All decoded work/output lives under `wowforever.tact70425WorkRoot`; no HTTP is used.
     */
    @Test
    fun optionalReal70425CatalogMatchesHostOracleRowsAndSourcesOffline() {
        val metadataRoot = System.getProperty("wowforever.tact70425MetadataRoot")?.let(::File)
        val hostCatalogFile = System.getProperty("wowforever.tact70425HostCatalog")?.let(::File)
        val workRoot = System.getProperty("wowforever.tact70425WorkRoot")?.let(::File)
        assumeTrue("supply the external host metadata tree, host SQLite oracle, and work root", metadataRoot != null && hostCatalogFile != null && workRoot != null)
        require(metadataRoot!!.isDirectory && hostCatalogFile!!.isFile && workRoot!!.isDirectory)
        require(!metadataRoot.canonicalFile.toPath().startsWith(File(".").canonicalFile.toPath())) {
            "real metadata artifacts must remain outside the repository"
        }
        val decodedRoot = File(workRoot, "offline-decoded").apply { mkdirs() }
        val fingerprint = ForeverClientUpdateCheck.Fingerprint(
            ForeverClientUpdateCheck.PRODUCT,
            "1.60.1.70425",
            ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH,
            ForeverClientUpdateCatalog.PINNED_CDN_CONFIG_HASH,
            ForeverClientUpdateCatalog.PINNED_BUILD_ID,
        )
        val readOnlyCdnCache = System.getProperty("wowforever.tact70425CdnCacheRoot")?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache/cascette/cdn/tpr/wow/data")
        require(readOnlyCdnCache.isDirectory) { "read-only cascette CDN index cache is missing" }
        val source = HostArtifactSource(metadataRoot, decodedRoot, readOnlyCdnCache)
        val closure = JSONObject(File(metadataRoot, "manifest-closure.json").readText(Charsets.UTF_8))
        assertEquals(2_208_563L, closure.getJSONObject("download_manifest").getLong("entry_count"))
        assertEquals(1_144_392L, closure.getJSONObject("download_manifest").getLong("selected_entry_count"))
        assertEquals(2_208_563L, closure.getJSONObject("size_manifest").getLong("entry_count"))
        assertEquals(1_144_392L, closure.getJSONObject("size_manifest").getLong("selected_entry_count"))
        val versionsFile = File(metadataRoot, "metadata/official-versions.tsv")
        require(versionsFile.isFile) { "host metadata tree lacks its read-only official versions response" }
        val versionsBytes = versionsFile.readBytes()
        assertEquals(fingerprint, ForeverClientUpdateCheck.parseVersions(versionsBytes))
        val versionsDigest = sha256(versionsBytes)
        val resolver = ForeverClientCatalogResolver(workRoot) { _, _ -> source }
        val generated = resolver.resolve(fingerprint, true, { false }, versionsDigest) { _, _, _ -> }
        val hostHash = sha256File(hostCatalogFile)
        ForeverClientUpdateCatalog(hostCatalogFile, hostHash, hostCatalogFile.length()).use { oracle ->
            ForeverClientUpdateCatalog(generated.catalogFile, generated.catalogSha256, generated.catalogBytes, generated.target).use { actual ->
                assertEquals(1_144_978L, actual.summary().objectCount)
                assertEquals(36, actual.summary().looseCount)
                assertEquals(1_144_392L, actual.metadata("download_selected_entry_count").toLong())
                assertEquals(1_144_392L, actual.metadata("download_selected_unique_ekey_count").toLong())
                assertEquals(90_752_004_058L, actual.metadata("download_selected_unique_encoded_bytes").toLong())
                assertEquals(1_144_392L, actual.metadata("size_selected_entry_count").toLong())
                assertEquals(1_424L, actual.metadata("cdn_index_count").toLong())
                assertEquals(588, JSONArray(actual.metadata("required_runtime_roles")).length())
                val sourceCounts = longArrayOf(0L, 0L)
                actual.withObjects({ false }) { rows ->
                    rows.forEach { row -> sourceCounts[if (row.sourceKind == "direct") 0 else 1]++ }
                }
                assertEquals(5_512L, sourceCounts[0])
                assertEquals(1_139_466L, sourceCounts[1])
                assertEquals(oracle.summary().objectCount, actual.summary().objectCount)
                assertEquals(oracle.summary().totalEncodedBytes, actual.summary().totalEncodedBytes)
                assertEquals(oracle.summary().looseCount, actual.summary().looseCount)
                assertEquals(orderedObjectFingerprint(oracle), orderedObjectFingerprint(actual))
                assertEquals(orderedLooseFingerprint(oracle), orderedLooseFingerprint(actual))
                listOf(
                    "official_versions_sha256",
                    "download_selected_entry_count", "download_selected_unique_ekey_count",
                    "download_selected_unique_encoded_bytes", "size_selected_entry_count",
                    "size_selected_estimated_bytes", "cdn_index_count", "cdn_index_sha256_manifest",
                ).forEach { key -> assertEquals("metadata field $key differs", oracle.metadata(key), actual.metadata(key)) }
                assertEquals(JSONArray(oracle.metadata("required_runtime_roles")).toString(), JSONArray(actual.metadata("required_runtime_roles")).toString())
                assertRuntimeFingerprintsMatch(oracle.metadata("runtime_role_fingerprints"), actual.metadata("runtime_role_fingerprints"))
                assertMetadataObjectBindingsMatch(oracle.metadata("metadata_objects"), actual.metadata("metadata_objects"))
                assertEquals(0, source.httpRequests)
            }
        }
    }

    private open class GraphSource(private val graph: SyntheticGraph) : ForeverClientCatalogResolver.Source {
        val calls = graph.sourceCalls
        override fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            calls.config++
            val file = graph.configs[hash.lowercase()]
                ?: throw AssertionError("offline source has no config $hash")
            progress(file.length())
            return file
        }

        override fun decoded(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            calls.decoded++
            val file = graph.decoded.getValue(reference.role)
            progress(file.length())
            return file
        }

        override fun raw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            calls.raw++
            val file = graph.raw.getValue(reference.role)
            progress(file.length())
            return file
        }

        override fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            calls.index++
            require(hash.equals(graph.indexHash, true)) { "offline source attempted an unconfigured CDN index" }
            progress(graph.indexFile.length())
            return graph.indexFile
        }
    }

    private class HostArtifactSource(
        private val root: File,
        private val decodedRoot: File,
        private val readOnlyCdnCache: File,
    ) : ForeverClientCatalogResolver.Source {
        var httpRequests = 0
            private set

        override fun config(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File =
            File(root, "metadata/${if (hash.equals(ForeverClientUpdateCatalog.PINNED_BUILD_CONFIG_HASH, true)) "build-config" else "cdn-config"}-$hash.txt")
                .also { require(it.isFile) { "host metadata tree lacks a hash-bound config: $it" }; progress(it.length()) }

        override fun decoded(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            val output = File(decodedRoot, "${reference.role}-${reference.contentKey}.decoded")
            if (!output.exists()) ForeverBlte.decode(raw(reference, cancelled) { }, output, requireNotNull(reference.decodedSize), reference.contentKey, cancelled)
            progress(output.length())
            return output
        }

        override fun raw(reference: ForeverTactMetadata.ObjectReference, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            val persistent = File(root, "verified-raw/${reference.role}-${reference.encodingKey}.blte")
            val initialInstall = File(root, "metadata/install-${reference.encodingKey}.blte")
            val file = if (persistent.isFile) persistent else initialInstall
            require(file.isFile) { "host metadata tree lacks raw ${reference.role} object ${reference.encodingKey}" }
            progress(file.length())
            return file
        }

        override fun index(hash: String, cancelled: () -> Boolean, progress: (Long) -> Unit): File {
            val downloaded = File(root, "verified-raw/cdn-index-$hash.index")
            val cached = File(readOnlyCdnCache, "${hash.take(2)}/${hash.substring(2, 4)}/$hash.index")
            val file = if (downloaded.isFile) downloaded else cached
            require(file.isFile) { "host metadata tree lacks persisted CDN index $hash" }
            progress(file.length())
            return file
        }
    }

    private data class Calls(var config: Int = 0, var decoded: Int = 0, var raw: Int = 0, var index: Int = 0)

    private inner class SyntheticGraph(
        root: File,
        buildId: Int,
        seed: Int,
        archiveCount: Int = 1,
        includeFileIndex: Boolean = true,
        includeArm64InstallTag: Boolean = true,
        overrideInstallMetadataCkey: String? = null,
    ) {
        val sourceCalls = Calls()
        val payloadDecoded = "inert-metadata-payload-$seed".toByteArray(Charsets.US_ASCII)
        val payloadRaw = singleChunk(payloadDecoded)
        val payloadCkey = md5(payloadDecoded)
        val payloadEkey = md5(payloadRaw)
        val indexHash = md5("synthetic-index-$buildId-$seed".toByteArray())
        val indexFile: File
        val decoded = LinkedHashMap<String, File>()
        val raw = LinkedHashMap<String, File>()
        val configs = LinkedHashMap<String, File>()
        val fingerprint: ForeverClientUpdateCheck.Fingerprint

        init {
            root.mkdirs()
            val metadataContents = linkedMapOf(
                "install" to installManifest(payloadCkey, payloadDecoded.size.toLong(), includeArm64InstallTag),
                "download" to downloadManifest(payloadEkey, payloadRaw.size.toLong()),
                "size" to sizeManifest(payloadEkey, payloadDecoded.size.toLong()),
                "encoding" to encodingManifest(payloadCkey, payloadEkey, payloadDecoded.size.toLong(), payloadRaw.size.toLong()),
            )
            val encoded = metadataContents.mapValues { singleChunk(it.value) }
            encoded.forEach { (role, rawBytes) ->
                val rawFile = File(root, "$role.raw").also { it.writeBytes(rawBytes) }
                val decodedFile = File(root, "$role.decoded").also { it.writeBytes(metadataContents.getValue(role)) }
                raw[role] = rawFile
                decoded[role] = decodedFile
            }
            val references = metadataContents.mapValues { roleAndBytes ->
                val role = roleAndBytes.key
                val manifest = roleAndBytes.value
                val rawBytes = encoded.getValue(role)
                listOf(
                    if (role == "install") overrideInstallMetadataCkey ?: md5(manifest) else md5(manifest),
                    md5(rawBytes.copyOfRange(0, 36)), manifest.size.toString(), rawBytes.size.toString(),
                )
            }
            val buildText = buildString {
                append("build-uid = wow_classic_beta\n")
                append("build-num = $buildId\n")
                for (role in listOf("install", "download", "size", "encoding")) {
                    val ref = references.getValue(role)
                    append("$role = ${ref[0]} ${ref[1]}\n")
                    append("$role-size = ${ref[2]} ${ref[3]}\n")
                }
                append("root = $payloadCkey\n")
                append("vfs-root = $payloadCkey $payloadEkey\n")
                append("vfs-root-size = ${payloadDecoded.size} ${payloadRaw.size}\n")
                append("vfs-1 = $payloadCkey $payloadEkey\n")
                append("vfs-1-size = ${payloadDecoded.size} ${payloadRaw.size}\n")
            }.toByteArray(Charsets.UTF_8)
            val buildHash = md5(buildText)
            val archives = if (archiveCount == 1) listOf(indexHash) else {
                (0 until archiveCount).map { md5("unused-index-$buildId-$it".toByteArray()) }
            }
            val fileIndexLine = if (includeFileIndex) "file-index = $indexHash\n" else ""
            val cdnText = "archives = ${archives.joinToString(" ")}\nbuilds = $buildHash\n$fileIndexLine".toByteArray(Charsets.UTF_8)
            val cdnHash = md5(cdnText)
            val buildConfigFile = File(root, "build-$buildId.config").also { it.writeBytes(buildText) }
            val cdnConfigFile = File(root, "cdn-$buildId.config").also { it.writeBytes(cdnText) }
            configs[buildHash] = buildConfigFile
            configs[cdnHash] = cdnConfigFile
            fingerprint = ForeverClientUpdateCheck.Fingerprint(
                ForeverClientUpdateCheck.PRODUCT,
                "1.60.1.$buildId",
                buildHash,
                cdnHash,
                buildId.toLong(),
            )
            val metadataEntries = raw.map { (role, file) ->
                IndexedObject(references.getValue(role)[1], file.length())
            }
            val allEntries = metadataEntries + IndexedObject(payloadEkey, payloadRaw.size.toLong())
            indexFile = File(root, "file-index.index").also { it.writeBytes(fileIndex(allEntries, if (includeFileIndex) 0 else 4)) }
        }
    }

    private data class IndexedObject(val ekey: String, val encodedSize: Long)

    private fun installManifest(contentKey: String, decodedSize: Long, includeArm64: Boolean): ByteArray {
        val tags = if (includeArm64) listOf("Windows", "arm64", "enUS") else listOf("Windows", "enUS")
        return ByteArrayOutputStream().apply {
        write("IN".toByteArray())
        write(1)
        write(16)
        writeU16(this, tags.size)
        writeU32(this, 1)
        writeTags(this, 1, tags)
        write("WowB-ARM64.exe\u0000".toByteArray(Charsets.UTF_8))
        write(contentKey.hexBytes())
        writeU32(this, decodedSize)
        }.toByteArray()
    }

    private fun downloadManifest(ekey: String, encodedSize: Long): ByteArray = ByteArrayOutputStream().apply {
        write("DL".toByteArray())
        write(1)
        write(16)
        write(0)
        writeU32(this, 2)
        writeU16(this, 3)
        repeat(2) {
            write(ekey.hexBytes())
            writeU40(this, encodedSize)
            write(0)
        }
        writeTags(this, 2)
    }.toByteArray()

    private fun sizeManifest(ekey: String, estimatedSize: Long): ByteArray = ByteArrayOutputStream().apply {
        write("DS".toByteArray())
        write(1)
        write(9)
        writeU32(this, 1)
        writeU16(this, 3)
        writeU40(this, estimatedSize)
        writeTags(this, 1)
        write(ekey.hexBytes().copyOfRange(0, 9))
        writeU32(this, estimatedSize)
    }.toByteArray()

    private fun encodingManifest(ckey: String, ekey: String, decodedSize: Long, encodedSize: Long): ByteArray {
        val contentPage = ByteArray(1024)
        contentPage[0] = 1
        writeU40(contentPage, 1, decodedSize)
        ckey.hexBytes().copyInto(contentPage, 6)
        ekey.hexBytes().copyInto(contentPage, 22)
        val encodingPage = ByteArray(1024)
        ekey.hexBytes().copyInto(encodingPage)
        writeU32(encodingPage, 16, 0)
        writeU40(encodingPage, 20, encodedSize)
        val header = ByteArrayOutputStream().apply {
            write("EN".toByteArray())
            write(1)
            write(16)
            write(16)
            writeU16(this, 1)
            writeU16(this, 1)
            writeU32(this, 1)
            writeU32(this, 1)
            write(0)
            writeU32(this, 2)
        }.toByteArray()
        return header + byteArrayOf('N'.code.toByte(), 0) +
            ckey.hexBytes() + md5Bytes(contentPage) + contentPage +
            ekey.hexBytes() + md5Bytes(encodingPage) + encodingPage
    }

    private fun singleChunk(decoded: ByteArray): ByteArray {
        val compressed = byteArrayOf('N'.code.toByte()) + decoded
        val headerSize = 36
        val header = ByteArray(headerSize)
        "BLTE".toByteArray().copyInto(header)
        writeU32(header, 4, headerSize.toLong())
        header[8] = 0x0f
        header[11] = 1
        writeU32(header, 12, compressed.size.toLong())
        writeU32(header, 16, decoded.size.toLong())
        md5Bytes(compressed).copyInto(header, 20)
        return header + compressed
    }

    private fun writeTags(
        output: ByteArrayOutputStream,
        entryCount: Int,
        names: List<String> = listOf("Windows", "arm64", "enUS"),
    ) {
        names.forEachIndexed { index, name ->
            output.write("$name\u0000".toByteArray(Charsets.UTF_8))
            writeU16(output, index + 1)
            val bitmap = ByteArray((entryCount + 7) / 8)
            repeat(entryCount) { bit -> bitmap[bit / 8] = (bitmap[bit / 8].toInt() or (0x80 ushr (bit and 7))).toByte() }
            output.write(bitmap)
        }
    }

    private fun fileIndex(objects: List<IndexedObject>, offsetBytes: Int): ByteArray {
        require(offsetBytes == 0 || offsetBytes == 4)
        val records = objects.sortedBy { it.ekey }
        val pageBytes = 4096
        val entryBytes = 20 + offsetBytes
        val entriesPerPage = pageBytes / entryBytes
        val pageCount = (records.size + entriesPerPage - 1) / entriesPerPage
        val pages = ByteArray(pageCount * pageBytes)
        val keys = ByteArray(pageCount * 16)
        val hashes = ByteArray(pageCount * 8)
        for (pageIndex in 0 until pageCount) {
            val first = pageIndex * entriesPerPage
            val pageCountEntries = minOf(entriesPerPage, records.size - first)
            var cursor = pageIndex * pageBytes
            repeat(pageCountEntries) { entryIndex ->
                val record = records[first + entryIndex]
                record.ekey.hexBytes().copyInto(pages, cursor)
                cursor += 16
                writeU32(pages, cursor, record.encodedSize)
                cursor += 4
                if (offsetBytes == 4) {
                    writeU32(pages, cursor, 0L)
                    cursor += 4
                }
            }
            records[first + pageCountEntries - 1].ekey.hexBytes().copyInto(keys, pageIndex * 16)
            md5Bytes(pages.copyOfRange(pageIndex * pageBytes, (pageIndex + 1) * pageBytes))
                .copyInto(hashes, pageIndex * 8, 0, 8)
        }
        val toc = keys + hashes
        val footer = ByteArray(28)
        md5Bytes(toc).copyInto(footer, 0, 0, 8)
        footer[8] = 1
        footer[11] = 4
        footer[12] = offsetBytes.toByte()
        footer[13] = 4
        footer[14] = 16
        footer[15] = 8
        writeU32Little(footer, 16, records.size.toLong())
        val checksumPayload = ByteArray(20)
        footer.copyInto(checksumPayload, 0, 8, 20)
        md5Bytes(checksumPayload).copyInto(footer, 20, 0, 8)
        return pages + toc + footer
    }

    private fun orderedObjectFingerprint(catalog: ForeverClientUpdateCatalog): String {
        val digest = MessageDigest.getInstance("SHA-256")
        catalog.withObjects({ false }) { rows ->
            rows.forEach { row ->
                digest.update(listOf(
                    row.ekey, row.contentKey, row.encodedSize.toString(), row.decodedSize.toString(), row.sourceKind,
                    row.sourceHash, row.sourceOffset.toString(), row.sourceLength.toString(),
                ).joinToString("\u0000", postfix = "\n").toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().hex()
    }

    private fun orderedLooseFingerprint(catalog: ForeverClientUpdateCatalog): String = MessageDigest.getInstance("SHA-256").let { digest ->
        catalog.looseFiles().forEach { loose ->
            val record = loose.objectRecord
            digest.update(listOf(
                loose.path, record.ekey, record.contentKey, record.encodedSize.toString(), record.decodedSize.toString(),
                record.sourceKind, record.sourceHash, record.sourceOffset.toString(), record.sourceLength.toString(),
            ).joinToString("\u0000", postfix = "\n").toByteArray(Charsets.UTF_8))
        }
        digest.digest().hex()
    }

    private fun assertMetadataObjectBindingsMatch(expectedText: String, actualText: String) {
        val expected = JSONObject(expectedText)
        val actual = JSONObject(actualText)
        val roles = setOf("download", "encoding", "install", "size")
        assertEquals(roles, expected.keys().asSequence().toSet())
        assertEquals(roles, actual.keys().asSequence().toSet())
        roles.forEach { role ->
            val left = expected.getJSONObject(role)
            val right = actual.getJSONObject(role)
            listOf("role", "content_key", "encoding_key", "declared_encoded_size", "decoded_size", "decoded_md5", "decoded_sha256", "blte_flags", "raw_sha256").forEach { field ->
                assertJsonValueEquals("metadata_objects.$role.$field", left.get(field), right.get(field))
            }
            assertEquals(left.getJSONArray("blte_modes").toString(), right.getJSONArray("blte_modes").toString())
        }
    }

    private fun assertRuntimeFingerprintsMatch(expectedText: String, actualText: String) {
        val expected = JSONObject(expectedText)
        val actual = JSONObject(actualText)
        val roles = expected.keys().asSequence().toSet()
        assertEquals(roles, actual.keys().asSequence().toSet())
        roles.forEach { role ->
            val left = expected.getJSONObject(role)
            val right = actual.getJSONObject(role)
            listOf("owner", "ekey", "ckey", "encoded_size", "decoded_size", "source_kind").forEach { field ->
                assertJsonValueEquals("runtime_role_fingerprints.$role.$field", left.get(field), right.get(field))
            }
        }
    }

    private fun assertJsonValueEquals(label: String, expected: Any, actual: Any) {
        if (expected is Number && actual is Number) assertEquals(label, expected.toLong(), actual.toLong())
        else assertEquals(label, expected, actual)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun completedDirectory(workRoot: File, fingerprint: ForeverClientUpdateCheck.Fingerprint): File = File(
        File(workRoot, "catalogs"),
        "${fingerprint.buildId}-${fingerprint.version}-${fingerprint.buildConfigHash.lowercase()}-${fingerprint.cdnConfigHash.lowercase()}",
    )

    private fun md5(bytes: ByteArray): String = md5Bytes(bytes).hex()
    private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun versionsResponse(
        fingerprint: ForeverClientUpdateCheck.Fingerprint,
        includeEu: Boolean,
    ): ByteArray = buildString {
        append("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|BuildId!DEC:4|VersionsName!STRING:0\n")
        if (includeEu) append("eu|${"1".repeat(32)}|${"2".repeat(32)}|70|1.60.1.70\n")
        append("us|${fingerprint.buildConfigHash}|${fingerprint.cdnConfigHash}|${fingerprint.buildId}|${fingerprint.version}\n")
    }.toByteArray(Charsets.UTF_8)

    private fun writeU16(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index -> output.write((value ushr (24 - index * 8)).toInt() and 0xff) }
    }

    private fun writeU40(output: ByteArrayOutputStream, value: Long) {
        repeat(5) { index -> output.write((value ushr (32 - index * 8)).toInt() and 0xff) }
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> target[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private fun writeU40(target: ByteArray, offset: Int, value: Long) {
        repeat(5) { index -> target[offset + index] = (value ushr (32 - index * 8)).toByte() }
    }

    private fun writeU32Little(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
