package app.gamenative

import com.winlator.core.envvars.EnvVars
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverBuiltinShaderCompilerTest {
    @Test
    fun mergeRetainsWineGroupsModesOrderAndAddsOneBuiltinEntry() {
        val existing = "dxgi=n;d3d11=n,b;D3DCOMPILER_47=n;foo,optional.dll=unknown;d3dcompiler_47.dll=n"
        assertEquals(
            "dxgi=n;d3d11=n,b;foo,optional.dll=unknown;d3dcompiler_47=b",
            ForeverBuiltinShaderCompiler.mergeWineDllOverrides(existing),
        )
    }

    @Test
    fun mergeHandlesDuplicatesGroupedTargetsAbsentModeAndUnknownValues() {
        assertEquals("dxgi=n,b;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("d3dcompiler_47,dxgi=n,b"))
        assertEquals("foo,bar=n,b;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("foo,d3dcompiler_47,bar=n,b"))
        assertEquals("d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("D3DCOMPILER_47=n;d3dcompiler_47=b"))
        assertEquals("foo;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("foo"))
        assertEquals("optional.dll=weird;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("optional.dll=weird"))
        assertEquals(";dxgi=n;;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides(";d3dcompiler_47=n;dxgi=n;"))
        assertEquals(";d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides("d3dcompiler_47=n;"))
        assertEquals("d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides(null))
        assertEquals("d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides(""))
        assertEquals(
            ForeverBuiltinShaderCompiler.mergeWineDllOverrides("dxgi=n;d3dcompiler_47=b"),
            ForeverBuiltinShaderCompiler.mergeWineDllOverrides(ForeverBuiltinShaderCompiler.mergeWineDllOverrides("dxgi=n;d3dcompiler_47=b")),
        )
    }

    @Test
    fun mergeDoesNotRewriteMalformedOrGlobalWildcardEntriesAndBoundsRawInput() {
        val malformed = "*=n;d3dcompiler_47;dxgi=n;bad,=x"
        assertEquals("*=n;d3dcompiler_47;dxgi=n;bad,=x;d3dcompiler_47=b", ForeverBuiltinShaderCompiler.mergeWineDllOverrides(malformed))
        assertThrows { ForeverBuiltinShaderCompiler.mergeWineDllOverrides("x\u0000y") }
        assertThrows { ForeverBuiltinShaderCompiler.mergeWineDllOverrides("x\r\ny") }
        assertThrows { ForeverBuiltinShaderCompiler.mergeWineDllOverrides("x".repeat(16 * 1024 + 1)) }
    }

    @Test
    fun envValueRemainsRawThroughEnvVarsParserAndInactiveSelectionIsByteExact() {
        val raw = "d3d11,dxgi=n,b;d3dcompiler_47=n;foo,bar=unknown"
        val env = EnvVars().apply { put("WINEDLLOVERRIDES", raw) }
        val parsed = EnvVars(env.toString())
        parsed.put("WINEDLLOVERRIDES", ForeverBuiltinShaderCompiler.mergeWineDllOverrides(parsed.get("WINEDLLOVERRIDES")))
        assertEquals("d3d11,dxgi=n,b;foo,bar=unknown;d3dcompiler_47=b", parsed.get("WINEDLLOVERRIDES"))

        val inactive = EnvVars().apply {
            put("WINEDLLOVERRIDES", "dxgi=n;d3dcompiler_47=n")
            put("FOREVER_BUILTIN_SHADER_COMPILER", "stale-extra")
        }
        val before = inactive.toString()
        ForeverPanvkWine.applySelectionEnvironment(inactive, selection())
        val expectedInactive = EnvVars(before).apply {
            put("ADRENOTOOLS_DRIVER_PATH", "/owned/panvk/")
            put("ADRENOTOOLS_DRIVER_NAME", ForeverPanvkWine.FILE_NAME)
            put("ADRENOTOOLS_HOOKS_PATH", "/owned/hooks/")
        }
        assertEquals(expectedInactive.toString(), inactive.toString())
        assertEquals("dxgi=n;d3dcompiler_47=n", inactive.get("WINEDLLOVERRIDES"))
    }

    @Test
    fun missingHashWrongPeWrongSymlinkAndOutsidePathFailWithoutMutation() {
        val parent = Files.createTempDirectory("builtin-compiler").toFile()
        try {
            val root = parent.resolve("files").also { assertTrue(it.mkdirs()) }
            val env = EnvVars().apply {
                put("WINEDLLOVERRIDES", "dxgi=n;d3dcompiler_47=n")
                put("LD_PRELOAD", "/owned/wx")
            }
            val missing = root.resolve("missing.dll")
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(root, missing, pin(ByteArray(1)))
            }

            val valid = root.resolve("valid.dll")
            val bytes = fakePe(1024)
            valid.writeBytes(bytes)
            val validPin = ForeverBuiltinShaderCompiler.CompilerPin(sha256(bytes), bytes.size.toLong())
            assertTrue(ForeverBuiltinShaderCompiler.verifyFileForTests(root, valid, validPin).path == valid.canonicalPath)
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(
                    root,
                    valid,
                    ForeverBuiltinShaderCompiler.CompilerPin("0".repeat(64), bytes.size.toLong()),
                )
            }

            val wrongPe = root.resolve("wrong-pe.dll").also { it.writeBytes(ByteArray(bytes.size) { 7 }) }
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(root, wrongPe, ForeverBuiltinShaderCompiler.CompilerPin(sha256(wrongPe.readBytes()), wrongPe.length()))
            }

            val outside = parent.resolve("outside.dll").also { it.writeBytes(bytes) }
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(root, outside, validPin)
            }
            val link = root.resolve("link.dll")
            Files.createSymbolicLink(link.toPath(), valid.toPath())
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(root, link, validPin)
            }
            val realDirectory = root.resolve("real").also { assertTrue(it.mkdir()) }
            realDirectory.resolve("valid.dll").writeBytes(bytes)
            val directoryAlias = root.resolve("alias")
            Files.createSymbolicLink(directoryAlias.toPath(), realDirectory.toPath())
            assertRejectedWithoutEnvMutation(env) {
                ForeverBuiltinShaderCompiler.verifyFileForTests(root, directoryAlias.resolve("valid.dll"), validPin)
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun selection() = ForeverPanvkWine.EnvironmentSelection(
        "/owned/panvk/", ForeverPanvkWine.FILE_NAME, "/owned/hooks/",
        ForeverPanvkWine.APPROVED_SHA256, ForeverPanvkWine.EXPECTED_WRAPPER_SHA256,
    )

    private fun pin(bytes: ByteArray) = ForeverBuiltinShaderCompiler.CompilerPin(sha256(bytes), bytes.size.toLong())

    private fun fakePe(size: Int): ByteArray {
        val bytes = ByteArray(size) { 1 }
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(128)
        bytes[128] = 'P'.code.toByte(); bytes[129] = 'E'.code.toByte()
        bytes[130] = 0; bytes[131] = 0
        ByteBuffer.wrap(bytes, 132, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0xaa64.toShort())
        ByteBuffer.wrap(bytes, 152, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x20b.toShort())
        return bytes
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private fun assertRejectedWithoutEnvMutation(env: EnvVars, block: () -> Unit) {
        val before = env.toString()
        assertThrows(block)
        assertEquals(before, env.toString())
    }
}
