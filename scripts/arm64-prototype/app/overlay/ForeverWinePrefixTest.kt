package app.gamenative

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverWinePrefixTest {
    @org.junit.Test fun filesRootAcceptsBothAndroidUserZeroAliasDirectionsOnly() {
        val pkg = "com.wowforever.arm64probe"
        val legacy = "/data/data/$pkg/files"
        val userZero = "/data/user/0/$pkg/files"
        org.junit.Assert.assertTrue(ForeverWinePrefix.validFilesRootAlias(legacy, userZero, pkg))
        org.junit.Assert.assertTrue(ForeverWinePrefix.validFilesRootAlias(userZero, legacy, pkg))
        org.junit.Assert.assertFalse(ForeverWinePrefix.validFilesRootAlias(userZero, "/data/user/10/$pkg/files", pkg))
        org.junit.Assert.assertFalse(ForeverWinePrefix.validFilesRootAlias(userZero, "/data/data/foreign.package/files", pkg))
        org.junit.Assert.assertFalse(ForeverWinePrefix.validFilesRootAlias("$userZero/../files", legacy, pkg))
        org.junit.Assert.assertFalse(ForeverWinePrefix.validFilesRootAlias("/foreign/files", "/foreign/files", pkg))
    }
    @Test fun filesRootDiagnosticsEnumerateOnlyOwnAliasKinds() {
        val pkg = "com.wowforever.arm64probe"
        val facts = ForeverWinePrefix.filesRootAliasFacts(
            "/data/data/$pkg/files", "/data/user/0/$pkg/files", pkg,
        )
        assertEquals("data", facts["suppliedAlias"])
        assertEquals("user0", facts["canonicalAlias"])
        assertEquals(true, facts["accepted"])
        assertEquals("other", ForeverWinePrefix.filesRootAliasFacts(
            "/data/user/10/$pkg/files", "/data/user/10/$pkg/files", pkg,
        )["suppliedAlias"])
    }
    @Test fun preparedApiKeepsExplicitKernelAndRuntimeBinding() {
        assertEquals(setOf("wsRunId", "nonce", "prefix", "runtimeRoot", "prefixDev", "prefixIno", "receipt"),
            ForeverWinePrefix.Prepared::class.java.declaredFields
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet())
        val method = ForeverWinePrefix::class.java.methods.single { it.name == "prepare" }
        assertEquals(4, method.parameterTypes.size)
        assertEquals("android.content.Context", method.parameterTypes[0].name)
        assertEquals("kotlin.jvm.functions.Function2", method.parameterTypes[3].name)
        val verify = ForeverWinePrefix::class.java.methods.single { it.name == "verifyPrepared" }
        assertEquals(2, verify.parameterTypes.size)
        assertEquals("android.content.Context", verify.parameterTypes[0].name)
        assertEquals("app.gamenative.ForeverWinePrefix\$Prepared", verify.parameterTypes[1].name)
        assertEquals(Void.TYPE, verify.returnType)
    }

    @Test fun directoryLinksAreNotRegularFileHardlinkCounts() {
        // Actual own files directory: UID/GID10330, mode0771, nlink14.
        assertTrue(allowed(0x4000 or 0x1f9, 14, true))
        assertTrue(allowed(0x4000 or 0x1c0, 4, false))
        assertFalse(allowed(0x8000 or 0x1c0, 1, false))
        assertFalse(allowed(0x4000 or 0x1c0, 0, false))
    }

    @Test fun privateGroupIsNotWorldWritableOrForeignOwner() {
        assertFalse(allowed(0x4000 or 0x1ff, 2, true))
        assertFalse(allowed(0x4000 or 0x1f9, 2, false))
        assertFalse(ForeverWinePrefix.ownedDirectory(0, 10330, 0x4000 or 0x1c0, 2, 10330, 10330, true))
        assertFalse(ForeverWinePrefix.ownedDirectory(10330, 0, 0x4000 or 0x1c0, 2, 10330, 10330, true))
    }

    @Test fun manifestTemplateDirectoryMayBe0777ButRootsMayNot() {
        assertTrue(ForeverWinePrefix.ownedTemplateDirectory(10330, 10330, 0x4000 or 0x1ff, 1, 10330, 10330, 0x1ff))
        assertFalse(ForeverWinePrefix.ownedTemplateDirectory(0, 10330, 0x4000 or 0x1ff, 1, 10330, 10330, 0x1ff))
        assertFalse(ForeverWinePrefix.ownedTemplateDirectory(10330, 10330, 0x8000 or 0x1ff, 1, 10330, 10330, 0x1ff))
        assertFalse(ForeverWinePrefix.ownedTemplateDirectory(10330, 10330, 0x4000 or 0x1fe, 1, 10330, 10330, 0x1ff))
        assertFalse(ForeverWinePrefix.ownedDirectory(10330, 10330, 0x4000 or 0x1ff, 1, 10330, 10330, false))
    }

    private fun allowed(mode: Int, links: Long, allowGroup: Boolean) =
        ForeverWinePrefix.ownedDirectory(10330, 10330, mode, links, 10330, 10330, allowGroup)

    @Test fun receiptAcceptsThePinnedClosedShape() {
        val prepared = prepared()
        ForeverWinePrefix.validatePreparedReceiptJsonForTest(receipt(prepared), prepared)
    }

    @Test fun receiptRejectsTamperedBindingAndPins() {
        val prepared = prepared()
        val original = String(receipt(prepared), Charsets.US_ASCII)
        listOf(
            original.replace(prepared.wsRunId, "WS-${"f".repeat(32)}"),
            original.replace(prepared.prefix.path, "/foreign/prefix/.wine"),
            original.replace(prepared.runtimeRoot.path, "/foreign/runtime"),
            original.replace("\"prefixDev\":17", "\"prefixDev\":18"),
            original.replace("\"prefixIno\":29", "\"prefixIno\":30"),
            original.replace("2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9", "0".repeat(64)),
        ).forEach { candidate -> assertRejected { validate(candidate, prepared) } }
    }

    @Test fun receiptBindingRejectsDanglingSymlinkAndReplacedPrefixValues() {
        // This bounded seam exercises receipt/path agreement only; it is not a POSIX symlink/race proof.
        val prepared = prepared()
        val original = String(receipt(prepared), Charsets.US_ASCII)
        val danglingAlias = original.replace(prepared.prefix.path, "${prepared.prefix.path}.dangling-link")
        val replacedPrefix = original.replace(prepared.prefix.path, "/data/user/0/app.gamenative/files/forever/replaced/.wine")
        assertRejected { validate(danglingAlias, prepared) }
        assertRejected { validate(replacedPrefix, prepared) }
    }

    @Test fun receiptRejectsMissingSchemaExtraAndDuplicateKeys() {
        val prepared = prepared()
        val original = String(receipt(prepared), Charsets.US_ASCII)
        val missing = original.replace("\"schema\":\"wowforever.wine-prefix-prepared/v1\",", "")
        val extra = original.replace("}\n", ",\"extra\":1}\n")
        val duplicate = original.replace("\"nonce\":\"${prepared.nonce}\",", "\"nonce\":\"${prepared.nonce}\",\"nonce\":\"${prepared.nonce}\",")
        val wrongSchema = original.replace("wowforever.wine-prefix-prepared/v1", "wrong/v1")
        listOf(missing, extra, duplicate, wrongSchema).forEach { candidate ->
            assertRejected { validate(candidate, prepared) }
        }
    }

    @Test fun receiptRejectsWrongFieldTypes() {
        val prepared = prepared()
        val original = String(receipt(prepared), Charsets.US_ASCII)
        assertRejected { validate(original.replace("\"prefixDev\":17", "\"prefixDev\":\"17\""), prepared) }
        assertRejected { validate(original.replace("\"nonce\":\"${prepared.nonce}\"", "\"nonce\":true"), prepared) }
    }

    private fun validate(json: String, prepared: ForeverWinePrefix.Prepared) =
        ForeverWinePrefix.validatePreparedReceiptJsonForTest(json.toByteArray(Charsets.US_ASCII), prepared)

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: RuntimeException) { rejected = true }
        assertTrue("receipt candidate was accepted", rejected)
    }

    private fun prepared(): ForeverWinePrefix.Prepared {
        val root = "/data/user/0/app.gamenative/files/forever/wine-controls/WS-${"a".repeat(32)}"
        return ForeverWinePrefix.Prepared(
            "WS-${"a".repeat(32)}", "${"b".repeat(32)}", File("$root/prefix/.wine"),
            File("/data/user/0/app.gamenative/files/imagefs_shared/proton/proton-11.0-90624-arm64ec"),
            17L, 29L, File("$root/prepared.receipt"),
        )
    }

    private fun receipt(prepared: ForeverWinePrefix.Prepared): ByteArray =
        ("{" +
            "\"schema\":\"wowforever.wine-prefix-prepared/v1\"," +
            "\"wsRunId\":\"${prepared.wsRunId}\",\"nonce\":\"${prepared.nonce}\"," +
            "\"prefix\":\"${prepared.prefix.path}\",\"runtimeRoot\":\"${prepared.runtimeRoot.path}\"," +
            "\"prefixDev\":${prepared.prefixDev},\"prefixIno\":${prepared.prefixIno}," +
            "\"imageManifestSha256\":\"2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9\"," +
            "\"imageZipSha256\":\"42f9e9119682f5e3cbd9071efe3f079d0cac76896fe3ce0fa9b2ef0d41048853\"}\n").toByteArray(Charsets.US_ASCII)
}
