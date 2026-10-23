package app.gamenative

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.json.JSONObject

/** Closed Android-side policy for the first SERVER_ISOLATION Wine handoff. */
object ForeverWineSupervisorPolicy {
    const val SCHEMA = 2
    const val PACKAGE = "com.wowforever.arm64probe"
    const val PROCESS_SUFFIX = ":wine_supervisor"
    const val LINKER = "/system/bin/linker64"
    private const val LINKER_APEX_ALIAS = "/apex/com.android.runtime/bin/linker64"
    const val SUPERVISOR_NAME = "libforever_wine_supervisor.so"
    const val FIXTURE_NAME = "libforever_run_fixture.so"
    const val SUPERVISOR_PIN_STATUS = "READY"
    const val PUBLISHER_PIN_STATUS = "READY"
    // READY pins are accepted only with the complete independently audited resource set.
    const val PUBLISHER_SIZE = 123_560L
    const val PUBLISHER_SHA256 = "f479ea8def9dc3cffd42affa489ae1554f9214273b13af7d79053a2773ad6914"
    const val SUPERVISOR_SIZE = 45_056L
    const val SUPERVISOR_SHA256 = "6f641c3683b239eb58a3986e947afe2099555ef138d495f8490a000b5b52f0da"
    const val MANIFEST_SHA256 = "8c9bcde26c1ae3a7ebcb620db47b5a5d4f9dc450f021ae4b4d97b28785b3b894"
    const val SERVER_SHA256 = "804d676d11f228c565182d9b49317f42d0fca093b5c1c20a0960ba5753c199f2"
    const val WINE_SHA256 = "389c7e15f699471042845a24fa47ce7d4e3276fd23181e0afb78b63b91cecc3f"
    const val NTDLL_SHA256 = "4239686f121164bd3d96f8a44db81eb708ac9e30fe783c53d0291d93dbade42c"
    const val FIXTURE_SIZE = 12_784L
    const val FIXTURE_SHA256 = "7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f"
    const val MAX_LINE_BYTES = 8 * 1024
    const val MAX_OUTPUT_BYTES = 256 * 1024
    const val MAX_STREAM_BYTES = 64 * 1024
    const val PREFIX_TIMEOUT_SECONDS = 120L
    const val NATIVE_TIMEOUT_SECONDS = 45L
    const val CLEANUP_TIMEOUT_SECONDS = 10L
    const val WATCHDOG_SECONDS = 55L
    const val TOTAL_TIMEOUT_SECONDS = 195L
    const val MAX_CHILDREN = 64L
    const val WITNESS_TTL_SECONDS = 35L

    val COMMAND_KEYS = setOf("schema", "runId", "nonce", "seq", "command", "phase")
    val EVENT_KEYS = setOf(
        "schema", "runId", "nonce", "seq", "event", "phase", "pid", "startTicks", "ppid",
        "uid0", "uid1", "uid2", "uid3", "role", "status", "exitCode", "childCount", "echild",
        "setResult", "getResult", "getValue", "prefixDev", "prefixIno", "socketDev", "socketIno",
        "lockDev", "lockIno",
    )

    data class NativeArtifactPaths(
        val nativeLibraryDir: File,
        val supervisor: File,
        val fixture: File,
        val manifestSha256: String,
    )

    internal data class NativeFileIdentity(val device: Long, val inode: Long)

    fun isSupervisorProcess(debug: Boolean, packageName: String?, processName: String?): Boolean =
        debug && packageName == PACKAGE && processName == "$PACKAGE$PROCESS_SUFFIX"

    /** Safe pre-Hilt gate used by the application bootstrap. */
    @JvmStatic
    fun shouldIsolate(context: Context): Boolean {
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
        return isSupervisorProcess(debug, context.packageName, processName)
    }

    fun requireDebugProcOnly(context: Context) {
        require(BuildConfig.DEBUG) { "Wine supervisor is debug-only" }
        require(BuildConfig.APPLICATION_ID == PACKAGE && context.packageName == PACKAGE)
        require(shouldIsolate(context)) {
            "Wine supervisor reached a non-isolated process"
        }
    }

    fun runtimeRoot(context: Context): File = File(context.filesDir, "imagefs_shared/proton/proton-11.0-90624-arm64ec").canonicalFile

    fun resolve(context: Context): NativeArtifactPaths {
        requireDebugProcOnly(context)
        val directory = File(requireNotNull(context.applicationInfo.nativeLibraryDir)).canonicalFile
        require(directory == File(context.applicationInfo.nativeLibraryDir).absoluteFile)
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        requireImmutableNativeDirectory(directory)
        val manifest = context.assets.open("forever/wine-supervisor/manifest.json").use { input ->
            input.readBytes().also { require(it.size <= MAX_LINE_BYTES * 4) }
        }
        require(sha256(manifest) == MANIFEST_SHA256) { "Wine supervisor manifest pin mismatch" }
        val supervisor = File(directory, SUPERVISOR_NAME)
        require(supervisor.canonicalFile == File(directory, SUPERVISOR_NAME) && supervisor.isFile)
        requireImmutableNative(supervisor)
        require(supervisor.length() == SUPERVISOR_SIZE && sha256(supervisor.readBytes()) == SUPERVISOR_SHA256)
        val fixture = File(directory, FIXTURE_NAME)
        require(fixture.canonicalFile == File(directory, FIXTURE_NAME) && fixture.isFile)
        requireImmutableNative(fixture)
        require(fixture.length() == FIXTURE_SIZE && sha256(fixture.readBytes()) == FIXTURE_SHA256)
        return NativeArtifactPaths(directory, supervisor, fixture, MANIFEST_SHA256)
    }

    internal fun forTesting(supervisor: File, fixture: File): NativeArtifactPaths =
        NativeArtifactPaths(supervisor.parentFile.canonicalFile, supervisor.canonicalFile, fixture.canonicalFile, "test")

    internal fun validateIdentityHex(value: String, prefix: String): Boolean =
        value.matches(Regex("${Regex.escape(prefix)}[0-9a-f]{32}"))

    internal fun validateNonce(value: String): Boolean = value.matches(Regex("[0-9a-f]{32}"))

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    internal fun requireCanonicalPrivate(file: File, label: String) {
        require(file.absoluteFile == file.canonicalFile) { "$label is not canonical" }
        require(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "$label is not a private directory"
        }
    }

    internal fun noGroupOrOtherWrite(mode: Int): Boolean = (mode and 0x12) == 0

    internal fun requireNotWritableForTesting(permissionCheck: () -> Boolean) {
        try {
            require(!permissionCheck()) { "native supervisor is writable" }
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EACCES) throw failure
        }
    }

    internal fun validatePublisherArgv(argv: List<String>) {
        require(argv.size >= 2 && argv[0] in setOf(LINKER, LINKER_APEX_ALIAS))
        require(argv.drop(1).all { it.isNotEmpty() && !it.contains('\u0000') })
    }

    /** App-owned runtime files are mutable cache, not platform-owned ELF. */
    internal fun requireRuntimeCacheEntry(file: File) {
        require(file.absoluteFile == file.canonicalFile && file.isFile)
        val parent = requireNotNull(file.parentFile)
        val fileStat = Os.stat(file.absolutePath)
        val parentStat = Os.stat(parent.absolutePath)
        val uid = Process.myUid()
        require(fileStat.st_uid == uid && fileStat.st_gid == uid)
        require(parentStat.st_uid == uid && parentStat.st_gid == uid)
        require((fileStat.st_mode and 0x1ff) == 0x1ed) { "runtime cache executable must be 0755" }
        require((parentStat.st_mode and 0x1ff) == 0x1f9) { "runtime cache parent must be 0771" }
    }

    /** Revalidate the immutable path and device/inode immediately around exec. */
    internal fun requireImmutableElfLaunch(file: File): NativeFileIdentity {
        requireImmutableNativeDirectory(requireNotNull(file.parentFile).canonicalFile)
        val before = requireImmutableNative(file)
        val after = requireImmutableNative(file)
        require(before == after) { "immutable native ELF changed before launch" }
        return before
    }

    private fun requireImmutableNativeDirectory(directory: File) {
        val stat = Os.stat(directory.absolutePath)
        require(stat.st_uid in setOf(0, 1000) && stat.st_gid in setOf(0, 1000))
        require(noGroupOrOtherWrite(stat.st_mode))
        requireNotWritableForTesting { Os.access(directory.absolutePath, OsConstants.W_OK) }
    }

    private fun requireImmutableNative(file: File): NativeFileIdentity {
        require(file.absoluteFile == file.canonicalFile && file.isFile)
        val stat = Os.stat(file.absolutePath)
        require(!Files.isSymbolicLink(file.toPath()) && stat.st_uid in setOf(0, 1000) && stat.st_gid in setOf(0, 1000))
        require(noGroupOrOtherWrite(stat.st_mode))
        requireNotWritableForTesting { Os.access(file.absolutePath, OsConstants.W_OK) }
        val after = Os.stat(file.absolutePath)
        require(stat.st_dev == after.st_dev && stat.st_ino == after.st_ino)
        return NativeFileIdentity(stat.st_dev, stat.st_ino)
    }
}

internal enum class ForeverWinePhase(val wire: String) { NONE("none"), SERVER("server"), CLIENT("client") }

internal enum class ForeverWineCommandKind { START, STOP, CLOSE }

internal data class ForeverWineCommand(
    val seq: Long,
    val kind: ForeverWineCommandKind,
    val phase: ForeverWinePhase,
    val json: JSONObject,
) {
    companion object {
        fun create(seq: Long, runId: String, nonce: String, kind: ForeverWineCommandKind, phase: ForeverWinePhase): ForeverWineCommand {
            require(seq > 0 && seq < Long.MAX_VALUE && ForeverWineSupervisorPolicy.validateIdentityHex(runId, "WS-") &&
                ForeverWineSupervisorPolicy.validateNonce(nonce))
            require((kind == ForeverWineCommandKind.START && phase != ForeverWinePhase.NONE) ||
                (kind != ForeverWineCommandKind.START && phase == ForeverWinePhase.NONE))
            val json = JSONObject().put("schema", ForeverWineSupervisorPolicy.SCHEMA).put("runId", runId)
                .put("nonce", nonce).put("seq", seq).put("command", kind.name).put("phase", phase.wire)
            return ForeverWineCommand(seq, kind, phase, json)
        }

        fun parse(text: String, runId: String, nonce: String): ForeverWineCommand {
            val json = ForeverWineFlatJson.objectOf(text)
            require(json.keys().asSequence().toSet() == ForeverWineSupervisorPolicy.COMMAND_KEYS)
            require(ForeverWineFlatJson.long(json, "schema") == ForeverWineSupervisorPolicy.SCHEMA.toLong())
            require(json.getString("runId") == runId && json.getString("nonce") == nonce)
            val seq = ForeverWineFlatJson.long(json, "seq"); require(seq > 0 && seq < Long.MAX_VALUE)
            val kind = runCatching { ForeverWineCommandKind.valueOf(ForeverWineFlatJson.string(json, "command")) }
                .getOrElse { error("invalid Wine command") }
            val phase = ForeverWinePhase.values().firstOrNull { it.wire == ForeverWineFlatJson.string(json, "phase") }
                ?: error("invalid Wine phase")
            require((kind == ForeverWineCommandKind.START && phase != ForeverWinePhase.NONE) ||
                (kind != ForeverWineCommandKind.START && phase == ForeverWinePhase.NONE))
            return ForeverWineCommand(seq, kind, phase, json)
        }
    }
}

internal data class ForeverWineEvent(val json: JSONObject) {
    val seq get() = ForeverWineFlatJson.long(json, "seq")
    val event get() = ForeverWineFlatJson.string(json, "event")
    val phase get() = ForeverWinePhase.values().firstOrNull { it.wire == ForeverWineFlatJson.string(json, "phase") }
        ?: error("invalid Wine event phase")
    val pid get() = ForeverWineFlatJson.long(json, "pid")
    val startTicks get() = ForeverWineFlatJson.long(json, "startTicks")
    val ppid get() = ForeverWineFlatJson.long(json, "ppid")
    val role get() = ForeverWineFlatJson.string(json, "role")
    val status get() = ForeverWineFlatJson.string(json, "status")
    val exitCode get() = ForeverWineFlatJson.long(json, "exitCode")
    val childCount get() = ForeverWineFlatJson.long(json, "childCount")
    val prefixDev get() = ForeverWineFlatJson.long(json, "prefixDev")
    val prefixIno get() = ForeverWineFlatJson.long(json, "prefixIno")
    val echild get() = json.get("echild") as Boolean

    companion object {
        private val events = setOf("READY", "SPAWN", "OWNED_ADOPT", "EXIT", "SERVER_READY", "PHASE_DONE", "RUN_DONE", "ERROR")
        private val roles = setOf("supervisor", "server", "client", "adopted")
        private val statuses = setOf("READY", "RUNNING", "REAPED", "PASS", "CANCELLED", "UNKNOWN")

        fun parse(text: String, runId: String, nonce: String): ForeverWineEvent {
            val json = ForeverWineFlatJson.objectOf(text)
            require(json.keys().asSequence().toSet() == ForeverWineSupervisorPolicy.EVENT_KEYS)
            require(ForeverWineFlatJson.long(json, "schema") == ForeverWineSupervisorPolicy.SCHEMA.toLong())
            require(json.getString("runId") == runId && json.getString("nonce") == nonce)
            val value = ForeverWineEvent(json)
            require(value.seq > 0 && value.seq < Long.MAX_VALUE && value.event in events && value.role in roles && value.status in statuses)
            require(value.pid in 1L..Int.MAX_VALUE.toLong() && value.ppid in 1L..Int.MAX_VALUE.toLong() && value.startTicks > 0L)
            require(value.exitCode in -1L..Int.MAX_VALUE.toLong())
            require(value.phase != ForeverWinePhase.NONE || value.event in setOf("READY", "RUN_DONE", "ERROR"))
            val uids = (0..3).map { ForeverWineFlatJson.long(json, "uid$it") }
            require(uids.all { it in 0L..Int.MAX_VALUE.toLong() } && uids.distinct().size == 1)
            require(value.childCount in 0L..ForeverWineSupervisorPolicy.MAX_CHILDREN && json.get("echild") is Boolean)
            require(value.event == "EXIT" || value.exitCode == -1L)
            if (value.event == "READY") {
                require(value.role == "supervisor" && value.phase == ForeverWinePhase.NONE && value.status == "READY")
                require(ForeverWineFlatJson.long(json, "setResult") == 0L &&
                    ForeverWineFlatJson.long(json, "getResult") == 0L && ForeverWineFlatJson.long(json, "getValue") == 1L)
            } else {
                require(ForeverWineFlatJson.long(json, "setResult") == -1L &&
                    ForeverWineFlatJson.long(json, "getResult") == -1L && ForeverWineFlatJson.long(json, "getValue") == -1L)
            }
            if (value.event == "SERVER_READY") {
                require(value.role == "server" && value.phase == ForeverWinePhase.SERVER && value.status == "RUNNING")
                require(ForeverWineFlatJson.long(json, "socketDev") > 0 && ForeverWineFlatJson.long(json, "socketIno") > 0 &&
                    ForeverWineFlatJson.long(json, "lockDev") > 0 && ForeverWineFlatJson.long(json, "lockIno") > 0)
            } else {
                require(ForeverWineFlatJson.long(json, "socketDev") == -1L && ForeverWineFlatJson.long(json, "socketIno") == -1L &&
                    ForeverWineFlatJson.long(json, "lockDev") == -1L && ForeverWineFlatJson.long(json, "lockIno") == -1L)
            }
            when (value.event) {
                "SPAWN" -> require(value.role in setOf("server", "client") && value.status == "RUNNING" && value.phase != ForeverWinePhase.NONE)
                "OWNED_ADOPT" -> require(value.role == "adopted" && value.status == "RUNNING" && value.phase != ForeverWinePhase.NONE)
                "EXIT" -> require(value.role in setOf("server", "client", "adopted") && value.status == "REAPED" && value.exitCode >= 0)
                // The native schema reports the just-reaped client, not the
                // supervisor.  PASS is only a candidate; the controller must
                // cross-check this identity against the preceding EXIT.
                "PHASE_DONE" -> require(value.role == "client" && value.phase == ForeverWinePhase.CLIENT &&
                    value.status in setOf("PASS", "UNKNOWN"))
                "RUN_DONE" -> require(value.role == "supervisor" && value.phase == ForeverWinePhase.NONE && value.childCount == 0L && value.echild)
                "ERROR" -> require(value.role == "supervisor" && value.status == "UNKNOWN")
            }
            require(ForeverWineFlatJson.long(json, "prefixDev") > 0 && ForeverWineFlatJson.long(json, "prefixIno") > 0)
            return value
        }
    }
}

/** Small duplicate-key/type-preserving parser for the flat native wire. */
internal object ForeverWineFlatJson {
    fun objectOf(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= ForeverWineSupervisorPolicy.MAX_LINE_BYTES && !text.contains('\r'))
        val keys = mutableSetOf<String>(); var i = skip(text, 0); require(i < text.length && text[i] == '{'); i++
        while (true) {
            i = skip(text, i); if (i < text.length && text[i] == '}') { i++; break }
            require(i < text.length && text[i] == '"'); val end = quotedEnd(text, i)
            val key = org.json.JSONTokener(text.substring(i, end + 1)).nextValue()
            require(key is String && keys.add(key)); i = skip(text, end + 1); require(i < text.length && text[i] == ':')
            i = valueEnd(text, i + 1); i = skip(text, i); require(i < text.length && (text[i] == ',' || text[i] == '}'))
            if (text[i++] == '}') break
        }
        require(skip(text, i) == text.length); return JSONObject(text)
    }
    fun string(json: JSONObject, key: String): String = json.get(key).also { require(it is String) } as String
    fun long(json: JSONObject, key: String): Long {
        val value = json.get(key); require(value is Number && value !is Float && value !is Double)
        return value.toString().toLongOrNull() ?: error("$key is not a bounded integer")
    }
    private fun skip(s: String, start: Int): Int { var i = start; while (i < s.length && s[i].isWhitespace()) i++; return i }
    private fun quotedEnd(s: String, start: Int): Int { var escaped = false; for (i in start + 1 until s.length) { if (escaped) escaped = false else if (s[i] == '\\') escaped = true else if (s[i] == '"') return i }; error("unterminated string") }
    private fun valueEnd(s: String, start: Int): Int { var i = skip(s, start); require(i < s.length); if (s[i] == '"') return quotedEnd(s, i) + 1; while (i < s.length && s[i] != ',' && s[i] != '}') i++; return i }
}
