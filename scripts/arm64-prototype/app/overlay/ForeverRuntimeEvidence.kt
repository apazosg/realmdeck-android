package app.gamenative

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.LinkedHashSet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, read-only evidence for the runtime immediately before/after a launch.
 *
 * This intentionally does not inspect the client tree, process memory, credentials,
 * or the network. A missing or unreadable item is UNKNOWN, never a successful probe.
 */
object ForeverRuntimeEvidence {
    private const val MAX_COMPONENT_BYTES = 8L * 1024L * 1024L
    private const val MAX_HEADER_BYTES = 4096
    private const val MAX_COMPONENTS = 12
    private const val MAX_HELPERS = 12
    private const val MAX_TEXT = 512

    private val packageIdentifier = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    fun inspect(context: Context, runId: String, argv: List<String>, env: Map<String, String>): JSONObject {
        val filesRoot = canonical(context.filesDir)
        val nativeRoot = canonical(File(context.applicationInfo.nativeLibraryDir))
        val managedRoots = listOfNotNull(filesRoot, nativeRoot).distinctBy { it.path }

        val host = inspectHostPackage(context.packageName, env)
        val imageFs = inspectImageFs(filesRoot, managedRoots, env)
        val components = inspectNativeComponents(nativeRoot, managedRoots, env)
        val prefixes = prefixRoots(filesRoot, managedRoots, env)
        val helpers = inspectHelpers(filesRoot, managedRoots, prefixes, imageFs)
        val wine = inspectWineExecutable(argv, env, managedRoots)
        val helperRequired = argv.any { it.substringAfterLast('/').substringAfterLast('\\').equals("winhandler.exe", true) }

        val unknowns = sequenceOf(
            host.optString("status"), imageFs.optString("status"), wine.optString("status"),
        ).count { it != "PASS" } +
            (0 until components.length()).count { components.getJSONObject(it).optString("status") != "PASS" } +
            (if (helperRequired) (0 until helpers.length()).count { helpers.getJSONObject(it).optString("status") != "PASS" } else 0)

        return JSONObject()
            .put("schema", 1)
            .put("readonly", true)
            .put("verificationBoundary", "file/header evidence only; no helper execution, WoW identity, render or login proof")
            .put("runId", runId.take(MAX_TEXT))
            .put("status", if (unknowns == 0) "PASS" else "UNKNOWN")
            .put("unknownCount", unknowns)
            .put("hostPackage", host)
            .put("effectiveHostPackage", host.opt("effective"))
            .put("imageFs", imageFs)
            .put("effectiveImageFsRoot", imageFs.opt("effectiveRoot"))
            .put("wineExecutable", wine)
            .put("nativeComponents", components)
            .put("ldPreloadComponents", components)
            .put("helpers", helpers)
            .put("helperCandidates", helpers)
            .put("helperExecutionRequired", helperRequired)
    }

    private fun inspectHostPackage(applicationPackage: String, env: Map<String, String>): JSONObject {
        val winemu = env["WINEMU_HOST_PKG"]
        val host = env["HOST_PKG"]
        val valid = !winemu.isNullOrBlank() && !host.isNullOrBlank() &&
            packageIdentifier.matches(winemu) && packageIdentifier.matches(host)
        val same = valid && winemu == host && winemu == applicationPackage
        return JSONObject()
            .put("status", if (same) "PASS" else "UNKNOWN")
            .put("effective", if (valid && winemu == host) winemu else JSONObject.NULL)
            .put("application", applicationPackage.take(MAX_TEXT))
            .put("aliases", JSONArray().put("WINEMU_HOST_PKG").put("HOST_PKG"))
            .put("reason", if (same) "declared-and-matches-application" else "aliases-missing-invalid-or-mismatched")
    }

    private fun inspectImageFs(
        filesRoot: File?,
        managedRoots: List<File>,
        env: Map<String, String>,
    ): JSONObject {
        val expected = filesRoot?.let { File(it, "imagefs/usr") }
        val declared = env["IMAGEFS"] ?: env["WINEMU_IMAGEFS"]
        val candidate = declared?.let { File(it) } ?: expected
        val safe = candidate?.let { managedCandidate(it, managedRoots) }
        val exists = safe?.isDirectory == true
        return JSONObject()
            .put("status", if (exists) "PASS" else "UNKNOWN")
            .put("path", safe?.path ?: JSONObject.NULL)
            .put("exists", exists)
            .put("effectiveRoot", safe?.path ?: JSONObject.NULL)
            .put("reason", when {
                safe == null -> "missing-or-unmanaged-root"
                exists -> "managed-directory"
                else -> "not-installed"
            })
    }

    private fun inspectNativeComponents(
        nativeRoot: File?,
        managedRoots: List<File>,
        env: Map<String, String>,
    ): JSONArray {
        val paths = LinkedHashSet<String>()
        env["LD_PRELOAD"].orEmpty().split(':').filter { it.isNotBlank() }.forEach { paths += it }
        val result = JSONArray()
        paths.take(MAX_COMPONENTS).forEach { raw ->
            val candidate = if (File(raw).isAbsolute) File(raw) else File(nativeRoot, raw)
            result.put(inspectElf(candidate, managedRoots))
        }
        if (result.length() == 0) {
            result.put(JSONObject().put("status", "UNKNOWN").put("reason", "LD_PRELOAD-not-declared"))
        }
        return result
    }

    private fun inspectElf(file: File, managedRoots: List<File>): JSONObject {
        val safe = managedCandidate(file, managedRoots)
        if (safe == null) return unknownNativePath()
        if (!safe.isFile) return unknownNativeFile(safe, "not-installed")
        if (safe.length() > MAX_COMPONENT_BYTES) return unknownNativeFile(safe, "component-too-large")
        return try {
            val header = readAtMost(safe, 64)
            val valid = header.size >= 64 && header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte()
            val machine = if (valid) u16(header, 18) else -1
            val arch = when (machine) {
                183 -> "AA64"
                62 -> "X86_64"
                3 -> "X86"
                else -> "UNKNOWN"
            }
            val output = baseFile(safe)
                .put("kind", "native-component")
                .put("size", safe.length())
                .put("sha256", sha256(safe))
                .put("elf", valid)
                .put("elfMachine", machine)
                .put("elfArch", arch)
            output.put("status", if (valid && arch == "AA64") "PASS" else "UNKNOWN")
            if (!valid) output.put("reason", "invalid-ELF-header")
            else if (arch != "AA64") output.put("reason", "host-preload-requires-AArch64")
            output
        } catch (e: Exception) {
            unknownNativeFile(safe, "unreadable")
        }
    }

    private fun inspectWineExecutable(argv: List<String>, env: Map<String, String>, managedRoots: List<File>): JSONObject {
        val explicit = listOf("REDIRECT_EXEC__PROC_SELF_EXE", "WINELOADER")
            .firstOrNull { !env[it].isNullOrBlank() }
        val nativeIndex = if (argv.firstOrNull() == "/system/bin/linker64") 1 else 0
        val raw = explicit?.let { env[it] } ?: argv.getOrNull(nativeIndex)?.takeIf { it.isNotBlank() }
        val candidate = raw?.let { File(it) }
        val safe = candidate?.let { managedCandidate(it, managedRoots) }
        val exists = safe?.isFile == true
        val output = JSONObject()
            .put("status", if (exists) "PASS" else "UNKNOWN")
            .put("path", safe?.path ?: JSONObject.NULL)
            .put("exists", exists)
            .put("source", explicit ?: "argv[$nativeIndex]")
            .put("argvCount", argv.size.coerceAtMost(32))
            .put("reason", if (exists) "managed-native-executable" else "missing-or-unmanaged-path")
        if (safe != null && exists) {
            val header = inspectElf(safe, managedRoots)
            output.put("header", header)
            if (header.optString("status") != "PASS" || header.optString("elfArch") != "AA64") {
                output.put("status", "UNKNOWN").put("reason", "native-Wine-header-not-verified")
            }
        }
        return output
    }

    private fun prefixRoots(
        filesRoot: File?,
        managedRoots: List<File>,
        env: Map<String, String>,
    ): List<File> {
        val candidates = LinkedHashSet<String>()
        env["WINEPREFIX"]?.let { candidates += it }
        filesRoot?.let {
            listOf("imagefs/home/xuser/.wine", "prefix", "wineprefix")
                .forEach { relative -> candidates += File(it, relative).path }
        }
        return candidates.mapNotNull { managedCandidate(File(it), managedRoots) }.distinctBy { it.path }.take(4)
    }

    private fun inspectHelpers(
        filesRoot: File?,
        managedRoots: List<File>,
        prefixes: List<File>,
        imageFs: JSONObject,
    ): JSONArray {
        val candidates = LinkedHashSet<String>()
        fun addPrefix(prefix: File) {
            listOf(
                "system32/winhandler.exe",
                "syswow64/winhandler.exe",
                "drive_c/windows/system32/winhandler.exe",
                "drive_c/windows/syswow64/winhandler.exe",
            ).forEach { candidates += File(prefix, it).path }
        }
        // Effective prefix first; ImageFS must not disappear behind twelve
        // fallback prefix paths before the inspection budget is applied.
        prefixes.firstOrNull()?.let(::addPrefix)
        val imageRoot = imageFs.optString("effectiveRoot").takeIf { it.isNotBlank() && it != "null" }?.let { File(it) }
        (imageRoot ?: filesRoot?.let { File(it, "imagefs") })?.let { root ->
            val relative = if (imageRoot != null) listOf("bin/winhandler.exe", "share/winhandler.exe")
            else listOf("usr/bin/winhandler.exe", "usr/share/winhandler.exe")
            relative.forEach { candidates += File(root, it).path }
        }
        prefixes.drop(1).forEach(::addPrefix)

        val result = JSONArray()
        candidates.take(MAX_HELPERS).forEach { path -> result.put(inspectPe(File(path), managedRoots)) }
        if (result.length() == 0) result.put(unknownPath("winhandler"))
        return result
    }

    private fun inspectPe(file: File, managedRoots: List<File>): JSONObject {
        val safe = managedCandidate(file, managedRoots)
        if (safe == null) return unknownPePath()
        if (!safe.isFile) return unknownPeFile(safe, "not-installed")
        if (safe.length() > MAX_COMPONENT_BYTES) return unknownPeFile(safe, "helper-too-large")
        return try {
            val machine = readPeMachine(safe)
            val label = when (machine) {
                0xaa64 -> "ARM64"
                0x8664 -> "X64"
                0x14c -> "X86"
                else -> "UNKNOWN"
            }
            val output = baseFile(safe)
                .put("kind", "winhandler")
                .put("size", safe.length())
                .put("sha256", sha256(safe))
                .put("peMachine", label)
                .put("peMachineHex", if (machine >= 0) "0x${machine.toString(16)}" else JSONObject.NULL)
            output.put("status", if (machine >= 0 && label != "UNKNOWN") "PASS" else "UNKNOWN")
            if (machine < 0) output.put("reason", "invalid-PE-header")
            output
        } catch (_: Exception) {
            unknownPeFile(safe, "unreadable-or-invalid-PE")
        }
    }

    private fun readPeMachine(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 64) return -1
            val dos = ByteArray(64)
            raf.readFully(dos)
            if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return -1
            val offset = u32(dos, 0x3c)
            if (offset < 64 || offset > MAX_HEADER_BYTES || offset > raf.length() - 24) return -1
            raf.seek(offset)
            val pe = ByteArray(24)
            raf.readFully(pe)
            if (pe[0] != 'P'.code.toByte() || pe[1] != 'E'.code.toByte() || pe[2] != 0.toByte() || pe[3] != 0.toByte()) return -1
            return u16(pe, 4)
        }
    }

    private fun managedCandidate(file: File, roots: List<File>): File? {
        val candidate = canonical(file) ?: return null
        return if (roots.any { isContained(candidate, it) }) candidate else null
    }

    private fun canonical(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun isContained(file: File, root: File): Boolean {
        val rootPath = root.path + File.separator
        return file.path == root.path || file.path.startsWith(rootPath)
    }

    private fun baseFile(file: File): JSONObject = JSONObject().put("path", file.path).put("exists", file.isFile)

    private fun unknownNativePath(): JSONObject = JSONObject()
        .put("kind", "native-component")
        .put("status", "UNKNOWN")
        .put("path", JSONObject.NULL)
        .put("exists", false)
        .put("size", JSONObject.NULL)
        .put("sha256", JSONObject.NULL)
        .put("elfMachine", -1)
        .put("elfArch", "UNKNOWN")
        .put("reason", "outside-managed-roots")

    private fun unknownNativeFile(file: File, reason: String): JSONObject = baseFile(file)
        .put("kind", "native-component")
        .put("status", "UNKNOWN")
        .put("size", if (file.exists()) file.length() else JSONObject.NULL)
        .put("sha256", JSONObject.NULL)
        .put("elfMachine", -1)
        .put("elfArch", "UNKNOWN")
        .put("reason", reason)

    private fun unknownPePath(): JSONObject = JSONObject()
        .put("kind", "winhandler")
        .put("status", "UNKNOWN")
        .put("path", JSONObject.NULL)
        .put("exists", false)
        .put("size", JSONObject.NULL)
        .put("sha256", JSONObject.NULL)
        .put("peMachine", "UNKNOWN")
        .put("peMachineHex", JSONObject.NULL)
        .put("reason", "outside-managed-roots")

    private fun unknownPeFile(file: File, reason: String): JSONObject = baseFile(file)
        .put("kind", "winhandler")
        .put("status", "UNKNOWN")
        .put("size", if (file.exists()) file.length() else JSONObject.NULL)
        .put("sha256", JSONObject.NULL)
        .put("peMachine", "UNKNOWN")
        .put("peMachineHex", JSONObject.NULL)
        .put("reason", reason)

    private fun unknownPath(kind: String): JSONObject = JSONObject()
        .put("kind", kind).put("status", "UNKNOWN").put("path", JSONObject.NULL).put("reason", "outside-managed-roots")

    private fun unknownFile(file: File, reason: String): JSONObject = baseFile(file).put("status", "UNKNOWN").put("reason", reason)

    private fun readAtMost(file: File, max: Int): ByteArray = Files.newInputStream(
        file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
    ).use { input ->
        val bytes = ByteArray(max)
        var count = 0
        while (count < max) {
            val read = input.read(bytes, count, max - count)
            if (read < 0) break
            count += read
        }
        bytes.copyOf(count)
    }

    private fun sha256(file: File): String {
        require(file.length() <= MAX_COMPONENT_BYTES) { "digest exceeds evidence limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_COMPONENT_BYTES) { "digest exceeds evidence limit" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
