package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverClientUpdateCheckTest {
    private val pin = ForeverClientUpdateCheck.Fingerprint(
        product = "wow_classic_beta",
        version = "1.60.1.70009",
        buildConfigHash = "05215079e3905ef5922ae0b03ffefb73",
        cdnConfigHash = "9b3c456dbb837d133a026d380c7c13e9",
        buildId = 70009,
    )

    @Test
    fun identicalPublishedFingerprintIsOnlyReportedAsMetadataMatch() {
        val result = ForeverClientUpdateCheck.check(pin, source(table(pin)))

        assertEquals(ForeverClientUpdateCheck.Status.METADATA_MATCH, result.status)
        assertTrue(result.message.contains("no se han detectado novedades"))
        assertEquals(pin, result.published)
    }

    @Test
    fun sameVersionWithDifferentConfigHashIsACandidateNotAStringVersionComparison() {
        val candidate = pin.copy(buildConfigHash = "a".repeat(32), buildId = 70010)
        val result = ForeverClientUpdateCheck.check(pin, source(table(candidate)))

        assertEquals(ForeverClientUpdateCheck.Status.CANDIDATE_NOT_ARM64_CONFIRMED, result.status)
        assertEquals(candidate, result.published)
        assertFalse(result.message.contains("novedades"))
    }

    @Test
    fun admittedExperimentalBuildIsNotPresentedAsForeverAuthenticationProof() {
        val installed = pin.copy(version = "1.60.1.69977", buildId = 69977)
        val result = ForeverClientUpdateCheck.check(installed, source(table(pin)))

        assertEquals(ForeverClientUpdateCheck.Status.APPROVED_AVAILABLE, result.status)
        assertTrue(result.message.contains("experimental"))
        assertTrue(result.message.contains("no acredita acceso a Forever"))
        assertFalse(result.message.contains("compatible con Forever"))
    }

    @Test
    fun matchingManifestArchitectureEvidenceLeavesForeverCompatibilityPending() {
        val candidate = fingerprint("1.60.1.70500", "a".repeat(32), "b".repeat(32), 70500)
        val evidence = ForeverClientUpdateCheck.ArchitectureEvidence(
            product = candidate.product,
            version = candidate.version,
            buildConfigHash = candidate.buildConfigHash,
            architecture = "arm64",
        )

        val confirmed = ForeverClientUpdateCheck.check(pin, source(table(candidate)), evidence)
        val unrelated = ForeverClientUpdateCheck.check(
            pin,
            source(table(candidate)),
            evidence.copy(buildConfigHash = "c".repeat(32)),
        )

        assertEquals(ForeverClientUpdateCheck.Status.ARM64_CONFIRMED_COMPATIBILITY_PENDING, confirmed.status)
        assertTrue(confirmed.message.contains("compatibilidad con Forever pendiente"))
        assertEquals(ForeverClientUpdateCheck.Status.CANDIDATE_NOT_ARM64_CONFIRMED, unrelated.status)
    }

    @Test
    fun admitted70425RequiresBoundArm64EvidenceAndDoesNotClaimOwnLoginProof() {
        val candidate = fingerprint("1.60.1.70425", "ea9c525eef74f1d682c00a8ef39a5d2b", "fcab7fec2ccc6b8158b56b6938ec4551", 70425)
        val raw = source(table(candidate))
        assertEquals(ForeverClientUpdateCheck.Status.CANDIDATE_NOT_ARM64_CONFIRMED,
            ForeverClientUpdateCheck.check(pin, raw).status)
        val evidence = ForeverClientUpdateCheck.ArchitectureEvidence(candidate.product, candidate.version, candidate.buildConfigHash, "arm64")
        val result = ForeverClientUpdateCheck.check(pin, raw, evidence, admittedFingerprint = candidate)
        assertEquals(ForeverClientUpdateCheck.Status.APPROVED_AVAILABLE, result.status)
        assertTrue(result.message.contains("confirmación del usuario"))
        assertTrue(result.message.contains("siguen pendientes"))
    }

    @Test
    fun sameCheckerAdmitsDifferentDataDrivenTargetsAndRejectsWrongFingerprintOrArchitecture() {
        listOf(
            fingerprint("1.60.1.70500", "a".repeat(32), "b".repeat(32), 70500),
            fingerprint("1.60.1.70700", "c".repeat(32), "d".repeat(32), 70700),
        ).forEach { admitted ->
            val metadata = source(table(admitted))
            val architecture = ForeverClientUpdateCheck.ArchitectureEvidence(
                admitted.product,
                admitted.version,
                admitted.buildConfigHash,
                "arm64",
            )
            val accepted = ForeverClientUpdateCheck.check(
                pin,
                metadata,
                architecture,
                admittedFingerprint = admitted,
            )
            val wrongBuild = ForeverClientUpdateCheck.check(
                pin,
                metadata,
                architecture,
                admittedFingerprint = admitted.copy(buildConfigHash = "f".repeat(32)),
            )
            val wrongCdn = ForeverClientUpdateCheck.check(
                pin,
                source(table(admitted.copy(cdnConfigHash = "e".repeat(32)))),
                architecture,
                admittedFingerprint = admitted,
            )
            val wrongArchitecture = ForeverClientUpdateCheck.check(
                pin,
                metadata,
                architecture.copy(architecture = "x86_64"),
                admittedFingerprint = admitted,
            )

            assertEquals(ForeverClientUpdateCheck.Status.APPROVED_AVAILABLE, accepted.status)
            assertEquals(ForeverClientUpdateCheck.Status.ARM64_CONFIRMED_COMPATIBILITY_PENDING, wrongBuild.status)
            assertEquals(ForeverClientUpdateCheck.Status.ARM64_CONFIRMED_COMPATIBILITY_PENDING, wrongCdn.status)
            assertEquals(ForeverClientUpdateCheck.Status.CANDIDATE_NOT_ARM64_CONFIRMED, wrongArchitecture.status)
            assertTrue(accepted.message.contains(admitted.version))
            assertFalse(accepted.message.contains("Login confirmado"))
        }
    }

    @Test
    fun unknownFutureCandidateIsNeverApprovedWithoutAnAdmittedFingerprint() {
        val candidate = fingerprint("1.60.1.70700", "a".repeat(32), "b".repeat(32), 70700)
        val architecture = ForeverClientUpdateCheck.ArchitectureEvidence(
            candidate.product,
            candidate.version,
            candidate.buildConfigHash,
            "arm64",
        )

        val result = ForeverClientUpdateCheck.check(pin, source(table(candidate)), architecture)

        assertEquals(ForeverClientUpdateCheck.Status.ARM64_CONFIRMED_COMPATIBILITY_PENDING, result.status)
        assertFalse(result.message.contains("admitida para el launcher"))
    }

    @Test
    fun missingOrIncompleteInstalledIdentityStaysUnknownEvenWhenMetadataLoads() {
        val missing = ForeverClientUpdateCheck.check(null, source(table(pin)))
        val incomplete = ForeverClientUpdateCheck.check(pin.copy(buildId = null), source(table(pin)))

        assertEquals(ForeverClientUpdateCheck.Status.INSTALLED_IDENTITY_UNKNOWN, missing.status)
        assertEquals(ForeverClientUpdateCheck.Status.INSTALLED_IDENTITY_UNKNOWN, incomplete.status)
        assertTrue(missing.message.contains("estado desconocido"))
        assertEquals(pin, missing.published)
    }

    @Test
    fun parserRejectsAmbiguousHeadersAndDuplicateRegions() {
        val duplicateHeader = table(pin).replace(
            "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16",
            "Region!STRING:0|Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions(duplicateHeader.toByteArray(Charsets.UTF_8))
        }

        val duplicateRegion = table(pin).replace(
            "## seqn = 7\n",
            "## seqn = 7\nus|${pin.buildConfigHash}|${pin.cdnConfigHash}||${pin.buildId}|${pin.version}|${"c".repeat(32)}\n",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions(duplicateRegion.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun parserRejectsMalformedUtf8HashesErrorsTruncationAndOversizeData() {
        assertThrows(Exception::class.java) { ForeverClientUpdateCheck.parseVersions(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions(table(pin.copy(cdnConfigHash = "z".repeat(32))).toByteArray(Charsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions("ERROR: not available\n".toByteArray(Charsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions(table(pin).trimEnd().toByteArray(Charsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForeverClientUpdateCheck.parseVersions(ByteArray(ForeverClientUpdateCheck.MAX_METADATA_BYTES + 1))
        }
        val unavailable = ForeverClientUpdateCheck.check(pin, source("ERROR: not available\n"))
        assertEquals(ForeverClientUpdateCheck.Status.METADATA_UNAVAILABLE, unavailable.status)
    }

    @Test
    fun officialSourceEndpointIsTheRibbitVersionsRouteAndTestsStayInjected() {
        assertEquals(
            "https://us.version.battle.net/v2/products/wow_classic_beta/versions",
            ForeverClientUpdateCheck.OFFICIAL_VERSIONS_URL,
        )
        var called = false
        val result = ForeverClientUpdateCheck.check(null, ForeverClientUpdateCheck.MetadataSource {
            called = true
            table(pin).toByteArray(Charsets.UTF_8)
        })
        assertTrue(called)
        assertEquals(ForeverClientUpdateCheck.Status.INSTALLED_IDENTITY_UNKNOWN, result.status)
    }

    private fun source(content: String) = ForeverClientUpdateCheck.MetadataSource { content.toByteArray(Charsets.UTF_8) }

    private fun row(fingerprint: ForeverClientUpdateCheck.Fingerprint): String =
        "us|${fingerprint.buildConfigHash}|${fingerprint.cdnConfigHash}||${fingerprint.buildId}|${fingerprint.version}|${"c".repeat(32)}"

    private fun table(fingerprint: ForeverClientUpdateCheck.Fingerprint): String =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n" +
            "## seqn = 7\n" + row(fingerprint) + "\n" +
            "eu|${fingerprint.buildConfigHash}|${fingerprint.cdnConfigHash}||${fingerprint.buildId}|${fingerprint.version}|${"d".repeat(32)}\n"

    private fun fingerprint(version: String, buildConfig: String, cdnConfig: String, buildId: Long) =
        ForeverClientUpdateCheck.Fingerprint("wow_classic_beta", version, buildConfig, cdnConfig, buildId)
}
