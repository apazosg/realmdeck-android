package app.gamenative

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Private-prefix preparation only.  It never starts Wine or creates a process. */
object ForeverWinePrefix {
    private const val IMAGE_ASSET = "forever/wine-prefix/prefix-image.zip"
    private const val IMAGE_MANIFEST_ASSET = "forever/wine-prefix/image-manifest.json"
    private const val PUBLISHER_NAME = "libforever_wine_prefix.so"
    private const val IMAGE_MANIFEST_SHA256 = "2318de72bb020da9263300511a2139abbd06c228f46fac1708d0fb759e625ba9"
    private const val IMAGE_ZIP_SHA256 = "42f9e9119682f5e3cbd9071efe3f079d0cac76896fe3ce0fa9b2ef0d41048853"
    private const val PUBLISHER_SHA256 = "f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914"
    private const val IMAGE_ZIP_MAX = 256L * 1024L * 1024L
    private const val MANIFEST_MAX = 4L * 1024L * 1024L
    private const val RECEIPT_MAX = 16L * 1024L
    private const val PREPARE_NANOS = 120L * 1_000_000_000L
    // Linux asm-generic O_DIRECTORY on the supported Android AArch64 ABI;
    // the Android public SDK does not expose this libcore constant.
    private const val DIRECTORY_OPEN_FLAG = 0x10000
    private val wsPattern = Regex("WS-[0-9a-f]{32}")
    private val noncePattern = Regex("[0-9a-f]{32}")

    data class Prepared(
        val wsRunId: String,
        val nonce: String,
        val prefix: File,
        val runtimeRoot: File,
        val prefixDev: Long,
        val prefixIno: Long,
        val receipt: File,
    )

    fun prepare(
        context: Context,
        wsRunId: String,
        nonce: String,
        launchPublisher: (List<String>, Long) -> Int,
    ): Prepared {
        require(wsPattern.matches(wsRunId)) { "WS must be exactly 32 hexadecimal characters" }
        require(noncePattern.matches(nonce)) { "nonce must be exactly 32 lowercase hexadecimal characters" }
        val deadline = System.nanoTime() + PREPARE_NANOS
        fun checkDeadline() { check(System.nanoTime() <= deadline) { "Wine prefix preparation deadline exceeded" } }

        val files = ownFilesRoot(context)
        requireAppRoot(files)
        val forever = fixedDirectory(files, "forever")
        val controls = fixedDirectory(forever, "wine-controls")
        val run = exclusiveDirectory(controls, wsRunId)
        val stageParent = exclusiveDirectory(run, "prefix-stage")
        val stage = exclusiveDirectory(stageParent, ".wine")
        val prefixParent = exclusiveDirectory(run, "prefix")
        val probeRoot = exclusiveDirectory(run, "probe-root")
        val imagefs = File(files, "imagefs_shared").absoluteFile
        requireAppRoot(imagefs)
        val runtimeRoot = File(imagefs, "proton/proton-11.0-90624-arm64ec").absoluteFile
        requireAppRoot(runtimeRoot)
        checkDeadline()

        val manifestBytes = context.assets.open(IMAGE_MANIFEST_ASSET).use { readBounded(it, MANIFEST_MAX) }
        check(sha256(manifestBytes) == IMAGE_MANIFEST_SHA256) { "prefix image manifest pin mismatch" }
        val image = JSONObject(String(manifestBytes, Charsets.US_ASCII))
        check(image.optString("schema") == "wowforever.wine-prefix-image/v1") { "unsupported prefix image manifest" }
        val expected = linkedMapOf<String, Entry>()
        val entries = image.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val path = safeRelative(item.getString("path"))
            check(expected.put(path, Entry(item.getString("type"), item.getLong("bytes"), item.getInt("mode"), item.optString("sha256"))) == null) { "duplicate image entry" }
        }
        check(entries.length() == 574 && expected.values.count { it.type == "regular" } == 411 && expected.values.count { it.type == "directory" } == 163) { "prefix image accounting mismatch" }
        checkDeadline()

        val zipDigest = MessageDigest.getInstance("SHA-256")
        context.assets.open(IMAGE_ASSET).use { input ->
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= IMAGE_ZIP_MAX) { "prefix image exceeds bound" }
                zipDigest.update(buffer, 0, count)
                checkDeadline()
            }
        }
        check(zipDigest.digest().hex() == IMAGE_ZIP_SHA256) { "prefix image ZIP pin mismatch" }

        val seen = HashSet<String>()
        context.assets.open(IMAGE_ASSET).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    checkDeadline()
                    val entry = zip.nextEntry ?: break
                    val path = normalizeZipPath(entry.name)
                    check(seen.add(path)) { "duplicate prefix image entry" }
                    val item = expected[path] ?: error("unexpected prefix image entry: $path")
                    val directory = entry.isDirectory
                    check((directory && item.type == "directory") || (!directory && item.type == "regular")) { "prefix image member type mismatch" }
                    check(item.mode in 0..0x1ff && item.type != "symlink" && item.type != "special") { "prefix image mode/type mismatch" }
                    val target = if (path == ".wine") stage else stage.resolve(path.removePrefix(".wine/"))
                    requireStagePath(stage, target)
                    if (directory) {
                        if (path == ".wine") {
                            requirePrivate(stage, item.mode)
                        } else {
                            exclusiveTemplateDirectory(stage, target.parentFile ?: error("missing directory parent"), target.name, item.mode)
                        }
                    } else {
                        val parent = target.parentFile ?: error("missing file parent")
                        requireStagePath(stage, parent)
                        check(parent.isDirectory) { "prefix image parent is absent" }
                        val descriptor = Os.open(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, item.mode)
                        writeEntry(descriptor, zip, item)
                    }
                    zip.closeEntry()
                }
            }
        }
        check(seen == expected.keys) { "prefix image member set mismatch" }
        createReconstructedLinks(stage, imagefs, probeRoot, image)
        checkDeadline()
        val publisherDir = File(context.applicationInfo.nativeLibraryDir).absoluteFile
        require(publisherDir.canonicalFile == publisherDir) { "nativeLibraryDir is not canonical" }
        val publisher = File(publisherDir, PUBLISHER_NAME).absoluteFile
        require(publisher.name == PUBLISHER_NAME && publisher.parentFile == publisherDir) { "publisher path is not fixed" }
        verifyPublisher(publisher)
        val argv = listOf("/system/bin/linker64", publisher.path, "--run", wsRunId, "--stage", stage.path, "--imagefs", imagefs.path, "--probe-root", probeRoot.path)
        val result = launchPublisher(argv, deadline)
        check(result == 0) { "prefix publisher failed: $result" }
        checkDeadline()
        check(!stage.exists()) { "publisher did not consume stage" }
        val prefix = File(prefixParent, ".wine").absoluteFile
        val identityBeforeReceipt = requirePrivate(prefix, 0x1c0)
        val identityAfterReceipt = Os.lstat(prefix.path)
        check(sameObject(identityBeforeReceipt, identityAfterReceipt)) { "published prefix identity changed before receipt" }
        val receipt = File(run, "prepared.receipt")
        writeReceipt(receipt, wsRunId, nonce, prefix, runtimeRoot, identityAfterReceipt.st_dev, identityAfterReceipt.st_ino)
        return Prepared(wsRunId, nonce, prefix, runtimeRoot, identityAfterReceipt.st_dev, identityAfterReceipt.st_ino, receipt)
    }

    /** Verifies the closed, private hand-off produced by [prepare]. */
    fun verifyPrepared(context: Context, prepared: Prepared): Unit {
        require(wsPattern.matches(prepared.wsRunId)) { "WS must be exactly 32 hexadecimal characters" }
        require(noncePattern.matches(prepared.nonce)) { "nonce must be exactly 32 lowercase hexadecimal characters" }
        check(prepared.prefixDev >= 0L && prepared.prefixIno > 0L) { "invalid prepared prefix identity" }

        val files = ownFilesRoot(context)
        requireAppRoot(files)

        val forever = existingPrivateDirectory(files, "forever")
        val controls = existingPrivateDirectory(forever, "wine-controls")
        val run = existingPrivateDirectory(controls, prepared.wsRunId)
        val prefixParent = existingPrivateDirectory(run, "prefix")
        val prefix = File(prefixParent, ".wine").absoluteFile
        requireCanonical(prefix, "prepared prefix is not canonical")
        check(prepared.prefix.path == prefix.path) { "prepared prefix is not the fixed WS prefix" }
        val prefixBefore = requirePrivate(prefix, 0x1c0)
        check(prefixBefore.st_dev == prepared.prefixDev && prefixBefore.st_ino == prepared.prefixIno) {
            "prepared prefix identity mismatch"
        }

        val imagefs = File(files, "imagefs_shared").absoluteFile
        val proton = File(imagefs, "proton").absoluteFile
        val runtime = File(proton, "proton-11.0-90624-arm64ec").absoluteFile
        requireCanonical(imagefs, "imagefs root is not canonical")
        requireCanonical(proton, "proton root is not canonical")
        requireCanonical(runtime, "runtime root is not canonical")
        requireAppRoot(imagefs)
        requireAppRoot(proton)
        requireAppRoot(runtime)
        check(prepared.runtimeRoot.path == runtime.path) { "prepared runtime is not the fixed runtime cache" }

        val receipt = File(run, "prepared.receipt").absoluteFile
        requireCanonical(receipt, "prepared receipt is not canonical")
        check(prepared.receipt.path == receipt.path) { "prepared receipt is not the fixed WS receipt" }
        val receiptBefore = requireReceipt(receipt)
        val fields = parseReceipt(readReceipt(receipt, receiptBefore))
        validateReceiptFields(fields, prepared, prefix.path, runtime.path)

        // This is deliberately after the receipt read: the path and the hand-off
        // identity must still describe the same directory at the final boundary.
        val prefixAfter = Os.lstat(prefix.path)
        check(sameObject(prefixBefore, prefixAfter)) { "prepared prefix changed during receipt validation" }
        check(prefixAfter.st_dev == prepared.prefixDev && prefixAfter.st_ino == prepared.prefixIno) {
            "prepared prefix identity changed during receipt validation"
        }
    }

    private data class Entry(val type: String, val bytes: Long, val mode: Int, val sha256: String)

    private fun fixedDirectory(parent: File, name: String): File {
        val candidate = File(parent, name)
        if (!candidate.exists()) {
            check(candidate.mkdir()) { "cannot create fixed directory: $candidate" }
            Os.chmod(candidate.path, 0x1c0)
        }
        requirePrivate(candidate, 0x1c0)
        return candidate
    }

    private fun existingPrivateDirectory(parent: File, name: String): File {
        val candidate = File(parent, name).absoluteFile
        requireCanonical(candidate, "private directory is not canonical")
        requirePrivate(candidate, 0x1c0)
        return candidate
    }

    private fun exclusiveDirectory(parent: File, name: String, mode: Int = 0x1c0): File {
        val candidate = File(parent, name)
        check(candidate.mkdir()) { "directory already exists or cannot be created: $candidate" }
        Os.chmod(candidate.path, mode)
        requirePrivate(candidate, mode)
        return candidate
    }

    private fun exclusiveTemplateDirectory(stage: File, parent: File, name: String, mode: Int): File {
        val candidate = File(parent, name)
        requirePrivate(stage, 0x1c0)
        requireStagePath(stage, parent)
        check(candidate.mkdir()) { "directory already exists or cannot be created: $candidate" }
        Os.chmod(candidate.path, mode)
        requireTemplateDirectory(candidate, mode)
        return candidate
    }

    private fun requirePrivate(path: File, exactMode: Int): android.system.StructStat {
        val stat = Os.lstat(path.path)
        check(ownedDirectory(stat.st_uid, stat.st_gid, stat.st_mode, stat.st_nlink.toLong(), Os.getuid(), Os.getgid(), false)) { "private directory ownership/mode mismatch: $path" }
        if (exactMode != 0) check(stat.st_mode and 0x1ff == exactMode) { "private directory mode mismatch: $path" }
        return stat
    }

    private fun requireTemplateDirectory(path: File, exactMode: Int): android.system.StructStat {
        val stat = Os.lstat(path.path)
        check(ownedTemplateDirectory(stat.st_uid, stat.st_gid, stat.st_mode, stat.st_nlink.toLong(), Os.getuid(), Os.getgid(), exactMode)) {
            "template directory ownership/mode mismatch: $path"
        }
        return stat
    }

    private fun requireAppRoot(path: File): android.system.StructStat {
        val stat = Os.lstat(path.path)
        check(ownedDirectory(stat.st_uid, stat.st_gid, stat.st_mode, stat.st_nlink.toLong(), Os.getuid(), Os.getgid(), true)) { "app root ownership/mode mismatch: $path" }
        return stat
    }

    private fun requireCanonical(path: File, message: String) {
        val absolute = path.absoluteFile
        check(absolute.path == path.path && absolute.canonicalFile.path == absolute.path) { message }
    }

    private fun ownFilesRoot(context: Context): File {
        val supplied = context.filesDir.absoluteFile
        val canonical = supplied.canonicalFile
        val packageName = context.packageName
        check(filesRootAliasFacts(supplied.path, canonical.path, packageName)["accepted"] == true) {
            "filesDir is not the canonical private app root"
        }
        return canonical
    }

    /** Enumerates only the two package/user0 aliases; no caller-supplied path is echoed. */
    internal fun filesRootAliasFacts(supplied: String, canonical: String, packageName: String): Map<String, Any> {
        val ownAliases = setOf("/data/user/0/$packageName/files", "/data/data/$packageName/files")
        fun kind(path: String): String = when (path) {
            "/data/user/0/$packageName/files" -> "user0"
            "/data/data/$packageName/files" -> "data"
            else -> "other"
        }
        return mapOf(
            "suppliedAlias" to kind(supplied),
            "canonicalAlias" to kind(canonical),
            "suppliedOwn" to (supplied in ownAliases),
            "canonicalOwn" to (canonical in ownAliases),
            "accepted" to (supplied in ownAliases && canonical in ownAliases),
        )
    }

    internal fun validFilesRootAlias(supplied: String, canonical: String, packageName: String): Boolean {
        // Android may canonicalize either spelling to the other. This permits
        // only the two user-0 spellings of this package, not arbitrary links.
        return filesRootAliasFacts(supplied, canonical, packageName)["accepted"] == true
    }

    private fun requireReceipt(path: File): android.system.StructStat {
        val stat = Os.lstat(path.path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_gid == Os.getgid() &&
            stat.st_nlink == 1L && stat.st_mode and 0x1ff == 0x180) { "prepared receipt metadata mismatch" }
        return stat
    }

    internal fun ownedDirectory(uid: Int, gid: Int, mode: Int, links: Long,
        ownUid: Int, ownGid: Int, allowPrivateGroupWrite: Boolean): Boolean =
        OsConstants.S_ISDIR(mode) && uid == ownUid && gid == ownGid && links >= 1L &&
            mode and (if (allowPrivateGroupWrite) 0x2 else 0x12) == 0

    internal fun ownedTemplateDirectory(uid: Int, gid: Int, mode: Int, links: Long,
        ownUid: Int, ownGid: Int, exactMode: Int): Boolean =
        OsConstants.S_ISDIR(mode) && uid == ownUid && gid == ownGid && links >= 1L &&
            mode and 0x1ff == exactMode

    private fun sameObject(first: android.system.StructStat, second: android.system.StructStat): Boolean =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino && first.st_uid == second.st_uid &&
            first.st_gid == second.st_gid && first.st_mode == second.st_mode && first.st_nlink == second.st_nlink

    private fun createReconstructedLinks(stage: File, imagefs: File, probe: File, image: JSONObject) {
        val links = image.getJSONArray("links")
        check(links.length() == 3) { "prefix image link accounting mismatch" }
        val expected = mapOf(
            ".wine/dosdevices/a:" to probe.path,
            ".wine/dosdevices/c:" to "../drive_c",
            ".wine/dosdevices/z:" to imagefs.path,
        )
        val seen = HashSet<String>()
        for (index in 0 until links.length()) {
            val item = links.getJSONObject(index)
            val path = safeRelative(item.getString("path"))
            val target = expected[path] ?: error("unexpected reconstructed link")
            val expectedKind = if (path == ".wine/dosdevices/c:") "relative" else "explicit_root"
            val expectedManifestTarget = if (path == ".wine/dosdevices/c:") "../drive_c" else if (path.endsWith("/a:")) "<probe_root>" else "<imagefs_root>"
            check(seen.add(path) && item.getString("target_kind") == expectedKind && item.getString("target") == expectedManifestTarget) { "invalid reconstructed link" }
            val link = stage.resolve(path.removePrefix(".wine/"))
            requireStagePath(stage, link)
            check(runCatching { Os.lstat(link.path) }.isFailure) { "reconstructed link collision" }
            Os.symlink(target, link.path)
            val linkStat = Os.lstat(link.path)
            check(OsConstants.S_ISLNK(linkStat.st_mode) && linkStat.st_uid == Os.getuid() && linkStat.st_gid == Os.getgid() && linkStat.st_nlink == 1L) {
                "reconstructed link metadata mismatch"
            }
            val actual = Os.readlink(link.path)
            check(actual == target) { "reconstructed link target mismatch" }
        }
        check(seen == expected.keys) { "reconstructed link set mismatch" }
        val dosdevices = stage.resolve("dosdevices")
        val fd = Os.open(dosdevices.path, OsConstants.O_RDONLY or DIRECTORY_OPEN_FLAG or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun requireStagePath(stage: File, candidate: File) {
        check(candidate.canonicalFile == candidate.absoluteFile) { "stage path resolves through a symlink" }
        check(candidate.path == stage.path || candidate.path.startsWith(stage.path + File.separator)) { "stage path escapes" }
    }

    private fun safeRelative(path: String): String {
        check(path.isNotEmpty() && !path.startsWith('/') && !path.endsWith('/') && !path.contains('\\') && !path.contains('\u0000')) { "unsafe image path" }
        val parts = path.split('/')
        check(parts.none { it.isEmpty() || it == "." || it == ".." }) { "unsafe image path" }
        return path
    }

    private fun normalizeZipPath(name: String): String = safeRelative(name.removeSuffix("/"))

    private fun verifyPublisher(path: File) {
        val beforePath = Os.lstat(path.path)
        val parent = Os.lstat(path.parentFile!!.path)
        check(OsConstants.S_ISDIR(parent.st_mode) && (parent.st_uid == 0 || parent.st_uid == 1000) && parent.st_mode and 0x12 == 0) { "nativeLibraryDir is mutable or foreign" }
        check(OsConstants.S_ISREG(beforePath.st_mode) && (beforePath.st_uid == 0 || beforePath.st_uid == 1000) && beforePath.st_nlink == 1L && beforePath.st_mode and 0x12 == 0) { "publisher is not immutable native authority" }
        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val beforeFd = Os.fstat(fd)
            check(sameObject(beforePath, beforeFd) && beforeFd.st_size == 123560L) { "publisher identity/size mismatch" }
            val digest = MessageDigest.getInstance("SHA-256")
            val input = FileInputStream(Os.dup(fd))
            input.use {
                val buffer = ByteArray(1024 * 1024)
                while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val afterFd = Os.fstat(fd)
            check(sameObject(beforeFd, afterFd) && digest.digest().hex() == PUBLISHER_SHA256) { "publisher changed or hash mismatch" }
        } finally { Os.close(fd) }
        val afterPath = Os.lstat(path.path)
        check(sameObject(beforePath, afterPath)) { "publisher path changed during validation" }
    }

    private fun writeEntry(fd: FileDescriptor, input: ZipInputStream, item: Entry) {
        FileOutputStream(fd).use { output ->
            Os.fchmod(fd, item.mode)
            requireRegularEntry(Os.fstat(fd), item, 0L)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buffer = ByteArray(1024 * 1024)
            while (total < item.bytes) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), item.bytes - total).toInt())
                check(count > 0) { "short prefix image entry" }
                total += count
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            check(input.read() < 0) { "oversized prefix image entry" }
            output.flush()
            Os.fsync(fd)
            requireRegularEntry(Os.fstat(fd), item, item.bytes)
            check(total == item.bytes && digest.digest().hex() == item.sha256) { "prefix image entry digest mismatch" }
        }
    }

    private fun requireRegularEntry(stat: android.system.StructStat, item: Entry, exactBytes: Long) {
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_gid == Os.getgid() &&
            stat.st_nlink == 1L && stat.st_mode and 0x1ff == item.mode && stat.st_size == exactBytes) {
            "prefix image file metadata mismatch"
        }
    }

    private fun readBounded(input: java.io.InputStream, bound: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= bound) { "bounded asset exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(path: File): String = FileInputStream(path).use { input -> sha256(input) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().hex()
    }

    private fun readReceipt(path: File, pathStat: android.system.StructStat): ByteArray {
        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val beforeFd = Os.fstat(fd)
            check(sameObject(pathStat, beforeFd) && beforeFd.st_size <= RECEIPT_MAX) {
                "prepared receipt changed before read"
            }
            val bytes = FileInputStream(Os.dup(fd)).use { input -> readBounded(input, RECEIPT_MAX) }
            val afterFd = Os.fstat(fd)
            check(sameObject(beforeFd, afterFd) && afterFd.st_size == bytes.size.toLong()) {
                "prepared receipt changed during read"
            }
            val afterPath = Os.lstat(path.path)
            check(sameObject(pathStat, afterPath) && afterPath.st_size == bytes.size.toLong()) {
                "prepared receipt path changed during read"
            }
            return bytes
        } finally {
            Os.close(fd)
        }
    }

    private sealed class ReceiptValue {
        data class Text(val value: String) : ReceiptValue()
        data class Number(val value: Long) : ReceiptValue()
    }

    private class ReceiptParser(private val source: String) {
        private var offset = 0

        fun parse(): Map<String, ReceiptValue> {
            skipWhitespace()
            check(take() == '{') { "prepared receipt is not an object" }
            skipWhitespace()
            val fields = LinkedHashMap<String, ReceiptValue>()
            check(peek() != '}') { "prepared receipt is empty" }
            while (true) {
                val key = parseString()
                check(!fields.containsKey(key)) { "duplicate prepared receipt key: $key" }
                skipWhitespace()
                check(take() == ':') { "prepared receipt key has no value" }
                skipWhitespace()
                fields[key] = parseValue()
                skipWhitespace()
                when (take()) {
                    ',' -> { skipWhitespace(); check(peek() != '}') { "trailing receipt comma" } }
                    '}' -> break
                    else -> error("invalid prepared receipt separator")
                }
            }
            skipWhitespace()
            check(offset == source.length) { "trailing prepared receipt data" }
            return fields
        }

        private fun parseValue(): ReceiptValue = when (peek()) {
            '"' -> ReceiptValue.Text(parseString())
            '-', in '0'..'9' -> ReceiptValue.Number(parseNumber())
            else -> error("prepared receipt value has an invalid type")
        }

        private fun parseNumber(): Long {
            val start = offset
            if (peek() == '-') offset++
            check(peek()?.let { it in '0'..'9' } == true) { "invalid prepared receipt number" }
            if (take() == '0') {
                check(peek()?.let { it in '0'..'9' } != true) { "leading zero in prepared receipt number" }
            } else {
                while (peek()?.let { it in '0'..'9' } == true) offset++
            }
            val token = source.substring(start, offset)
            return token.toLongOrNull() ?: error("prepared receipt number is out of range")
        }

        private fun parseString(): String {
            check(take() == '"') { "prepared receipt key/value is not a string" }
            val result = StringBuilder()
            while (true) {
                check(offset < source.length) { "unterminated prepared receipt string" }
                when (val character = take()) {
                    '"' -> return result.toString()
                    '\\' -> when (val escaped = take()) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            check(offset + 4 <= source.length) { "invalid prepared receipt unicode escape" }
                            val digits = source.substring(offset, offset + 4)
                            check(digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                                "invalid prepared receipt unicode escape"
                            }
                            result.append(digits.toInt(16).toChar())
                            offset += 4
                        }
                        else -> error("invalid prepared receipt escape")
                    }
                    else -> {
                        check(character >= ' ' && character != '\u007f') { "control character in prepared receipt" }
                        result.append(character)
                    }
                }
            }
        }

        private fun skipWhitespace() { while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') offset++ }
        private fun peek(): Char? = source.getOrNull(offset)
        private fun take(): Char = source.getOrNull(offset++) ?: error("truncated prepared receipt")
    }

    private fun parseReceipt(bytes: ByteArray): Map<String, ReceiptValue> {
        check(bytes.all { (it.toInt() and 0xff) < 0x80 }) { "prepared receipt is not ASCII" }
        return ReceiptParser(String(bytes, Charsets.US_ASCII)).parse()
    }

    private fun validateReceiptFields(
        fields: Map<String, ReceiptValue>,
        prepared: Prepared,
        expectedPrefix: String,
        expectedRuntime: String,
    ) {
        val expectedKeys = setOf(
            "schema", "wsRunId", "nonce", "prefix", "runtimeRoot", "prefixDev", "prefixIno",
            "imageManifestSha256", "imageZipSha256",
        )
        check(fields.keys == expectedKeys) { "prepared receipt key set mismatch" }
        fun text(name: String): String = (fields[name] as? ReceiptValue.Text)?.value
            ?: error("prepared receipt field is not a string: $name")
        fun number(name: String): Long = (fields[name] as? ReceiptValue.Number)?.value
            ?: error("prepared receipt field is not an integer: $name")
        check(text("schema") == "wowforever.wine-prefix-prepared/v1") { "unsupported prepared receipt schema" }
        check(text("wsRunId") == prepared.wsRunId) { "prepared receipt WS mismatch" }
        check(text("nonce") == prepared.nonce) { "prepared receipt nonce mismatch" }
        check(text("prefix") == expectedPrefix) { "prepared receipt prefix mismatch" }
        check(text("runtimeRoot") == expectedRuntime) { "prepared receipt runtime mismatch" }
        check(number("prefixDev") == prepared.prefixDev && number("prefixIno") == prepared.prefixIno) {
            "prepared receipt prefix identity mismatch"
        }
        check(text("imageManifestSha256") == IMAGE_MANIFEST_SHA256 && text("imageZipSha256") == IMAGE_ZIP_SHA256) {
            "prepared receipt image pin mismatch"
        }
    }

    /** Pure receipt-binding seam for JVM tests; it does not prove filesystem safety. */
    internal fun validatePreparedReceiptJsonForTest(bytes: ByteArray, prepared: Prepared): Unit {
        validateReceiptFields(parseReceipt(bytes), prepared, prepared.prefix.path, prepared.runtimeRoot.path)
    }

    private fun writeReceipt(receipt: File, ws: String, nonce: String, prefix: File, runtime: File, dev: Long, ino: Long) {
        check(!receipt.exists()) { "prepared receipt already exists" }
        val content = "{" +
            "\"schema\":\"wowforever.wine-prefix-prepared/v1\"," +
            "\"wsRunId\":\"$ws\",\"nonce\":\"$nonce\"," +
            "\"prefix\":\"${prefix.path}\",\"runtimeRoot\":\"${runtime.path}\"," +
            "\"prefixDev\":$dev,\"prefixIno\":$ino," +
            "\"imageManifestSha256\":\"$IMAGE_MANIFEST_SHA256\",\"imageZipSha256\":\"$IMAGE_ZIP_SHA256\"}\n"
        val descriptor = Os.open(receipt.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        FileOutputStream(descriptor).use { output -> output.write(content.toByteArray(Charsets.US_ASCII)); output.fd.sync() }
        Os.chmod(receipt.path, 0x180)
        val parent = Os.open(receipt.parentFile!!.path, OsConstants.O_RDONLY or DIRECTORY_OPEN_FLAG or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(parent) } finally { Os.close(parent) }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
