package app.gamenative

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.json.JSONObject

/** Frozen policy for the native-only supervisor laboratory process. */
object ForeverNativeSupervisorPolicy {
    const val PACKAGE = "com.wowforever.arm64probe"
    const val LINKER = "/system/bin/linker64"
    const val SUPERVISOR_NAME = "libforever_run_supervisor.so"
    const val FIXTURE_NAME = "libforever_run_fixture.so"
    const val MANIFEST_ASSET = "forever/native-supervisor/manifest.json"
    const val PROCESS_SUFFIX = ":run_supervisor"
    const val MANIFEST_VARIANT = "native-run-supervisor-v2"
    const val MAX_LINE_BYTES = 8 * 1024
    const val MAX_STREAM_BYTES = 64 * 1024
    const val OPERATION_TIMEOUT_SECONDS = 15L
    const val CLEANUP_TIMEOUT_SECONDS = 5L
    const val FIXTURE_MIN_TTL_SECONDS = 30L

    const val APPROVED_MANIFEST_SHA256 = "a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6"
    const val APPROVED_NATIVE_PINS_STATUS = "READY"
    const val APPROVED_SUPERVISOR_SIZE = 30_344L
    const val APPROVED_SUPERVISOR_SHA256 = "fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c"
    const val APPROVED_FIXTURE_SIZE = 12_784L
    const val APPROVED_FIXTURE_SHA256 = "7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f"

    enum class Scenario(val wireName: String, val nativeScenario: String) {
        NORMAL("normal", "normal"),
        CANCEL_BEFORE_START("cancel_before_start", "normal"),
        CANCEL_FORK("cancel_fork", "hold"),
        EOF("eof", "normal"),
        INVALID_PROTOCOL("invalid_protocol", "normal"),
        SUPERVISOR_FAILURE("supervisor_failure", "normal"),
    }

    data class NativeArtifactPaths(
        val nativeLibraryDir: File,
        val supervisor: File,
        val fixture: File,
        val manifestSha256: String,
        val fixtureIdentity: NativeFileIdentity?,
    )

    data class NativeFileIdentity(val device: Long, val inode: Long)

    fun scenario(value: String?): Scenario = Scenario.values().firstOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("scenario is not allowlisted")

    fun scenarioFromIntent(intent: Intent): Scenario {
        val extras = intent.extras
        require(extras == null || extras.keySet().all { it == "scenario" }) { "only scenario is accepted" }
        return scenario(intent.getStringExtra("scenario") ?: Scenario.NORMAL.wireName)
    }

    /** This predicate is safe to call from Application.onCreate before Hilt/services. */
    @JvmStatic
    fun shouldIsolate(context: Context): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return isIsolatedProcess(debug, context.packageName, processName)
    }

    @JvmStatic
    fun isIsolatedProcess(debug: Boolean, packageName: String?, processName: String?): Boolean =
        debug && packageName == PACKAGE && processName == "$PACKAGE$PROCESS_SUFFIX"

    /** Test seam only: production always obtains the process name from Android. */
    internal fun shouldIsolateForTest(context: Context, processName: String): Boolean {
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return isIsolatedProcess(debug, context.packageName, processName)
    }

    fun requireDebugProcOnly(context: Context) {
        require(BuildConfig.DEBUG) { "native supervisor is debug-only" }
        require(BuildConfig.APPLICATION_ID == PACKAGE) { "native supervisor application id mismatch" }
        require(context.packageName == PACKAGE) { "native supervisor context package mismatch" }
        require(shouldIsolate(context)) { "native supervisor reached the normal application process" }
    }

    fun resolve(context: Context): NativeArtifactPaths {
        requireDebugProcOnly(context)
        val raw = File(requireNotNull(context.applicationInfo.nativeLibraryDir))
        require(raw.absoluteFile == raw.canonicalFile) { "nativeLibraryDir may not be a symlink" }
        val directory = raw.canonicalFile
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        val manifest = context.assets.open(MANIFEST_ASSET).use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_STREAM_BYTES)
            bytes
        }
        require(APPROVED_NATIVE_PINS_STATUS == "READY") { "new native supervisor pins are blocked" }
        val hash = sha256(manifest)
        require(hash == APPROVED_MANIFEST_SHA256) { "native manifest hash mismatch" }
        val pins = parseManifest(manifest)
        val fixtureIdentity = validateElf(directory, FIXTURE_NAME, pins.getValue(FIXTURE_NAME))
        return NativeArtifactPaths(
            directory,
            validateElf(directory, SUPERVISOR_NAME, pins.getValue(SUPERVISOR_NAME)).first,
            fixtureIdentity.first,
            hash,
            fixtureIdentity.second,
        )
    }

    internal fun forTesting(supervisor: File, fixture: File): NativeArtifactPaths =
        NativeArtifactPaths(supervisor.parentFile.canonicalFile, supervisor.canonicalFile, fixture.canonicalFile, "test", null)

    private data class ManifestPin(val bytes: Long, val sha256: String)

    /** The artifact map is keyed by canonical names; it is deliberately not a files array. */
    private fun parseManifest(bytes: ByteArray): Map<String, ManifestPin> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getInt("schema") == 1 && root.getString("variant") == MANIFEST_VARIANT)
        val artifacts = root.getJSONObject("artifacts")
        require(artifacts.length() == 2)
        val result = linkedMapOf<String, ManifestPin>()
        for (name in artifacts.keys()) {
            require(name == SUPERVISOR_NAME || name == FIXTURE_NAME) { "unexpected artifact" }
            val item = artifacts.getJSONObject(name)
            val size = item.getLong("bytes")
            val digest = item.getString("sha256")
            require(size >= 0 && digest.matches(Regex("[0-9a-f]{64}")))
            // Current manifests store provenance metadata as flat fields.  A future
            // producer may put the same metadata under `metadata`; both are maps keyed
            // by artifact name, never an untrusted executable list.
            val metadata = item.optJSONObject("metadata") ?: item
            require(metadata.getInt("elfMachine") == 183)
            require(metadata.getString("elfType") == "ET_DYN")
            require(metadata.getString("interpreter") == LINKER)
            require(metadata.getLong("loadSegmentAlignment") == 16_384L)
            val needed = metadata.getJSONArray("neededLibraries")
            require(needed.length() == 1 && needed.getString(0) == "libc.so")
            require(result.put(name, ManifestPin(size, digest)) == null)
        }
        require(result.keys == setOf(SUPERVISOR_NAME, FIXTURE_NAME))
        require(result[SUPERVISOR_NAME] == ManifestPin(APPROVED_SUPERVISOR_SIZE, APPROVED_SUPERVISOR_SHA256))
        require(result[FIXTURE_NAME] == ManifestPin(APPROVED_FIXTURE_SIZE, APPROVED_FIXTURE_SHA256))
        return result
    }

    private fun validateElf(directory: File, name: String, pin: ManifestPin): Pair<File, NativeFileIdentity> {
        val file = File(directory, name)
        require(file.absoluteFile == file.canonicalFile && file.canonicalFile.parentFile == directory)
        val path = file.toPath()
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        val secure = secureFile(path.toFile())
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.size() == pin.bytes && before.size() == approvedSize(name))
        val bytes = Files.readAllBytes(path)
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.size() == after.size() && before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime())
        require(sha256(bytes) == pin.sha256 && sha256(bytes) == approvedHash(name))
        require(isArm64Pie16K(bytes))
        return file to secure
    }

    /** Re-check the fixture in the process that is about to fork/exec it. */
    internal fun requireFixtureStable(file: File, expected: NativeFileIdentity) {
        require(secureFile(file) == expected) { "native fixture device/inode changed before exec" }
    }

    private fun secureFile(file: File): NativeFileIdentity {
        require(file.absoluteFile == file.canonicalFile)
        val stat = Os.stat(file.absolutePath)
        require(stat.st_uid == 0 || stat.st_uid == 1000) { "native artifact is not root/system-owned" }
        require(stat.st_gid == 0 || stat.st_gid == 1000) { "native artifact group is not root/system" }
        require(noGroupOrOtherWrite(stat.st_mode)) { "native artifact is group/other-writable" }
        requireNotWritable { productionOsAccess(file.absolutePath, OsConstants.W_OK) }
        return NativeFileIdentity(stat.st_dev, stat.st_ino)
    }

    private fun productionOsAccess(path: String, mode: Int): Boolean = Os.access(path, mode)

    private fun requireNotWritable(permissionCheck: () -> Boolean) {
        try {
            require(!permissionCheck()) { "native artifact is writable by the app" }
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EACCES) throw error
        }
    }

    internal fun requireNotWritableForTesting(permissionCheck: () -> Boolean) =
        requireNotWritable(permissionCheck)

    private fun approvedSize(name: String): Long = if (name == SUPERVISOR_NAME) APPROVED_SUPERVISOR_SIZE else APPROVED_FIXTURE_SIZE

    internal fun noGroupOrOtherWrite(mode: Int): Boolean = (mode and 0x12) == 0 // octal 0022
    private fun approvedHash(name: String): String = if (name == SUPERVISOR_NAME) APPROVED_SUPERVISOR_SHA256 else APPROVED_FIXTURE_SHA256

    private fun isArm64Pie16K(bytes: ByteArray): Boolean {
        if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[4].toInt() != 2 || bytes[5].toInt() != 1) return false
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (view.getShort(16).toInt() != 3 || view.getShort(18).toInt() != 183) return false
        val phoff = view.getLong(32)
        val entrySize = view.getShort(54).toInt() and 0xffff
        val count = view.getShort(56).toInt() and 0xffff
        if (phoff < 0 || entrySize < 56 || count == 0 || phoff > bytes.size || phoff + entrySize.toLong() * count > bytes.size) return false
        var loads = 0
        for (index in 0 until count) {
            val offset = (phoff + index.toLong() * entrySize).toInt()
            if (view.getInt(offset) == 1) {
                loads++
                if (view.getLong(offset + 48) != 16_384L) return false
            }
        }
        return loads > 0
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
