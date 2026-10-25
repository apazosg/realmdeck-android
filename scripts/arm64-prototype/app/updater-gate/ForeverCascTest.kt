package app.gamenative

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverCascTest {
    @Test
    fun buildInfoMatchesPinnedProducerSerialization() {
        val fields = JSONObject()
            .put("branch", "us")
            .put("build_key", "05215079e3905ef5922ae0b03ffefb73")
            .put("cdn_key", "9b3c456dbb837d133a026d380c7c13e9")
            .put("install_key", "8d7fa7d8544305bf0491e534e0b8e9aa")
            .put("cdn_path", "tpr/wow")
            .put("cdn_hosts", JSONArray().put("level3.blizzard.com").put("us.cdn.blizzard.com"))
            .put("version", "1.60.1.70009")
            .put("product", "wow_classic_beta")
            .put("platform", "Windows")
            .put("architecture", "arm64")
            .put("locale", "enUS")
            .put("region", "us")
        val bytes = ForeverCasc.buildInfo(fields)
        assertEquals(668, bytes.size)
        assertEquals("e43f5e18b8a1cf88caa7d9999811d8cc5526c9d51d5c5334d1ed916c7a906524", sha256Hex(bytes))
    }

    @Test
    fun materializedStoreMatchesIndependentV7LayoutAndChecksums() {
        val goldenFixture = System.getProperty("wowforever.cascGolden")?.let { JSONObject(File(it).readText()) }
        val root = goldenFixture?.let { File(it.getString("staging_root")).apply {
            check(!exists()) { "golden staging root must be new; regenerate the independent fixture" }
            check(mkdirs())
        } } ?: tempDir()
        val decoded = "casc-golden".toByteArray()
        val raw = blteRaw(decoded)
        val ekey = "0123456789abcdef0123456789abcdef"
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(root, ".build.info\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, file(root, "raw", raw))), { false })

        val bucket = bucket(ekey.hexBytes())
        val indexDir = File(root, "Data/data")
        for (index in 0 until 16) {
            val bytes = File(indexDir, "%02x%08x.idx".format(index, 1)).readBytes()
            assertEquals(0x30000, bytes.size)
            assertEquals(7, u16(bytes, 8))
            assertEquals(index, bytes[10].toInt() and 0xff)
            assertEquals(16, u32(bytes, 0))
            assertEquals(hashlittle(bytes.copyOfRange(8, 24), 0), u32(bytes, 4))
            val blockSize = u32(bytes, 0x20)
            var pc = 0
            var pb = 0
            var at = 0x28
            while (at < 0x28 + blockSize) {
                val entry = bytes.copyOfRange(at, at + 18)
                val hashes = hashlittle2(entry, pc, pb)
                pc = hashes.first
                pb = hashes.second
                at += 18
            }
            assertEquals(pc, u32(bytes, 0x24))
            if (index == bucket) {
                val entryAt = findEntry(bytes, ekey.hexBytes().copyOfRange(0, 9))
                assertEquals(480, u32be(bytes, entryAt + 10) and 0x3fffffff)
                assertEquals(raw.size + 30, u32(bytes, entryAt + 14))
            }
        }

        val archive = File(indexDir, "data.000")
        val segment = archive.readBytes().copyOfRange(0, 480)
        for (slot in 0 until 16) {
            val header = segment.copyOfRange(slot * 30, slot * 30 + 30)
            assertEquals(30, u32(header, 0x10))
            assertEquals(1, u16(header, 0x14))
            assertEquals(hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971), u32(header, 0x16))
            assertEquals(checksumB(header, slot * 30L), u32(header, 0x1a))
        }
        val objectOffset = 480
        val objectHeader = archive.readBytes().copyOfRange(objectOffset, objectOffset + 30)
        assertEquals(ekey.hexBytes().reversedArray().toList(), objectHeader.copyOfRange(0, 16).toList())
        assertEquals(raw.size + 30, u32(objectHeader, 0x10))
        assertEquals(0, u16(objectHeader, 0x14))
        assertEquals(hashlittle(objectHeader.copyOfRange(0, 0x16), 0x3D6BE971), u32(objectHeader, 0x16))
        assertEquals(checksumB(objectHeader, objectOffset.toLong()), u32(objectHeader, 0x1a))
        goldenFixture?.let { fixture ->
            val golden = fixture.getJSONObject("files")
            val expectedFiles = golden.keys().asSequence().toSet()
            assertEquals(indexDir.listFiles()!!.map { it.name }.toSet(), expectedFiles)
            expectedFiles.forEach { name -> assertEquals("independent cascette output: $name", golden.getString(name), sha256Hex(File(indexDir, name).readBytes())) }
        }
        delete(root)
    }

    @Test
    fun grownGenerationAndExpandedFileArePreservedAndStagingCanResume() {
        val root = tempDir()
        val decoded = "preserve".toByteArray()
        val raw = blteRaw(decoded)
        val ekey = encodedEkey(raw)
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        val rawFile = file(root, "raw", raw)
        ForeverCasc.materialize(root, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        val expanded = file(File(root, "Data"), "expanded-by-game.bin", byteArrayOf(1, 2, 3))
        val bucket = bucket(ekey.hexBytes())
        val first = File(root, "Data/data/%02x%08x.idx".format(bucket, 1))
        File(root, "Data/data/%02x%08x.idx".format(bucket, 2)).writeBytes(first.readBytes())
        ForeverCasc.verifyExisting(root, listOf(ForeverCasc.RuntimeObjectSpec(ekey, md5Hex(decoded), decoded.size.toLong())), { false })
        assertTrue(expanded.isFile)
        val outside = File.createTempFile("casc-outside-", ".bin").also { it.writeBytes(byteArrayOf(8)) }
        val verifyLink = File(root, ".forever-casc-verify.tmp")
        java.nio.file.Files.createSymbolicLink(verifyLink.toPath(), outside.toPath())
        ForeverCasc.verifyExisting(root, listOf(ForeverCasc.RuntimeObjectSpec(ekey, md5Hex(decoded), decoded.size.toLong())), { false })
        assertTrue("verification must not follow or replace the legacy temp path", java.nio.file.Files.isSymbolicLink(verifyLink.toPath()))
        assertEquals(listOf(8.toByte()), outside.readBytes().toList())
        verifyLink.delete()
        outside.delete()
        // The managed staging marker makes a retry safe even after Data.new or
        // a promoted staging Data directory survived an interrupted run.
        val retry = File(root, "retry-stage").also { it.mkdirs() }
        ForeverCasc.materialize(retry, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        assertTrue(File(retry, "Data/data").isDirectory)
        assertTrue(expanded.isFile)
        delete(root)
    }

    @Test
    fun incrementalDeltaReusesVerifiedObjectsAndRepairsCorruptionWithoutCopyingOldSegments() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val objectSpecs = (0 until 20).map { index ->
            val decoded = "incremental-object-$index".toByteArray()
            val raw = if (index < 2) blteChunked(decoded) else blteRaw(decoded)
            val rawFile = file(root, "encoded/$index.blte", raw)
            val ekey = encodedEkey(raw)
            if (index < 2) {
                assertEquals(0x0f, raw[8].toInt() and 0xff)
                assertEquals(36, u32be(raw, 4))
                assertFalse("chunked EKey is not the whole-object hash", ekey == md5Hex(raw))
            }
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
            spec to ForeverCasc.RuntimeObject(record, rawFile)
        }
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), objectSpecs.map { it.second }, { false })
        sourceData.listFiles()!!.filter { it.name.endsWith(".idx") }.forEach { firstGeneration ->
            val bucket = firstGeneration.name.substring(0, 2).toInt(16)
            firstGeneration.copyTo(File(sourceData, "%02x%08x.idx".format(bucket, 2)))
        }

        val corruptSpec = objectSpecs[0].first
        val corruptLocation = localEntry(sourceData, corruptSpec.ekey)
        val oldArchive = File(sourceData, "data.%03d".format(corruptLocation.first))
        RandomAccessFile(oldArchive, "rw").use { archive ->
            val at = corruptLocation.second + 30 + corruptSpec.encodedSize - 1
            archive.seek(at)
            val original = archive.readUnsignedByte()
            archive.seek(at)
            archive.writeByte(original xor 1)
        }
        val damagedRaw = indexedRaw(sourceData, corruptSpec.ekey)
        assertEquals("chunk-table EKey is unchanged by payload damage", corruptSpec.ekey, encodedEkey(damagedRaw))
        assertFalse("payload damage changes the whole-object hash", corruptSpec.ekey == md5Hex(damagedRaw))
        val sourceBefore = directFileHashes(sourceData)
        val repairSource = objectSpecs[0].second.raw
        val newObject = run {
            val decoded = "incremental-object-new".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(root, "encoded/new.blte", raw)
            val ekey = encodedEkey(raw)
            ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()) to rawFile
        }
        val target = objectSpecs.take(19).map { it.first } + newObject.first
        val replacementSources = mapOf(corruptSpec.ekey to repairSource, newObject.first.ekey to newObject.second)
        val outputRoot = File(root, "incremental")
        val outputData = File(outputRoot, "Data/data")

        val result = ForeverCasc.writeIncremental(
            sourceData,
            outputData,
            target.asSequence(),
            { replacementSources[it.ekey] },
            { false },
        )

        assertEquals(18L, result.reusedObjects)
        assertEquals(2L, result.writtenObjects)
        assertEquals(1, result.newSegments)
        assertEquals(sourceBefore, directFileHashes(sourceData))
        assertFalse(File(outputData, "data.000").exists())
        assertTrue(File(outputData, "data.001").isFile)
        assertEquals(17, outputData.listFiles()!!.size)
        assertFalse(File(outputRoot, "Data.new").exists())
        for (bucket in 0 until 16) {
            assertTrue(File(outputData, "%02x%08x.idx".format(bucket, 3)).isFile)
            assertFalse(File(outputData, "%02x%08x.idx".format(bucket, 1)).exists())
            assertFalse(File(outputData, "%02x%08x.idx".format(bucket, 2)).exists())
        }
        assertEquals(0, localEntry(outputData, target[1].ekey).first)
        assertEquals(1, localEntry(outputData, corruptSpec.ekey).first)
        assertEquals(1, localEntry(outputData, newObject.first.ekey).first)
        assertIndependentNewRecord(outputData, corruptSpec)
        assertIndependentNewRecord(outputData, newObject.first)

        // The writer output is an overlay, not a standalone store: the source
        // archive remains the backing store for reused entries. Assemble this
        // read-only verification view only after asserting no old archive was
        // copied by the writer.
        val verifyRoot = File(root, "merged-view")
        val verifyData = File(verifyRoot, "Data/data").also { assertTrue(it.mkdirs()) }
        sourceData.listFiles()!!.filter { it.name.startsWith("data.") }.forEach { it.copyTo(File(verifyData, it.name)) }
        outputData.listFiles()!!.forEach { it.copyTo(File(verifyData, it.name)) }
        ForeverCasc.verifyExisting(
            verifyRoot,
            target.map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
            { false },
        )
        delete(root)
    }

    @Test
    fun incrementalWriterRejectsShortEkeyPrefixCollisionWithoutTouchingSource() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "collision-target".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/target.blte", raw)
        val targetEkey = encodedEkey(raw)
        val differentSuffix = if (targetEkey.substring(18) == "00000000000000") "11111111111111" else "00000000000000"
        val storedEkey = targetEkey.substring(0, 18) + differentSuffix
        val oldRecord = JSONObject().put("encoding_keys", JSONArray().put(storedEkey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(oldRecord, rawFile)), { false })
        val before = directFileHashes(sourceData)
        val outputData = File(root, "output/Data/data")
        val spec = ForeverCasc.IncrementalObjectSpec(targetEkey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())

        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeIncremental(sourceData, outputData, sequenceOf(spec), { rawFile }, { false })
        }

        assertTrue(error.message!!.contains("short EKey prefix collision"))
        assertEquals(before, directFileHashes(sourceData))
        // A failed stage may leave its empty output directory, but must not
        // publish any segment or index before rejecting the ambiguous key.
        assertTrue(outputData.listFiles().orEmpty().isEmpty())

        // The canonical target path may see the same 9-byte prefix as a hint,
        // but must read the physical full EKey and treat this stale collision as
        // a miss before downloading the requested full-key object.
        val canonicalOutput = File(root, "canonical-output/Data/data")
        var sourceRequests = 0
        val canonicalResult = ForeverCasc.writeTargetIncremental(
            sourceData,
            canonicalOutput,
            sequenceOf(spec),
            { sourceRequests++; rawFile },
            { false },
        )
        assertEquals(0L, canonicalResult.reusedObjects)
        assertEquals(1L, canonicalResult.writtenObjects)
        assertEquals(1L, canonicalResult.candidateHintsExamined)
        assertEquals(1L, canonicalResult.candidateHintsRejected)
        assertEquals(1, sourceRequests)
        assertEquals(1, localEntry(canonicalOutput, targetEkey).first)
        assertEquals(before, directFileHashes(sourceData))
        val canonicalVerifyRoot = assembleTargetView(root, sourceData, canonicalOutput)
        ForeverCasc.verifyExisting(canonicalVerifyRoot, listOf(ForeverCasc.RuntimeObjectSpec(targetEkey, md5Hex(decoded), decoded.size.toLong())), { false })

        val collidingTarget = spec.copy(ekey = storedEkey)
        val collidingOutput = File(root, "colliding-target/Data/data")
        var collidingSourceRequests = 0
        val collisionError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeTargetIncremental(
                sourceData,
                collidingOutput,
                sequenceOf(spec, collidingTarget),
                { collidingSourceRequests++; rawFile },
                { false },
            )
        }
        assertTrue(collisionError.message.orEmpty().contains("short EKey prefix collision"))
        assertEquals(1, collidingSourceRequests)
        assertTrue(collidingOutput.listFiles().orEmpty().none { it.name.endsWith(".idx") })
        assertFalse(File(collidingOutput, ".forever-casc-index-work").exists())
        assertEquals(before, directFileHashes(sourceData))
        delete(root)
    }

    @Test
    fun incrementalWriterGrowsV7IndexPastLegacyBucketCapacity() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val oldDecoded = "capacity-fixture".toByteArray()
        val oldRaw = blteRaw(oldDecoded)
        val oldRawFile = file(root, "encoded/old.blte", oldRaw)
        val oldObjects = (1L..10_919L).map { index ->
            val ekey = bucketZeroEkey(index)
            val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", oldDecoded.size).put("content_key", md5Hex(oldDecoded))
            ForeverCasc.RuntimeObject(record, oldRawFile)
        }
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), oldObjects, { false })
        val before = directFileHashes(sourceData)

        val (decoded, raw, rawFile) = rawForBucketZero(root)
        val spec = ForeverCasc.IncrementalObjectSpec(encodedEkey(raw), md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
        val outputData = File(root, "output/Data/data")
        val result = ForeverCasc.writeIncremental(sourceData, outputData, sequenceOf(spec), { rawFile }, { false })

        assertEquals(0L, result.reusedObjects)
        assertEquals(1L, result.writtenObjects)
        val grownIndex = File(outputData, "0000000002.idx")
        assertTrue(grownIndex.isFile)
        assertEquals(0x3D000L, grownIndex.length())
        val grownBytes = grownIndex.readBytes()
        assertEquals(10_922 * 18, u32(grownBytes, 0x20))
        assertEquals(0, u32(grownBytes, 0x20) % 18)
        assertEquals(10_922, countIndexRecords(grownBytes))
        assertEquals(0, u32(grownBytes, 0x24) - indexSortedChecksum(grownBytes))
        // The sorted section still ends on its 4KiB boundary, but the file
        // must now carry the 48KiB empty update reserve after it: the exact
        // native-binder gap the zero-reserve writer used to close.
        val sortedEnd = 0x28L + u32(grownBytes, 0x20).toLong()
        val updateStart = roundUpIndexLength(sortedEnd)
        assertEquals(0x31000L, updateStart)
        assertEquals(updateStart + 0xC000L, grownIndex.length())
        assertTrue(grownBytes.copyOfRange(sortedEnd.toInt(), grownBytes.size).all { it.toInt() == 0 })
        // Header, bucket, and the newly written entry survive the resize.
        assertEquals(7, u16(grownBytes, 8))
        assertEquals(0, grownBytes[10].toInt() and 0xff)
        assertEquals(hashlittle(grownBytes.copyOfRange(8, 24), 0), u32(grownBytes, 4))
        assertEquals(1, localEntry(outputData, spec.ekey).first)
        assertEquals(before, directFileHashes(sourceData))
        assertTrue(File(outputData, "data.001").isFile)
        assertFalse(File(outputData, ".forever-casc-index-work").exists())

        try {
            val verifyRoot = File(root, "grown-merged-view")
            val verifyData = File(verifyRoot, "Data/data").also { assertTrue(it.mkdirs()) }
            sourceData.listFiles()!!.forEach { it.copyTo(File(verifyData, it.name)) }
            outputData.listFiles()!!.forEach { it.copyTo(File(verifyData, it.name)) }
            ForeverCasc.verifyExisting(
                verifyRoot,
                listOf(ForeverCasc.RuntimeObjectSpec(spec.ekey, spec.contentKey, spec.decodedSize)),
                { false },
            )
            assertTrue(verifyRoot.listFiles()!!.none { it.name.startsWith(".forever-casc-verify-") })

            // A corrupt newer grown index must fail, not silently select generation 1.
            // The fallback request is deliberately present in generation 1 so the old
            // fixed-size discovery would otherwise return success using stale metadata.
            val invalidActive = File(verifyData, "0000000003.idx")
            grownIndex.copyTo(invalidActive)
            RandomAccessFile(invalidActive, "rw").use { index ->
                index.seek(0x24)
                val original = index.readUnsignedByte()
                index.seek(0x24)
                index.writeByte(original xor 1)
            }
            val olderEkey = oldObjects.first().record.getJSONArray("encoding_keys").getString(0)
            val invalidError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.verifyExisting(
                    verifyRoot,
                    listOf(ForeverCasc.RuntimeObjectSpec(olderEkey, md5Hex(oldDecoded), oldDecoded.size.toLong())),
                    { false },
                )
            }
            assertTrue(invalidError.message.orEmpty().contains("checksum"))
            assertTrue(verifyRoot.listFiles()!!.none { it.name.startsWith(".forever-casc-verify-") })
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetEstimateReservesEmptyUpdateCapacityBeyondLegacyFloor() {
        val root = tempDir()
        try {
            // 11,000 bucket-zero objects in strictly increasing EKey order: a
            // payload-free plan, so no object bytes are read or written.
            // (bucketZeroEkey is little-endian, so the keys must be sorted.)
            val manifest = (1L..11_000L).map { index ->
                ForeverCasc.IncrementalObjectSpec(bucketZeroEkey(index), "0".repeat(32), 100L, 100L)
            }.sortedBy { it.ekey }
            val work = File(root, "plan")
            var callbacks = 0L
            val plan = ForeverCasc.inspectTargetOrderedSourceNull(work, manifest.asSequence(), { false }) { _, _ -> callbacks++ }

            assertEquals(11_000L, callbacks)
            assertEquals(11_000L, plan.totalObjects)
            assertEquals(11_000L, plan.missingObjects)
            assertEquals(1, plan.newSegmentCount)
            // Bucket 0 holds 11,000 objects plus its segment reconstruction
            // record; every other bucket holds only its reconstruction record.
            // 0x28 + 11,001 * 18 aligns to 0x31000, then the 0xC000 empty
            // update reserve applies, while compact buckets stay floored.
            assertEquals(0x3D000L + 15L * 0x30000L, plan.indexBytesUpperBound)
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetEstimateMatchesActualResumableWriterIndexBytes() {
        val root = tempDir()
        try {
            val objects = (0..2).map { index ->
                val decoded = "estimate-match-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val manifest = objects.map { it.first }.sortedBy { it.ekey }
            val plan = ForeverCasc.inspectTargetOrderedSourceNull(
                File(root, "private-work/plan"),
                manifest.asSequence(),
                { false },
            ) { _, _ -> }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }

            ForeverCasc.writeTargetIncrementalResumable(
                null,
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                { false },
                "catalog-test|estimate-match",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
            )

            val indices = target.listFiles()!!.filter { it.name.endsWith(".idx") }
            assertEquals(16, indices.size)
            var actual = 0L
            indices.forEach { actual = Math.addExact(actual, it.length()) }
            assertEquals(plan.indexBytesUpperBound, actual)
            // A compact manifest stays on the legacy floor per bucket, on both
            // the planning and the writing path.
            assertEquals(16L * 0x30000L, actual)
        } finally {
            delete(root)
        }
    }

    @Test
    fun incrementalWriterHandlesFullManifestSizedMetadataWithBoundedDiskBackedRecords() {
        val exportPath = System.getProperty("wowforever.cascLargeMetadataFixture")
        val root = if (exportPath == null) {
            tempDir()
        } else {
            val export = File(exportPath).canonicalFile
            val repository = File(System.getProperty("user.dir")).canonicalFile.toPath()
            require(!export.exists()) { "large metadata fixture export path must be new" }
            require(!export.toPath().startsWith(repository)) { "large metadata fixture export must be outside the working tree" }
            check(export.mkdirs())
            println("LARGE_METADATA_FIXTURE_EXPORT=${export.absolutePath}")
            export
        }
        val sourceData = File(root, "source/Data/data").also { assertTrue(it.mkdirs()) }
        val totalRecords = 1_319_619
        writeMetadataOnlyLargeStore(sourceData, totalRecords)
        val sourceBefore = directFileHashesStreaming(sourceData)
        val outputData = File(root, "output/Data/data")

        val result = ForeverCasc.writeIncremental(sourceData, outputData, emptySequence(), { error("no payload source expected") }, { false })

        assertEquals(0L, result.reusedObjects)
        assertEquals(0L, result.writtenObjects)
        assertEquals(0, result.newSegments)
        var outputRecords = 0L
        for (bucket in 0 until 16) {
            val index = File(outputData, "%02x%08x.idx".format(bucket, 2))
            assertTrue("missing generated bucket $bucket", index.isFile)
            val bytes = index.readBytes()
            val count = countIndexRecords(bytes)
            assertTrue("bucket $bucket crossed the legacy capacity", count > 10_920)
            assertEquals(indexSortedChecksum(bytes), u32(bytes, 0x24))
            assertEquals(maxOf(0x30000L, roundUpIndexLength(0x28L + count.toLong() * 18L) + 0xC000L), index.length())
            outputRecords += count
        }
        assertEquals(totalRecords.toLong(), outputRecords)
        assertTrue(outputData.listFiles()!!.none { it.name.startsWith("data.") })
        assertFalse(File(outputData, ".forever-casc-index-work").exists())
        assertEquals(sourceBefore, directFileHashesStreaming(sourceData))
        if (exportPath == null) delete(root)
    }

    @Test
    fun targetCanonicalWriterIndexesFullCountHintsWithoutAdoptingUnrequestedRecords() {
        val root = tempDir()
        try {
            val sourceData = File(root, "source/Data/data").also { assertTrue(it.mkdirs()) }
            val totalRecords = 1_319_619
            writeMetadataOnlyLargeStore(sourceData, totalRecords)
            val sourceBefore = directFileHashesStreaming(sourceData)
            val outputData = File(root, "target/Data/data")

            val result = ForeverCasc.writeTargetIncremental(
                sourceData,
                outputData,
                emptySequence(),
                { error("empty target manifest must not request object payloads") },
                { false },
            )

            assertEquals(0L, result.reusedObjects)
            assertEquals(0L, result.writtenObjects)
            assertEquals(0, result.newSegments)
            assertEquals(totalRecords.toLong(), result.candidateHintsLoaded)
            assertEquals(0L, result.candidateHintsExamined)
            assertEquals(0L, result.candidateHintsRejected)
            for (bucket in 0 until 16) {
                val index = File(outputData, "%02x%08x.idx".format(bucket, 2))
                assertTrue(index.isFile)
                assertEquals(0x30000L, index.length())
                assertEquals(0, countIndexRecords(index.readBytes()))
            }
            assertTrue(outputData.listFiles()!!.none { it.name.startsWith("data.") })
            assertFalse(File(outputData, ".forever-casc-index-work").exists())
            assertEquals(sourceBefore, directFileHashesStreaming(sourceData))
        } finally {
            delete(root)
        }
    }

    @Test
    fun incrementalWriterRejectsMalformedVariableIndexLayoutAndNonzeroUpdateSection() {
        val root = tempDir()
        val sourceRoot = File(root, "base")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "index-layout-source".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/index-layout.blte", raw)
        val ekey = encodedEkey(raw)
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        val bucketWithTwoRecords = bucket(ekey.hexBytes())
        val originalIndex = File(sourceData, "%02x%08x.idx".format(bucketWithTwoRecords, 1))
        val originalBytes = originalIndex.readBytes()

        val corruptions = listOf<(ByteArray) -> Unit>(
            { bytes -> bytes[0x24] = (bytes[0x24].toInt() xor 1).toByte() },
            { bytes -> putU32le(bytes, 0x20, 0xfffffff0L) },
            { bytes -> bytes[0x1000] = 1 },
            { bytes ->
                val first = bytes.copyOfRange(0x28, 0x28 + 18)
                bytes.copyOfRange(0x28 + 18, 0x28 + 36).copyInto(bytes, 0x28)
                first.copyInto(bytes, 0x28 + 18)
                putU32le(bytes, 0x24, indexSortedChecksum(bytes).toLong() and 0xffffffffL)
            },
            { bytes -> putU32le(bytes, 0x20, 18_000_018L) },
            { bytes ->
                java.util.Arrays.fill(bytes, 0x28, 0x31, 0.toByte())
                putU32le(bytes, 0x24, indexSortedChecksum(bytes).toLong() and 0xffffffffL)
            },
            { bytes ->
                val at = findEntry(bytes, ekey.hexBytes().copyOfRange(0, 9))
                val duplicate = bytes.copyOfRange(at, at + 18)
                System.arraycopy(bytes, at + 18, bytes, at + 36, bytes.size - at - 36)
                duplicate.copyInto(bytes, at + 18)
                putU32le(bytes, 0x20, u32(bytes, 0x20).toLong() + 18L)
                val duplicateSize = (u32(bytes, at + 18 + 14).toLong() and 0xffffffffL) + 1L
                putU32le(bytes, at + 18 + 14, duplicateSize)
                putU32le(bytes, 0x24, indexSortedChecksum(bytes).toLong() and 0xffffffffL)
            },
        )
        repeat(8) { index ->
            val scenario = File(root, "malformed-$index/Data/data")
            assertTrue(scenario.mkdirs())
            sourceData.listFiles()!!.forEach { it.copyTo(File(scenario, it.name)) }
            val changedLength = if (index == 7) 0x0ffc else originalBytes.size
            val changed = originalBytes.copyOf(changedLength)
            if (index < corruptions.size) corruptions[index](changed)
            File(scenario, originalIndex.name).writeBytes(changed)
            val before = directFileHashes(scenario)
            val output = File(root, "malformed-output-$index/Data/data")
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeIncremental(scenario, output, emptySequence(), { null }, { false })
            }
            val expected = when (index) {
                0 -> "checksum"
                1 -> "record count"
                2 -> "nonzero update section"
                3 -> "strictly sorted"
                4 -> "record count"
                5 -> "zero 9-byte EKey prefix"
                6 -> "CONFLICTING_SORTED_PREFIX_RECORDS"
                else -> "truncated"
            }
            assertTrue("${error.message}", error.message.orEmpty().contains(expected))
            assertEquals(before, directFileHashes(scenario))
            assertTrue(output.listFiles().orEmpty().isEmpty())
            delete(scenario.parentFile!!)
        }
        delete(root)
    }

    @Test
    fun incrementalWriterCoalescesOnlyIdenticalDuplicateSortedRecordsAfterHeaderIdentityCheck() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "exact-index-duplicate".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/exact-duplicate.blte", raw)
        val ekey = encodedEkey(raw)
        val objectRecord = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(
            sourceRoot,
            "build\n".toByteArray(),
            emptyList(),
            emptyList(),
            listOf(ForeverCasc.RuntimeObject(objectRecord, rawFile)),
            { false },
        )
        val bucket = bucket(ekey.hexBytes())
        val sourceIndex = File(sourceData, "%02x%08x.idx".format(bucket, 1))
        val bytes = sourceIndex.readBytes()
        val originalCount = countIndexRecords(bytes)
        val entryAt = findEntry(bytes, ekey.hexBytes().copyOfRange(0, 9))
        val duplicate = bytes.copyOfRange(entryAt, entryAt + 18)
        val afterEntry = entryAt + 18
        System.arraycopy(bytes, afterEntry, bytes, afterEntry + 18, bytes.size - afterEntry - 18)
        duplicate.copyInto(bytes, afterEntry)
        putU32le(bytes, 0x20, originalCount.toLong() * 18L + 18L)
        putU32le(bytes, 0x24, indexSortedChecksum(bytes).toLong() and 0xffffffffL)
        sourceIndex.writeBytes(bytes)
        val sourceBefore = directFileHashes(sourceData)
        val outputData = File(root, "output/Data/data")

        val result = ForeverCasc.writeIncremental(sourceData, outputData, emptySequence(), { null }, { false })

        assertEquals(0L, result.reusedObjects)
        assertEquals(0L, result.writtenObjects)
        val merged = File(outputData, "%02x%08x.idx".format(bucket, 2)).readBytes()
        assertEquals(originalCount, countIndexRecords(merged))
        assertEquals(sourceBefore, directFileHashes(sourceData))
        assertFalse(File(outputData, ".forever-casc-index-work").exists())
        delete(root)
    }

    @Test
    fun incrementalWriterAcceptsSmallerAlignedZeroTailSourceIndicesButKeepsOutputFloor() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "small-aligned-source-index".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/small-index.blte", raw)
        val ekey = encodedEkey(raw)
        val objectRecord = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(
            sourceRoot,
            "build\n".toByteArray(),
            emptyList(),
            emptyList(),
            listOf(ForeverCasc.RuntimeObject(objectRecord, rawFile)),
            { false },
        )
        sourceData.listFiles()!!.filter { it.name.endsWith(".idx") }.forEach { index ->
            RandomAccessFile(index, "rw").use { it.setLength(0x10000L) }
        }
        val sourceBefore = directFileHashes(sourceData)
        val outputData = File(root, "output/Data/data")

        val result = ForeverCasc.writeIncremental(sourceData, outputData, emptySequence(), { null }, { false })

        assertEquals(0L, result.writtenObjects)
        for (bucket in 0 until 16) {
            assertEquals(0x30000L, File(outputData, "%02x%08x.idx".format(bucket, 2)).length())
        }
        assertEquals(sourceBefore, directFileHashes(sourceData))
        delete(root)
    }

    @Test
    fun incrementalWriterClassifiesGuarded512ByteUpdatePagesButRefusesUnknownSemantics() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "guarded-update-page-fixture".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/update-page.blte", raw)
        val ekey = encodedEkey(raw)
        val objectRecord = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(
            sourceRoot,
            "build\n".toByteArray(),
            emptyList(),
            emptyList(),
            listOf(ForeverCasc.RuntimeObject(objectRecord, rawFile)),
            { false },
        )
        val bucket = bucket(ekey.hexBytes())
        val sourceIndex = File(sourceData, "%02x%08x.idx".format(bucket, 1))
        val bytes = sourceIndex.readBytes()
        val sortedEnd = 0x28 + u32(bytes, 0x20)
        val updateStart = roundUpIndexLength(sortedEnd.toLong()).toInt()
        val update = ByteArray(24)
        ekey.hexBytes().copyOfRange(0, 9).copyInto(update, 4)
        putU32be(update, 14, 0)
        putU32le(update, 18, raw.size.toLong() + 30L)
        update[22] = 2
        val guard = hashlittle(update.copyOfRange(4, 23), 0) or Int.MIN_VALUE
        putU32le(update, 0, guard.toLong() and 0xffffffffL)
        update.copyInto(bytes, updateStart)
        sourceIndex.writeBytes(bytes)
        val sourceBefore = directFileHashes(sourceData)
        val outputData = File(root, "output/Data/data")

        val error = org.junit.Assert.assertThrows(ForeverCasc.UnsupportedV7IndexFormatException::class.java) {
            ForeverCasc.writeIncremental(sourceData, outputData, emptySequence(), { null }, { false })
        }

        assertEquals("UNSUPPORTED_UPDATE_SEMANTICS", error.reasonCode)
        val diagnostic = error.updateDiagnostic!!
        assertEquals("candidate-512-byte-update-pages", diagnostic.classification)
        assertEquals(512, diagnostic.pageSize)
        assertEquals(1, diagnostic.nonemptyRecordCount)
        assertEquals(1, diagnostic.validGuardRecordCount)
        assertEquals(mapOf(2 to 1), diagnostic.statusCounts)
        assertEquals(1, diagnostic.sortedPrefixOverlapCount)
        assertTrue(diagnostic.zeroPadding)
        assertTrue(error.message.orEmpty().contains("precedence"))
        assertFalse(outputData.exists())
        assertEquals(sourceBefore, directFileHashes(sourceData))
        delete(root)
    }

    @Test
    fun targetCanonicalWriterUsesOnlyVerifiedTargetObjectsAndTreatsCacheUpdatesAsHints() {
        val reuseRoot = tempDir()
        val reuse = canonicalHintFixture(reuseRoot, listOf(2 to 0L, 6 to 1L))
        val reuseOutput = File(reuseRoot, "output/Data/data")
        var reuseSourceRequests = 0

        val reuseResult = ForeverCasc.writeTargetIncremental(
            reuse.sourceData,
            reuseOutput,
            sequenceOf(reuse.target),
            { reuseSourceRequests++; reuse.targetRaw },
            { false },
        )

        assertEquals(1L, reuseResult.reusedObjects)
        assertEquals(0L, reuseResult.writtenObjects)
        assertEquals(0, reuseResult.newSegments)
        assertEquals(2L, reuseResult.candidateHintsExamined)
        assertEquals(1L, reuseResult.candidateHintsRejected)
        assertEquals(0, reuseSourceRequests)
        assertEquals(reuse.sourceBefore, directFileHashes(reuse.sourceData))
        assertTrue(indexContainsEkey(reuseOutput, reuse.target.ekey))
        assertFalse(indexContainsEkey(reuseOutput, reuse.unrequestedEkey))
        assertEquals(17, totalIndexRecords(reuseOutput)) // target plus its source segment's 16 headers
        assertTrue(reuseOutput.listFiles()!!.none { it.name.startsWith("data.") })

        val reuseVerifyRoot = assembleTargetView(reuseRoot, reuse.sourceData, reuseOutput)
        ForeverCasc.verifyExisting(reuseVerifyRoot, listOf(reuse.target.toRuntimeSpec()), { false })
        assertEquals(reuse.sourceBefore, directFileHashes(reuse.sourceData))
        delete(reuseRoot)

        val repairRoot = tempDir()
        val repair = canonicalHintFixture(repairRoot, listOf(6 to 1L)) // stale encoded-size hint only
        val repairOutput = File(repairRoot, "output/Data/data")
        var repairSourceRequests = 0

        val repairResult = ForeverCasc.writeTargetIncremental(
            repair.sourceData,
            repairOutput,
            sequenceOf(repair.target),
            { repairSourceRequests++; repair.targetRaw },
            { false },
        )

        assertEquals(0L, repairResult.reusedObjects)
        assertEquals(1L, repairResult.writtenObjects)
        assertEquals(1, repairResult.newSegments)
        assertEquals(1L, repairResult.candidateHintsExamined)
        assertEquals(1L, repairResult.candidateHintsRejected)
        assertEquals(1, repairSourceRequests)
        assertEquals(repair.sourceBefore, directFileHashes(repair.sourceData))
        assertTrue(indexContainsEkey(repairOutput, repair.target.ekey))
        assertFalse(indexContainsEkey(repairOutput, repair.unrequestedEkey))
        assertTrue(File(repairOutput, "data.001").isFile)
        assertFalse(File(repairOutput, "data.000").exists())
        val repairVerifyRoot = assembleTargetView(repairRoot, repair.sourceData, repairOutput)
        ForeverCasc.verifyExisting(repairVerifyRoot, listOf(repair.target.toRuntimeSpec()), { false })
        assertEquals(repair.sourceBefore, directFileHashes(repair.sourceData))
        delete(repairRoot)
    }

    @Test
    fun targetCanonicalWriterFallsBackFromStaleUpdateHintToVerifiedSortedHint() {
        val root = tempDir()
        val fixture = canonicalHintFixture(root, listOf(6 to 1L), removeTargetSorted = false)
        val outputData = File(root, "output/Data/data")
        var sourceRequests = 0

        val result = ForeverCasc.writeTargetIncremental(
            fixture.sourceData,
            outputData,
            sequenceOf(fixture.target),
            { sourceRequests++; fixture.targetRaw },
            { false },
        )

        assertEquals(1L, result.reusedObjects)
        assertEquals(0L, result.writtenObjects)
        assertEquals(2L, result.candidateHintsExamined)
        assertEquals(1L, result.candidateHintsRejected)
        assertEquals(0, sourceRequests)
        assertTrue(indexContainsEkey(outputData, fixture.target.ekey))
        assertFalse(indexContainsEkey(outputData, fixture.unrequestedEkey))
        assertEquals(fixture.sourceBefore, directFileHashes(fixture.sourceData))
        delete(root)
    }

    @Test
    fun targetInspectionPlansNineVerifiedAndOneMissingObjectWithoutProducingOutput() {
        val root = tempDir()
        try {
            val sourceData = File(root, "source/Data/data")
            val existing = (0 until 9).map { index ->
                val decoded = "planner-existing-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "encoded/existing-$index.blte", raw)
                val ekey = encodedEkey(raw)
                val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
                val record = JSONObject().put("encoding_keys", JSONArray().put(ekey))
                    .put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
                spec to ForeverCasc.RuntimeObject(record, rawFile)
            }
            ForeverCasc.materialize(
                File(root, "source"), "planner-nine-one\n".toByteArray(), emptyList(), emptyList(),
                existing.map { it.second }, { false },
            )
            val newDecoded = "planner-new-object".toByteArray()
            val newRaw = blteRaw(newDecoded)
            val newRawFile = file(root, "encoded/new.blte", newRaw)
            val newEkey = encodedEkey(newRaw)
            val newSpec = ForeverCasc.IncrementalObjectSpec(
                newEkey, md5Hex(newDecoded), newRaw.size.toLong(), newDecoded.size.toLong(),
            )
            val target = existing.map { it.first } + newSpec
            val sourceBefore = directFileHashes(sourceData)
            val outputData = File(root, "canonical-output/Data/data")
            val work = File(root, "app-cache/target-plan")
            assertTrue(work.parentFile!!.mkdirs())
            val callbacks = mutableListOf<Pair<String, Boolean>>()

            val plan = ForeverCasc.inspectTarget(sourceData, work, target.asSequence(), { false }) { spec, verified ->
                callbacks += spec.ekey to verified
            }

            assertEquals(10L, plan.totalObjects)
            assertEquals(9L, plan.verifiedObjects)
            assertEquals(1L, plan.missingObjects)
            assertEquals(newRaw.size.toLong(), plan.downloadBytes)
            assertEquals(existing.sumOf { it.first.encodedSize }, plan.reusedEncodedBytes)
            assertEquals(480L + 30L + newRaw.size, plan.newSegmentBytesUpperBound)
            assertEquals(existing.map { it.first.ekey to true } + (newEkey to false), callbacks)
            assertFalse("planning must not create canonical output", outputData.exists())
            assertFalse("owned planner scratch is removed", work.exists())
            assertEquals(sourceBefore, directFileHashes(sourceData))

            val result = ForeverCasc.writeTargetIncremental(
                sourceData,
                outputData,
                target.asSequence(),
                { spec -> if (spec.ekey == newEkey) newRawFile else error("verified object requested as a payload") },
                { false },
            )
            assertEquals(plan.verifiedObjects, result.reusedObjects)
            assertEquals(plan.missingObjects, result.writtenObjects)
            assertEquals(plan.newSegmentBytesUpperBound, File(outputData, "data.001").length())
            assertEquals(sourceBefore, directFileHashes(sourceData))
        } finally {
            delete(root)
        }
    }

    @Test
    fun resumableTargetWriterKeepsVerifiedSegmentAndTruncatesOnlyItsUncheckpointedTail() {
        val root = tempDir()
        val logs = LogCapture()
        val logger = java.util.logging.Logger.getLogger("ForeverClientUpdate")
        logger.addHandler(logs)
        try {
            val objects = (0..1).map { index ->
                val decoded = "resume-object-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            var stopAfterFirst = false
            var sourceCalls = 0
            val commitFlushRequest = AtomicReference<(() -> Boolean)?>(null)
            val interruptedManifest = sequence {
                yield(manifest.first())
                // Model the producer reaching byte-budget backpressure before
                // its next item can arrive. It asks the writer to drain the
                // completed one-object group rather than waiting for a full batch.
                assertTrue(commitFlushRequest.get()?.invoke() == true)
                yield(manifest.last())
            }
            val firstPass = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null,
                    target,
                    checkpointDirectory,
                    interruptedManifest,
                    { spec ->
                        sourceCalls++
                        objects.single { it.first.ekey == spec.ekey }.second
                    },
                    { stopAfterFirst },
                    "catalog-70425|empty-target",
                    { _, raw ->
                        if (raw.exists()) assertTrue(raw.delete())
                        stopAfterFirst = true
                    },
                    commitFlushRequest = commitFlushRequest,
                )
            }
            assertTrue(firstPass is java.util.concurrent.CancellationException)
            val timing = logs.records.map { it.message }.single { it.startsWith("CASC commit final newObjects=1") }
            listOf("segmentTransitionNanos=", "objectHeaderCopyNanos=", "indexPutNanos=", "groupFlushNanos=", "groupForceNanos=").forEach { key ->
                val value = timing.substringAfter(key).substringBefore(' ').toLong()
                assertTrue("$key must be nonnegative", value >= 0L)
            }
            assertEquals(1, sourceCalls)
            val dataSegment = File(target, "data.000")
            val oneObjectEnd = 480L + 30L + objects.first().first.encodedSize
            assertEquals(oneObjectEnd, dataSegment.length())
            val checkpointSummary = ForeverCasc.inspectResumableCheckpoint(
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { false },
                "catalog-70425|empty-target",
            )
            assertEquals(1L, checkpointSummary.completedObjectCount)
            assertEquals(objects.first().first.encodedSize, checkpointSummary.completedEncodedBytes)
            assertEquals(oneObjectEnd, checkpointSummary.retainedSegmentBytes)
            assertEquals(
                objects.first().first.ekey,
                ForeverCasc.withResumableCheckpointObjects(target, checkpointDirectory, "catalog-70425|empty-target", { false }) { staged -> staged.single().ekey },
            )
            val beforeWrongIdentity = dataSegment.readBytes()
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, manifest.asSequence(), { error("wrong identity must fail before download") }, { false },
                    "different-catalog-or-root",
                )
            }
            assertTrue(beforeWrongIdentity.contentEquals(dataSegment.readBytes()))

            // Simulate a process death after bytes reached the owned segment but before
            // the next grouped frame finished appending. The complete frame is
            // retained; only the partial final frame and segment tail are disposable.
            java.io.FileOutputStream(dataSegment, true).use { it.write(byteArrayOf(9, 8, 7, 6)) }
            val checkpointFile = File(checkpointDirectory, ".forever-casc-resume-checkpoint")
            assertEquals(40L + 72L, checkpointFile.length())
            java.io.FileOutputStream(checkpointFile, true).use { it.write(ByteArray(19) { 7 }) }
            assertTrue("unacknowledged raw must remain available", objects.last().second.isFile)
            stopAfterFirst = false
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { spec ->
                    sourceCalls++
                    objects.single { it.first.ekey == spec.ekey }.second
                },
                { false },
                "catalog-70425|empty-target",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
            )
            assertEquals(1L, result.reusedObjects)
            assertEquals(1L, result.writtenObjects)
            assertEquals(2, sourceCalls)
            assertEquals(480L + objects.sumOf { 30L + it.first.encodedSize }, dataSegment.length())
            val completedSummary = ForeverCasc.inspectResumableCheckpoint(
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { false },
                "catalog-70425|empty-target",
            )
            assertEquals(2L, completedSummary.completedObjectCount)
            assertTrue(completedSummary.finalizing)
            assertTrue(completedSummary.finalizingIndexBytes > 0L)

            val installRoot = target.parentFile!!.parentFile!!
            ForeverCasc.verifyExisting(
                installRoot,
                manifest.map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        } finally {
            logger.removeHandler(logs)
            delete(root)
        }
    }

    @Test
    fun checkpointInspectionAuthenticatesUnorderedMembershipWithoutChangingItsBytes() {
        val root = tempDir()
        try {
            val objects = (0..1).map { index ->
                val decoded = "unordered-checkpoint-$index".toByteArray()
                val raw = blteRaw(decoded)
                ForeverCasc.IncrementalObjectSpec(encodedEkey(raw), md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()) to
                    file(root, "private-work/$index.blte", raw)
            }.sortedByDescending { it.first.ekey }
            val target = File(root, "stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpoint = File(root, "checkpoint").also { assertTrue(it.mkdir()) }
            val identity = "admitted-catalog|unchanged-root"
            ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpoint, objects.asSequence().map { it.first },
                { spec -> objects.single { it.first.ekey == spec.ekey }.second }, { false }, identity,
            )
            val before = directFileHashes(checkpoint)
            val stageBefore = directFileHashes(target)
            val byKey = objects.sortedBy { it.first.ekey }.mapIndexed { index, item ->
                item.first.ekey to IndexedValue(index, item.first)
            }.toMap()
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectResumableCheckpoint(target, checkpoint,
                    byKey.values.asSequence().map { it.value }, { false }, identity)
            }
            val summary = ForeverCasc.inspectResumableCheckpoint(
                target, checkpoint, emptySequence(), { false }, identity,
                targetObjectCount = byKey.size, targetMembership = { byKey.getValue(it) },
            )
            assertEquals(2L, summary.completedObjectCount)
            assertEquals(objects.sumOf { it.first.encodedSize }, summary.completedEncodedBytes)
            assertTrue(summary.finalizing)
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectResumableCheckpoint(target, checkpoint, emptySequence(), { false }, identity,
                    targetObjectCount = byKey.size, targetMembership = { key ->
                        val admitted = byKey.getValue(key)
                        IndexedValue(admitted.index, admitted.value.copy(contentKey = "ff".repeat(16)))
                    })
            }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectResumableCheckpoint(target, checkpoint, emptySequence(), { false }, identity,
                    targetObjectCount = byKey.size, targetMembership = { key -> IndexedValue(0, byKey.getValue(key).value) })
            }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectResumableCheckpoint(target, checkpoint, emptySequence(), { false }, identity,
                    targetObjectCount = byKey.size, targetMembership = { throw NoSuchElementException("unknown target key") })
            }
            fun assertWriterRejectsWithoutMutation(
                membership: (String) -> IndexedValue<ForeverCasc.IncrementalObjectSpec>,
            ) {
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        target,
                        checkpoint,
                        emptySequence(),
                        { error("invalid checkpoint must fail before source lookup") },
                        { false },
                        identity,
                        targetObjectCount = byKey.size,
                        targetMembership = membership,
                    )
                }
                assertEquals(before, directFileHashes(checkpoint))
                assertEquals(stageBefore, directFileHashes(target))
            }
            assertWriterRejectsWithoutMutation { key ->
                val admitted = byKey.getValue(key)
                IndexedValue(admitted.index, admitted.value.copy(contentKey = "ff".repeat(16)))
            }
            assertWriterRejectsWithoutMutation { key -> IndexedValue(0, byKey.getValue(key).value) }
            assertEquals(before, directFileHashes(checkpoint))
        } finally { delete(root) }
    }

    @Test
    fun resumableWriterVerifiesObjectsThroughPrivateCheckpointTemporary() {
        val root = tempDir()
        try {
            val objects = (0..1).map { index ->
                val decoded = "private-temp-object-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            val usbTemp = File(target, ".forever-casc-incremental-object.tmp")
            val privateTemp = File(checkpointDirectory, ".forever-casc-incremental-object.tmp")
            val checkpoint = File(checkpointDirectory, ".forever-casc-resume-checkpoint")
            val expectedSegmentSize = 480L + objects.sumOf { 30L + it.first.encodedSize }
            var observedPrivateTemp = false
            var observedUsbTemp = false
            var firstAcknowledgement = true
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                {
                    if (privateTemp.isFile) observedPrivateTemp = true
                    if (usbTemp.exists()) observedUsbTemp = true
                    false
                },
                "catalog-70425|private-temp",
                { _, raw ->
                    // Every acknowledgement follows the full group data force
                    // and checkpoint append; all group raws still exist for
                    // the first acknowledgement.
                    assertEquals(2L, (checkpoint.length() - 40L) / 72L)
                    assertEquals(expectedSegmentSize, File(target, "data.000").length())
                    if (firstAcknowledgement) {
                        assertTrue(objects.all { it.second.isFile })
                        firstAcknowledgement = false
                    }
                    if (raw.exists()) assertTrue(raw.delete())
                },
            )
            assertEquals(0L, result.reusedObjects)
            assertEquals(2L, result.writtenObjects)
            assertTrue("verification temporary must live in the private checkpoint directory", observedPrivateTemp)
            assertFalse("verification temporary must never touch the destination volume", observedUsbTemp)
            assertFalse(privateTemp.exists())
            assertFalse(usbTemp.exists())
            ForeverCasc.verifyExisting(
                target.parentFile!!.parentFile!!,
                manifest.map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        } finally {
            delete(root)
        }
    }

    @Test
    fun resumableWriterRecoversPrivateAndLegacyUsbTemporariesWithValidMarkers() {
        val root = tempDir()
        try {
            val objects = (0..1).map { index ->
                val decoded = "recover-temp-object-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            // Own the stage with a cancelled first pass, as an interrupted
            // download would leave it.
            var stopAfterFirst = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, checkpointedPrefixCut(manifest),
                    { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                    { stopAfterFirst },
                    "catalog-70425|temp-recovery",
                    { _, raw -> if (raw.exists()) assertTrue(raw.delete()); stopAfterFirst = true },
                )
            }
            // A c99 USB-side leftover and a private leftover, both under the
            // same valid ownership, are reclaimed on resume.
            val usbTemp = File(target, ".forever-casc-incremental-object.tmp")
            val privateTemp = File(checkpointDirectory, ".forever-casc-incremental-object.tmp")
            usbTemp.writeBytes(byteArrayOf(4, 5, 6))
            privateTemp.writeBytes(byteArrayOf(1, 2, 3))
            stopAfterFirst = false
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpointDirectory, manifest.asSequence(),
                { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                { false },
                "catalog-70425|temp-recovery",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
            )
            assertEquals(1L, result.reusedObjects)
            assertEquals(1L, result.writtenObjects)
            assertFalse(usbTemp.exists())
            assertFalse(privateTemp.exists())
            assertEquals(480L + objects.sumOf { 30L + it.first.encodedSize }, File(target, "data.000").length())
            ForeverCasc.verifyExisting(
                target.parentFile!!.parentFile!!,
                manifest.map { ForeverCasc.RuntimeObjectSpec(it.ekey, it.contentKey, it.decodedSize) },
                { false },
            )
        } finally {
            delete(root)
        }
    }

    @Test
    fun resumableWriterRejectsOrphanedPrivateTemporaryWithoutTouchingIt() {
        val root = tempDir()
        try {
            val decoded = "orphan-temp-object".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(root, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            // Orphans in both locations: neither gains retroactive authority.
            // The stage directory is pre-created so the legacy orphan has a
            // location; preparation must still refuse before adopting it.
            assertTrue(target.mkdirs())
            val orphan = File(checkpointDirectory, ".forever-casc-incremental-object.tmp")
            orphan.writeBytes(byteArrayOf(9, 9, 9))
            val legacyOrphan = File(target, ".forever-casc-incremental-object.tmp")
            legacyOrphan.writeBytes(byteArrayOf(5, 6))
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile }, { false }, "catalog-70425|orphan-temp",
                )
            }
            assertTrue(error.message.orEmpty().contains("no checkpoint ownership"))
            // No retroactive authority: markers are not created and both
            // foreign files are left byte-identical for manual review.
            assertEquals(listOf(9.toByte(), 9.toByte(), 9.toByte()), orphan.readBytes().toList())
            assertEquals(listOf(5.toByte(), 6.toByte()), legacyOrphan.readBytes().toList())
            assertFalse(File(checkpointDirectory, ".forever-casc-resume-owner").exists())
            assertFalse(File(checkpointDirectory, ".forever-casc-resume-checkpoint").exists())
            assertEquals(listOf(".forever-casc-incremental-object.tmp"), target.listFiles()!!.map { it.name })

            // Owned markers under a different identity do not adopt the
            // temporaries either: the owner/header mismatch fails first and
            // preserves everything.
            val ownedRoot = tempDir()
            try {
                val ownedTarget = File(ownedRoot, "private-work/stage/Data/data")
                assertTrue(ownedTarget.parentFile!!.mkdirs())
                val ownedCheckpoint = File(ownedRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
                ForeverCasc.writeTargetIncrementalResumable(
                    null, ownedTarget, ownedCheckpoint, sequenceOf(spec),
                    { rawFile }, { false }, "catalog-70425|orphan-temp-owner",
                    { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                )
                val ownedPrivate = File(ownedCheckpoint, ".forever-casc-incremental-object.tmp")
                ownedPrivate.writeBytes(byteArrayOf(9, 9, 9))
                val ownedLegacy = File(ownedTarget, ".forever-casc-incremental-object.tmp")
                ownedLegacy.writeBytes(byteArrayOf(5, 6))
                val identityError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null, ownedTarget, ownedCheckpoint, sequenceOf(spec),
                        { rawFile }, { false }, "catalog-70425|different-identity",
                    )
                }
                assertTrue(identityError.message.orEmpty().contains("does not match"))
                assertEquals(listOf(9.toByte(), 9.toByte(), 9.toByte()), ownedPrivate.readBytes().toList())
                assertEquals(listOf(5.toByte(), 6.toByte()), ownedLegacy.readBytes().toList())
                assertTrue(File(ownedCheckpoint, ".forever-casc-resume-owner").isFile)
                assertTrue(File(ownedCheckpoint, ".forever-casc-resume-checkpoint").isFile)
            } finally {
                delete(ownedRoot)
            }
        } finally {
            delete(root)
        }
    }

    @Test
    fun resumableResetRemovesPrivateTemporaryOnlyWithValidOwnership() {
        val roots = mutableListOf<File>()
        try {
            fun ownedStage(identity: String): Triple<File, File, ForeverCasc.IncrementalObjectSpec> {
                val root = tempDir().also { roots += it }
                val decoded = "reset-temp-object".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/object.blte", raw)
                val ekey = encodedEkey(raw)
                val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
                val target = File(root, "private-work/stage/Data/data")
                assertTrue(target.parentFile!!.mkdirs())
                val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile }, { false }, identity,
                    { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                )
                return Triple(target, checkpointDirectory, spec)
            }
            fun markersOf(checkpointDirectory: File) = listOf(
                File(checkpointDirectory, ".forever-casc-resume-owner"),
                File(checkpointDirectory, ".forever-casc-resume-checkpoint"),
            )

            // Legacy no-temporary reset keeps working: only markers are dropped.
            val (_, legacyCheckpoint, _) = ownedStage("catalog-70425|reset-legacy")
            ForeverCasc.resetResumableCheckpoint(legacyCheckpoint, "catalog-70425|reset-legacy")
            assertTrue(markersOf(legacyCheckpoint).none { it.exists() })

            // Owned temporary: removed before the markers, so no crash window
            // can leave scratch without authority.
            val (_, ownedCheckpoint, _) = ownedStage("catalog-70425|reset-owned")
            val ownedTemp = File(ownedCheckpoint, ".forever-casc-incremental-object.tmp")
            ownedTemp.writeBytes(byteArrayOf(1, 2, 3))
            ForeverCasc.resetResumableCheckpoint(ownedCheckpoint, "catalog-70425|reset-owned")
            assertFalse(ownedTemp.exists())
            assertTrue(markersOf(ownedCheckpoint).none { it.exists() })

            // Invalid temporary: rejected with markers and file preserved.
            val (_, guardedCheckpoint, _) = ownedStage("catalog-70425|reset-guarded")
            val guardedTemp = File(guardedCheckpoint, ".forever-casc-incremental-object.tmp")
            val outside = file(guardedCheckpoint.parentFile!!, "outside.bin", byteArrayOf(7, 7))
            java.nio.file.Files.createSymbolicLink(guardedTemp.toPath(), outside.toPath())
            val symlinkError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.resetResumableCheckpoint(guardedCheckpoint, "catalog-70425|reset-guarded")
            }
            assertTrue(symlinkError.message.orEmpty().contains("symlink"))
            assertTrue(java.nio.file.Files.isSymbolicLink(guardedTemp.toPath()))
            assertTrue(markersOf(guardedCheckpoint).all { it.isFile })

            java.nio.file.Files.delete(guardedTemp.toPath())
            java.io.RandomAccessFile(guardedTemp, "rw").use { it.setLength(1L shl 30) }
            val oversizedError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.resetResumableCheckpoint(guardedCheckpoint, "catalog-70425|reset-guarded")
            }
            assertTrue(oversizedError.message.orEmpty().contains("exceeds"))
            assertEquals(1L shl 30, guardedTemp.length())
            assertTrue(markersOf(guardedCheckpoint).all { it.isFile })

            // Temporary without both markers: no authority is acquired to
            // remove it; reset returns without creating markers.
            val bareRoot = tempDir().also { roots += it }
            val bareCheckpoint = File(bareRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val bareTemp = File(bareCheckpoint, ".forever-casc-incremental-object.tmp")
            bareTemp.writeBytes(byteArrayOf(9, 9, 9))
            ForeverCasc.resetResumableCheckpoint(bareCheckpoint, "catalog-70425|reset-bare")
            assertEquals(listOf(9.toByte(), 9.toByte(), 9.toByte()), bareTemp.readBytes().toList())
            assertTrue(markersOf(bareCheckpoint).none { it.exists() })

            // Wrong identity: owner/header mismatch fails with markers and
            // temporary preserved.
            val (_, foreignCheckpoint, _) = ownedStage("catalog-70425|reset-owner")
            val foreignTemp = File(foreignCheckpoint, ".forever-casc-incremental-object.tmp")
            foreignTemp.writeBytes(byteArrayOf(1, 2, 3))
            val identityError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.resetResumableCheckpoint(foreignCheckpoint, "catalog-70425|reset-foreign")
            }
            assertTrue(identityError.message.orEmpty().contains("does not match"))
            assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), foreignTemp.readBytes().toList())
            assertTrue(markersOf(foreignCheckpoint).all { it.isFile })
        } finally {
            roots.forEach { delete(it) }
        }
    }

    @Test
    fun resumableWriterRejectsUnsafePrivateTemporaryWithoutDeletingIt() {
        val root = tempDir()
        try {
            val decoded = "guarded-temp-object".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(root, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            fun runWriter(): IllegalStateException = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile }, { false }, "catalog-70425|guarded-temp",
                    { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                )
            }
            // First own the markers with a complete run.
            ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpointDirectory, sequenceOf(spec),
                { rawFile }, { false }, "catalog-70425|guarded-temp",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
            )
            val privateTemp = File(checkpointDirectory, ".forever-casc-incremental-object.tmp")
            assertFalse(privateTemp.exists())

            // A symlink never gains authority and is never followed.
            val outside = file(root, "outside.bin", byteArrayOf(7, 7))
            java.nio.file.Files.createSymbolicLink(privateTemp.toPath(), outside.toPath())
            val symlinkError = runWriter()
            assertTrue(symlinkError.message.orEmpty().contains("symlink"))
            assertTrue(java.nio.file.Files.isSymbolicLink(privateTemp.toPath()))
            assertEquals(listOf(7.toByte(), 7.toByte()), outside.readBytes().toList())
            assertTrue(privateTemp.delete())

            // A non-regular entry with the exact name is rejected in place.
            assertTrue(privateTemp.mkdir())
            val directoryError = runWriter()
            assertTrue(directoryError.message.orEmpty().contains("not a regular file"))
            assertTrue(privateTemp.isDirectory)
            assertTrue(privateTemp.delete())

            // An oversized leftover (sparse: no disk cost) is rejected, not truncated.
            java.io.RandomAccessFile(privateTemp, "rw").use { it.setLength(1L shl 30) }
            val oversizedError = runWriter()
            assertTrue(oversizedError.message.orEmpty().contains("exceeds"))
            assertEquals(1L shl 30, privateTemp.length())
            assertTrue(privateTemp.delete())

            // Ownership still valid: a retry without the leftover completes,
            // reusing the checkpointed object without a new source.
            val retry = ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpointDirectory, sequenceOf(spec),
                { error("checkpointed object must verify without a new source") },
                { false }, "catalog-70425|guarded-temp",
            )
            assertEquals(1L, retry.reusedObjects)
        } finally {
            delete(root)
        }
    }

    @Test
    fun failedDurableGroupHookRetainsRawAndAllowsIdentityBoundResume() {
        val root = tempDir()
        try {
            val decoded = "group-hook-object".toByteArray()
            val rawBytes = blteRaw(decoded)
            val raw = file(root, "private/raw.blte", rawBytes)
            val spec = ForeverCasc.IncrementalObjectSpec(
                encodedEkey(rawBytes), md5Hex(decoded), rawBytes.size.toLong(), decoded.size.toLong(),
            )
            val output = File(root, "client/Data/data")
            assertTrue(output.parentFile!!.mkdirs())
            val checkpoint = File(root, "private/checkpoint").apply { assertTrue(mkdirs()) }
            var segmentHooks = 0
            var groupHooks = 0
            var consumed = false
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, output, checkpoint, sequenceOf(spec), { raw }, { false }, "same-plan",
                    sourceConsumed = { _, _ -> consumed = true },
                    beforeSegmentOpen = { segmentHooks++ },
                    beforeDurableGroup = { groupHooks++; throw IllegalStateException("identity changed") },
                )
            }
            assertEquals("identity changed", failure.message)
            assertEquals(1, segmentHooks)
            assertEquals("the single-object partial flush is checked", 1, groupHooks)
            assertFalse(consumed)
            assertTrue(raw.isFile)
            val beforeResume = ForeverCasc.inspectResumableCheckpoint(
                output, checkpoint, sequenceOf(spec), { false }, "same-plan",
            )
            assertEquals(0L, beforeResume.completedObjectCount)

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, output, checkpoint, sequenceOf(spec), { raw }, { false }, "same-plan",
                    beforeSegmentOpen = { throw IllegalStateException("resume admission changed") },
                )
            }
            assertTrue("failed resumed segment admission retains the raw", raw.isFile)

            var resumedSegmentHooks = 0
            var resumedGroupHooks = 0
            val resumed = ForeverCasc.writeTargetIncrementalResumable(
                null, output, checkpoint, sequenceOf(spec), { raw }, { false }, "same-plan",
                sourceConsumed = { _, consumedRaw -> assertTrue(consumedRaw.delete()) },
                beforeSegmentOpen = { resumedSegmentHooks++ },
                beforeDurableGroup = { resumedGroupHooks++ },
            )
            assertEquals(1, resumedSegmentHooks)
            assertEquals(1, resumedGroupHooks)
            assertEquals(1L, resumed.writtenObjects)
            assertFalse(raw.exists())
            val afterResume = ForeverCasc.inspectResumableCheckpoint(
                output, checkpoint, sequenceOf(spec), { false }, "same-plan",
            )
            assertEquals(1L, afterResume.completedObjectCount)
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionTreatsCorruptPhysicalHintAsDownloadMiss() {
        val root = tempDir()
        try {
            val fixture = canonicalHintFixture(root, listOf(2 to 0L), removeTargetSorted = false)
            val location = localEntry(fixture.sourceData, fixture.target.ekey)
            RandomAccessFile(File(fixture.sourceData, "data.%03d".format(location.first)), "rw").use { archive ->
                val payloadByte = location.second + 30 + fixture.target.encodedSize - 1
                archive.seek(payloadByte)
                val old = archive.readUnsignedByte()
                archive.seek(payloadByte)
                archive.writeByte(old xor 1)
            }
            val sourceBefore = directFileHashes(fixture.sourceData)
            val work = File(root, "app-cache/corrupt-plan")
            assertTrue(work.parentFile!!.mkdirs())
            val outcomes = mutableListOf<Boolean>()

            val plan = ForeverCasc.inspectTarget(fixture.sourceData, work, sequenceOf(fixture.target), { false }) { _, reused ->
                outcomes += reused
            }

            assertEquals(1L, plan.totalObjects)
            assertEquals(0L, plan.verifiedObjects)
            assertEquals(1L, plan.missingObjects)
            assertEquals(fixture.target.encodedSize, plan.downloadBytes)
            assertEquals(0L, plan.reusedEncodedBytes)
            assertEquals(480L + 30L + fixture.target.encodedSize, plan.newSegmentBytesUpperBound)
            assertEquals(listOf(false), outcomes)
            assertFalse(work.exists())
            assertEquals(sourceBefore, directFileHashes(fixture.sourceData))
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionRejectsUnrecognizedUpdateHintStructureAndRemovesScratch() {
        val root = tempDir()
        try {
            val decoded = "malformed-target-plan-index".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(root, "encoded/malformed-index.blte", raw)
            val ekey = encodedEkey(raw)
            val record = JSONObject().put("encoding_keys", JSONArray().put(ekey))
                .put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
            val sourceRoot = File(root, "source")
            val sourceData = File(sourceRoot, "Data/data")
            ForeverCasc.materialize(sourceRoot, "malformed-target-plan\n".toByteArray(), emptyList(), emptyList(),
                listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
            val index = File(sourceData, "%02x%08x.idx".format(bucket(ekey.hexBytes()), 1))
            RandomAccessFile(index, "rw").use { it.seek(0x1000); it.writeByte(1) }
            val sourceBefore = directFileHashes(sourceData)
            val work = File(root, "app-cache/blocked-plan")
            assertTrue(work.parentFile!!.mkdirs())

            val error = org.junit.Assert.assertThrows(ForeverCasc.UnsupportedV7IndexFormatException::class.java) {
                ForeverCasc.inspectTarget(sourceData, work, emptySequence(), { false }) { _, _ ->
                    error("invalid source index must block the plan before object callbacks")
                }
            }

            assertEquals("UNRECOGNIZED_NONZERO_UPDATE_LAYOUT", error.reasonCode)
            assertFalse(work.exists())
            assertEquals(sourceBefore, directFileHashes(sourceData))
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionAllowsAbsentSourceOnlyAsAllMissingAndKeepsWorkspaceFresh() {
        val root = tempDir()
        try {
            val absentSource = File(root, "empty-client/Data/data")
            val scratchParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val work = File(scratchParent, "empty-target-plan")
            val specs = listOf(
                ForeverCasc.IncrementalObjectSpec("1".repeat(32), "2".repeat(32), 11, 13),
                ForeverCasc.IncrementalObjectSpec("3".repeat(32), "4".repeat(32), 17, 19),
            )

            val plan = ForeverCasc.inspectTarget(absentSource, work, specs.asSequence(), { false }) { _, _ -> }

            assertEquals(2L, plan.totalObjects)
            assertEquals(0L, plan.verifiedObjects)
            assertEquals(2L, plan.missingObjects)
            assertEquals(28L, plan.downloadBytes)
            assertEquals(0L, plan.reusedEncodedBytes)
            assertEquals(28L + 2L * 30L + 480L, plan.newSegmentBytesUpperBound)
            assertFalse(absentSource.exists())
            assertFalse(work.exists())

            val largestEncodedObject = (1L shl 30) - 480L - 30L
            val largeSpecs = listOf(
                ForeverCasc.IncrementalObjectSpec("5".repeat(32), "6".repeat(32), largestEncodedObject, 0),
                ForeverCasc.IncrementalObjectSpec("7".repeat(32), "8".repeat(32), largestEncodedObject, 0),
            )
            val largePlan = ForeverCasc.inspectTarget(
                null,
                File(scratchParent, "large-target-plan"),
                largeSpecs.asSequence(),
                { false },
            ) { _, _ -> }
            assertEquals(2L, largePlan.totalObjects)
            assertEquals(2L * largestEncodedObject, largePlan.downloadBytes)
            assertEquals(0L, largePlan.reusedEncodedBytes)
            assertEquals(2L * (largestEncodedObject + 30L + 480L), largePlan.newSegmentBytesUpperBound)

            val preexisting = File(scratchParent, "caller-data").also { assertTrue(it.mkdirs()) }
            file(preexisting, "keep.bin", byteArrayOf(1, 2, 3))
            val rejectedWork = File(preexisting, "keep.bin")
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTarget(null, rejectedWork, emptySequence(), { false }) { _, _ -> }
            }
            assertEquals(listOf("keep.bin"), preexisting.listFiles()!!.map { it.name })
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionRejectsDuplicateAndShortPrefixCollisionAndCleansScratch() {
        val root = tempDir()
        try {
            val ekey = "123456789abcdef012" + "0".repeat(14)
            val duplicate = ForeverCasc.IncrementalObjectSpec(ekey, "a".repeat(32), 7, 9)
            val collision = duplicate.copy(ekey = ekey.substring(0, 18) + "1".repeat(14))
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            var callbacks = 0
            val duplicateError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.inspectTarget(null, File(workParent, "duplicate"), sequenceOf(duplicate, duplicate), { false }) { _, _ ->
                    callbacks++
                }
            }
            assertTrue(duplicateError.message.orEmpty().contains("duplicate EKey"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "duplicate").exists())

            callbacks = 0
            val collisionError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTarget(null, File(workParent, "collision"), sequenceOf(duplicate, collision), { false }) { _, _ ->
                    callbacks++
                }
            }
            assertTrue(collisionError.message.orEmpty().contains("short EKey prefix collision"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "collision").exists())
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionCancellationRemovesOnlyItsOwnedScratch() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val work = File(workParent, "cancelled-plan")
            val unrelated = file(workParent, "caller-owned.bin", byteArrayOf(9, 8, 7))
            val specs = sequence {
                yield(ForeverCasc.IncrementalObjectSpec("5".repeat(32), "6".repeat(32), 3, 4))
                yield(ForeverCasc.IncrementalObjectSpec("7".repeat(32), "8".repeat(32), 5, 6))
            }
            var cancelNext = false
            var callbacks = 0

            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.inspectTarget(null, work, specs, { cancelNext }) { _, _ ->
                    callbacks++
                    cancelNext = true
                }
            }

            assertEquals(1, callbacks)
            assertFalse(work.exists())
            assertEquals(listOf(9.toByte(), 8.toByte(), 7.toByte()), unrelated.readBytes().toList())
        } finally {
            delete(root)
        }
    }

    @Test
    fun targetInspectionRejectsArchiveIdOverflowBeforeRequestingTheNextObject() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val work = File(workParent, "archive-overflow-plan")
            val maximumObject = (1L shl 30) - 480L - 30L
            val specs = sequence {
                for (index in 1..1025) {
                    yield(
                        ForeverCasc.IncrementalObjectSpec(
                            "%018x".format(index) + "0".repeat(14),
                            "0".repeat(32),
                            maximumObject,
                            0,
                        ),
                    )
                }
            }
            var callbacks = 0

            val oversizedSpec = ForeverCasc.IncrementalObjectSpec(
                "9".repeat(32),
                "0".repeat(32),
                maximumObject + 1L,
                0,
            )
            val oversizedError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.inspectTarget(null, File(workParent, "oversized-plan"), sequenceOf(oversizedSpec), { false }) { _, _ ->
                    callbacks++
                }
            }
            assertTrue(oversizedError.message.orEmpty().contains("archive limit"))
            assertEquals(0, callbacks)
            assertFalse(File(workParent, "oversized-plan").exists())

            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTarget(null, work, specs, { false }) { _, _ -> callbacks++ }
            }

            assertTrue(error.message.orEmpty().contains("archive id capacity"))
            assertEquals(1024, callbacks)
            assertFalse(work.exists())
        } finally {
            delete(root)
        }
    }

    @Test
    fun cachedNativeIndexDiagnosticIsOptInAndFailsClosedWithStructuredEvidence() {
        val samplePath = System.getProperty("wowforever.cascNativeIndexDiagnosticFixture")
        org.junit.Assume.assumeTrue("set wowforever.cascNativeIndexDiagnosticFixture to the cached read-only sample", samplePath != null)
        val sample = File(samplePath!!)
        assertEquals("ef6b9a2675aaa99582315e4c13b1de3dd6c1e05b7a3f28ddcbb70d2a8a11bbe6", sha256Hex(sample.readBytes()))
        val root = tempDir()
        try {
            val dataFiles = File(root, "Data/data").also { assertTrue(it.mkdirs()) }
            sample.copyTo(File(dataFiles, "0000000001.idx"))
            file(dataFiles, "data.000", ByteArray(0))
            val output = File(root, "output/Data/data")

            val error = org.junit.Assert.assertThrows(ForeverCasc.UnsupportedV7IndexFormatException::class.java) {
                ForeverCasc.writeIncremental(dataFiles, output, emptySequence(), { null }, { false })
            }

            assertEquals("CONFLICTING_SORTED_PREFIX_RECORDS", error.reasonCode)
            assertEquals(1, error.sortedPrefixConflictCount)
            val diagnostic = error.updateDiagnostic!!
            assertEquals("candidate-512-byte-update-pages", diagnostic.classification)
            assertEquals(512, diagnostic.pageSize)
            assertEquals(96, diagnostic.pageCount)
            assertEquals(461, diagnostic.nonemptyRecordCount)
            assertEquals(461, diagnostic.validGuardRecordCount)
            assertEquals(mapOf(2 to 108, 6 to 353), diagnostic.statusCounts)
            assertEquals(115, diagnostic.pageLocalDuplicatePrefixGroups)
            assertEquals(337, diagnostic.pageLocalDuplicateExtraRecords)
            assertEquals(0, diagnostic.sortedPrefixOverlapCount)
            assertTrue(diagnostic.zeroPadding)
            assertTrue(error.message.orEmpty().contains("precedence"))
            assertFalse(output.exists())
        } finally {
            delete(root)
        }
    }

    @Test
    fun incrementalWriterRejectsReservedZeroPrefixSpecWithoutRequestingPayload() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "reserved-index-prefix".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/reserved-prefix.blte", raw)
        val ekey = encodedEkey(raw)
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        val before = directFileHashes(sourceData)
        val output = File(root, "output/Data/data")
        var sourceRequested = false
        val invalid = ForeverCasc.IncrementalObjectSpec("0".repeat(32), md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())

        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ForeverCasc.writeIncremental(sourceData, output, sequenceOf(invalid), { sourceRequested = true; rawFile }, { false })
        }

        assertTrue(error.message.orEmpty().contains("reserved all-zero 9-byte index prefix"))
        assertFalse(sourceRequested)
        assertEquals(before, directFileHashes(sourceData))
        assertTrue(output.listFiles().orEmpty().isEmpty())
        delete(root)
    }

    @Test
    fun incrementalCancellationCleansOnlyItsScratchAndLeavesSourceUntouched() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "cancel-index-scan".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/cancel.blte", raw)
        val ekey = encodedEkey(raw)
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })
        val before = directFileHashes(sourceData)
        val output = File(root, "cancelled/Data/data")
        var checks = 0

        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            ForeverCasc.writeIncremental(sourceData, output, emptySequence(), { null }, { output.exists() && ++checks >= 10 })
        }

        assertTrue(output.isDirectory)
        assertEquals(emptyList<String>(), output.listFiles().orEmpty().map { it.name })
        assertEquals(before, directFileHashes(sourceData))
        delete(root)
    }

    @Test
    fun incrementalWriterRejectsOversizedArchiveObjectBeforeRequestingSource() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "archive-boundary".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/base.blte", raw)
        val baseKey = encodedEkey(raw)
        val baseRecord = JSONObject().put("encoding_keys", JSONArray().put(baseKey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(baseRecord, rawFile)), { false })
        val before = directFileHashes(sourceData)
        val oversizedKey = "f".repeat(32)
        val spec = ForeverCasc.IncrementalObjectSpec(
            oversizedKey,
            md5Hex(decoded),
            (1L shl 30) - 480 - 30 + 1,
            decoded.size.toLong(),
        )
        val outputData = File(root, "output/Data/data")
        var requestedSource = false

        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ForeverCasc.writeIncremental(sourceData, outputData, sequenceOf(spec), { requestedSource = true; rawFile }, { false })
        }

        assertTrue(error.message!!.contains("archive limit"))
        assertFalse(requestedSource)
        assertEquals(before, directFileHashes(sourceData))
        assertEquals(emptyList<String>(), outputData.listFiles()!!.map { it.name })
        delete(root)
    }

    @Test
    fun incrementalWriterRejectsSymlinkedOutputParentsAndSourceSegments() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val decoded = "no-follow-casc".toByteArray()
        val raw = blteRaw(decoded)
        val rawFile = file(root, "encoded/base.blte", raw)
        val ekey = encodedEkey(raw)
        val record = JSONObject().put("encoding_keys", JSONArray().put(ekey)).put("decoded_size", decoded.size).put("content_key", md5Hex(decoded))
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(record, rawFile)), { false })

        val outside = file(root, "outside/marker", byteArrayOf(7, 8, 9))
        val replacementDecoded = "symlinked-replacement".toByteArray()
        val replacementRaw = blteRaw(replacementDecoded)
        val replacementLink = File(root, "linked-replacement.blte")
        java.nio.file.Files.createSymbolicLink(replacementLink.toPath(), outside.toPath())
        val replacementSpec = ForeverCasc.IncrementalObjectSpec(
            encodedEkey(replacementRaw), md5Hex(replacementDecoded), replacementRaw.size.toLong(), replacementDecoded.size.toLong(),
        )
        val sourceLinkOutput = File(root, "source-link-output/Data/data")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeIncremental(sourceData, sourceLinkOutput, sequenceOf(replacementSpec), { replacementLink }, { false })
        }
        assertEquals(listOf(7.toByte(), 8.toByte(), 9.toByte()), outside.readBytes().toList())
        assertEquals(emptyList<String>(), sourceLinkOutput.listFiles()!!.map { it.name })

        val outputLink = File(root, "linked-output")
        java.nio.file.Files.createSymbolicLink(outputLink.toPath(), outside.parentFile.toPath())
        val linkedOutput = File(outputLink, "Data/data")
        val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeIncremental(sourceData, linkedOutput, sequenceOf(spec), { null }, { false })
        }
        assertEquals(listOf(7.toByte(), 8.toByte(), 9.toByte()), outside.readBytes().toList())

        val archive = File(sourceData, "data.000")
        val movedArchive = File(sourceData, "data.saved")
        assertTrue(archive.renameTo(movedArchive))
        java.nio.file.Files.createSymbolicLink(archive.toPath(), outside.toPath())
        val linkedSourceOutput = File(root, "safe-output/Data/data")
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeIncremental(sourceData, linkedSourceOutput, sequenceOf(spec), { null }, { false })
        }
        assertEquals(listOf(7.toByte(), 8.toByte(), 9.toByte()), outside.readBytes().toList())
        assertFalse(linkedSourceOutput.exists())
        delete(root)
    }

    @Test
    fun incrementalWriterRejectsWholeObjectHashAsChunkedEkey() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val baseDecoded = "chunked-ekey-base".toByteArray()
        val baseRaw = blteRaw(baseDecoded)
        val baseFile = file(root, "encoded/base.blte", baseRaw)
        val baseKey = encodedEkey(baseRaw)
        val baseRecord = JSONObject().put("encoding_keys", JSONArray().put(baseKey)).put("decoded_size", baseDecoded.size).put("content_key", md5Hex(baseDecoded))
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(baseRecord, baseFile)), { false })
        val sourceBefore = directFileHashes(sourceData)

        val decoded = "chunked-wrong-ekey".toByteArray()
        val raw = blteChunked(decoded)
        val source = file(root, "encoded/chunked.blte", raw)
        val correctEkey = encodedEkey(raw)
        val incorrectEkey = md5Hex(raw)
        assertFalse(correctEkey == incorrectEkey)
        val spec = ForeverCasc.IncrementalObjectSpec(incorrectEkey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
        val outputData = File(root, "output/Data/data")

        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForeverCasc.writeIncremental(sourceData, outputData, sequenceOf(spec), { source }, { false })
        }

        assertTrue(error.message!!.contains("raw BLTE EKey mismatch"))
        assertEquals(sourceBefore, directFileHashes(sourceData))
        assertEquals(emptyList<String>(), outputData.listFiles()!!.map { it.name })
        delete(root)
    }

    @Test
    fun incrementalWriterBoundsChunkedEkeyHeaderAndChunkCount() {
        val root = tempDir()
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val baseDecoded = "bounded-header-base".toByteArray()
        val baseRaw = blteRaw(baseDecoded)
        val baseFile = file(root, "encoded/base.blte", baseRaw)
        val baseKey = encodedEkey(baseRaw)
        val baseRecord = JSONObject().put("encoding_keys", JSONArray().put(baseKey)).put("decoded_size", baseDecoded.size).put("content_key", md5Hex(baseDecoded))
        ForeverCasc.materialize(sourceRoot, "build-A\n".toByteArray(), emptyList(), emptyList(), listOf(ForeverCasc.RuntimeObject(baseRecord, baseFile)), { false })
        val sourceBefore = directFileHashes(sourceData)
        val overlong = byteArrayOf(0x42, 0x4c, 0x54, 0x45) + u32beBytes(12)
        val excessiveCountHeaderSize = 12 + 8_193 * 24
        val excessiveCount = ByteArray(excessiveCountHeaderSize).also { bytes ->
            byteArrayOf(0x42, 0x4c, 0x54, 0x45).copyInto(bytes)
            u32beBytes(excessiveCountHeaderSize).copyInto(bytes, 4)
            byteArrayOf(0x0f, 0, 0x20, 0x01).copyInto(bytes, 8)
        }
        val badSources = listOf(overlong, excessiveCount).mapIndexed { index, bytes ->
            val file = file(root, "encoded/malformed-$index.blte", bytes)
            val spec = ForeverCasc.IncrementalObjectSpec(md5Hex(bytes), md5Hex(ByteArray(0)), bytes.size.toLong(), 0)
            file to spec
        }

        badSources.forEachIndexed { index, (source, spec) ->
            val outputData = File(root, "malformed-$index/Data/data")
            val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.writeIncremental(sourceData, outputData, sequenceOf(spec), { source }, { false })
            }
            val expected = if (index == 0) "invalid or oversized BLTE EKey header" else "chunk table exceeds safety limit"
            assertTrue(error.message.orEmpty().contains(expected))
            assertEquals(emptyList<String>(), outputData.listFiles()!!.map { it.name })
        }
        assertEquals(sourceBefore, directFileHashes(sourceData))
        delete(root)
    }

    private data class CanonicalHintFixture(
        val sourceData: File,
        val target: ForeverCasc.IncrementalObjectSpec,
        val targetRaw: File,
        val unrequestedEkey: String,
        val sourceBefore: Map<String, String>,
    )

    /**
     * FAKE staged-segment reader for contract and lifetime tests only. It
     * mirrors the CascReplayReader protocol (one open segment, path-vs-FD
     * binding on fresh fileKey/size observations, bounded positional reads,
     * close-before-write) on top of host RandomAccessFile. It proves the
     * writer-side protocol; it never proves the Android Os/FUSE physical
     * binding, which stays gated on device evidence.
     */
    /** Cut the source stream precisely, independently of durable group size. */
    private fun checkpointedPrefixCut(
        manifest: List<ForeverCasc.IncrementalObjectSpec>, count: Int = 1,
    ): Sequence<ForeverCasc.IncrementalObjectSpec> = sequence {
        yieldAll(manifest.take(count))
        throw java.util.concurrent.CancellationException("synthetic prefix cut")
    }

    private class FakeCascReplayReader(
        private val cancelled: () -> Boolean = { false },
    ) : CascReplayReader {
        private var currentPath: String? = null
        private var input: RandomAccessFile? = null
        private var boundKey: String? = null
        private var boundLength: Long = -1L
        var segmentsOpened = 0
            private set
        var bindingsChecked = 0
            private set
        var readsServed = 0L
            private set
        var closed = false
            private set
        /** When non-null, [close] detaches first and then throws this. */
        var closeError: Throwable? = null
        var closeCalls = 0
            private set

        override fun ensureSegment(file: File) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException("FAKE staged segment is a symlink")
            if (input != null && currentPath == file.absolutePath) return
            closeCurrent()
            if (!file.isFile) throw IllegalStateException("FAKE staged segment is not a regular file")
            currentPath = file.absolutePath
            input = RandomAccessFile(file, "r")
            // Identity is the inode alone, mirroring the production
            // dev/ino proof: append-growth keeps the inode, so a grown
            // segment passes the covering pre-check and only the exact post
            // sealed proof rejects it. Length is checked separately.
            boundKey = identityOf(file)
            boundLength = file.length()
            segmentsOpened++
        }

        override fun checkBinding(endExclusive: Long) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            if (endExclusive < 0 || endExclusive > (1L shl 30)) throw IllegalStateException("FAKE binding outside archive bound")
            val open = input ?: throw IllegalStateException("FAKE replay reader is closed")
            open.seek(0)
            val path = requireNotNull(currentPath) { "FAKE replay reader has no bound segment" }
            val file = File(path)
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) throw IllegalStateException("FAKE staged binding changed")
            if (identityOf(file) != boundKey) {
                throw IllegalStateException("FAKE staged binding changed under its reader")
            }
            if (file.length() < endExclusive) throw IllegalStateException("FAKE staged segment no longer covers its object end")
            bindingsChecked++
        }

        override fun checkSealedBinding(exactSize: Long) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            if (exactSize < 0 || exactSize > (1L shl 30)) throw IllegalStateException("FAKE sealed binding outside archive bound")
            val open = input ?: throw IllegalStateException("FAKE replay reader is closed")
            open.seek(0)
            val path = requireNotNull(currentPath) { "FAKE replay reader has no bound segment" }
            val file = File(path)
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) throw IllegalStateException("FAKE staged segment binding changed")
            if (identityOf(file) != boundKey) {
                throw IllegalStateException("FAKE sealed binding changed under its reader")
            }
            if (file.length() != exactSize) throw IllegalStateException("FAKE sealed segment size is not its frozen group end")
            bindingsChecked++
        }

        override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int {
            if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
                throw IndexOutOfBoundsException("FAKE replay read is outside its buffer")
            }
            if (position < 0) throw IllegalArgumentException("negative FAKE replay read position")
            val open = input ?: throw IllegalStateException("FAKE replay reader is closed")
            if (len == 0) return 0
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            open.seek(position)
            val count = open.read(buffer, off, len)
            if (count < 0) return 0
            readsServed = Math.addExact(readsServed, count.toLong())
            return count
        }

        override fun close() {
            closeCalls++
            closeCurrent()
            closed = true
            closeError?.let { throw it }
        }

        private fun closeCurrent() {
            try {
                input?.close()
            } finally {
                input = null
                currentPath = null
            }
        }

        private fun identityOf(file: File): String {
            val attributes = Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            // Inode alone, mirroring the production dev/ino proof. Length is
            // a separate covering/exact assertion, never part of identity.
            return attributes.fileKey()?.toString() ?: file.absolutePath
        }
    }

    @Test
    fun stagedReplayReaderReusesResumedPrefixByteIdenticalToLegacyPath() {
        data class ResumeOutcome(
            val reused: Long,
            val written: Long,
            val segment: ByteArray,
            val sourceCalls: Int,
            val fakes: List<FakeCascReplayReader>,
        )
        fun resume(root: File, withReader: Boolean): ResumeOutcome {
            val objects = (0..2).map { index ->
                val decoded = "replay-reader-object-$index".toByteArray()
                val raw = if (index == 1) blteChunked(decoded) else blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            var stopAfterFirst = false
            var sourceCalls = 0
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null,
                    target,
                    checkpointDirectory,
                    checkpointedPrefixCut(manifest),
                    { spec ->
                        sourceCalls++
                        objects.single { it.first.ekey == spec.ekey }.second
                    },
                    { stopAfterFirst },
                    "catalog-70425|replay-reader-equivalence",
                    { _, raw ->
                        if (raw.exists()) assertTrue(raw.delete())
                        stopAfterFirst = true
                    },
                )
            }
            assertEquals(1, sourceCalls)
            stopAfterFirst = false
            val fakes = mutableListOf<FakeCascReplayReader>()
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                target,
                checkpointDirectory,
                manifest.asSequence(),
                { spec ->
                    sourceCalls++
                    objects.single { it.first.ekey == spec.ekey }.second
                },
                { false },
                "catalog-70425|replay-reader-equivalence",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                replayReaderFactory = if (withReader) {
                    { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } }
                } else {
                    null
                },
            )
            ForeverCasc.verifyExisting(
                target.parentFile!!.parentFile!!,
                manifest.map { it.toRuntimeSpec() },
                { false },
            )
            return ResumeOutcome(result.reusedObjects, result.writtenObjects, File(target, "data.000").readBytes(), sourceCalls, fakes)
        }
        val readerRoot = tempDir()
        val legacyRoot = tempDir()
        try {
            val reader = resume(readerRoot, true)
            val legacy = resume(legacyRoot, false)
            assertEquals(1L, reader.reused)
            assertEquals(2L, reader.written)
            assertEquals(legacy.reused, reader.reused)
            assertEquals(legacy.written, reader.written)
            assertEquals(legacy.segment.size, reader.segment.size)
            // The 480-byte reconstruction header binds the absolute staging
            // path, so only the object region (headers plus payloads) is
            // comparable across roots.
            assertTrue(reader.segment.copyOfRange(480, reader.segment.size).contentEquals(legacy.segment.copyOfRange(480, legacy.segment.size)))
            // One pre-cancel fetch plus two genuine misses: the checkpointed
            // object never reached sourceFor in reader mode either.
            assertEquals(3, reader.sourceCalls)
            assertEquals(3, legacy.sourceCalls)
            val fake = reader.fakes.single()
            assertEquals(1, fake.segmentsOpened)
            assertEquals(2, fake.bindingsChecked)
            assertTrue(fake.readsServed > 0)
            assertTrue(fake.closed)
        } finally {
            delete(readerRoot)
            delete(legacyRoot)
        }
    }

    @Test
    fun stagedReplayReaderRejectsCorruptStagedBytesWithoutFallback() {
        // Each entry: name, corruption, expected rejection, and whether the
        // replay reader must have been created (load-time guards reject a
        // short or shrunken segment before any flags=2 object is reached, so
        // the reader is then correctly never built).
        data class Corruption(val corrupt: (File, Long) -> Unit, val expectedMessage: String, val expectReader: Boolean)
        val corruptions = listOf(
            "payload-flip" to Corruption(
                { target, firstEncodedSize ->
                    RandomAccessFile(File(target, "data.000"), "rw").use { archive ->
                        val at = 480L + 30L + firstEncodedSize - 1L
                        archive.seek(at)
                        val original = archive.readUnsignedByte()
                        archive.seek(at)
                        archive.writeByte(original xor 1)
                    }
                },
                "checkpointed CASC object failed",
                true,
            ),
            "truncated-segment" to Corruption(
                { target, firstEncodedSize ->
                    // Cut one byte off the CHECKPOINTED object itself: cutting
                    // only unwritten tail bytes would leave the staged prefix
                    // intact and must (correctly) not fail.
                    val segment = File(target, "data.000")
                    RandomAccessFile(segment, "rw").use { it.setLength(480L + 30L + firstEncodedSize - 1L) }
                },
                "segment size disagrees with its checkpoint",
                false,
            ),
            "header-damage" to Corruption(
                { target, _ ->
                    RandomAccessFile(File(target, "data.000"), "rw").use { archive ->
                        archive.seek(480L + 5L)
                        val original = archive.readUnsignedByte()
                        archive.seek(480L + 5L)
                        archive.writeByte(original xor 1)
                    }
                },
                "checkpointed CASC object failed",
                true,
            ),
            "symlink-swap" to Corruption(
                { target, firstEncodedSize ->
                    // Same-length decoy so the load-time size guard passes;
                    // the owned-segment header guard (NOFOLLOW) then refuses
                    // the symlink before any reader is built.
                    val segment = File(target, "data.000")
                    val stagedLength = 480L + 30L + firstEncodedSize
                    assertEquals(stagedLength, segment.length())
                    val decoy = File(target, "decoy.blte").also { it.writeBytes(ByteArray(stagedLength.toInt()) { 7 }) }
                    assertTrue(segment.delete())
                    Files.createSymbolicLink(segment.toPath(), decoy.toPath())
                },
                "resumable CASC segment header is missing or truncated",
                false,
            ),
            "recreate-whole-garbage" to Corruption(
                { target, _ ->
                    // The owned-segment header guard catches a fully
                    // replaced segment at load, before any reader exists.
                    val segment = File(target, "data.000")
                    val length = segment.length()
                    assertTrue(segment.delete())
                    segment.writeBytes(ByteArray(length.toInt()) { (it and 0xff).toByte() })
                },
                "resumable CASC segment header does not match the owned output identity",
                false,
            ),
        )
        corruptions.forEach { (name, corruption) ->
            val root = tempDir()
            try {
                val objects = (0..1).map { index ->
                    val decoded = "replay-corrupt-$name-$index".toByteArray()
                    val raw = blteRaw(decoded)
                    val rawFile = file(root, "private-work/$index.blte", raw)
                    val ekey = encodedEkey(raw)
                    Triple(
                        ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                        rawFile,
                        raw,
                    )
                }
                val target = File(root, "private-work/stage/Data/data")
                assertTrue(target.parentFile!!.mkdirs())
                val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
                val manifest = objects.map { it.first }
                var stopAfterFirst = false
                org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null, target, checkpointDirectory, checkpointedPrefixCut(manifest),
                        { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                        { stopAfterFirst },
                        "catalog-70425|replay-corrupt-$name",
                        { _, raw ->
                            if (raw.exists()) assertTrue(raw.delete())
                            stopAfterFirst = true
                        },
                    )
                }
                corruption.corrupt(target, objects.first().first.encodedSize)
                var sourceCalls = 0
                val fakes = mutableListOf<FakeCascReplayReader>()
                val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null, target, checkpointDirectory, manifest.asSequence(),
                        { spec ->
                            sourceCalls++
                            objects.single { it.first.ekey == spec.ekey }.second
                        },
                        { false },
                        "catalog-70425|replay-corrupt-$name",
                        { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                        replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
                    )
                }
                assertTrue("corruption $name must fail staged verification", error.message.orEmpty().contains(corruption.expectedMessage))
                assertEquals("corruption $name must not fall back to the copy verifier or a download", 0, sourceCalls)
                if (corruption.expectReader) {
                    assertTrue("corruption $name must still release the reader", fakes.isNotEmpty() && fakes.all { it.closed })
                } else {
                    assertTrue("corruption $name must reject before any reader exists", fakes.isEmpty())
                }
            } finally {
                delete(root)
            }
        }
    }

    @Test
    fun stagedReplayReaderRejectsMidReplayIdentitySwap() {
        val root = tempDir()
        try {
            val objects = (0..2).map { index ->
                val decoded = "replay-mid-swap-$index".toByteArray()
                val raw = if (index == 1) blteChunked(decoded) else blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            // Pass 1 commits two objects, then cancels: the resume below
            // replays a two-object checkpointed prefix.
            var commits = 0
            var stopAfterTwo = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, checkpointedPrefixCut(manifest, 2),
                    { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                    { stopAfterTwo },
                    "catalog-70425|replay-mid-swap",
                    { _, raw ->
                        if (raw.exists()) assertTrue(raw.delete())
                        if (++commits >= 2) stopAfterTwo = true
                    },
                )
            }
            assertEquals(2, commits)
            // Rewrite byte-identical segment bytes under a new inode once
            // object 0 is fully served: content checks cannot see the swap,
            // only the pre/post path-vs-FD binding of object 1 can.
            val firstEncodedSize = objects[0].first.encodedSize
            val fakes = mutableListOf<FakeCascReplayReader>()
            var swapped = false
            var sourceCalls = 0
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, manifest.asSequence(),
                    { spec ->
                        sourceCalls++
                        objects.single { it.first.ekey == spec.ekey }.second
                    },
                    {
                        if (!swapped && fakes.sumOf { it.readsServed } >= 30L + firstEncodedSize) {
                            val segment = File(target, "data.000")
                            val bytes = segment.readBytes()
                            val before = Files.readAttributes(
                                segment.toPath(),
                                java.nio.file.attribute.BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).fileKey()
                            assertTrue(segment.delete())
                            segment.writeBytes(bytes)
                            val after = Files.readAttributes(
                                segment.toPath(),
                                java.nio.file.attribute.BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).fileKey()
                            assertTrue("mid-replay swap needs filesystem file identity", before != null && after != null && before != after)
                            swapped = true
                        }
                        false
                    },
                    "catalog-70425|replay-mid-swap",
                    { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                    replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
                )
            }
            assertTrue(error.message.orEmpty().contains("checkpointed CASC object failed"))
            assertTrue(swapped)
            assertEquals(0, sourceCalls)
            assertTrue(fakes.isNotEmpty() && fakes.all { it.closed })
        } finally {
            delete(root)
        }
    }

    @Test
    fun stagedReplayReaderCloseFailuresPreservePrimaryOutcome() {
        val closeFailure = IllegalStateException("FAKE close failure")
        // Case A: replay succeeds, then the owned reader close fails. The
        // close error must propagate (nothing masks it), the reader close
        // must have been attempted exactly once, no FD may leak (the
        // archive append FD opened at resume must still have been closed),
        // and no verification temporary may remain.
        val successRoot = tempDir()
        try {
            val decoded = "replay-close-success".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(successRoot, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(successRoot, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(successRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpointDirectory, sequenceOf(spec),
                { rawFile }, { false }, "catalog-70425|replay-close",
                { _, transferred -> if (transferred.exists()) assertTrue(transferred.delete()) },
            )
            val fakes = mutableListOf<FakeCascReplayReader>()
            val fdsBefore = openFdCount()
            assertTrue(fdsBefore >= 0)
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { error("replayed object must not resolve a download source") },
                    { false },
                    "catalog-70425|replay-close",
                    { _, transferred -> if (transferred.exists()) assertTrue(transferred.delete()) },
                    replayReaderFactory = { cancelled ->
                        FakeCascReplayReader(cancelled).also { it.closeError = closeFailure; fakes += it }
                    },
                )
            }
            assertTrue(error === closeFailure)
            val fake = fakes.single()
            assertEquals(1, fake.closeCalls)
            assertTrue(fake.closed)
            assertEquals(fdsBefore, openFdCount())
            assertFalse(File(target, ".forever-casc-incremental-object.tmp").exists())
            assertFalse(File(checkpointDirectory, ".forever-casc-incremental-object.tmp").exists())
            // The staged object itself verified before the close failed.
            assertEquals(
                1L,
                ForeverCasc.inspectResumableCheckpoint(
                    target, checkpointDirectory, sequenceOf(spec), { false }, "catalog-70425|replay-close",
                ).completedObjectCount,
            )
        } finally {
            delete(successRoot)
        }

        // Case B: the staged object is corrupt (primary failure) and the
        // reader close also fails. The primary staged-corrupt error must be
        // preserved with the close failure only as a suppressed diagnostic.
        val corruptRoot = tempDir()
        try {
            val decoded = "replay-close-corrupt".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(corruptRoot, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(corruptRoot, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(corruptRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            var stopAfterFirst = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile },
                    { stopAfterFirst },
                    "catalog-70425|replay-close-corrupt",
                    { _, transferred ->
                        if (transferred.exists()) assertTrue(transferred.delete())
                        stopAfterFirst = true
                    },
                )
            }
            RandomAccessFile(File(target, "data.000"), "rw").use { archive ->
                val at = 480L + 30L + spec.encodedSize - 1L
                archive.seek(at)
                val original = archive.readUnsignedByte()
                archive.seek(at)
                archive.writeByte(original xor 1)
            }
            val fakes = mutableListOf<FakeCascReplayReader>()
            val fdsBefore = openFdCount()
            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { error("corrupt staged object must not resolve a download source") },
                    { false },
                    "catalog-70425|replay-close-corrupt",
                    { _, transferred -> if (transferred.exists()) assertTrue(transferred.delete()) },
                    replayReaderFactory = { cancelled ->
                        FakeCascReplayReader(cancelled).also { it.closeError = closeFailure; fakes += it }
                    },
                )
            }
            assertTrue(error.message.orEmpty().contains("checkpointed CASC object failed"))
            assertTrue(error.suppressed.any { it === closeFailure })
            val fake = fakes.single()
            assertEquals(1, fake.closeCalls)
            assertEquals(fdsBefore, openFdCount())
        } finally {
            delete(corruptRoot)
        }

        // Case C: cancellation is the primary failure and the reader close
        // also fails. Cancellation must propagate with the close failure
        // only as a suppressed diagnostic.
        val cancelRoot = tempDir()
        try {
            val decoded = "replay-close-cancel".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(cancelRoot, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(cancelRoot, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(cancelRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            var stopAfterFirst = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile },
                    { stopAfterFirst },
                    "catalog-70425|replay-close-cancel",
                    { _, transferred ->
                        if (transferred.exists()) assertTrue(transferred.delete())
                        stopAfterFirst = true
                    },
                )
            }
            var readerCreated = false
            var replayCalls = 0
            val fakes = mutableListOf<FakeCascReplayReader>()
            val fdsBefore = openFdCount()
            val error = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { error("cancelled replay must not resolve a download source") },
                    { if (!readerCreated) false else ++replayCalls > 2 },
                    "catalog-70425|replay-close-cancel",
                    { _, transferred -> if (transferred.exists()) assertTrue(transferred.delete()) },
                    replayReaderFactory = { cancelled ->
                        FakeCascReplayReader(cancelled).also { readerCreated = true; it.closeError = closeFailure; fakes += it }
                    },
                )
            }
            assertTrue(readerCreated)
            assertTrue(error.suppressed.any { it === closeFailure })
            val fake = fakes.single()
            assertEquals(1, fake.closeCalls)
            assertTrue(fake.closed)
            assertEquals(fdsBefore, openFdCount())
        } finally {
            delete(cancelRoot)
        }
    }

    @Test
    fun stagedReplayReaderClosesBeforeWritesAndPropagatesCancellation() {
        val root = tempDir()
        try {
            val objects = (0..1).map { index ->
                val decoded = "replay-lifetime-$index".toByteArray()
                val raw = blteRaw(decoded)
                val rawFile = file(root, "private-work/$index.blte", raw)
                val ekey = encodedEkey(raw)
                Triple(
                    ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong()),
                    rawFile,
                    raw,
                )
            }
            val target = File(root, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(root, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            val manifest = objects.map { it.first }
            var stopAfterFirst = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, checkpointedPrefixCut(manifest),
                    { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                    { stopAfterFirst },
                    "catalog-70425|replay-lifetime",
                    { _, raw ->
                        if (raw.exists()) assertTrue(raw.delete())
                        stopAfterFirst = true
                    },
                )
            }
            stopAfterFirst = false
            val fakes = mutableListOf<FakeCascReplayReader>()
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null, target, checkpointDirectory, manifest.asSequence(),
                { spec -> objects.single { it.first.ekey == spec.ekey }.second },
                { false },
                "catalog-70425|replay-lifetime",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
            )
            assertEquals(1L, result.reusedObjects)
            assertEquals(1L, result.writtenObjects)
            // One reader for the whole call, opened once for the single
            // staged segment and closed before the new download was resolved,
            // never reopened afterwards.
            val fake = fakes.single()
            assertEquals(1, fake.segmentsOpened)
            assertTrue(fake.closed)
            // Contract edges on a standalone FAKE: zero-length reads serve
            // nothing without I/O, out-of-range reads fail, and a closed
            // reader never serves or binds.
            val probe = FakeCascReplayReader()
            probe.ensureSegment(File(target, "data.000"))
            assertEquals(0, probe.readAt(ByteArray(4), 0, 0, 0))
            org.junit.Assert.assertThrows(IndexOutOfBoundsException::class.java) { probe.readAt(ByteArray(4), 0, 5, 0) }
            probe.close()
            probe.close()
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { probe.readAt(ByteArray(4), 0, 1, 0) }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { probe.checkBinding(480L) }
        } finally {
            delete(root)
        }

        val cancelRoot = tempDir()
        try {
            val decoded = "replay-cancel-object".toByteArray()
            val raw = blteRaw(decoded)
            val rawFile = file(cancelRoot, "private-work/object.blte", raw)
            val ekey = encodedEkey(raw)
            val spec = ForeverCasc.IncrementalObjectSpec(ekey, md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            val target = File(cancelRoot, "private-work/stage/Data/data")
            assertTrue(target.parentFile!!.mkdirs())
            val checkpointDirectory = File(cancelRoot, "private-work/checkpoint").also { assertTrue(it.mkdirs()) }
            var stopAfterFirst = false
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { rawFile },
                    { stopAfterFirst },
                    "catalog-70425|replay-cancel",
                    { _, transferred ->
                        if (transferred.exists()) assertTrue(transferred.delete())
                        stopAfterFirst = true
                    },
                )
            }
            // Cancel only after the reader exists, so the failing call must
            // have owned (and released) exactly one reader mid-replay.
            var readerCreated = false
            var replayCalls = 0
            val cancelledFakes = mutableListOf<FakeCascReplayReader>()
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, target, checkpointDirectory, sequenceOf(spec),
                    { error("cancelled replay must not resolve a download source") },
                    { if (!readerCreated) false else ++replayCalls > 2 },
                    "catalog-70425|replay-cancel",
                    { _, transferred -> if (transferred.exists()) assertTrue(transferred.delete()) },
                    replayReaderFactory = { cancelled ->
                        FakeCascReplayReader(cancelled).also { readerCreated = true; cancelledFakes += it }
                    },
                )
            }
            assertTrue(readerCreated)
            assertTrue(cancelledFakes.single().closed)
        } finally {
            delete(cancelRoot)
        }
    }

    private fun canonicalHintFixture(
        root: File,
        hints: List<Pair<Int, Long>>,
        removeTargetSorted: Boolean = true,
    ): CanonicalHintFixture {
        val sourceRoot = File(root, "source")
        val sourceData = File(sourceRoot, "Data/data")
        val unrequestedDecoded = "canonical-unrequested-source-object".toByteArray()
        val unrequestedRaw = blteRaw(unrequestedDecoded)
        val unrequestedRawFile = file(root, "encoded/unrequested.blte", unrequestedRaw)
        val unrequestedEkey = encodedEkey(unrequestedRaw)
        val targetDecoded = "canonical-manifest-target".toByteArray()
        val targetRawBytes = blteRaw(targetDecoded)
        val targetRaw = file(root, "encoded/target.blte", targetRawBytes)
        val targetEkey = encodedEkey(targetRawBytes)
        val objects = listOf(
            JSONObject().put("encoding_keys", JSONArray().put(unrequestedEkey)).put("decoded_size", unrequestedDecoded.size)
                .put("content_key", md5Hex(unrequestedDecoded)) to unrequestedRawFile,
            JSONObject().put("encoding_keys", JSONArray().put(targetEkey)).put("decoded_size", targetDecoded.size)
                .put("content_key", md5Hex(targetDecoded)) to targetRaw,
        )
        ForeverCasc.materialize(
            sourceRoot,
            "canonical-hint-fixture\n".toByteArray(),
            emptyList(),
            emptyList(),
            objects.map { (record, raw) -> ForeverCasc.RuntimeObject(record, raw) },
            { false },
        )
        val location = localEntry(sourceData, targetEkey)
        val bucket = bucket(targetEkey.hexBytes())
        removeSortedTargetAndAppendHints(
            File(sourceData, "%02x%08x.idx".format(bucket, 1)),
            targetEkey.hexBytes().copyOfRange(0, 9),
            location,
            hints,
            removeTargetSorted,
        )
        val target = ForeverCasc.IncrementalObjectSpec(
            targetEkey,
            md5Hex(targetDecoded),
            targetRawBytes.size.toLong(),
            targetDecoded.size.toLong(),
        )
        return CanonicalHintFixture(sourceData, target, targetRaw, unrequestedEkey, directFileHashes(sourceData))
    }

    private fun removeSortedTargetAndAppendHints(
        index: File,
        keyPrefix: ByteArray,
        location: Triple<Int, Long, Long>,
        hints: List<Pair<Int, Long>>,
        removeTargetSorted: Boolean,
    ) {
        require(hints.size <= 21)
        val bytes = index.readBytes()
        val originalBlockSize = u32(bytes, 0x20)
        val originalEnd = 0x28 + originalBlockSize
        val matches = (0x28 until originalEnd step 18).filter { at -> bytes.copyOfRange(at, at + 9).contentEquals(keyPrefix) }
        assertEquals("fixture has exactly one sorted target record", 1, matches.size)
        val removeAt = matches.single()
        val kept = if (removeTargetSorted) ByteArray(originalBlockSize - 18) else bytes.copyOfRange(0x28, originalEnd)
        if (removeTargetSorted) {
            bytes.copyInto(kept, 0, 0x28, removeAt)
            bytes.copyInto(kept, removeAt - 0x28, removeAt + 18, originalEnd)
        }
        bytes.fill(0, 0x28, bytes.size)
        kept.copyInto(bytes, 0x28)
        val sortedEnd = 0x28 + kept.size
        putU32le(bytes, 0x20, kept.size.toLong())
        putU32le(bytes, 0x24, indexSortedChecksum(bytes).toLong() and 0xffffffffL)
        val updateStart = roundUpIndexLength(sortedEnd.toLong()).toInt()
        hints.forEachIndexed { slot, (status, sizeDelta) ->
            val record = ByteArray(24)
            keyPrefix.copyInto(record, 4)
            record[13] = (location.first ushr 2).toByte()
            putU32be(record, 14, ((location.first and 3) shl 30) or (location.second.toInt() and 0x3fffffff))
            putU32le(record, 18, location.third + sizeDelta)
            record[22] = status.toByte()
            val guard = hashlittle(record.copyOfRange(4, 23), 0) or Int.MIN_VALUE
            putU32le(record, 0, guard.toLong() and 0xffffffffL)
            record.copyInto(bytes, updateStart + slot * 24)
        }
        index.writeBytes(bytes)
    }

    private fun assembleTargetView(root: File, sourceData: File, outputData: File): File {
        val verifyRoot = File(root, "target-verify-view")
        val verifyData = File(verifyRoot, "Data/data").also { assertTrue(it.mkdirs()) }
        sourceData.listFiles()!!.filter { it.name.startsWith("data.") }.forEach { it.copyTo(File(verifyData, it.name)) }
        outputData.listFiles()!!.forEach { it.copyTo(File(verifyData, it.name)) }
        return verifyRoot
    }

    private fun indexContainsEkey(dataFiles: File, ekey: String): Boolean {
        val prefix = ekey.hexBytes().copyOfRange(0, 9)
        return dataFiles.listFiles()!!.filter { it.name.endsWith(".idx") }.any { index ->
            val bytes = index.readBytes()
            val end = 0x28 + u32(bytes, 0x20)
            (0x28 until end step 18).any { at -> bytes.copyOfRange(at, at + 9).contentEquals(prefix) }
        }
    }

    private fun totalIndexRecords(dataFiles: File): Int = dataFiles.listFiles()!!
        .filter { it.name.endsWith(".idx") }
        .sumOf { countIndexRecords(it.readBytes()) }

    // ------------------------------------------------------------------
    // Sealed-prefix overlap (C+A): one sequential verifier per segment plus
    // one ordered writer on new segments, durable checkpoint compatibility,
    // and a joint publish barrier. The verifier never mutates the table or
    // the checkpoint; the writer never proves prefix bytes.
    // ------------------------------------------------------------------

    private data class OverlapFixture(
        val root: File,
        val dataFiles: File,
        val checkpointDir: File,
        val manifest: List<ForeverCasc.IncrementalObjectSpec>,
        val rawByKey: Map<String, File>,
        val prefixCount: Int,
        val identity: String,
    )

    /** First run commits exactly [prefixSize] objects, then cancels: a real torn resume. */
    private fun buildOverlapFixture(
        prefixSize: Int,
        newSize: Int,
        name: String,
        unorderedPrefix: Boolean = false,
    ): OverlapFixture {
        val root = tempDir()
        val total = prefixSize + newSize
        val raws = (0 until total).map { index ->
            val decoded = "overlap-$name-object-$index".toByteArray()
            blteRaw(decoded)
        }
        val pairs = raws.mapIndexed { index, raw ->
            val decoded = "overlap-$name-object-$index".toByteArray()
            val spec = ForeverCasc.IncrementalObjectSpec(encodedEkey(raw), md5Hex(decoded), raw.size.toLong(), decoded.size.toLong())
            spec to raw
        // Ekey order, like the production catalog cursor: the read-only
        // inspect path requires strictly increasing checkpoint keys.
        }.sortedBy { it.first.ekey }
        val manifest = pairs.map { it.first }
        val prefixPairs = if (unorderedPrefix) {
            pairs.filterIndexed { index, _ -> index % 2 == 0 }.take(prefixSize).sortedByDescending { it.first.ekey }
        } else {
            pairs.take(prefixSize)
        }
        val dataFiles = File(root, "stage/Data/data").also { assertTrue(it.mkdirs()) }
        val checkpointDir = File(root, "checkpoint").also { assertTrue(it.mkdirs()) }
        val rawByKey = HashMap<String, File>()
        pairs.forEachIndexed { index, (spec, raw) ->
            rawByKey[spec.ekey] = file(root, "raws/$index.blte", raw)
        }
        val identity = "overlap-catalog|$name"
        var committed = 0
        val prefixThenCancel = sequence {
            prefixPairs.forEach { yield(it.first) }
            throw java.util.concurrent.CancellationException("synthetic prefix cut")
        }
        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            ForeverCasc.writeTargetIncrementalResumable(
                null,
                dataFiles,
                checkpointDir,
                prefixThenCancel,
                { spec -> rawByKey.getValue(spec.ekey) },
                { false },
                identity,
                { _, _ -> committed++ },
            )
        }
        assertEquals(prefixSize, committed)
        return OverlapFixture(root, dataFiles, checkpointDir, manifest, rawByKey, prefixSize, identity)
    }

    private fun checkpointFrameCount(checkpointDir: File): Long {
        val checkpoint = File(checkpointDir, ".forever-casc-resume-checkpoint")
        return (checkpoint.length() - 40L) / 72L
    }

    /** Repack four tiny existing fixture objects as two interleaved-key frozen archives. */
    private fun splitOverlapFixtureIntoInterleavedGroups(fixture: OverlapFixture): Pair<List<String>, List<String>> {
        require(fixture.prefixCount == 4)
        val checkpointFile = File(fixture.checkpointDir, ".forever-casc-resume-checkpoint")
        val checkpointBytes = checkpointFile.readBytes()
        val checkpointHeader = checkpointBytes.copyOfRange(0, 40)
        val originalFrames = (0 until fixture.prefixCount).map { index ->
            checkpointBytes.copyOfRange(40 + index * 72, 40 + (index + 1) * 72)
        }
        val physicalGroups = listOf(listOf(0, 2), listOf(1, 3))
        val newFrames = ArrayList<ByteArray>(fixture.prefixCount)
        physicalGroups.forEachIndexed { archiveId, indices ->
            val segment = java.io.ByteArrayOutputStream().apply {
                write(testCascSegmentHeader(fixture.dataFiles, archiveId))
            }
            var offset = 480L
            indices.forEach { index ->
                val spec = fixture.manifest[index]
                val raw = fixture.rawByKey.getValue(spec.ekey).readBytes()
                val localSize = 30L + raw.size
                segment.write(testCascLocalHeader(
                    spec.ekey,
                    localSize,
                    archiveId.toLong() * (1L shl 30) + offset,
                    0,
                ))
                segment.write(raw)

                val frame = originalFrames[index].copyOf()
                java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
                    putInt(56, archiveId)
                    putLong(60, offset)
                }
                putU32be(
                    frame,
                    68,
                    java.util.zip.CRC32().also { it.update(frame, 0, 68) }.value.toInt(),
                )
                newFrames += frame
                offset += localSize
            }
            File(fixture.dataFiles, "data.%03d".format(archiveId)).writeBytes(segment.toByteArray())
        }
        val rewrittenCheckpoint = java.io.ByteArrayOutputStream().apply {
            write(checkpointHeader)
            newFrames.forEach { write(it) }
        }
        checkpointFile.writeBytes(rewrittenCheckpoint.toByteArray())
        return physicalGroups[0].map { fixture.manifest[it].ekey } to
            physicalGroups[1].map { fixture.manifest[it].ekey }
    }

    private fun testCascSegmentHeader(dataFiles: File, segmentId: Int): ByteArray = ByteArray(480).also { result ->
        val pathHash = md5Bytes(dataFiles.absolutePath.toByteArray(Charsets.UTF_8))
        repeat(16) { bucketIndex ->
            val key = pathHash.copyOf()
            key[1] = segmentId.toByte()
            key[2] = (segmentId ushr 8).toByte()
            var probe = 0
            while (probe < 256) {
                key[0] = probe.toByte()
                if (bucket(key, 1) == bucketIndex) break
                probe++
            }
            check(probe < 256)
            val ekey = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            testCascLocalHeader(ekey, 30L, segmentId.toLong() * (1L shl 30) + bucketIndex * 30L, 1)
                .copyInto(result, bucketIndex * 30)
        }
    }

    private fun testCascLocalHeader(ekey: String, size: Long, offset: Long, flags: Int): ByteArray =
        ByteArray(30).also { header ->
            ekey.hexBytes().reversedArray().copyInto(header)
            putU32le(header, 0x10, size)
            putU16le(header, 0x14, flags)
            putU32le(header, 0x16, hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971).toLong() and 0xffffffffL)
            putU32le(header, 0x1a, checksumB(header, offset).toLong() and 0xffffffffL)
        }

    @Test
    fun overlapReplaysUnorderedLegacyPrefixWithExactTargetMembership() {
        val fixture = buildOverlapFixture(prefixSize = 4, newSize = 4, name = "unordered-membership", unorderedPrefix = true)
        try {
            val membership = fixture.manifest.mapIndexed { index, spec ->
                spec.ekey to IndexedValue(index, spec)
            }.toMap()
            val prefixKeys = ForeverCasc.withResumableCheckpointObjects(
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.identity,
                { false },
            ) { objects -> objects.map { it.ekey }.toList() }
            assertEquals(fixture.prefixCount, prefixKeys.size)
            val beforeStage = directFileHashes(fixture.dataFiles)
            val beforeCheckpoint = directFileHashes(fixture.checkpointDir)
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectResumableCheckpoint(
                    fixture.dataFiles,
                    fixture.checkpointDir,
                    fixture.manifest.asSequence(),
                    { false },
                    fixture.identity,
                )
            }
            val inspected = ForeverCasc.inspectResumableCheckpoint(
                fixture.dataFiles,
                fixture.checkpointDir,
                emptySequence(),
                { false },
                fixture.identity,
                targetObjectCount = fixture.manifest.size,
                targetMembership = { membership.getValue(it) },
            )
            assertEquals(fixture.prefixCount.toLong(), inspected.completedObjectCount)
            assertEquals(beforeStage, directFileHashes(fixture.dataFiles))
            assertEquals(beforeCheckpoint, directFileHashes(fixture.checkpointDir))

            val retired = java.util.concurrent.CopyOnWriteArrayList<String>()
            val coordinator = CascOverlapCoordinator(
                retirePrefixRaw = { retired += it },
                wantedKeys = prefixKeys.toSet(),
            )
            prefixKeys.forEach { assertFalse(coordinator.registerRawKey(it)) }
            var sourceCalls = 0
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.manifest.asReversed().asSequence(),
                { spec ->
                    sourceCalls++
                    fixture.rawByKey.getValue(spec.ekey)
                },
                { false },
                fixture.identity,
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled) },
                overlapCoordinator = coordinator,
                targetObjectCount = fixture.manifest.size,
                targetMembership = { membership.getValue(it) },
            )

            assertEquals(fixture.prefixCount.toLong(), result.reusedObjects)
            assertEquals((fixture.manifest.size - fixture.prefixCount).toLong(), result.writtenObjects)
            assertEquals(fixture.manifest.size - fixture.prefixCount, sourceCalls)
            assertEquals(prefixKeys.toSet(), retired.toSet())
            assertEquals(prefixKeys.size, retired.size)
            assertEquals(fixture.prefixCount.toLong(), coordinator.ackedObjects.get())
            ForeverCasc.verifyExisting(
                fixture.dataFiles.parentFile!!.parentFile!!,
                fixture.manifest.map { it.toRuntimeSpec() },
                { false },
            )
        } finally {
            delete(fixture.root)
        }
    }

    @Test(timeout = 60_000)
    fun overlapTwoInterleavedArchiveGroupsRetireOnlyTheirExactWantedKeys() {
        val fixture = buildOverlapFixture(prefixSize = 4, newSize = 0, name = "interleaved-two-groups")
        val reachedSecondGroup = java.util.concurrent.CountDownLatch(1)
        val releaseSecondGroup = java.util.concurrent.CountDownLatch(1)
        val result = AtomicReference<ForeverCasc.IncrementalResult?>(null)
        val failure = AtomicReference<Throwable?>(null)
        var writer: Thread? = null
        try {
            val (firstGroup, secondGroup) = splitOverlapFixtureIntoInterleavedGroups(fixture)
            assertTrue(firstGroup[0] < secondGroup[0])
            assertTrue(secondGroup[0] < firstGroup[1])
            assertTrue(firstGroup[1] < secondGroup[1])
            val membership = fixture.manifest.mapIndexed { index, spec ->
                spec.ekey to IndexedValue(index, spec)
            }.toMap()
            val retired = java.util.concurrent.CopyOnWriteArrayList<String>()
            val coordinator = CascOverlapCoordinator(
                retirePrefixRaw = { retired += it },
                wantedKeys = fixture.manifest.map { it.ekey }.toSet(),
            )
            assertFalse(coordinator.registerRawKey(firstGroup[0]))
            assertFalse(coordinator.registerRawKey(secondGroup[0]))

            val runWriter = Thread {
                try {
                    result.set(ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        fixture.dataFiles,
                        fixture.checkpointDir,
                        fixture.manifest.asSequence(),
                        { error("all fixture objects are already checkpointed") },
                        { false },
                        fixture.identity,
                        replayReaderFactory = { cancelled ->
                            val delegate = FakeCascReplayReader(cancelled)
                            object : CascReplayReader by delegate {
                                override fun ensureSegment(file: File) {
                                    delegate.ensureSegment(file)
                                    if (file.name == "data.001") {
                                        reachedSecondGroup.countDown()
                                        if (!releaseSecondGroup.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                                            throw IllegalStateException("second-group test gate timed out")
                                        }
                                    }
                                }
                            }
                        },
                        overlapCoordinator = coordinator,
                        targetObjectCount = fixture.manifest.size,
                        targetMembership = { membership.getValue(it) },
                    ))
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }.apply { isDaemon = true }
            writer = runWriter
            runWriter.start()
            assertTrue("verifier must reach the second frozen archive", reachedSecondGroup.await(10, java.util.concurrent.TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("overlap writer failed before the second group", it) }
            assertEquals(2L, coordinator.ackedObjects.get())
            assertEquals(listOf(firstGroup[0]), retired.toList())

            // The first group has already acked this wanted key, so a delayed
            // raw registration must retire immediately; the interleaved key
            // from group two must remain pending until that group is proven.
            assertTrue(coordinator.registerRawKey(firstGroup[1]))
            retired += firstGroup[1]
            assertFalse(coordinator.registerRawKey(secondGroup[1]))
            assertFalse(secondGroup.any { it in retired })
            assertEquals(2L, coordinator.ackedObjects.get())

            releaseSecondGroup.countDown()
            runWriter.join(20_000)
            assertFalse("overlap writer must finish after releasing group two", runWriter.isAlive)
            failure.get()?.let { throw AssertionError("overlap writer failed", it) }
            assertEquals(4L, result.get()?.reusedObjects)
            assertEquals(4L, coordinator.ackedObjects.get())
            assertEquals(fixture.manifest.map { it.ekey }.toSet(), retired.toSet())
            assertEquals(4, retired.size)
        } finally {
            releaseSecondGroup.countDown()
            writer?.join(20_000)
            delete(fixture.root)
        }
    }

    private class GateReader(
        private val delegate: FakeCascReplayReader,
        private val gate: java.util.concurrent.CountDownLatch,
        private val enteredLatch: java.util.concurrent.CountDownLatch,
    ) : CascReplayReader {
        val readsServed: Long get() = delegate.readsServed
        val bindingsChecked: Int get() = delegate.bindingsChecked
        val closed: Boolean get() = delegate.closed
        override fun ensureSegment(file: File) {
            // Bind FIRST, then signal and park: the test mutates the file
            // only after the reader holds the old FD, so the post-group
            // binding proof (not the bind itself) is what must catch it.
            delegate.ensureSegment(file)
            enteredLatch.countDown()
            if (!gate.await(120, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("test overlap gate timed out")
        }

        override fun checkBinding(endExclusive: Long) = delegate.checkBinding(endExclusive)
        override fun checkSealedBinding(exactSize: Long) = delegate.checkSealedBinding(exactSize)

        override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int =
            delegate.readAt(buffer, off, len, position)

        override fun close() = delegate.close()
    }

    private class LogCapture : java.util.logging.Handler() {
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        override fun publish(record: java.util.logging.LogRecord) { records += record }
        override fun flush() {}
        override fun close() {}
    }

    private fun overlapOfficialStreamOf(fixture: OverlapFixture): OverlapOfficialStream =
        { _, consume -> consume(fixture.manifest.asSequence()) }

    @Test
    fun overlapCommitsNewTailWhilePrefixVerificationIsBlocked() {
        val fixture = buildOverlapFixture(prefixSize = 10, newSize = 40, name = "blocked-v")
        try {
            val prefixBytes = File(fixture.dataFiles, "data.000").readBytes()
            val gate = java.util.concurrent.CountDownLatch(1)
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val gates = ArrayList<GateReader>()
            val retired = java.util.concurrent.CopyOnWriteArrayList<String>()
            // Register leftover raws for two prefix keys: only those retire on group ack.
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { retired += it })
            coordinator.registerRawKey(fixture.manifest[0].ekey)
            coordinator.registerRawKey(fixture.manifest[3].ekey)
            val logs = LogCapture()
            val logger = java.util.logging.Logger.getLogger("ForeverClientUpdate")
            logger.addHandler(logs)
            try {
                var sourceCalls = 0
                // Latch-driven: the writer thread counts down when the whole
                // new tail (40 commits) is durable; no polling.
                val tailCommitted = java.util.concurrent.CountDownLatch(1)
                var commits = 0
                var result: ForeverCasc.IncrementalResult? = null
                var failure: Throwable? = null
                val writer = Thread {
                    try {
                        result = ForeverCasc.writeTargetIncrementalResumable(
                            null,
                            fixture.dataFiles,
                            fixture.checkpointDir,
                            fixture.manifest.asSequence(),
                            { spec ->
                                sourceCalls++
                                fixture.rawByKey.getValue(spec.ekey)
                            },
                            { false },
                            fixture.identity,
                            { _, _ ->
                                commits++
                                if (commits == 40) tailCommitted.countDown()
                            },
                            replayReaderFactory = { cancelled ->
                                FakeCascReplayReader(cancelled).let { fake ->
                                    GateReader(fake, gate, enteredLatch).also { gates += it }
                                }
                            },
                            overlapCoordinator = coordinator,
                            overlapOfficialStream = overlapOfficialStreamOf(fixture),
                        )
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                writer.isDaemon = true
                writer.start()
                assertTrue("writer must commit the whole new tail while V is blocked", tailCommitted.await(120, java.util.concurrent.TimeUnit.SECONDS))
                if (failure != null) throw AssertionError("writer failed while verifier blocked", failure)
                assertEquals(40, sourceCalls)
                assertEquals(50L, checkpointFrameCount(fixture.checkpointDir))
                // Durable checkpoint prefix bytes are byte-identical: the
                // verifier never mutates old segments and the writer seals them.
                assertTrue(prefixBytes.contentEquals(File(fixture.dataFiles, "data.000").readBytes()))
                // No finalization while the verifier is blocked: no indices —
                // the joint barrier has not run.
                assertTrue(fixture.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
                val firstSource = logs.records.map { it.message }.single { it.startsWith("CASC first source request overlap=true") }
                assertTrue("first request must report pending prefix, not verified prefix: $firstSource", firstSource.contains("checkpointPending=10"))
                assertTrue("only registered leftover raws retire, and only on group ack", retired.isEmpty())
                gate.countDown()
                writer.join(120_000)
                assertTrue("writer thread must exit after the gate opens", !writer.isAlive)
                if (failure != null) throw AssertionError("overlap run failed after unblock", failure)
                val done = requireNotNull(result)
                assertEquals(10L, done.reusedObjects)
                assertEquals(40L, done.writtenObjects)
                assertEquals(listOf(fixture.manifest[0].ekey, fixture.manifest[3].ekey), retired.toList())
                val barrier = logs.records.map { it.message }.single { it.startsWith("CASC prefix verification complete") }
                assertTrue(barrier.contains("objects=10"))
                // Exactly one whole-segment pass, no tiny preads, no over-read.
                val gateReader = gates.single()
                val segEnd = File(fixture.dataFiles, "data.000").length()
                assertEquals(segEnd - 480L, gateReader.readsServed)
                assertTrue(gateReader.bindingsChecked >= 2)
                assertTrue(gateReader.closed)
                ForeverCasc.verifyExisting(
                    fixture.dataFiles.parentFile!!.parentFile!!,
                    fixture.manifest.map { it.toRuntimeSpec() },
                    { false },
                )
            } finally {
                logger.removeHandler(logs)
            }
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapPostGroupBindingRejectsGrowthAndSwapDuringVerification() {
        // Growth during verification: the prefix region still verifies, but
        // the post-group exact-size proof fails closed instead of accepting a
        // file that changed under its reader.
        val grown = buildOverlapFixture(prefixSize = 4, newSize = 2, name = "sealed-growth")
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val gates = ArrayList<GateReader>()
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            // Latch-driven: both new-tail commits are durable before the
            // growth lands, so the run aborts in the post-group proof with
            // the full new tail preserved — never in the pre-freeze guards.
            val tailDone = java.util.concurrent.CountDownLatch(1)
            var tailCommits = 0
            var failure: Throwable? = null
            val writer = Thread {
                try {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        grown.dataFiles,
                        grown.checkpointDir,
                        grown.manifest.asSequence(),
                        { spec -> grown.rawByKey.getValue(spec.ekey) },
                        { false },
                        grown.identity,
                        { _, _ ->
                            tailCommits++
                            if (tailCommits == 2) tailDone.countDown()
                        },
                        replayReaderFactory = { cancelled ->
                            FakeCascReplayReader(cancelled).let { fake ->
                                GateReader(fake, gate, enteredLatch).also { gates += it }
                            }
                        },
                        overlapCoordinator = coordinator,
                        overlapOfficialStream = overlapOfficialStreamOf(grown),
                    )
                } catch (t: Throwable) {
                    failure = t
                }
            }
            writer.isDaemon = true
            writer.start()
            assertTrue("verifier must reach its first segment bind", enteredLatch.await(120, java.util.concurrent.TimeUnit.SECONDS))
            if (failure != null) throw AssertionError("writer failed before growth", failure)
            assertTrue("new tail must be durable before growth", tailDone.await(120, java.util.concurrent.TimeUnit.SECONDS))
            val segment = File(grown.dataFiles, "data.000")
            val frozenLength = segment.length()
            RandomAccessFile(segment, "rw").use { it.setLength(frozenLength + 1024) }
            gate.countDown()
            writer.join(120_000)
            assertTrue("writer thread must exit", !writer.isAlive)
            val error = requireNotNull(failure) { "growth during verification must fail the run" }
            assertTrue("post-group proof must name the sealed end: ${error.message}", error.message.orEmpty().contains("sealed"))
            assertFalse("failure must not suggest automatic recovery wording", error.message.orEmpty().contains("recuper", ignoreCase = true))
            // Nothing published, tail preserved: new objects were committed
            // before the barrier aborted, indices were never written.
            assertTrue(grown.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
            assertEquals(6L, checkpointFrameCount(grown.checkpointDir))
        } finally {
            delete(grown.root)
        }

        // Same-size swap during verification: crypto passes on the held FD's
        // stale bytes, but the post-group proof observes the replaced inode.
        val swapped = buildOverlapFixture(prefixSize = 4, newSize = 2, name = "sealed-swap")
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val gates = ArrayList<GateReader>()
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            // Same latch discipline as growth: the swap lands after both new
            // commits are durable, isolating the post-group inode proof.
            val tailDone = java.util.concurrent.CountDownLatch(1)
            var tailCommits = 0
            var failure: Throwable? = null
            val writer = Thread {
                try {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        swapped.dataFiles,
                        swapped.checkpointDir,
                        swapped.manifest.asSequence(),
                        { spec -> swapped.rawByKey.getValue(spec.ekey) },
                        { false },
                        swapped.identity,
                        { _, _ ->
                            tailCommits++
                            if (tailCommits == 2) tailDone.countDown()
                        },
                        replayReaderFactory = { cancelled ->
                            FakeCascReplayReader(cancelled).let { fake ->
                                GateReader(fake, gate, enteredLatch).also { gates += it }
                            }
                        },
                        overlapCoordinator = coordinator,
                        overlapOfficialStream = overlapOfficialStreamOf(swapped),
                    )
                } catch (t: Throwable) {
                    failure = t
                }
            }
            writer.isDaemon = true
            writer.start()
            assertTrue("verifier must reach its first segment bind", enteredLatch.await(120, java.util.concurrent.TimeUnit.SECONDS))
            if (failure != null) throw AssertionError("writer failed before swap", failure)
            assertTrue("new tail must be durable before swap", tailDone.await(120, java.util.concurrent.TimeUnit.SECONDS))
            val segment = File(swapped.dataFiles, "data.000")
            val sameBytes = segment.readBytes()
            assertTrue(segment.delete())
            segment.writeBytes(sameBytes)
            gate.countDown()
            writer.join(120_000)
            assertTrue("writer thread must exit", !writer.isAlive)
            val error = requireNotNull(failure) { "swap during verification must fail the run" }
            assertTrue("post-group proof must report the binding change: ${error.message}", error.message.orEmpty().contains("binding"))
            assertTrue(swapped.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
            assertEquals(6L, checkpointFrameCount(swapped.checkpointDir))
        } finally {
            delete(swapped.root)
        }
    }

    @Test
    fun overlapLatePrefixCorruptionCancelsWriterAndPreservesDurableTail() {
        val fixture = buildOverlapFixture(prefixSize = 3, newSize = 2, name = "late-corrupt")
        try {
            // Corrupt the last prefix object's payload before the run: the
            // load-time guards (sizes, owned header identity) still pass, so
            // the freeze succeeds and only the verifier's crypto catches it.
            val segment = File(fixture.dataFiles, "data.000")
            val before = segment.readBytes()
            val lastSpec = fixture.manifest[2]
            // Object layout: the writer packs contiguously from 480 in
            // manifest order (load enforces the same contiguity), so the last
            // payload byte of the third object is arithmetic, no CP scan.
            var cursor = 480L
            fixture.manifest.take(2).forEach { cursor += 30L + it.encodedSize }
            val flipAt = (cursor + 30L + lastSpec.encodedSize - 1).toInt()
            assertTrue("flip target must land inside the staged segment", flipAt < segment.length())
            RandomAccessFile(segment, "rw").use { archive ->
                archive.seek(flipAt.toLong())
                val original = archive.readUnsignedByte()
                archive.seek(flipAt.toLong())
                archive.writeByte(original xor 1)
            }
            val gate = java.util.concurrent.CountDownLatch(1)
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            var sourceCalls = 0
            // Latch-driven: both new-tail commits are durable before the
            // verifier is released onto the corrupted prefix.
            val tailDone = java.util.concurrent.CountDownLatch(1)
            var tailCommits = 0
            var failure: Throwable? = null
            var result: ForeverCasc.IncrementalResult? = null
            val writer = Thread {
                try {
                    result = ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        fixture.dataFiles,
                        fixture.checkpointDir,
                        fixture.manifest.asSequence(),
                        { spec ->
                            sourceCalls++
                            fixture.rawByKey.getValue(spec.ekey)
                        },
                        { false },
                        fixture.identity,
                        { _, _ ->
                            tailCommits++
                            if (tailCommits == 2) tailDone.countDown()
                        },
                        replayReaderFactory = { cancelled ->
                            FakeCascReplayReader(cancelled).let { fake ->
                                GateReader(fake, gate, java.util.concurrent.CountDownLatch(0))
                            }
                        },
                        overlapCoordinator = coordinator,
                        overlapOfficialStream = overlapOfficialStreamOf(fixture),
                    )
                } catch (t: Throwable) {
                    failure = t
                }
            }
            writer.isDaemon = true
            writer.start()
            // Let the writer commit the new tail first: corruption is late.
            assertTrue("new tail must be durably committed before the verifier fails", tailDone.await(120, java.util.concurrent.TimeUnit.SECONDS))
            if (failure != null) throw AssertionError("writer failed before the tail was durable", failure)
            assertEquals("new tail must be durably committed before the verifier fails", 5L, checkpointFrameCount(fixture.checkpointDir))
            gate.countDown()
            writer.join(120_000)
            assertTrue("writer thread must exit", !writer.isAlive)
            assertTrue("no result on prefix corruption", result == null)
            val error = requireNotNull(failure) { "late prefix corruption must fail the run" }
            assertFalse("failure must not suggest automatic recovery wording", error.message.orEmpty().contains("recuper", ignoreCase = true))
            // Preserved, not reset: old prefix bytes (corrupted as found, never
            // rewritten by this run), the durable new tail, and the checkpoint.
            assertEquals(2, sourceCalls)
            assertEquals(5L, checkpointFrameCount(fixture.checkpointDir))
            assertTrue(File(fixture.dataFiles, "data.001").isFile)
            assertTrue(fixture.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
            // The checkpoint format stays legacy-compatible: the read-only
            // inspection still streams all five frames in manifest order.
            val summary = ForeverCasc.inspectResumableCheckpoint(
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.manifest.asSequence(),
                { false },
                fixture.identity,
            )
            assertEquals(5L, summary.completedObjectCount)
            assertEquals(before.size + File(fixture.dataFiles, "data.001").length(), summary.retainedSegmentBytes)
        } finally {
            delete(fixture.root)
        }
    }

    @Test(timeout = 30_000)
    fun interruptedFailureCleanupJoinsVerifierWithoutMaskingPrimary() {
        val fixture = buildOverlapFixture(1, 2, "interrupted-join")
        val entered = java.util.concurrent.CountDownLatch(1)
        val sourceFailed = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val primary = IllegalStateException("synthetic writer failure")
        val failure = AtomicReference<Throwable?>(null)
        val verifier = AtomicReference<Thread?>(null)
        val reader = AtomicReference<FakeCascReplayReader?>(null)
        val interruptRestored = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            try {
                var sources = 0
                ForeverCasc.writeTargetIncrementalResumable(
                    null, fixture.dataFiles, fixture.checkpointDir, fixture.manifest.asSequence(),
                    { spec ->
                        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        if (++sources == 2) { sourceFailed.countDown(); throw primary }
                        fixture.rawByKey.getValue(spec.ekey)
                    },
                    { false }, fixture.identity,
                    replayReaderFactory = { cancelled ->
                        val fake = FakeCascReplayReader(cancelled).also { reader.set(it) }
                        object : CascReplayReader by fake {
                            override fun ensureSegment(file: File) {
                                fake.ensureSegment(file)
                                verifier.set(Thread.currentThread())
                                entered.countDown()
                                assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            }
                        }
                    },
                    overlapCoordinator = CascOverlapCoordinator(retirePrefixRaw = { }),
                    overlapOfficialStream = overlapOfficialStreamOf(fixture),
                )
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
        }.apply { isDaemon = true }
        try {
            writer.start()
            assertTrue(sourceFailed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            writer.interrupt()
            assertFalse("interrupted cleanup must still wait for its verifier", returned.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(returned.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("primary writer failure must survive interruption", failure.get() === primary)
            assertTrue(interruptRestored.get())
            assertFalse(verifier.get()!!.isAlive)
            assertTrue(reader.get()!!.closed)
        } finally {
            release.countDown()
            writer.join(10_000)
            verifier.get()?.join(10_000)
            delete(fixture.root)
        }
    }

    @Test(timeout = 30_000)
    fun cancellationFlushFailureStillJoinsOverlapVerifier() {
        val fixture = buildOverlapFixture(1, 2, "cancel-flush-failure")
        val entered = java.util.concurrent.CountDownLatch(1)
        val emergencyRelease = java.util.concurrent.CountDownLatch(1)
        val verifier = AtomicReference<Thread?>(null)
        val reader = AtomicReference<FakeCascReplayReader?>(null)
        try {
            var sources = 0
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null, fixture.dataFiles, fixture.checkpointDir,
                    fixture.manifest.asSequence(),
                    { spec ->
                        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        if (++sources == 2) {
                            val checkpoint = File(fixture.checkpointDir, ".forever-casc-resume-checkpoint")
                            assertTrue(checkpoint.delete())
                            assertTrue(checkpoint.mkdir())
                            throw java.util.concurrent.CancellationException("synthetic cancellation before group flush")
                        }
                        fixture.rawByKey.getValue(spec.ekey)
                    },
                    { false }, fixture.identity,
                    { _, _ -> error("failed checkpoint must not retire raw sources") },
                    replayReaderFactory = { cancelled ->
                        val fake = FakeCascReplayReader(cancelled).also { reader.set(it) }
                        object : CascReplayReader by fake {
                            override fun ensureSegment(file: File) {
                                fake.ensureSegment(file)
                                verifier.set(Thread.currentThread())
                                entered.countDown()
                                while (!cancelled() && !emergencyRelease.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                    // The source cancellation predicate stays false:
                                    // only writer peer-cancel can stop this verifier.
                                }
                            }
                        }
                    },
                    overlapCoordinator = CascOverlapCoordinator(retirePrefixRaw = { }),
                    overlapOfficialStream = overlapOfficialStreamOf(fixture),
                )
            }
            assertTrue(failure.message.orEmpty().contains("checkpoint changed"))
            assertTrue(failure.suppressed.any { it is java.util.concurrent.CancellationException })
            assertTrue(verifier.get() != null)
            assertFalse("verifier must be joined before returning", verifier.get()!!.isAlive)
            assertTrue(reader.get()!!.closed)
            assertTrue(fixture.rawByKey.values.all { it.isFile })
        } finally {
            emergencyRelease.countDown()
            verifier.get()?.join(10_000)
            delete(fixture.root)
        }
    }

    @Test
    fun overlapCancelPreservesPrefixAndRecoversTornNewTailOnce() {
        val fixture = buildOverlapFixture(prefixSize = 3, newSize = 5, name = "cancel-recover")
        try {
            val prefixBytes = File(fixture.dataFiles, "data.000").readBytes()
            val committed = java.util.concurrent.atomic.AtomicInteger(0)
            val cancelNow = java.util.concurrent.atomic.AtomicBoolean(false)
            var newSources = 0
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            val error = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null,
                    fixture.dataFiles,
                    fixture.checkpointDir,
                    fixture.manifest.asSequence(),
                    { spec ->
                        if (++newSources > 2) throw java.util.concurrent.CancellationException("synthetic tail cut")
                        fixture.rawByKey.getValue(spec.ekey)
                    },
                    { cancelNow.get() },
                    fixture.identity,
                    { _, _ -> committed.incrementAndGet() },
                    replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled) },
                    overlapCoordinator = coordinator,
                    overlapOfficialStream = overlapOfficialStreamOf(fixture),
                )
            }
            assertTrue(prefixBytes.contentEquals(File(fixture.dataFiles, "data.000").readBytes()))
            assertEquals(5L, checkpointFrameCount(fixture.checkpointDir))
            assertTrue(fixture.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
            // One recovery run completes: old prefix format-compatible, torn
            // new tail recovered once, no old prefix overwrite.
            var secondSourceCalls = 0
            val secondCoordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            val retried = ForeverCasc.writeTargetIncrementalResumable(
                null,
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.manifest.asSequence(),
                { spec ->
                    secondSourceCalls++
                    fixture.rawByKey.getValue(spec.ekey)
                },
                { false },
                fixture.identity,
                { _, _ -> },
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled) },
                overlapCoordinator = secondCoordinator,
                overlapOfficialStream = overlapOfficialStreamOf(fixture),
            )
            assertEquals(5L, retried.reusedObjects)
            assertEquals(3L, retried.writtenObjects)
            assertEquals(3, secondSourceCalls)
            assertTrue(prefixBytes.contentEquals(File(fixture.dataFiles, "data.000").readBytes()))
            ForeverCasc.verifyExisting(
                fixture.dataFiles.parentFile!!.parentFile!!,
                fixture.manifest.map { it.toRuntimeSpec() },
                { false },
            )
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapStagedCopyProofAdmitsOnlyExactCopiesForEarlyRetire() {
        val fixture = buildOverlapFixture(prefixSize = 3, newSize = 1, name = "evict-proof")
        try {
            // Freeze record located during the single load pass: offsets are
            // contiguous from 480 in manifest order.
            var at = 480L
            val records = fixture.manifest.take(3).associate { spec ->
                val record = OverlapRawKeyRecord(
                    spec.ekey, spec.contentKey, spec.encodedSize, spec.decodedSize, 0, at,
                )
                at += 30L + spec.encodedSize
                spec.ekey to record
            }
            val factory = { cancelled: () -> Boolean -> FakeCascReplayReader(cancelled) }
            val valid = fixture.manifest[0]
            assertTrue(
                "an exact staged copy must prove",
                ForeverCasc.verifyStagedCopyForRawRetire(fixture.dataFiles, records.getValue(valid.ekey), valid, factory, { false }),
            )
            // Flip one staged payload byte: the range crypto must reject.
            val segment = File(fixture.dataFiles, "data.000")
            val flipAt = (records.getValue(valid.ekey).offset + 30).toInt()
            val original = RandomAccessFile(segment, "r").use { archive ->
                archive.seek(flipAt.toLong())
                archive.readUnsignedByte()
            }
            RandomAccessFile(segment, "rw").use { archive ->
                archive.seek(flipAt.toLong())
                archive.writeByte(original xor 1)
            }
            try {
                assertFalse(
                    "a tampered staged copy must not prove",
                    ForeverCasc.verifyStagedCopyForRawRetire(fixture.dataFiles, records.getValue(valid.ekey), valid, factory, { false }),
                )
                // Wrong sizes: the record cross-check must reject.
                assertFalse(ForeverCasc.verifyStagedCopyForRawRetire(fixture.dataFiles, records.getValue(valid.ekey), valid.copy(decodedSize = valid.decodedSize + 1), factory, { false }))
            } finally {
                RandomAccessFile(segment, "rw").use { archive ->
                    archive.seek(flipAt.toLong())
                    archive.writeByte(original)
                }
            }
            // Same-size inode swap mid-proof: crypto passes on the held FD's
            // stale bytes, but the post proof observes the replaced inode and
            // fails — with the raw left alone, no retire, no fallback.
            val gate = java.util.concurrent.CountDownLatch(1)
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val blockingFactory = { cancelled: () -> Boolean ->
                FakeCascReplayReader(cancelled).let { fake ->
                    object : CascReplayReader by fake {
                        override fun ensureSegment(file: File) {
                            fake.ensureSegment(file)
                            // Signal only after the old FD/inode is bound;
                            // otherwise the swap can precede the proof itself.
                            enteredLatch.countDown()
                        }

                        override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int {
                            if (!gate.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw IllegalStateException("test proof gate timed out")
                            }
                            return fake.readAt(buffer, off, len, position)
                        }
                    }
                }
            }
            var proof: Boolean? = null
            var proofFailure: Throwable? = null
            val prover = Thread {
                try {
                    proof = ForeverCasc.verifyStagedCopyForRawRetire(fixture.dataFiles, records.getValue(valid.ekey), valid, blockingFactory, { false })
                } catch (t: Throwable) {
                    proofFailure = t
                }
            }
            prover.isDaemon = true
            prover.start()
            assertTrue("proof must reach its segment bind", enteredLatch.await(120, java.util.concurrent.TimeUnit.SECONDS))
            val sameBytes = segment.readBytes()
            assertTrue(segment.delete())
            segment.writeBytes(sameBytes)
            gate.countDown()
            prover.join(120_000)
            assertTrue("prover thread must exit", !prover.isAlive)
            assertTrue("no failure, just a negative proof", proofFailure == null)
            assertEquals("a swapped same-size inode must not prove", false, proof)
            assertTrue("swapped segment is preserved untouched", sameBytes.contentEquals(segment.readBytes()))
            // Unknown key: the coordinator has no freeze record — null, never a proof.
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            assertTrue("unclaimed record stays absent", coordinator.rawKeyRecord("ff".repeat(16)) == null)
            // Read-only admission helpers.
            assertTrue(ForeverCasc.resumableCheckpointHasObjects(fixture.dataFiles, fixture.checkpointDir, fixture.identity, { false }))
            val fresh = tempDir()
            try {
                File(fresh, "empty-output").mkdirs()
                File(fresh, "empty-checkpoint").mkdirs()
                assertFalse(ForeverCasc.resumableCheckpointHasObjects(File(fresh, "empty-output"), File(fresh, "empty-checkpoint"), fixture.identity, { false }))
            } finally {
                delete(fresh)
            }
            assertEquals(listOf(0), ForeverCasc.resumableStagedSegmentIds(fixture.dataFiles))
            ForeverCasc.requireOverlapSegmentCapacity(0, 1)
            ForeverCasc.requireOverlapSegmentCapacity(1023, 0)
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { ForeverCasc.requireOverlapSegmentCapacity(1023, 1) }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { ForeverCasc.requireOverlapSegmentCapacity(0, 1024) }
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapCoordinatorRetiresEachKeyExactlyOnceAcrossRaces() {
        val retired = java.util.concurrent.CopyOnWriteArrayList<String>()
        val counted = java.util.concurrent.atomic.AtomicInteger(0)
        fun registerAndAccount(coordinator: CascOverlapCoordinator, key: String) {
            counted.incrementAndGet()
            if (coordinator.registerRawKey(key)) {
                retired += key
                counted.decrementAndGet()
            }
        }
        val keys = (0..9).map { "%032x".format(it) }
        // Order 1: ack before register (late registration) — immediate single retire.
        run {
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { retired += it; counted.decrementAndGet() })
            coordinator.ackGroup(1, keys[0], keys[0], 10L)
            assertEquals(0, retired.size)
            registerAndAccount(coordinator, keys[0])
            assertEquals(listOf(keys[0]), retired.toList())
            assertEquals(0, counted.get())
            assertFalse("claimed keys are never re-acknowledged", coordinator.tryClaim(keys[0]))
        }
        retired.clear()
        // Order 2: register, claim (evict path), then ack — ack retires nothing more.
        run {
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { retired += it; counted.decrementAndGet() })
            registerAndAccount(coordinator, keys[1])
            assertTrue(coordinator.tryClaim(keys[1]))
            retired += keys[1]
            counted.decrementAndGet()
            coordinator.ackGroup(1, keys[1], keys[1], 10L)
            assertEquals(listOf(keys[1]), retired.toList())
            assertEquals(0, counted.get())
        }
        retired.clear()
        // Order 3: register then ack — single retire from the group.
        run {
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { retired += it; counted.decrementAndGet() })
            registerAndAccount(coordinator, keys[2])
            coordinator.ackGroup(1, keys[1], keys[2], 20L)
            assertEquals(listOf(keys[2]), retired.toList())
            assertEquals(0, counted.get())
            assertFalse(coordinator.tryClaim(keys[2]))
        }
        retired.clear()
        counted.set(0)
        // Threaded: registration and acks race across 34 keys with a shared
        // start gate; every key still retires exactly once and the counter
        // returns to zero with no hang.
        run {
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { retired += it; counted.decrementAndGet() })
            val gate = java.util.concurrent.CountDownLatch(1)
            val threads = (0..33).map { index ->
                val key = "%032x".format(index)
                Thread {
                    gate.await()
                    registerAndAccount(coordinator, key)
                }
            } + listOf(Thread {
                gate.await()
                coordinator.ackGroup(34, "%032x".format(0), "%032x".format(33), 340L)
            })
            threads.forEach { it.isDaemon = true; it.start() }
            gate.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120)
            threads.forEach { thread ->
                val remaining = deadline - System.nanoTime()
                assertTrue("coordinator race must not hang", remaining > 0)
                thread.join(remaining / 1_000_000)
                assertTrue("coordinator race must not hang", !thread.isAlive)
            }
            assertEquals(34, retired.size)
            assertEquals((0..33).map { "%032x".format(it) }.toSet(), retired.toSet())
            assertEquals("every registration balanced by exactly one retire", 0, counted.get())
        }
    }

    @Test
    fun exactOverlapAcknowledgementsDoNotRetireLexicallyInterleavedKeysEarly() {
        val keys = (0..3).map { "%032x".format(it) }
        val retired = java.util.concurrent.CopyOnWriteArrayList<String>()
        val outstanding = java.util.concurrent.atomic.AtomicInteger(0)
        val coordinator = CascOverlapCoordinator(
            retirePrefixRaw = { key -> retired += key; outstanding.decrementAndGet() },
            wantedKeys = keys.toSet(),
        )
        fun register(key: String) {
            outstanding.incrementAndGet()
            if (coordinator.registerRawKey(key)) {
                retired += key
                outstanding.decrementAndGet()
            }
        }

        // The first physical archive group contains keys 0 and 2, while key 1
        // belongs to a later group and lies inside their lexical min/max range.
        register(keys[0])
        register(keys[1])
        coordinator.ackGroupExact(2, setOf(keys[0], keys[2]), 20L)
        assertEquals(listOf(keys[0]), retired.toList())
        assertEquals("a range ack would retire this later-group key too soon", 1, outstanding.get())

        // Registration can race behind the exact ack and still retire once.
        register(keys[2])
        assertEquals(listOf(keys[0], keys[2]), retired.toList())

        // Individual proof wins for key 1; the later group ack must not claim
        // it again. Key 3 is likewise registered after its group ack.
        assertTrue(coordinator.tryClaim(keys[1]))
        retired += keys[1]
        outstanding.decrementAndGet()
        coordinator.ackGroupExact(2, setOf(keys[1], keys[3]), 30L)
        register(keys[3])

        assertEquals(setOf(keys[0], keys[1], keys[2], keys[3]), retired.toSet())
        assertEquals(4, retired.size)
        assertEquals(0, outstanding.get())
        assertEquals(4L, coordinator.ackedObjects.get())
        assertEquals(50L, coordinator.ackedBytes.get())
        assertFalse(coordinator.tryClaim(keys[0]))

        val ackedBeforeOversizedInput = coordinator.ackedObjects.get()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            coordinator.ackGroupExact(1, List(35) { keys[0] }, 1L)
        }
        assertEquals(ackedBeforeOversizedInput, coordinator.ackedObjects.get())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CascOverlapCoordinator(
                retirePrefixRaw = { },
                wantedKeys = (0..34).map { "%032x".format(it) }.toSet(),
            )
        }
    }

    @Test
    fun overlapCheckpointReplacementDuringVerificationFailsWithoutAck() {
        val fixture = buildOverlapFixture(prefixSize = 4, newSize = 2, name = "cp-swap")
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            var failure: Throwable? = null
            var result: ForeverCasc.IncrementalResult? = null
            // Both new-tail commits must be durable before the swap lands, so
            // the failure isolates the per-group-post checkpoint recheck.
            val tailDone = java.util.concurrent.CountDownLatch(1)
            var tailCommits = 0
            val writer = Thread {
                try {
                    result = ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        fixture.dataFiles,
                        fixture.checkpointDir,
                        fixture.manifest.asSequence(),
                        { spec -> fixture.rawByKey.getValue(spec.ekey) },
                        { false },
                        fixture.identity,
                        { _, _ -> tailCommits++
                            if (tailCommits == 2) tailDone.countDown()
                        },
                        replayReaderFactory = { cancelled ->
                            FakeCascReplayReader(cancelled).let { fake ->
                                GateReader(fake, gate, enteredLatch)
                            }
                        },
                        overlapCoordinator = coordinator,
                        overlapOfficialStream = overlapOfficialStreamOf(fixture),
                    )
                } catch (t: Throwable) {
                    failure = t
                }
            }
            writer.isDaemon = true
            writer.start()
            assertTrue("verifier must reach its first segment bind", enteredLatch.await(120, java.util.concurrent.TimeUnit.SECONDS))
            if (failure != null) throw AssertionError("writer failed before checkpoint swap", failure)
            assertTrue("new tail must be durable before swap", tailDone.await(120, java.util.concurrent.TimeUnit.SECONDS))
            // Replace the checkpoint file under the held reader: same size,
            // fresh inode, corrupted identity — the per-group-post recheck
            // must observe it before any ack.
            val checkpoint = File(fixture.checkpointDir, ".forever-casc-resume-checkpoint")
            val sameSize = checkpoint.readBytes()
            assertTrue(checkpoint.delete())
            sameSize[8] = (sameSize[8].toInt() xor 1).toByte()
            checkpoint.writeBytes(sameSize)
            gate.countDown()
            writer.join(120_000)
            assertTrue("writer thread must exit", !writer.isAlive)
            assertTrue("no result on checkpoint replacement", result == null)
            val error = requireNotNull(failure) { "checkpoint replacement during verification must fail the run" }
            assertTrue("failure must name the checkpoint seal: ${error.message}", error.message.orEmpty().contains("checkpoint"))
            assertEquals("nothing may ack after a checkpoint replacement", 0L, coordinator.ackedObjects.get())
            assertTrue("no indices without a verified prefix", fixture.dataFiles.listFiles()!!.none { it.name.endsWith(".idx") })
            assertEquals("new tail stays durable, not reset", 6L, checkpointFrameCount(fixture.checkpointDir))
            assertFalse("failure must not suggest automatic recovery wording", error.message.orEmpty().contains("recuper", ignoreCase = true))
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapThousandObjectGroupStreamsWithoutMaterializing() {
        // One sealed group with 1000 tiny objects: the verifier streams it
        // with O(128 KiB + 34 + 1024) memory — no per-group object list, no
        // per-key map — while V proves everything and W commits the tail.
        val fixture = buildOverlapFixture(prefixSize = 1000, newSize = 3, name = "thousand-group")
        try {
            val fakes = ArrayList<FakeCascReplayReader>()
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.manifest.asSequence(),
                { spec -> fixture.rawByKey.getValue(spec.ekey) },
                { false },
                fixture.identity,
                { _, _ -> },
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
                overlapCoordinator = coordinator,
                overlapOfficialStream = overlapOfficialStreamOf(fixture),
            )
            assertEquals(1000L, result.reusedObjects)
            assertEquals(3L, result.writtenObjects)
            assertEquals(1000L, coordinator.ackedObjects.get())
            val reader = fakes.single()
            val segEnd = File(fixture.dataFiles, "data.000").length()
            assertEquals("one whole-segment pass, no over-read", segEnd - 480L, reader.readsServed)
            ForeverCasc.verifyExisting(
                fixture.dataFiles.parentFile!!.parentFile!!,
                fixture.manifest.map { it.toRuntimeSpec() },
                { false },
            )
        } finally {
            delete(fixture.root)
        }
    }


    @Test
    fun overlapArmingRejectsPartialWiringWithoutFallback() {
        val fixture = buildOverlapFixture(prefixSize = 2, newSize = 1, name = "arming")
        try {
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            // Armed coordinator with an empty (fresh-install) checkpoint: the
            // initial install keeps the old writer with no verifier thread.
            val fresh = tempDir()
            try {
                val freshData = File(fresh, "stage/Data/data").also { assertTrue(it.mkdirs()) }
                val freshCheckpoint = File(fresh, "checkpoint").also { assertTrue(it.mkdirs()) }
                val freshManifest = fixture.manifest.take(1)
                val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    ForeverCasc.writeTargetIncrementalResumable(
                        null,
                        freshData,
                        freshCheckpoint,
                        freshManifest.asSequence(),
                        { spec -> fixture.rawByKey.getValue(spec.ekey) },
                        { false },
                        "fresh-install-identity",
                        { _, _ -> },
                        replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled) },
                        overlapCoordinator = coordinator,
                        overlapOfficialStream = overlapOfficialStreamOf(fixture),
                    )
                }
                assertTrue(error.message.orEmpty().contains("without a checkpointed prefix"))
            } finally {
                delete(fresh)
            }
            // Coordinator without the official-tuple lookup is misuse, never a
            // silent downgrade.
            val misuse = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.writeTargetIncrementalResumable(
                    null,
                    fixture.dataFiles,
                    fixture.checkpointDir,
                    fixture.manifest.asSequence(),
                    { spec -> fixture.rawByKey.getValue(spec.ekey) },
                    { false },
                    fixture.identity,
                    { _, _ -> },
                    replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled) },
                    overlapCoordinator = coordinator,
                    overlapOfficialStream = null,
                )
            }
            assertTrue(misuse.message.orEmpty().contains("partially armed"))
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapSealedLayoutCostFitsPlannedBoundsExactly() {
        // Worked example for the admission-cost claim: sealing the last frozen
        // segment costs exactly one 480-byte header plus its 16 index records
        // on the new segment — no gigabyte preallocation, no hidden tail.
        // The read-only plan already packs missing objects into fresh 480-based
        // segments, so this sealed layout needs zero post-consent delta; only
        // the 0..1023 ID range needs the explicit pre-consent probe (covered
        // by requireOverlapSegmentCapacity and the engine's buildPlan check).
        val fixture = buildOverlapFixture(prefixSize = 3, newSize = 5, name = "seal-cost")
        try {
            val fakes = ArrayList<FakeCascReplayReader>()
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            val result = ForeverCasc.writeTargetIncrementalResumable(
                null,
                fixture.dataFiles,
                fixture.checkpointDir,
                fixture.manifest.asSequence(),
                { spec -> fixture.rawByKey.getValue(spec.ekey) },
                { false },
                fixture.identity,
                { _, _ -> },
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
                overlapCoordinator = coordinator,
                overlapOfficialStream = overlapOfficialStreamOf(fixture),
            )
            assertEquals(3L, result.reusedObjects)
            assertEquals(5L, result.writtenObjects)
            // New segments start above the frozen maximum with offset 480.
            val newSegments = fixture.dataFiles.listFiles()!!.filter { it.name.matches(Regex("data\\.[0-9]{3,4}")) }.sortedBy { it.name }
            assertEquals(listOf("data.000", "data.001"), newSegments.map { it.name })
            val newTail = fixture.manifest.drop(3)
            val expectedNewBytes = 480L + newTail.sumOf { 30L + it.encodedSize }
            assertEquals(expectedNewBytes, File(fixture.dataFiles, "data.001").length())
            assertTrue("no segment preallocation waste", File(fixture.dataFiles, "data.001").length() < 1024L * 1024L)
            // Indices hold exactly the admitted records: 3 frozen + 5 new +
            // 16 reconstruction records per owned segment, bounded per bucket.
            val indices = fixture.dataFiles.listFiles()!!.filter { it.name.endsWith(".idx") }
            assertEquals(16, indices.size)
            indices.forEach { index ->
                assertEquals(0x30000, index.length())
            }
            assertEquals(3 + 5 + 32, totalIndexRecords(fixture.dataFiles))
            // Per-object bytes are identical to a serial fresh layout for the
            // same objects: the seal changes segment IDs (embedded in each
            // 30-byte local header), never payloads or framing.
            newTail.forEach { spec ->
                assertIndependentNewRecord(fixture.dataFiles, spec)
                assertTrue(indexedRaw(fixture.dataFiles, spec.ekey).contentEquals(fixture.rawByKey.getValue(spec.ekey).readBytes()))
            }
        } finally {
            delete(fixture.root)
        }
    }

    @Test
    fun overlapFinalizedCheckpointResumesWithoutFallback() {
        // A fully finalized checkpoint (FINISH frame plus indices, as left by
        // an uninterrupted run) still arms overlap: the load recovers the
        // finalizing state once, the freeze covers the complete prefix, and a
        // re-run with no new objects verifies and re-finalizes.
        val root = tempDir()
        try {
            val decoded = (0 until 3).map { "finalized-$it".toByteArray() }
            val manifest = decoded.map { bytes ->
                val raw = blteRaw(bytes)
                ForeverCasc.IncrementalObjectSpec(encodedEkey(raw), md5Hex(bytes), raw.size.toLong(), bytes.size.toLong())
            }
            val dataFiles = File(root, "stage/Data/data").also { assertTrue(it.mkdirs()) }
            val checkpointDir = File(root, "checkpoint").also { assertTrue(it.mkdirs()) }
            val raws = manifest.mapIndexed { index, spec -> spec to file(root, "raws/$index.blte", blteRaw(decoded[index])) }.toMap()
            val first = ForeverCasc.writeTargetIncrementalResumable(
                null,
                dataFiles,
                checkpointDir,
                manifest.asSequence(),
                { spec -> raws.getValue(spec) },
                { false },
                "finalized-identity",
                { _, raw -> if (raw.exists()) assertTrue(raw.delete()) },
            )
            assertEquals(0L, first.reusedObjects)
            assertEquals(3L, first.writtenObjects)
            assertTrue(dataFiles.listFiles()!!.any { it.name.endsWith(".idx") })
            // Same manifest, now fully checkpointed: overlap arms (non-empty
            // prefix) and completes with zero new writes.
            val fakes = ArrayList<FakeCascReplayReader>()
            val coordinator = CascOverlapCoordinator(retirePrefixRaw = { })
            val second = ForeverCasc.writeTargetIncrementalResumable(
                null,
                dataFiles,
                checkpointDir,
                manifest.asSequence(),
                { spec -> raws.getValue(spec) },
                { false },
                "finalized-identity",
                { _, _ -> },
                replayReaderFactory = { cancelled -> FakeCascReplayReader(cancelled).also { fakes += it } },
                overlapCoordinator = coordinator,
                overlapOfficialStream = { _, consume -> consume(manifest.asSequence()) },
            )
            assertEquals(3L, second.reusedObjects)
            assertEquals(0L, second.writtenObjects)
            assertTrue("verifier must have run for the finalized prefix", fakes.isNotEmpty())
            ForeverCasc.verifyExisting(
                dataFiles.parentFile!!.parentFile!!,
                manifest.map { it.toRuntimeSpec() },
                { false },
            )
        } finally {
            delete(root)
        }
    }

    @Test
    fun orderedSourceNullPlanMatchesGenericPlanExactly() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            assertOrderedMatchesGeneric(emptyList(), workParent, "empty")
            // Small ordered manifest with mixed-case keys: normalization to
            // lowercase is shared, so both routes compare the same order.
            val small = listOf(
                ForeverCasc.IncrementalObjectSpec("aa".repeat(16), "10".repeat(16), 11, 13),
                ForeverCasc.IncrementalObjectSpec("BB".repeat(16), "20".repeat(16), 17, 19),
                ForeverCasc.IncrementalObjectSpec("cc".repeat(16), "30".repeat(16), 23, 29),
            )
            val smallPlan = assertOrderedMatchesGeneric(small, workParent, "small")
            assertEquals(3L, smallPlan.totalObjects)
            assertEquals(0L, smallPlan.verifiedObjects)
            assertEquals(3L, smallPlan.missingObjects)
            assertEquals(51L, smallPlan.downloadBytes)
            assertEquals(0L, smallPlan.reusedEncodedBytes)
            assertEquals(1, smallPlan.newSegmentCount)
            assertEquals(480L + 3L * 30L + 51L, smallPlan.newSegmentBytesUpperBound)
            // Virtual segment crossing with GiB-scale sizes but no files: the
            // planner only packs numbers, never materializes objects.
            val crossingSize = (1L shl 30) - 480L - 30L - 500L
            val crossing = listOf(
                ForeverCasc.IncrementalObjectSpec("44".repeat(16), "40".repeat(16), crossingSize, 0),
                ForeverCasc.IncrementalObjectSpec("55".repeat(16), "50".repeat(16), 1000, 0),
                ForeverCasc.IncrementalObjectSpec("66".repeat(16), "60".repeat(16), 10, 0),
            )
            val crossingPlan = assertOrderedMatchesGeneric(crossing, workParent, "crossing")
            assertEquals(3L, crossingPlan.totalObjects)
            assertEquals(0L, crossingPlan.verifiedObjects)
            assertEquals(3L, crossingPlan.missingObjects)
            assertEquals(crossingSize + 1010L, crossingPlan.downloadBytes)
            assertEquals(0L, crossingPlan.reusedEncodedBytes)
            assertEquals(2, crossingPlan.newSegmentCount)
            assertEquals(2L * 480L + 3L * 30L + crossingSize + 1010L, crossingPlan.newSegmentBytesUpperBound)
        } finally {
            delete(root)
        }
    }

    @Test
    fun orderedSourceNullPlanRejectsDuplicatePrefixCollisionAndUnordered() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val duplicate = ForeverCasc.IncrementalObjectSpec("ab".repeat(16), "a".repeat(32), 7, 9)
            var callbacks = 0
            val duplicateError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(File(workParent, "duplicate"), sequenceOf(duplicate, duplicate), { false }) { _, _ ->
                    callbacks++
                }
            }
            assertTrue(duplicateError.message.orEmpty().contains("duplicate EKey"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "duplicate").exists())

            callbacks = 0
            val head = "123456789abcdef012" + "0".repeat(14)
            val colliding = head.substring(0, 18) + "1".repeat(14)
            val collisionError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(
                    File(workParent, "collision"),
                    sequenceOf(
                        duplicate.copy(ekey = head),
                        duplicate.copy(ekey = colliding),
                    ),
                    { false },
                ) { _, _ -> callbacks++ }
            }
            assertTrue(collisionError.message.orEmpty().contains("short EKey prefix collision"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "collision").exists())

            // Strictly decreasing keys with distinct prefixes: the order guard
            // fires before anything could be falsely accepted as missing.
            callbacks = 0
            val unorderedError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(
                    File(workParent, "unordered"),
                    sequenceOf(
                        duplicate.copy(ekey = "2".repeat(32)),
                        duplicate.copy(ekey = "1".repeat(32)),
                    ),
                    { false },
                ) { _, _ -> callbacks++ }
            }
            assertTrue(unorderedError.message.orEmpty().contains("strictly sorted EKey order"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "unordered").exists())

            // Same 9-byte prefix in decreasing full-key order: still an order
            // violation, never a silent accept and never a prefix collision.
            callbacks = 0
            val reversedCollisionError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(
                    File(workParent, "reversed-collision"),
                    sequenceOf(
                        duplicate.copy(ekey = colliding),
                        duplicate.copy(ekey = head),
                    ),
                    { false },
                ) { _, _ -> callbacks++ }
            }
            assertTrue(reversedCollisionError.message.orEmpty().contains("strictly sorted EKey order"))
            assertEquals(1, callbacks)
            assertFalse(File(workParent, "reversed-collision").exists())

            // The reserved all-zero 9-byte prefix still fails through the
            // shared spec validation before any row is reported.
            callbacks = 0
            val zeroPrefix = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(
                    File(workParent, "zero-prefix"),
                    sequenceOf(duplicate.copy(ekey = "0".repeat(18) + "1".repeat(14))),
                    { false },
                ) { _, _ -> callbacks++ }
            }
            assertTrue(zeroPrefix.message.orEmpty().contains("all-zero 9-byte"))
            assertEquals(0, callbacks)
            assertFalse(File(workParent, "zero-prefix").exists())
        } finally {
            delete(root)
        }
    }

    @Test
    fun orderedSourceNullPlanEnforcesArchiveAndRecordBounds() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val maximumObject = (1L shl 30) - 480L - 30L
            var callbacks = 0
            val oversizedError = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(
                    File(workParent, "oversized-plan"),
                    sequenceOf(
                        ForeverCasc.IncrementalObjectSpec("9".repeat(32), "0".repeat(32), maximumObject + 1L, 0),
                    ),
                    { false },
                ) { _, _ -> callbacks++ }
            }
            assertTrue(oversizedError.message.orEmpty().contains("archive limit"))
            assertEquals(0, callbacks)
            assertFalse(File(workParent, "oversized-plan").exists())

            // 1025 maximum-size objects need 1025 segments while the 0..1023 ID
            // range holds 1024. Both routes must agree on the guard error and
            // on the 1024 rows reported before it.
            val overflowSpecs = (1..1025).map { index ->
                ForeverCasc.IncrementalObjectSpec("%018x".format(index) + "0".repeat(14), "0".repeat(32), maximumObject, 0)
            }
            var genericCallbacks = 0
            val genericError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTarget(null, File(workParent, "generic-overflow"), overflowSpecs.asSequence(), { false }) { _, _ ->
                    genericCallbacks++
                }
            }
            var orderedCallbacks = 0
            val orderedError = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(File(workParent, "ordered-overflow"), overflowSpecs.asSequence(), { false }) { _, _ ->
                    orderedCallbacks++
                }
            }
            assertEquals(genericError.message, orderedError.message)
            assertEquals(genericCallbacks, orderedCallbacks)
            assertEquals(1024, orderedCallbacks)
            assertFalse(File(workParent, "generic-overflow").exists())
            assertFalse(File(workParent, "ordered-overflow").exists())
        } finally {
            delete(root)
        }
    }

    @Test
    fun orderedSourceNullPlanCancellationRemovesOnlyItsOwnedScratch() {
        val root = tempDir()
        try {
            val workParent = File(root, "app-cache").also { assertTrue(it.mkdirs()) }
            val work = File(workParent, "cancelled-plan")
            val unrelated = file(workParent, "caller-owned.bin", byteArrayOf(9, 8, 7))
            val specs = sequence {
                yield(ForeverCasc.IncrementalObjectSpec("5".repeat(32), "6".repeat(32), 3, 4))
                yield(ForeverCasc.IncrementalObjectSpec("7".repeat(32), "8".repeat(32), 5, 6))
            }
            var cancelNext = false
            var callbacks = 0
            org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                ForeverCasc.inspectTargetOrderedSourceNull(work, specs, { cancelNext }) { _, _ ->
                    callbacks++
                    cancelNext = true
                }
            }
            assertEquals(1, callbacks)
            assertFalse(work.exists())
            assertEquals(listOf(9.toByte(), 8.toByte(), 7.toByte()), unrelated.readBytes().toList())
        } finally {
            delete(root)
        }
    }

    private fun assertOrderedMatchesGeneric(
        specs: List<ForeverCasc.IncrementalObjectSpec>,
        workParent: File,
        name: String,
    ): ForeverCasc.TargetScanResult {
        val genericCallbacks = mutableListOf<Pair<String, Boolean>>()
        val generic = ForeverCasc.inspectTarget(null, File(workParent, "generic-$name"), specs.asSequence(), { false }) { spec, verified ->
            genericCallbacks += spec.ekey to verified
        }
        val orderedWork = File(workParent, "ordered-$name")
        val orderedCallbacks = mutableListOf<Pair<String, Boolean>>()
        val ordered = ForeverCasc.inspectTargetOrderedSourceNull(orderedWork, specs.asSequence(), { false }) { spec, verified ->
            assertFalse(
                "ordered source-null plan must not create a disk-backed target table",
                File(orderedWork, "target-records.bin").exists() ||
                    File(orderedWork, "candidate-prefixes.bin").exists() ||
                    File(orderedWork, "verified-object.tmp").exists(),
            )
            orderedCallbacks += spec.ekey to verified
        }
        assertEquals(generic, ordered)
        assertEquals(genericCallbacks, orderedCallbacks)
        assertTrue("ordered source-null plan reports every object as missing", orderedCallbacks.all { !it.second })
        assertFalse(File(workParent, "generic-$name").exists())
        assertFalse(orderedWork.exists())
        return ordered
    }

    private fun ForeverCasc.IncrementalObjectSpec.toRuntimeSpec() =
        ForeverCasc.RuntimeObjectSpec(ekey, contentKey, decodedSize)

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "casc-test-${System.nanoTime()}").also { it.mkdirs() }
    private fun openFdCount(): Int = File("/proc/self/fd").listFiles()?.size ?: -1
    private fun file(parent: File, name: String, bytes: ByteArray): File = File(parent, name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
    private fun delete(file: File) { file.deleteRecursively() }
    private fun directFileHashes(directory: File): Map<String, String> = directory.listFiles()!!.associate { it.name to sha256Hex(it.readBytes()) }
    private fun localEntry(dataFiles: File, ekey: String): Triple<Int, Long, Long> {
        val prefix = ekey.hexBytes().copyOfRange(0, 9)
        dataFiles.listFiles()!!.filter { it.name.endsWith(".idx") }.forEach { index ->
            val bytes = index.readBytes()
            val end = 0x28 + u32(bytes, 0x20)
            var at = 0x28
            while (at < end) {
                if (bytes.copyOfRange(at, at + 9).contentEquals(prefix)) {
                    val location = u32be(bytes, at + 10)
                    val archive = ((bytes[at + 9].toInt() and 0xff) shl 2) or ((location ushr 30) and 3)
                    val size = u32(bytes, at + 14).toLong() and 0xffffffffL
                    return Triple(archive, (location and 0x3fffffff).toLong(), size)
                }
                at += 18
            }
        }
        error("EKey not found in local indices")
    }
    private fun assertIndependentNewRecord(dataFiles: File, spec: ForeverCasc.IncrementalObjectSpec) {
        val location = localEntry(dataFiles, spec.ekey)
        val archive = File(dataFiles, "data.%03d".format(location.first))
        RandomAccessFile(archive, "r").use { input ->
            input.seek(location.second)
            val header = ByteArray(30)
            input.readFully(header)
            assertEquals(spec.ekey.hexBytes().reversedArray().toList(), header.copyOfRange(0, 16).toList())
            assertEquals(location.third.toInt(), u32(header, 0x10))
            assertEquals(hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971), u32(header, 0x16))
            val globalOffset = location.first * (1L shl 30) + location.second
            assertEquals(checksumB(header, globalOffset), u32(header, 0x1a))
            val raw = ByteArray((location.third - 30).toInt())
            input.readFully(raw)
            assertEquals(spec.ekey, encodedEkey(raw))
        }
    }
    private fun bucketZeroEkey(index: Long): String {
        val bytes = ByteArray(16)
        var value = index
        var xor = 0
        repeat(8) { slot ->
            val byte = value.toByte()
            bytes[slot] = byte
            xor = xor xor (byte.toInt() and 0xff)
            value = value ushr 8
        }
        bytes[8] = xor.toByte()
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun rawForBucketZero(root: File): Triple<ByteArray, ByteArray, File> {
        var index = 0
        while (true) {
            val decoded = "new-capacity-object-$index".toByteArray()
            val raw = blteRaw(decoded)
            if (bucket(encodedEkey(raw).hexBytes()) == 0) return Triple(decoded, raw, file(root, "encoded/new-capacity.blte", raw))
            index++
        }
    }
    private fun writeMetadataOnlyLargeStore(dataFiles: File, totalRecords: Int) {
        val quotient = totalRecords / 16
        val remainder = totalRecords % 16
        val counts = IntArray(16) { quotient + if (it < remainder) 1 else 0 }
        val indexFiles = Array(16) { bucket -> File(dataFiles, "%02x%08x.idx".format(bucket, 1)) }
        val indexOutputs = Array(16) { bucket ->
            BufferedOutputStream(
                Files.newOutputStream(
                    indexFiles[bucket].toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
                64 * 1024,
            )
        }
        val checksumsA = IntArray(16)
        val checksumsB = IntArray(16)
        indexOutputs.forEachIndexed { bucket, output ->
            val header = fixtureV7Header(bucket, counts[bucket])
            output.write(header)
        }
        val archiveFile = File(dataFiles, "data.000")
        BufferedOutputStream(
            Files.newOutputStream(
                archiveFile.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
            64 * 1024,
        ).use { archive ->
            var globalRecord = 0L
            for (bucket in 0 until 16) {
                // All-zero 9-byte prefixes are reserved by CASC readers, so keep
                // the first synthetic key away from the empty-entry sentinel.
                var counter = 1L
                repeat(counts[bucket]) {
                    val key = syntheticKeyForBucket(counter++, bucket)
                    val offset = globalRecord * 30L
                    val local = fixtureLocalHeader(key, offset)
                    archive.write(local)
                    val entry = ByteArray(18)
                    key.copyInto(entry, 0, 0, 9)
                    putU32be(entry, 10, offset.toInt())
                    putU32le(entry, 14, 30)
                    indexOutputs[bucket].write(entry)
                    val hashes = hashlittle2(entry, checksumsA[bucket], checksumsB[bucket])
                    checksumsA[bucket] = hashes.first
                    checksumsB[bucket] = hashes.second
                    globalRecord++
                }
            }
        }
        indexOutputs.forEachIndexed { bucket, output ->
            val finalLength = maxOf(0x30000L, roundUpIndexLength(0x28L + counts[bucket].toLong() * 18L))
            var padding = finalLength - 0x28L - counts[bucket].toLong() * 18L
            val zeros = ByteArray(4096)
            while (padding > 0) {
                val count = minOf(padding, zeros.size.toLong()).toInt()
                output.write(zeros, 0, count)
                padding -= count
            }
            output.close()
            RandomAccessFile(indexFiles[bucket], "rw").use { index ->
                index.seek(0x24)
                index.write(byteArrayOf(
                    checksumsA[bucket].toByte(),
                    (checksumsA[bucket] ushr 8).toByte(),
                    (checksumsA[bucket] ushr 16).toByte(),
                    (checksumsA[bucket] ushr 24).toByte(),
                ))
            }
            assertEquals(finalLength, indexFiles[bucket].length())
        }
        assertEquals(totalRecords.toLong() * 30L, archiveFile.length())
    }

    private fun syntheticKeyForBucket(counter: Long, expectedBucket: Int): ByteArray {
        val key = ByteArray(16)
        for (index in 0 until 8) key[index] = (counter ushr ((7 - index) * 8)).toByte()
        for (candidate in 0..255) {
            key[8] = candidate.toByte()
            if (bucket(key, 1) == expectedBucket) return key
        }
        error("cannot synthesize EKey for bucket $expectedBucket")
    }

    private fun fixtureV7Header(bucket: Int, recordCount: Int): ByteArray {
        val header = ByteArray(0x28)
        putU32le(header, 0, 16)
        putU16le(header, 8, 7)
        header[10] = bucket.toByte()
        header[12] = 4
        header[13] = 5
        header[14] = 9
        header[15] = 30
        putU32le(header, 16, 0)
        putU32le(header, 20, 64)
        putU32le(header, 4, hashlittle(header.copyOfRange(8, 24), 0).toLong() and 0xffffffffL)
        putU32le(header, 0x20, recordCount.toLong() * 18L)
        return header
    }

    private fun fixtureLocalHeader(key: ByteArray, offset: Long): ByteArray {
        val header = ByteArray(30)
        key.reversedArray().copyInto(header)
        putU32le(header, 0x10, 30)
        putU16le(header, 0x14, 1)
        putU32le(header, 0x16, hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971).toLong() and 0xffffffffL)
        putU32le(header, 0x1a, checksumB(header, offset).toLong() and 0xffffffffL)
        return header
    }

    private fun directFileHashesStreaming(directory: File): Map<String, String> = directory.listFiles()!!.associate { file ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        file.name to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun countIndexRecords(bytes: ByteArray): Int = u32(bytes, 0x20) / 18
    private fun indexSortedChecksum(bytes: ByteArray): Int {
        var pc = 0
        var pb = 0
        var at = 0x28
        val end = at + u32(bytes, 0x20)
        while (at < end) {
            val hashes = hashlittle2(bytes.copyOfRange(at, at + 18), pc, pb)
            pc = hashes.first
            pb = hashes.second
            at += 18
        }
        return pc
    }
    private fun roundUpIndexLength(size: Long): Long = (size + 0xfffL) and -0x1000L
    private fun putU16le(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }
    private fun putU32le(bytes: ByteArray, at: Int, value: Long) {
        repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() }
    }
    private fun putU32be(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }
    private fun blteChunked(value: ByteArray): ByteArray {
        val compressed = Deflater().let { deflater ->
            deflater.setInput(value)
            deflater.finish()
            val buffer = ByteArray(4096)
            val count = deflater.deflate(buffer)
            deflater.end()
            buffer.copyOf(count)
        }
        val payload = byteArrayOf('Z'.code.toByte()) + compressed
        val header = u32beBytes(36) + byteArrayOf(0x0f, 0, 0, 1) +
            u32beBytes(payload.size) + u32beBytes(value.size) + md5Bytes(payload)
        return byteArrayOf(0x42, 0x4c, 0x54, 0x45) + header + payload
    }
    private fun encodedEkey(raw: ByteArray): String {
        val magic = byteArrayOf(0x42, 0x4c, 0x54, 0x45)
        if (raw.size < 8 || !raw.copyOfRange(0, 4).contentEquals(magic)) return md5Hex(raw)
        val headerSize = u32be(raw, 4).toLong() and 0xffffffffL
        return if (headerSize == 0L) md5Hex(raw) else md5Hex(raw.copyOfRange(0, headerSize.toInt()))
    }
    private fun indexedRaw(dataFiles: File, ekey: String): ByteArray {
        val location = localEntry(dataFiles, ekey)
        return RandomAccessFile(File(dataFiles, "data.%03d".format(location.first)), "r").use { archive ->
            archive.seek(location.second + 30)
            ByteArray((location.third - 30).toInt()).also { archive.readFully(it) }
        }
    }
    private fun u32beBytes(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun blteRaw(value: ByteArray): ByteArray = byteArrayOf(0x42, 0x4c, 0x54, 0x45) + byteArrayOf(0, 0, 0, 0) + byteArrayOf('N'.code.toByte()) + value
    private fun md5Hex(value: ByteArray): String = MessageDigest.getInstance("MD5").digest(value).joinToString("") { "%02x".format(it) }
    private fun md5Bytes(value: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(value)
    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun bucket(key: ByteArray, seed: Int = 0): Int { var value = 0; repeat(9) { value = value xor (key[it].toInt() and 0xff) }; return ((((value ushr 4) xor value) + seed) and 0xf) }
    private fun findEntry(bytes: ByteArray, key: ByteArray): Int {
        val size = u32(bytes, 0x20)
        var at = 0x28
        while (at < 0x28 + size) { if (bytes.copyOfRange(at, at + 9).contentEquals(key)) return at; at += 18 }
        error("entry not found")
    }
    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)
    private fun u32be(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
    private fun rot(value: Int, shift: Int) = (value shl shift) or (value ushr (32 - shift))
    private fun mix(v: IntArray) { var a=v[0];var b=v[1];var c=v[2];a-=c;a=a xor rot(c,4);c+=b;b-=a;b=b xor rot(a,6);a+=c;c-=b;c=c xor rot(b,8);b+=a;a-=c;a=a xor rot(c,16);c+=b;b-=a;b=b xor rot(a,19);a+=c;c-=b;c=c xor rot(b,4);b+=a;v[0]=a;v[1]=b;v[2]=c }
    private fun finish(v: IntArray) { var a=v[0];var b=v[1];var c=v[2];c=c xor b;c-=rot(b,14);a=a xor c;a-=rot(c,11);b=b xor a;b-=rot(a,25);c=c xor b;c-=rot(b,16);a=a xor c;a-=rot(c,4);b=b xor a;b-=rot(a,14);c=c xor b;c-=rot(b,24);v[0]=a;v[1]=b;v[2]=c }
    private fun hashlittle(data: ByteArray, seed: Int): Int { var o=0;var l=data.size;val v=intArrayOf(-559038737+l+seed,-559038737+l+seed,-559038737+l+seed);while(l>12){word(v,0,data,o);word(v,1,data,o+4);word(v,2,data,o+8);mix(v);o+=12;l-=12};for(i in 0 until minOf(l,4))v[0]+=(data[o+i].toInt()and 255)shl(8*i);for(i in 4 until minOf(l,8))v[1]+=(data[o+i].toInt()and 255)shl(8*(i-4));for(i in 8 until l)v[2]+=(data[o+i].toInt()and 255)shl(8*(i-8));finish(v);return v[2] }
    private fun hashlittle2(data: ByteArray, pc: Int, pb: Int): Pair<Int,Int> { var o=0;var l=data.size;val v=intArrayOf(-559038737+l+pc,-559038737+l+pc,-559038737+l+pc+pb);while(l>12){word(v,0,data,o);word(v,1,data,o+4);word(v,2,data,o+8);mix(v);o+=12;l-=12};for(i in 0 until minOf(l,4))v[0]+=(data[o+i].toInt()and 255)shl(8*i);for(i in 4 until minOf(l,8))v[1]+=(data[o+i].toInt()and 255)shl(8*(i-4));for(i in 8 until l)v[2]+=(data[o+i].toInt()and 255)shl(8*(i-8));finish(v);return v[2] to v[1] }
    private fun word(v: IntArray, i: Int, d: ByteArray, at: Int) { v[i]+=(d[at].toInt()and 255)or((d[at+1].toInt()and 255)shl 8)or((d[at+2].toInt()and 255)shl 16)or(d[at+3].toInt()shl 24) }
    private val lut = intArrayOf(0x049396B8,0x72A82A9B.toInt(),0xEE626CCA.toInt(),0x9917754F.toInt(),0x15DE40B1,0xF5A8A9B6.toInt(),0x421EAC7E,0xA9D55C9A.toInt(),0x317FD40C,0x04FAF80D,0x3D6BE971,0x52933CFD,0x27F64B7D,0xC6F5C11B.toInt(),0xD5757E3A.toInt(),0x6C388745)
     private fun checksumB(header: ByteArray, offset: Long): Int { val a=IntArray(4);for(i in 0 until 0x1a){val s=(offset.toInt()+i)and 3;a[s]=a[s] xor (header[i].toInt()and 255)};val plus=((offset+30)and 0xffffffffL).toInt();val mask=lut[plus and 15] xor plus;val m=byteArrayOf(mask.toByte(),(mask ushr 8).toByte(),(mask ushr 16).toByte(),(mask ushr 24).toByte());val r=ByteArray(4);for(i in 0 until 4){val s=(offset.toInt()+0x1a+i)and 3;r[i]=(a[s] xor (m[s].toInt()and 255)).toByte()};return u32(r,0)}
}
