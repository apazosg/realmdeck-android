package app.gamenative

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

/**
 * A deliberately narrow OpenGL control for the managed WoW launch only.
 *
 * The asset is data-only: it exports no GL/GLX entry points and is not a
 * replacement OpenGL implementation.  Its only purpose is to make Wine's
 * optional OpenGL lookup fail closed while DXVK remains on the Vulkan path.
 */
object ForeverNoOpenGL {
    const val ASSET_DIRECTORY = "forever/controls/no-opengl"
    const val ASSET_LIBRARY = "$ASSET_DIRECTORY/libGL.so.1"
    const val ASSET_MANIFEST = "$ASSET_DIRECTORY/manifest.json"
    const val CONTROL_METADATA = "scoped-no-opengl"
    const val APPROVED_SHA256 = "36e36a7d3f1c6fffcaebe4c8ad3602fd72c974489bee469215fb9ed88c18124b"

    private const val MAX_ASSET_BYTES = 64 * 1024
    private const val MAX_MANIFEST_BYTES = 4 * 1024
    private const val APPROVED_SIZE = 1944L
    private const val RUN_EXTRA = "foreverRunId"
    private const val RUN_ENV = "WOWFOREVER_RUN_ID"
    private val RUN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val HASH_COMPONENT = Regex("^[0-9a-f]{64}$")
    private const val ELF_AARCH64 = 183

    private data class Manifest(
        val sha256: String,
        val size: Long,
        val elfMachine: Int,
        val soname: String,
    )

    /**
     * Materializes the verified asset into the app-private files tree.
     * No ImageFS, shared library directory, or existing unknown path is used.
     */
    @JvmStatic
    @Synchronized
    fun prepare(context: Context): File {
        val manifest = context.assets.open(ASSET_MANIFEST).use { input ->
            parseManifest(readBounded(input, MAX_MANIFEST_BYTES))
        }
        val library = context.assets.open(ASSET_LIBRARY).use { input ->
            readBounded(input, MAX_ASSET_BYTES)
        }
        verifyAsset(library, manifest)

        val root = context.filesDir.canonicalFile
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "app files directory is not a real directory"
        }
        val controlRoot = ensureDirectory(root, "forever")
            .let { ensureDirectory(it, "controls") }
            .let { ensureDirectory(it, "no-opengl") }
        require(!Files.isSymbolicLink(controlRoot.toPath())) { "control directory is a symlink" }

        val hashDirectory = controlRoot.resolve(manifest.sha256.lowercase())
        require(HASH_COMPONENT.matches(hashDirectory.name)) { "invalid private asset hash path" }
        ensureDirectory(hashDirectory.parentFile, hashDirectory.name)
        val target = hashDirectory.resolve("libGL.so.1")
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(target.toPath())) { "private OpenGL control is a symlink" }
            verifyPrivateFile(target, library, manifest)
            return hashDirectory
        }
        require(!Files.isSymbolicLink(target.toPath())) { "private OpenGL control path is a symlink" }

        // Android denies app-data hard links on this target. CREATE_NEW never
        // replaces an existing name; no child receives this path until fsync and
        // verification complete. A partial write fails closed on the next prepare.
        writeExclusive(target, library)
        verifyPrivateFile(target, library, manifest)
        return hashDirectory
    }

    /**
     * Applies only the final environment delta for an explicitly opted-in,
     * run-bound managed container.  All other calls are byte-for-byte no-ops.
     */
    @JvmStatic
    fun applyManagedEnvironment(context: Context, container: Container, env: EnvVars) {
        if (!ForeverLaunchDebug.SCOPED_NO_OPENGL) return
        applyForPolicy(context, container, env, enabled = ForeverLaunchDebug.SCOPED_NO_OPENGL)
    }

    internal fun applyForPolicy(context: Context, container: Container, env: EnvVars, enabled: Boolean) {
        if (!enabled) return
        if (container.getExtra("foreverManaged", "") != "true" ||
            container.getExtra("foreverScopedNoOpenGL", "") != "true"
        ) return

        val containerRun = container.getExtra(RUN_EXTRA, "")
        val environmentRun = env.get(RUN_ENV).orEmpty()
        requireValidRunId(containerRun, "container")
        requireValidRunId(environmentRun, "environment")
        require(containerRun == environmentRun) { "managed OpenGL control run identity differs" }

        val privatePreparedDir = prepare(context)
        val privatePrefix = privatePreparedDir.absolutePath
        val existing = env.get("LD_LIBRARY_PATH").orEmpty()
        val remainder = splitPathPreservingEmptyComponents(existing)
            .filterNot { it == privatePrefix }
        val merged = if (existing.isEmpty() || remainder.isEmpty()) {
            privatePrefix
        } else {
            "$privatePrefix:${remainder.joinToString(":")}"
        }
        env.put("LD_LIBRARY_PATH", merged)
        env.put("WOWFOREVER_GL_CONTROL", CONTROL_METADATA)
    }

    private fun requireValidRunId(value: String, label: String) {
        require(RUN_ID.matches(value)) { "invalid $label Forever run id" }
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optInt("schema", -1) == 1) { "unsupported no-OpenGL manifest schema" }
        val hash = json.optString("sha256", "")
        require(SHA256.matches(hash) && hash == APPROVED_SHA256) { "no-OpenGL manifest SHA-256 is not approved" }
        val size = json.optLong("size", -1L)
        require(size == APPROVED_SIZE) { "no-OpenGL asset size is not approved" }
        val machine = json.optInt("elfMachine", -1)
        require(machine == ELF_AARCH64) { "no-OpenGL asset is not declared AArch64" }
        val soname = json.optString("soname", "")
        require(soname == "libGL.so.1") { "no-OpenGL SONAME is not libGL.so.1" }
        return Manifest(hash.lowercase(), size, machine, soname)
    }

    private fun verifyAsset(bytes: ByteArray, manifest: Manifest) {
        require(bytes.size.toLong() == manifest.size) { "no-OpenGL asset size does not match manifest" }
        require(sha256(bytes) == manifest.sha256) { "no-OpenGL asset hash does not match manifest" }
        require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) { "no-OpenGL asset is not ELF" }
        require(bytes[4].toInt() == 2 && bytes[5].toInt() == 1) {
            "no-OpenGL asset is not ELF64 little-endian"
        }
        require(littleShort(bytes, 16) == 3 && littleInt(bytes, 20) == 1) {
            "no-OpenGL asset is not a current ELF shared object"
        }
        require(littleShort(bytes, 18) == manifest.elfMachine && manifest.elfMachine == ELF_AARCH64) {
            "no-OpenGL asset ELF machine is not AArch64"
        }
    }

    private fun verifyPrivateFile(file: File, expected: ByteArray, manifest: Manifest) {
        val before = privateFileIdentity(file)
        require(before.size == manifest.size) { "private OpenGL control size changed" }
        val actual = Files.newInputStream(
            file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
        ).use { readBounded(it, MAX_ASSET_BYTES) }
        val after = privateFileIdentity(file)
        require(before == after) { "private OpenGL control changed while it was read" }
        require(actual.contentEquals(expected)) { "private OpenGL control bytes changed" }
        verifyAsset(actual, manifest)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private fun ensureDirectory(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/')) {
            "invalid private OpenGL directory component"
        }
        val directory = parent.resolve(name)
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) {
                "private OpenGL directory is not a real directory: ${directory.path}"
            }
        } else {
            require(directory.mkdir() || directory.isDirectory) { "cannot create private OpenGL directory" }
            require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) {
                "private OpenGL directory changed during creation"
            }
        }
        directory.setReadable(true, true)
        directory.setWritable(true, true)
        directory.setExecutable(true, true)
        return directory
    }

    private fun writeExclusive(file: File, bytes: ByteArray) {
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "private temporary file already exists" }
        java.nio.channels.FileChannel.open(
            file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            var offset = 0
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                offset += channel.write(buffer)
            }
            require(offset == bytes.size) { "private OpenGL control write was incomplete" }
            channel.force(true)
        }
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximum, 16 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val requested = minOf(buffer.size, maximum - total + 1)
            val count = input.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) {
                // A broken/fragmented stream must not spin forever. Consume one
                // byte through the single-byte fallback, still under the cap.
                val one = input.read()
                if (one < 0) break
                require(total < maximum) { "asset exceeds bounded reader limit" }
                output.write(one)
                total++
                continue
            }
            require(count <= maximum - total) { "asset exceeds bounded reader limit" }
            total += count
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun splitPathPreservingEmptyComponents(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var start = 0
        value.forEachIndexed { index, character ->
            if (character == ':') {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    private data class PrivateFileIdentity(val fileKey: Any?, val size: Long, val modified: Long)

    private fun privateFileIdentity(file: File): PrivateFileIdentity {
        require(!Files.isSymbolicLink(file.toPath())) { "private OpenGL control is a symlink" }
        val attributes = Files.readAttributes(
            file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "private OpenGL control is not a regular file" }
        return PrivateFileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
