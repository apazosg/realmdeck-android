package app.gamenative

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/** Read-only qualification of the AA64 builtin shader compiler already in Proton. */
object ForeverBuiltinShaderCompiler {
    const val FILE_NAME = "d3dcompiler_47.dll"
    const val APPROVED_SIZE = 1_114_112L
    const val APPROVED_SHA256 = "c374b107fd14890e4b602921c82198bd7c3773f7f1d27f0712be92cb52ed3add"

    private const val RELATIVE_PATH =
        "imagefs_shared/proton/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/$FILE_NAME"
    private const val TARGET_MODULE = "d3dcompiler_47"
    private const val MAX_OVERRIDE_BYTES = 16 * 1024
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val MAX_PE_OFFSET = 4096
    private const val PE_HEADER_BYTES_AFTER_OFFSET = 26
    private const val AA64 = 0xaa64
    private const val PE32_PLUS = 0x20b

    internal data class CompilerPin(val sha256: String, val size: Long)

    internal data class Selection(
        val path: String,
        val sha256: String,
        val size: Long,
    )

    private data class FileIdentity(val key: Any?, val size: Long, val modified: Long)

    internal fun verify(context: Context): Selection {
        val root = requireRoot(context.filesDir)
        return verifyFile(root, File(root, RELATIVE_PATH), CompilerPin(APPROVED_SHA256, APPROVED_SIZE))
    }

    /** Test-only seam; production always uses [verify] and the frozen pin/path. */
    internal fun verifyFileForTests(root: File, candidate: File, pin: CompilerPin): Selection =
        verifyFile(requireRoot(root), candidate, pin)

    /** Pure merge of the final raw Wine override value. */
    internal fun mergeWineDllOverrides(existing: String?): String {
        require(existing == null || (existing.length <= MAX_OVERRIDE_BYTES &&
            existing.toByteArray(Charsets.UTF_8).size <= MAX_OVERRIDE_BYTES)) {
            "WINEDLLOVERRIDES exceeds the bounded input"
        }
        existing?.let {
            require(it.none { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
                "WINEDLLOVERRIDES contains a forbidden control character"
            }
        }
        if (existing.isNullOrEmpty()) return "$TARGET_MODULE=b"

        val retained = existing.split(';').mapNotNull { segment ->
            val equals = segment.indexOf('=')
            if (equals <= 0) return@mapNotNull segment // Preserve malformed segments verbatim.
            val modules = segment.substring(0, equals).split(',')
            if (modules.any(String::isEmpty)) return@mapNotNull segment
            val unrelated = modules.filterNot(::isTargetModule)
            if (unrelated.isEmpty()) null
            else unrelated.joinToString(",") + segment.substring(equals)
        }
        val prefix = retained.joinToString(";")
        return if (retained.isEmpty()) "$TARGET_MODULE=b" else "$prefix;$TARGET_MODULE=b"
    }

    private fun verifyFile(root: File, candidate: File, pin: CompilerPin): Selection {
        require(pin.sha256.matches(Regex("[0-9a-fA-F]{64}")) && pin.size > 0) {
            "builtin shader compiler pin is malformed"
        }
        val managed = secureFile(root, candidate)
        val before = fileIdentity(managed, pin.size)
        readPeHeader(managed)
        val digest = digestFile(managed, pin.size)
        val after = fileIdentity(managed, pin.size)
        require(before == after && digest.equals(pin.sha256, ignoreCase = true)) {
            "builtin shader compiler changed or does not match the approved pin"
        }
        return Selection(managed.canonicalPath, digest.lowercase(Locale.ROOT), after.size)
    }

    private fun requireRoot(raw: File): File {
        require(!Files.isSymbolicLink(raw.toPath()) && Files.isDirectory(raw.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "builtin shader compiler files root is not a real directory"
        }
        return raw.canonicalFile
    }

    private fun secureFile(root: File, raw: File): File {
        require(!Files.isSymbolicLink(raw.toPath())) { "builtin shader compiler is a symlink" }
        val canonicalRoot = root.canonicalFile
        val rawPath = raw.toPath().toAbsolutePath().normalize()
        require(rawPath.startsWith(canonicalRoot.toPath()) && rawPath != canonicalRoot.toPath()) {
            "builtin shader compiler raw path escaped the managed files root"
        }
        var rawComponent = canonicalRoot.toPath()
        canonicalRoot.toPath().relativize(rawPath).forEach { part ->
            rawComponent = rawComponent.resolve(part)
            require(!Files.isSymbolicLink(rawComponent)) { "builtin shader compiler source path contains a symlink" }
        }
        val canonical = raw.canonicalFile
        val rootPath = canonicalRoot.path
        require(canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) {
            "builtin shader compiler escaped the managed files root"
        }
        var current = canonicalRoot
        canonical.relativeTo(canonicalRoot).path.split(File.separatorChar).filter(String::isNotEmpty).forEach { part ->
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "builtin shader compiler path contains a symlink" }
        }
        return canonical
    }

    private fun fileIdentity(file: File, expectedSize: Long): FileIdentity {
        require(!Files.isSymbolicLink(file.toPath())) { "builtin shader compiler is a symlink" }
        require(Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "builtin shader compiler is missing" }
        val attributes = Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == expectedSize) {
            "builtin shader compiler has an unexpected bounded identity"
        }
        return FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun digestFile(file: File, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            require(input.size() == expectedSize)
            val buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES)
            var total = 0L
            while (total < expectedSize) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), expectedSize - total).toInt())
                val count = input.read(buffer)
                require(count > 0) { "bounded builtin shader compiler read made no progress" }
                buffer.flip()
                digest.update(buffer)
                total += count
            }
            require(total == expectedSize && input.position() == expectedSize)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readPeHeader(file: File) {
        val bytes = ByteArray(MAX_PE_OFFSET + PE_HEADER_BYTES_AFTER_OFFSET)
        var count = 0
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < bytes.size) {
                val read = input.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
                if (read < 0) break
                require(read > 0) { "bounded PE header read made no progress" }
                count += read
            }
        }
        require(count >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "builtin shader compiler is not an MZ image"
        }
        val offset = littleInt(bytes, 0x3c)
        require(offset >= 64 && offset <= MAX_PE_OFFSET && offset <= count - PE_HEADER_BYTES_AFTER_OFFSET) {
            "builtin shader compiler PE header is outside the bounded header"
        }
        require(bytes[offset] == 'P'.code.toByte() && bytes[offset + 1] == 'E'.code.toByte() &&
            bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte() &&
            littleShort(bytes, offset + 4) == AA64 && littleShort(bytes, offset + 24) == PE32_PLUS
        ) { "builtin shader compiler is not an AA64 PE32+ image" }
    }

    private fun isTargetModule(module: String): Boolean {
        val normalized = module.trim().lowercase(Locale.ROOT)
        return normalized == TARGET_MODULE || normalized == "$TARGET_MODULE.dll"
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
