package app.gamenative

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Small inert metadata/asset fixtures only; no catalog, client, or game payload is included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverClientUpdateServiceTest {
    @Test
    fun buildInfoParserRequiresOneBoundedUnambiguousPsvRecord() {
        val bytes = priorBuildInfo()

        val parsed = ForeverClientUpdateService.parseBuildInfo(bytes)

        assertNotNull(parsed)
        assertEquals("1.60.1.70009", parsed?.version)
        assertEquals("05215079e3905ef5922ae0b03ffefb73", parsed?.buildConfigHash)
        assertEquals(70_009L, parsed?.buildId)
        assertEquals("Windows arm64", parsed?.platformTag)
        assertEquals("enUS", parsed?.locale)
        assertTrue(parsed?.isArm64 == true)
        assertNull(ForeverClientUpdateService.parseBuildInfo(bytes.copyOf(bytes.size - 1)))
        assertNull(ForeverClientUpdateService.parseBuildInfo(bytes + "\n$PRIOR_VALUES\n".toByteArray()))
        val duplicateHeader = String(bytes, Charsets.UTF_8).replace("Build Key!HEX:16", "Branch!HEX:16").toByteArray()
        assertNull(ForeverClientUpdateService.parseBuildInfo(duplicateHeader))
        assertNull(ForeverClientUpdateService.parseBuildInfo(ByteArray(64 * 1024 + 1) { 'x'.code.toByte() }))
    }

    @Test
    fun pastedClientVerdictAppliesBuildProductAndPlatformRulesInD8Order() {
        val target = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70450", "wow_classic_beta", "us", "Windows arm64 US? enUS", "84".repeat(16), "f0".repeat(16)),
        )
        val prior = ForeverClientUpdateService.parseBuildInfo(priorBuildInfo())
        val unknown = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70333", "wow_classic_beta", "us", "Windows arm64 US? enUS", "12".repeat(16), "34".repeat(16)),
        )
        val x64 = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70333", "wow_classic_beta", "us", "Windows x86_64 US? enUS", "12".repeat(16), "34".repeat(16)),
        )
        val otherProduct = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70333", "wow_retail", "us", "Windows x86_64 US? enUS", "12".repeat(16), "34".repeat(16)),
        )
        val otherRegion = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70333", "wow_classic_beta", "eu", "Windows arm64 EU? enUS", "12".repeat(16), "34".repeat(16)),
        )
        val otherLocale = ForeverClientUpdateService.parseBuildInfo(
            buildInfo("1.60.1.70333", "wow_classic_beta", "us", "Windows arm64 US? frFR", "12".repeat(16), "34".repeat(16)),
        )
        assertNotNull(target)
        assertNotNull(prior)
        assertNotNull(unknown)

        fun classify(
            hasCasc: Boolean,
            hasBuildInfo: Boolean,
            info: ForeverClientUpdateService.BuildInfoFingerprint?,
            targetVerified: Boolean = false,
            priorVerified: Boolean = false,
        ) = ForeverClientUpdateService.classifyPastedClientMetadata(
            hasCasc,
            hasBuildInfo,
            info,
            targetVerified,
            priorVerified,
        )

        assertEquals(PastedClientVerdict.NOT_CASC, classify(false, true, target, targetVerified = true))
        assertEquals(PastedClientVerdict.WRONG_PRODUCT, classify(true, true, otherProduct, targetVerified = true))
        assertEquals(PastedClientVerdict.WRONG_PRODUCT, classify(true, true, otherRegion, priorVerified = true))
        assertEquals(PastedClientVerdict.WRONG_PRODUCT, classify(true, true, otherLocale))
        assertEquals(PastedClientVerdict.NOT_ARM64, classify(true, true, x64, targetVerified = true))
        assertEquals(PastedClientVerdict.TARGET_BUILD, classify(true, true, target, targetVerified = true))
        assertEquals(PastedClientVerdict.PRIOR_BUILD, classify(true, true, prior, priorVerified = true))
        assertEquals(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE, classify(true, true, unknown))
        assertEquals(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE, classify(true, false, null))
        assertEquals(PastedClientVerdict.NOT_CASC, classify(true, true, null))
    }

    @Test
    fun storageMarkerIdentityComparisonDistinguishesTheSelectedVolumeFromCopiedMarkers() {
        val currentVolumeMarker = "forever-client-storage-v1\nvolume-current\n".toByteArray()
        val copiedVolumeMarker = "forever-client-storage-v1\nvolume-other\n".toByteArray()

        assertTrue(ForeverClientUpdateService.storageMarkerMatches(currentVolumeMarker, "volume-current"))
        assertFalse(ForeverClientUpdateService.storageMarkerMatches(copiedVolumeMarker, "volume-current"))
        assertFalse(ForeverClientUpdateService.storageMarkerMatches("bad marker".toByteArray(), "volume-current"))
    }

    @Test
    fun pastedSourceCompletionAdmitsOnlyVerifiedCascSourceVerdicts() {
        fun report(verdict: PastedClientVerdict, present: Boolean = true) = PastedClientReport(
            verdict = verdict,
            present = present,
            hasCascData = verdict != PastedClientVerdict.NONE,
            version = null,
            buildId = 0L,
            product = null,
            platformTag = null,
            isArm64 = true,
            matchesTargetBuild = false,
            matchesPriorBuild = false,
            unexpectedEntries = emptyList(),
        )

        assertFalse(ForeverClientUpdateService.isPastedSourceCompletable(null))
        assertFalse(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.NONE, present = false)))
        assertFalse(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.NOT_CASC)))
        assertFalse(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.WRONG_PRODUCT)))
        assertFalse(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.NOT_ARM64)))
        assertTrue(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.TARGET_BUILD)))
        assertTrue(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.PRIOR_BUILD)))
        assertTrue(ForeverClientUpdateService.isPastedSourceCompletable(report(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE)))
    }

    @Test
    fun buildInfoFingerprintIsNotAdoptedWithoutMatchingBuildAndFileManifestProof() {
        val valid = priorBuildInfo()

        assertNull(
            ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                valid,
                "1.60.1.70009",
                PRIOR_BUILD,
                PRIOR_CDN,
                manifestVerified = false,
            ),
        )
        assertNull(
            ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                valid,
                "1.60.1.70009",
                "0".repeat(32),
                PRIOR_CDN,
                manifestVerified = true,
            ),
        )
        val fingerprint = ForeverClientUpdateService.verifiedBuildInfoFingerprint(
            valid,
            "1.60.1.70009",
            PRIOR_BUILD,
            PRIOR_CDN,
            manifestVerified = true,
        )
        assertNotNull(fingerprint)
        assertEquals(valid.size.toLong(), fingerprint?.size)
        assertEquals(sha256(valid), fingerprint?.sha256)

        val targetBytes = "target-build-info".toByteArray()
        assertNull(
            ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                valid,
                "1.60.1.70425",
                "ea9c525eef74f1d682c00a8ef39a5d2b",
                "fcab7fec2ccc6b8158b56b6938ec4551",
                manifestVerified = true,
                exactBytes = targetBytes,
            ),
        )
    }

    @Test
    fun pinnedPrivateAssetPublishesFreshFileAfterBothDigestsPass() {
        Fixture().use { fixture ->
            val bytes = "small inert TACT config fixture\n".toByteArray()
            val destination = File(fixture.root, "private/config.txt")

            val result = ForeverClientUpdateService.extractPinnedAsset(
                { ByteArrayInputStream(bytes) },
                destination,
                bytes.size.toLong(),
                sha256(bytes),
                md5(bytes),
                { false },
            )

            assertEquals(destination.canonicalFile, result.canonicalFile)
            assertTrue(destination.readBytes().contentEquals(bytes))
            assertEquals(setOf("config.txt", ".forever-private-publish.lock"), destination.parentFile!!.list()!!.toSet())
            assertEquals(0L, File(destination.parentFile, ".forever-private-publish.lock").length())
        }
    }

    @Test
    fun corruptPrivateAssetIsPreservedAndAssetIsNotReimportedOverIt() {
        Fixture().use { fixture ->
            val bytes = "pinned fixture".toByteArray()
            val destination = File(fixture.root, "private/config.txt")
            destination.parentFile!!.mkdirs()
            val sentinel = "keep corrupt private file".toByteArray()
            destination.writeBytes(sentinel)
            var opened = false

            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateService.extractPinnedAsset(
                    { opened = true; ByteArrayInputStream(bytes) },
                    destination,
                    bytes.size.toLong(),
                    sha256(bytes),
                    md5(bytes),
                    { false },
                )
            }

            assertFalse(opened)
            assertTrue(destination.readBytes().contentEquals(sentinel))
            assertEquals(setOf("config.txt"), destination.parentFile!!.list()!!.toSet())
        }
    }

    @Test
    fun invalidAssetDigestDoesNotPublishFinalPrivateFile() {
        Fixture().use { fixture ->
            val bytes = "wrong fixture".toByteArray()
            val destination = File(fixture.root, "private/config.txt")

            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateService.extractPinnedAsset(
                    { ByteArrayInputStream(bytes) },
                    destination,
                    bytes.size.toLong(),
                    "0".repeat(64),
                    md5(bytes),
                    { false },
                )
            }

            assertFalse(Files.exists(destination.toPath()))
            assertEquals(emptyList<String>(), destination.parentFile!!.list()!!.toList())
        }
    }

    @Test
    fun symlinkedPrivateAssetIsNeverReplacedOrFollowed() {
        Fixture().use { fixture ->
            val bytes = "private fixture".toByteArray()
            val outside = File(fixture.root, "outside.txt").apply { writeText("preserve") }
            val destination = File(fixture.root, "private/config.txt")
            destination.parentFile!!.mkdirs()
            Files.createSymbolicLink(destination.toPath(), outside.toPath())
            var opened = false

            assertThrows(IllegalStateException::class.java) {
                ForeverClientUpdateService.extractPinnedAsset(
                    { opened = true; ByteArrayInputStream(bytes) },
                    destination,
                    bytes.size.toLong(),
                    sha256(bytes),
                    md5(bytes),
                    { false },
                )
            }

            assertFalse(opened)
            assertTrue(Files.isSymbolicLink(destination.toPath()))
            assertEquals("preserve", outside.readText())
        }
    }

    @Test
    fun managedAdmissionRecordIsBoundedChecksummedAndCanProveAnEarlierApkTarget() {
        val target = admissionTarget(70500, "a1".repeat(16), "b2".repeat(16))
        val storageId = "storage-${"4".repeat(64)}"
        val storageIdentity = "volume:usb-test"
        val rootPath = "/storage/usb-test/Android/data/app.test/files/CustomGames/WoW Forever"
        val buildInfo = targetBuildInfo(70500, "a1".repeat(16), "b2".repeat(16))
        val loose = listOf(
            ForeverClientUpdateService.ManagedAdmissionLooseFile("Interface/patch.mpq", "cc".repeat(16), 4_096),
            ForeverClientUpdateService.ManagedAdmissionLooseFile("WowB-ARM64.exe", "5e6f9642d56cf15dff2364a0da6a1abd", 80_562_896),
        )

        val bytes = ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
            target,
            "83".repeat(32),
            storageId,
            storageIdentity,
            rootPath,
            buildInfo,
            loose,
        )
        val record = ForeverClientUpdateService.parseManagedInstallAdmissionRecord(bytes, storageId, storageIdentity, rootPath)
        assertNotNull(record)
        assertEquals("1.60.1.70500", record?.targetBuild?.version)
        assertEquals(70_500L, record?.targetBuild?.buildId)
        assertEquals(2, record?.looseFiles?.size)
        val laterTarget = ForeverClientTargetBuild.parse(admissionTarget(70700, "d3".repeat(16), "e4".repeat(16)))
        assertTrue(laterTarget.fingerprint != record?.targetBuild?.fingerprint)

        val tampered = bytes.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() }
        assertNull(ForeverClientUpdateService.parseManagedInstallAdmissionRecord(tampered, storageId, storageIdentity, rootPath))
        assertNull(ForeverClientUpdateService.parseManagedInstallAdmissionRecord(bytes, "storage-${"5".repeat(64)}", storageIdentity, rootPath))
        assertNull(ForeverClientUpdateService.parseManagedInstallAdmissionRecord(bytes, storageId, "volume:other", rootPath))
        assertNull(ForeverClientUpdateService.parseManagedInstallAdmissionRecord(bytes, storageId, storageIdentity, "/storage/other/CustomGames/WoW Forever"))
        assertNull(ForeverClientUpdateService.parseManagedInstallAdmissionRecord(ByteArray(64 * 1024 + 1), storageId, storageIdentity, rootPath))

        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                target,
                "83".repeat(32),
                storageId,
                storageIdentity,
                rootPath,
                buildInfo,
                loose + ForeverClientUpdateService.ManagedAdmissionLooseFile("../outside", "aa".repeat(16), 1),
            )
        }
        listOf("WTF/Config.wtf", "WTF/BetaSuspendedTest.wtf").forEach { protectedPath ->
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                    target,
                    "83".repeat(32),
                    storageId,
                    storageIdentity,
                    rootPath,
                    buildInfo,
                    loose + ForeverClientUpdateService.ManagedAdmissionLooseFile(protectedPath, "aa".repeat(16), 1),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                target,
                "83".repeat(32),
                storageId,
                storageIdentity,
                rootPath,
                buildInfo,
                loose.map { if (it.path == "WowB-ARM64.exe") it.copy(contentKey = "00".repeat(16)) else it },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                target,
                "83".repeat(32),
                storageId,
                "not a volume identity!",
                rootPath,
                buildInfo,
                loose,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                target,
                "83".repeat(32),
                storageId,
                storageIdentity,
                "relative/root",
                buildInfo,
                loose,
            )
        }
        // Schema-1 fileKey-bound records are never produced anymore, but the
        // legacy parser still accepts them for the bounded launch migration.
        val legacyBytes = legacyAdmissionEnvelope(
            storageId,
            "root-identity-123",
            target,
            "83".repeat(32),
            buildInfo,
            loose,
        )
        val legacy = ForeverClientUpdateService.parseLegacyManagedInstallAdmissionRecord(legacyBytes)
        assertNotNull(legacy)
        assertEquals("1.60.1.70500", legacy?.targetBuild?.version)
        assertEquals("root-identity-123", legacy?.rootIdentity)
        assertNull(ForeverClientUpdateService.parseLegacyManagedInstallAdmissionRecord(bytes))
        assertNull(
            ForeverClientUpdateService.parseLegacyManagedInstallAdmissionRecord(
                legacyBytes.copyOf().also { it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() },
            ),
        )
    }

    @Test
    fun managedInstallAdmissionSharedHelperReadsFromAnchorAndRejectsMismatch() {
        val anchor = Files.createTempDirectory("forever-managed-admission-helper").toFile()
        try {
            val target = admissionTarget(70500, "a1".repeat(16), "b2".repeat(16))
            val storageId = "storage-${"4".repeat(64)}"
            val storageIdentity = "volume:usb-test"
            val rootPath = "/storage/usb-test/Android/data/app.test/files/CustomGames/WoW Forever"
            val buildInfo = targetBuildInfo(70500, "a1".repeat(16), "b2".repeat(16))
            val loose = listOf(
                ForeverClientUpdateService.ManagedAdmissionLooseFile("WowB-ARM64.exe", "5e6f9642d56cf15dff2364a0da6a1abd", 80_562_896),
            )
            val bytes = ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                target, "83".repeat(32), storageId, storageIdentity, rootPath, buildInfo, loose,
            )
            // An absent directory reads as null without creating anything.
            assertNull(ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, rootPath))
            assertEquals(0, anchor.listFiles()?.size ?: 0)
            val directory = File(anchor, "forever-managed-install-admissions").apply { mkdirs() }
            val name = "managed-install-admission-" + sha256(storageId.toByteArray(Charsets.UTF_8)) + ".json"
            File(directory, name).writeBytes(bytes)
            val record = ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, rootPath)
            assertNotNull(record)
            assertEquals("1.60.1.70500", record?.targetBuild?.version)
            assertEquals(70_500L, record?.targetBuild?.buildId)
            assertEquals(buildInfo.size.toLong(), record?.buildInfoBytes)
            assertEquals(1, record?.looseFiles?.size)
            assertEquals(storageIdentity, record?.storageIdentity)
            assertEquals(rootPath, record?.rootPath)
            assertNull(ForeverClientUpdateService.readManagedInstallAdmission(anchor, "storage-${"5".repeat(64)}", storageIdentity, rootPath))
            assertNull(ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, "volume:other", rootPath))
            assertNull(ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, "/storage/other/CustomGames/WoW Forever"))
            File(directory, name).writeBytes(bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })
            assertNull(ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, rootPath))
        } finally {
            anchor.deleteRecursively()
        }
    }

    @Test
    fun dynamicReceiptPersistsAcrossTwoSyntheticBuildsAndCorruptionOrOutsidePathsBlockSelection() {
        Fixture().use { fixture ->
            val first = syntheticAdmission(fixture.root, 70_700)
            val second = syntheticAdmission(fixture.root, 70_900)
            assertNotEquals(first.sourceFingerprint, second.sourceFingerprint)

            val firstBytes = ForeverClientUpdateService.encodeActiveCatalogReceipt(first)
            ForeverClientUpdateService.publishActiveCatalogReceipt(fixture.root, firstBytes)
            val reopenedFirst = ForeverClientUpdateService.parseActiveCatalogReceipt(
                File(fixture.root, "active-admission.json").readBytes(), fixture.root,
            )
            assertEquals(first.target.fingerprint, reopenedFirst.target.fingerprint)
            assertEquals(first.officialVersionsSha256, reopenedFirst.officialVersionsSha256)
            assertEquals(first.catalogSha256, reopenedFirst.catalogSha256)

            val secondBytes = ForeverClientUpdateService.encodeActiveCatalogReceipt(second)
            ForeverClientUpdateService.publishActiveCatalogReceipt(fixture.root, secondBytes)
            val reopenedSecond = ForeverClientUpdateService.parseActiveCatalogReceipt(
                File(fixture.root, "active-admission.json").readBytes(), fixture.root,
            )
            assertEquals(second.target.fingerprint, reopenedSecond.target.fingerprint)
            assertNotEquals(reopenedFirst.catalogFile, reopenedSecond.catalogFile)
            listOf(reopenedSecond.catalogFile, reopenedSecond.buildConfigFile, reopenedSecond.cdnConfigFile).forEach {
                assertTrue(it.canonicalPath.startsWith(fixture.root.canonicalPath + File.separator))
            }

            val outside = File(fixture.root.parentFile, "outside-active-catalog-test").apply { writeText("preserve") }
            val escaped = rewriteReceiptPayload(secondBytes) { payload ->
                payload.put("catalog_path", "../../${outside.name}")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ForeverClientUpdateService.parseActiveCatalogReceipt(escaped, fixture.root)
            }
            assertEquals("preserve", outside.readText())

            val receipt = File(fixture.root, "active-admission.json")
            val corrupt = secondBytes.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
            receipt.writeBytes(corrupt)
            assertThrows(Exception::class.java) {
                ForeverClientUpdateService.parseActiveCatalogReceipt(receipt.readBytes(), fixture.root)
            }
            assertThrows(Exception::class.java) {
                ForeverClientUpdateService.publishActiveCatalogReceipt(fixture.root, firstBytes)
            }
            assertArrayEquals("corrupt active receipt is preserved; no fallback/replacement", corrupt, receipt.readBytes())
            outside.delete()
        }
    }

    @Test
    fun stalePublishedConsentIsRejectedBeforeResolverCanAcquireMetadata() {
        val app = RuntimeEnvironment.getApplication()
        val target = ForeverClientTargetBuild.parse(admissionTarget(70_500, "a1".repeat(16), "b2".repeat(16)))
        val displayed = target.fingerprint
        val latest = displayed.copy(
            version = "1.60.1.70700",
            buildConfigHash = "c3".repeat(16),
            cdnConfigHash = "d4".repeat(16),
            buildId = 70_700L,
        )
        var publishedQueries = 0
        var resolverCreated = false
        val service = ForeverClientUpdateService(
            app,
            target,
            { cancelled ->
                publishedQueries++
                assertFalse(cancelled())
                ForeverClientUpdateCheck.PublishedBuild(latest, "ab".repeat(32))
            },
            { root ->
                resolverCreated = true
                ForeverClientCatalogResolver(root)
            },
            Unit,
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                service.preparePublishedVersion(
                    ForeverClientUpdateService.MetadataConsent(displayed, (1L shl 30) - 1L, serverVersionConfirmed = true),
                    { false },
                ) { _, _, _ -> }
            }
            assertEquals("fixed transport budget rejected before official query", 0, publishedQueries)
            assertThrows(IllegalArgumentException::class.java) {
                service.preparePublishedVersion(
                    ForeverClientUpdateService.MetadataConsent(displayed, 1L shl 30, serverVersionConfirmed = true),
                    { false },
                ) { _, _, _ -> }
            }
            assertEquals(1, publishedQueries)
            assertFalse("stale displayed tuple is rejected before resolver/config acquisition", resolverCreated)
        } finally {
            service.close()
        }
    }

    @Test
    fun dynamicPendingNativeEvidenceIsNotPresentedAsValidated() {
        Fixture().use { fixture ->
            val pending = syntheticAdmission(fixture.root, 70_901).target
            assertFalse(pending.nativeActivationValidated)
        }
    }

    @Test
    fun frozenBuildPinTreatsCdnOnlyRotationAsSameAndRealBuildChangeAsDifferent() {
        val frozen = ForeverClientUpdateCheck.Fingerprint(
            "wow_classic_beta",
            "1.60.1.70450",
            "84bea4233331e3905fda8724c6f91ffa5",
            "f065eade5276a5ac05606e60a4c5a1f7",
            70_450L,
        )
        val rotated = frozen.copy(cdnConfigHash = "86e142712006071d5eb4b60e825c1f6e")
        assertTrue(ForeverClientUpdateService.isSameFrozenBuildForResume(frozen, rotated))
        assertTrue(ForeverClientUpdateService.isSameFrozenBuildForResume(frozen, frozen))
        assertFalse(
            "visible version alone never implies equivalence",
            ForeverClientUpdateService.isSameFrozenBuildForResume(
                frozen,
                frozen.copy(buildId = 70_451L),
            ),
        )
        assertFalse(
            ForeverClientUpdateService.isSameFrozenBuildForResume(
                frozen,
                frozen.copy(version = "1.60.1.70451", buildId = 70_451L),
            ),
        )
        assertFalse(
            ForeverClientUpdateService.isSameFrozenBuildForResume(
                frozen,
                frozen.copy(buildConfigHash = "00".repeat(16)),
            ),
        )
        val gate = ForeverClientUpdateService.FROZEN_RESUME_REQUIRED_MESSAGE.lowercase(java.util.Locale.ROOT)
        assertTrue("planning gate must name its trigger: $gate", "before planning" in gate)
        assertFalse("planning gate must never claim a recovery failed: $gate", "recover" in gate)
    }

    private fun priorBuildInfo(): ByteArray = (
        "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0|Tags!STRING:0\n" +
            "$PRIOR_VALUES|Windows arm64 US? enUS\n"
    ).toByteArray()

    private fun buildInfo(
        version: String,
        product: String,
        branch: String,
        tags: String,
        buildHash: String,
        cdnHash: String,
    ): ByteArray = (
        "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0|Tags!STRING:0\n" +
            "$branch|1|$buildHash|$cdnHash|$version|$product|$tags\n"
    ).toByteArray()

    private fun targetBuildInfo(buildId: Int, buildHash: String, cdnHash: String): ByteArray = (
        "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0\n" +
            "us|1|$buildHash|$cdnHash|1.60.1.$buildId|wow_classic_beta\n"
        ).toByteArray()

    /**
     * Hand-built schema-1 envelope for migration tests. Schema 1 is never
     * encoded by production code anymore; the helper mirrors the frozen
     * legacy field set so the legacy parser stays covered.
     */
    private fun legacyAdmissionEnvelope(
        storageId: String,
        rootIdentity: String,
        targetJson: ByteArray,
        catalogSha256: String,
        buildInfo: ByteArray,
        loose: List<ForeverClientUpdateService.ManagedAdmissionLooseFile>,
    ): ByteArray {
        val target = ForeverClientTargetBuild.parse(targetJson)
        require(target.catalog.sha256.equals(catalogSha256, ignoreCase = true)) {
            "legacy fixture catalog does not match its target"
        }
        val payload = JSONObject()
            .put("schema", 1)
            .put("storage_id", storageId)
            .put("root_identity", rootIdentity)
            .put("target_json", String(targetJson, Charsets.UTF_8))
            .put("catalog_sha256", target.catalog.sha256.lowercase(java.util.Locale.ROOT))
            .put("build_info_bytes", buildInfo.size)
            .put("build_info_sha256", sha256(buildInfo))
            .put("loose_files", org.json.JSONArray().also { array ->
                loose.forEach { file ->
                    array.put(JSONObject()
                        .put("path", file.path)
                        .put("content_key", file.contentKey)
                        .put("decoded_bytes", file.decodedBytes))
                }
            })
            .toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("payload", payload)
            .put("sha256", sha256(payloadBytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun admissionTarget(buildId: Int, buildHash: String, cdnHash: String): ByteArray = JSONObject()
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
        .put("launcher_version_name", "managed-proof-test")
        .put("artifact_subdir", "client-updater-packaged-catalog/$buildId")
        .put("build_config", JSONObject().put("hash", buildHash).put("bytes", 100).put("sha256", "11".repeat(32)))
        .put("cdn_config", JSONObject().put("hash", cdnHash).put("bytes", 100).put("sha256", "22".repeat(32)))
        .put("catalog", JSONObject()
            .put("bytes", 1_024)
            .put("sha256", "83".repeat(32))
            .put("gzip_bytes", 512)
            .put("gzip_sha256", "92".repeat(32)))
        .put("executable", JSONObject()
            .put("path", "WowB-ARM64.exe")
            .put("decoded_bytes", 80_562_896)
            .put("content_key", "5e6f9642d56cf15dff2364a0da6a1abd")
            .put("expected_machine", 43_620))
        .put("server_version_source", "user-confirmed")
        .put("native_validation", JSONObject().put("status", "verified").put("evidence_sha256", "a".repeat(64)))
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun syntheticAdmission(root: File, buildId: Int): ForeverClientUpdateService.ActiveCatalogAdmission {
        val buildConfig = "synthetic official build config $buildId\n".toByteArray()
        val cdnConfig = "synthetic official cdn config $buildId\n".toByteArray()
        val buildHash = md5(buildConfig)
        val cdnHash = md5(cdnConfig)
        val catalogBytes = "synthetic read-only catalog $buildId\n".toByteArray()
        val target = ForeverClientTargetBuild.parse(
            dynamicTarget(buildId, buildHash, cdnHash, buildConfig, cdnConfig, catalogBytes),
        )
        val fingerprint = target.fingerprint
        val key = "${fingerprint.buildId}-${fingerprint.version}-${fingerprint.buildConfigHash}-${fingerprint.cdnConfigHash}"
        val catalog = File(root, "catalogs/$key/client-catalog.sqlite3")
        val buildFile = File(root, "metadata-cache/$key/config/${buildHash.take(2)}/$buildHash.config")
        val cdnFile = File(root, "metadata-cache/$key/config/${cdnHash.take(2)}/$cdnHash.config")
        listOf(catalog, buildFile, cdnFile).forEach { it.parentFile!!.mkdirs() }
        catalog.writeBytes(catalogBytes)
        buildFile.writeBytes(buildConfig)
        cdnFile.writeBytes(cdnConfig)
        return ForeverClientUpdateService.ActiveCatalogAdmission(
            target = target,
            sourceFingerprint = fingerprint,
            officialVersionsSha256 = sha256("synthetic official versions response $buildId".toByteArray()),
            catalogFile = catalog,
            catalogSha256 = sha256(catalogBytes),
            catalogBytes = catalogBytes.size.toLong(),
            buildConfigFile = buildFile,
            cdnConfigFile = cdnFile,
        )
    }

    private fun dynamicTarget(
        buildId: Int,
        buildHash: String,
        cdnHash: String,
        buildConfig: ByteArray,
        cdnConfig: ByteArray,
        catalogBytes: ByteArray,
    ): ByteArray = JSONObject()
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
        .put("launcher_version_name", "synthetic-dynamic-test")
        .put("artifact_subdir", "client-updater-packaged-catalog/$buildId")
        .put("build_config", JSONObject().put("hash", buildHash).put("bytes", buildConfig.size).put("sha256", sha256(buildConfig)))
        .put("cdn_config", JSONObject().put("hash", cdnHash).put("bytes", cdnConfig.size).put("sha256", sha256(cdnConfig)))
        .put("catalog", JSONObject()
            .put("bytes", catalogBytes.size)
            .put("sha256", sha256(catalogBytes))
            .put("gzip_bytes", 1)
            .put("gzip_sha256", "91".repeat(32)))
        .put("executable", JSONObject()
            .put("path", "WowB-ARM64.exe")
            .put("decoded_bytes", 80_562_896)
            .put("content_key", "5e6f9642d56cf15dff2364a0da6a1abd")
            .put("expected_machine", 0xaa64))
        .put("server_version_source", "user-confirmed")
        .put("native_validation", JSONObject().put("status", "pending").put("evidence_sha256", JSONObject.NULL))
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun rewriteReceiptPayload(bytes: ByteArray, mutate: (JSONObject) -> Unit): ByteArray {
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        mutate(payload)
        val text = payload.toString()
        return JSONObject().put("payload", text).put("sha256", sha256(text.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class Fixture : AutoCloseable {
        val root: File = Files.createTempDirectory("client-update-service-test-").toFile()
        override fun close() {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val PRIOR_BUILD = "05215079e3905ef5922ae0b03ffefb73"
        const val PRIOR_CDN = "9b3c456dbb837d133a026d380c7c13e9"
        const val PRIOR_VALUES = "us|1|$PRIOR_BUILD|$PRIOR_CDN|1.60.1.70009|wow_classic_beta"
    }
}
