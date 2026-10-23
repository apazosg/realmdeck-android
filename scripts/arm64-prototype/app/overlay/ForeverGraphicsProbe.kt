package app.gamenative

import android.content.Context
import com.winlator.container.Container
import app.gamenative.utils.CustomGameScanner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * The sole APK-owned graphics diagnostic executable.  This route is deliberately
 * narrower than the normal client route: the asset, destination, PE identity,
 * hash, size, RUN and argv are all fixed here, rather than being selected from
 * container extras or a filename pattern.
 */
object ForeverGraphicsProbe {
    const val FILE_NAME = "GraphicsProbeReadback-ARM64.exe"
    const val WINDOWS_TARGET = "A:\\GraphicsProbeReadback-ARM64.exe"
    const val MANIFEST_ASSET = "forever/graphics/readback-manifest.json"
    const val PROBE_ASSET = "forever/graphics/GraphicsProbeReadback-ARM64.exe"
    const val PROFILE = "graphics_probe"
    const val PE_MACHINE = 0xaa64
    const val MANIFEST_MACHINE = PE_MACHINE
    const val PE_OPTIONAL_MAGIC = 0x20b
    const val MAX_PROBE_BYTES = 1024L * 1024L

    // Pinned to the cross-built AA64 source artifact, never supplied by an Intent.
    const val APPROVED_PROBE_SHA256 = "80c1c260d7781bda73e3d5552c7ca730e72382288a9256b4a78c7899d3f0c3de"
    const val APPROVED_PROBE_SIZE = 96768L

    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val MAX_MANIFEST_BYTES = 8 * 1024
    private val WINDOWS_LOG_PREFIX = "A:\\Logs\\"

    internal data class ProbePin(val sha256: String, val size: Long)

    data class VerifiedProbe(
        val file: File,
        val sha256: String,
        val size: Long,
        val machine: Int,
        val optionalMagic: Int,
        val windowsPath: String = WINDOWS_TARGET,
        val args: String,
    )

    data class ConfiguredProbe(
        val verified: VerifiedProbe,
        val kind: String = PROFILE,
        val profile: String = PROFILE,
    )

    internal data class ValidationInput(
        val managed: Boolean,
        val runId: String,
        val currentRunId: String?,
        val currentStatus: ForeverRunStatus?,
        val registeredGameFolder: File?,
        val gameFolder: File?,
        val aDriveFolders: List<File>,
        val executablePath: String,
        val verifiedExecutableExtra: String?,
        val verifiedFile: File,
        val cwd: File,
        val requestedArgs: String,
        val expectedSha256: String,
        val expectedSize: Long,
        val actualSha256: String,
        val actualSize: Long,
        val peMachine: Int,
        val peOptionalMagic: Int,
    )

    internal data class ValidatedProbe(
        val command: String,
        val canonicalCwd: String,
        val sha256: String,
        val size: Long,
        val args: String,
    )

    /**
     * Installs the fixed APK asset after the official client has been verified.
     * It configures only the existing managed container; it does not launch it.
     */
    fun configure(context: Context, container: Container, runId: String): ConfiguredProbe {
        require(ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED) {
            "graphics probe route is disabled in this APK"
        }
        require(container.getExtra("foreverManaged", "") == "true") {
            "graphics probe requires the managed Forever container"
        }
        require(container.getExtra("foreverRunId", "") == runId) {
            "graphics probe container RUN does not match the selected RUN"
        }
        val current = ForeverRunStoreRegistry.get(context).readCurrent()
        require(current != null && current.runId == runId && current.status == ForeverRunStatus.ACTIVE) {
            "graphics probe requires the current active Forever run"
        }
        val pin = publicPin()
        val args = exactArgs(runId)
        val clientRoot = fixedClientRoot()
        ensureLogsDirectory(clientRoot)
        val manifest = context.assets.open(MANIFEST_ASSET).use { input ->
            readBounded(input, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        }
        val verified = context.assets.open(PROBE_ASSET).use { input ->
            installFromAsset(input, clientRoot, manifest, pin)
        }
        require(verified.file.canonicalFile == File(clientRoot, FILE_NAME).canonicalFile)

        // RuntimeSetup has already selected the shared Wine/DXVK/Bionic profile.
        // These fields only replace the executable/argv for this frozen route.
        container.setExecutablePath(FILE_NAME)
        container.setExecArgs(args)
        container.putExtra("foreverLaunchKind", PROFILE)
        container.putExtra("foreverVerifiedExe", verified.file.canonicalPath)
        container.putExtra("foreverGraphicsProbeSha256", verified.sha256)
        container.putExtra("foreverGraphicsProbeSize", verified.size.toString())
        container.putExtra("foreverGraphicsProbeMachine", MANIFEST_MACHINE.toString())
        container.putExtra("foreverGraphicsProbeArgs", args)
        container.putExtra("foreverGraphicsProbeLog", logPath(runId))
        container.saveData()
        return ConfiguredProbe(verified.copy(args = args))
    }

    /** Builds and verifies the exact direct command selected by the XServer hook. */
    fun command(
        context: Context,
        container: Container,
        appId: String,
        gameFolderPath: String?,
        executablePath: String,
        requestedArgs: String,
    ): String {
        require(ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED) {
            "graphics probe route is disabled in this APK"
        }
        require(container.getExtra("foreverLaunchKind", "") == PROFILE) {
            "graphics probe route requires the frozen graphics_probe container profile"
        }
        val runId = container.getExtra("foreverRunId", "")
        require(runId.isNotEmpty()) { "graphics probe container has no Forever run id" }
        val current = ForeverRunStoreRegistry.get(context).readCurrent()
        require(current != null && current.runId == runId && current.status == ForeverRunStatus.ACTIVE) {
            "graphics probe route requires the current active Forever run"
        }
        require(!gameFolderPath.isNullOrBlank()) { "graphics probe route requires the discovered A: folder" }
        val registered = CustomGameScanner.getFolderPathFromAppId(appId)?.let(::File)
        val gameFolder = File(requireNotNull(gameFolderPath))
        val aDriveFolders = Container.drivesIterator(container.drives).asSequence()
            .filter { it.size >= 2 && it[0].equals("A", ignoreCase = true) }
            .map { File(it[1]) }
            .toList()
        val canonicalGame = ForeverArm64Launch.validateManagedContext(
            ForeverArm64Launch.ManagedContextInput(
                managed = container.getExtra("foreverManaged", "") == "true",
                runId = runId,
                currentRunId = current.runId,
                currentStatus = current.status,
                registeredGameFolder = registered,
                gameFolder = gameFolder,
                aDriveFolders = aDriveFolders,
                cwd = gameFolder,
            ),
        )
        require(canonicalGame == fixedClientRoot()) { "graphics probe route escaped the fixed WoW Forever root" }
        val pin = publicPin()
        val actual = verifyInstalled(File(canonicalGame, FILE_NAME), pin)
        val verifiedExtra = container.getExtra("foreverVerifiedExe", "")
        val expectedSha = container.getExtra("foreverGraphicsProbeSha256", "")
        val expectedSize = container.getExtra("foreverGraphicsProbeSize", "").toLongOrNull() ?: -1L
        require(container.getExtra("foreverGraphicsProbeMachine", "") == MANIFEST_MACHINE.toString()) {
            "graphics probe container PE identity descriptor is missing"
        }
        require(container.getExtra("foreverGraphicsProbeArgs", "") == requestedArgs) {
            "graphics probe container argv descriptor differs from the selected argv"
        }
        require(container.getExtra("foreverGraphicsProbeLog", "") == logPath(runId)) {
            "graphics probe container log descriptor differs from the selected RUN"
        }
        val input = ValidationInput(
            managed = container.getExtra("foreverManaged", "") == "true",
            runId = runId,
            currentRunId = current.runId,
            currentStatus = current.status,
            registeredGameFolder = registered,
            gameFolder = canonicalGame,
            aDriveFolders = aDriveFolders,
            executablePath = executablePath,
            verifiedExecutableExtra = verifiedExtra,
            verifiedFile = actual.file,
            cwd = canonicalGame,
            requestedArgs = requestedArgs,
            expectedSha256 = expectedSha,
            expectedSize = expectedSize,
            actualSha256 = actual.sha256,
            actualSize = actual.size,
            peMachine = actual.machine,
            peOptionalMagic = actual.optionalMagic,
        )
        val validated = validate(input)
        ForeverRunStoreRegistry.get(context).appendRaw(runId, JSONObject()
            .put("event", "launch_route_selected")
            .put("route", PROFILE).put("launchKind", PROFILE)
            .put("windowsExe", WINDOWS_TARGET).put("args", validated.args)
            .put("sha256", pin.sha256).put("size", pin.size)
            .put("canonicalCwd", validated.canonicalCwd)
            .put("peMachine", "0xaa64"))
        // The outer GameNative command already supplies Wine, desktop setup,
        // and container.execArgs. Returning a formatter result here would
        // duplicate all three pieces in the final ProcessHelper argv.
        return validated.command
    }

    internal fun validate(input: ValidationInput): ValidatedProbe {
        require(input.managed) { "graphics probe route is not valid for an unmanaged container" }
        val canonicalGame = ForeverArm64Launch.validateManagedContext(
            ForeverArm64Launch.ManagedContextInput(
                managed = input.managed,
                runId = input.runId,
                currentRunId = input.currentRunId,
                currentStatus = input.currentStatus,
                registeredGameFolder = input.registeredGameFolder,
                gameFolder = input.gameFolder,
                aDriveFolders = input.aDriveFolders,
                cwd = input.cwd,
            ),
        )
        require(input.executablePath.isApprovedPath()) {
            "executable path is not the approved graphics probe target"
        }
        val expectedFile = File(canonicalGame, FILE_NAME)
        require(input.verifiedFile.canonicalFile == expectedFile.canonicalFile) {
            "verified executable is not cwd/GraphicsProbeReadback-ARM64.exe"
        }
        require(input.verifiedExecutableExtra == expectedFile.canonicalPath) {
            "container verified executable does not exactly match the graphics probe"
        }
        require(!Files.isSymbolicLink(expectedFile.toPath())) {
            "graphics probe executable is a symlink"
        }
        require(parseArgs(input.requestedArgs, input.runId) == exactArgList(input.runId)) {
            "graphics probe arguments are not the canonical diagnostic flags"
        }
        require(input.expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "graphics probe hash extra is malformed"
        }
        require(input.expectedSha256.equals(input.actualSha256, ignoreCase = true)) {
            "graphics probe hash extra does not match the verified file"
        }
        require(input.expectedSize == input.actualSize && input.actualSize > 0L && input.actualSize <= MAX_PROBE_BYTES) {
            "graphics probe size extra does not match the verified file"
        }
        require(input.peMachine == PE_MACHINE && input.peOptionalMagic == PE_OPTIONAL_MAGIC) {
            "graphics probe is not PE32+ ARM64"
        }
        return ValidatedProbe(WINDOWS_TARGET, canonicalGame.path, input.actualSha256.lowercase(), input.actualSize, input.requestedArgs)
    }

    fun wineCommand(verifiedTarget: String, requestedArgs: String): String {
        require(verifiedTarget == WINDOWS_TARGET) { "Wine target is not the verified graphics probe" }
        val runId = parseArgs(requestedArgs).let { args ->
            require(args.size == 4 && args[0] == "--log" && args[2] == "--run-id") { "graphics probe argv shape is invalid" }
            args[3]
        }
        require(parseArgs(requestedArgs) == exactArgList(runId)) { "graphics probe flags are not canonical" }
        return (listOf("wine", WINDOWS_TARGET) + exactArgList(runId)).joinToString(" ")
    }

    internal fun logPath(runId: String): String = WINDOWS_LOG_PREFIX + "$runId-graphics-probe.jsonl"

    internal fun exactArgs(runId: String): String {
        validateRunId(runId)
        return "--log ${logPath(runId)} --run-id $runId"
    }

    internal fun exactArgList(runId: String): List<String> = listOf("--log", logPath(runId), "--run-id", runId)

    internal fun isProbeArgv(argv: List<String>): Boolean = argv.any {
        it.equals(FILE_NAME, ignoreCase = true) ||
            it.equals(WINDOWS_TARGET, ignoreCase = true) ||
            it.equals("A:/$FILE_NAME", ignoreCase = true)
    }

    internal fun publicPin(): ProbePin {
        require(APPROVED_PROBE_SHA256.matches(Regex("[0-9a-fA-F]{64}")) && APPROVED_PROBE_SIZE in 1..MAX_PROBE_BYTES) {
            "approved graphics probe artifact pin is not populated"
        }
        return ProbePin(APPROVED_PROBE_SHA256.lowercase(), APPROVED_PROBE_SIZE)
    }

    /** Test-only positive path: production never supplies a pin or bytes this way. */
    internal fun installFixture(root: File, manifest: String, bytes: ByteArray, pin: ProbePin): VerifiedProbe =
        installFromAsset(ByteArrayInputStream(bytes), root, manifest, pin)

    @Synchronized
    private fun installFromAsset(input: java.io.InputStream, root: File, manifestText: String, pin: ProbePin): VerifiedProbe {
        val manifest = parseManifest(manifestText)
        require(pin.sha256.matches(Regex("[0-9a-fA-F]{64}")) && pin.size in 1..MAX_PROBE_BYTES) {
            "graphics probe artifact pin is invalid"
        }
        require(manifest.sha256.equals(pin.sha256, ignoreCase = true) && manifest.size == pin.size) {
            "graphics probe manifest does not match the approved artifact pin"
        }
        require(manifest.file == FILE_NAME && manifest.machine == MANIFEST_MACHINE) {
            "graphics probe manifest identifies an unexpected file"
        }
        val canonicalRoot = fixedRoot(root)
        val target = File(canonicalRoot, FILE_NAME)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(target.toPath())) { "graphics probe destination is a symlink" }
            val existing = verifyInstalled(target, pin)
            require(existing.sha256.equals(manifest.sha256, ignoreCase = true) && existing.size == manifest.size) {
                "existing graphics probe is not the approved artifact"
            }
            return existing
        }

        val bytes = readBounded(input, MAX_PROBE_BYTES.toInt())
        require(bytes.size.toLong() == manifest.size) { "graphics probe asset size does not match its manifest" }
        val assetHash = sha256(bytes)
        require(assetHash.equals(pin.sha256, ignoreCase = true)) {
            "graphics probe asset does not match the approved artifact"
        }
        val assetPe = readPeHeader(bytes)
        require(assetPe.machine == PE_MACHINE && assetPe.optionalMagic == PE_OPTIONAL_MAGIC) {
            "graphics probe asset is not PE32+ ARM64"
        }
        val temporary = File(canonicalRoot, ".graphics-probe-${UUID.randomUUID()}.part")
        var ownedTemporary = false
        try {
            FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                ownedTemporary = true
                var offset = 0
                while (offset < bytes.size) {
                    val written = channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                    require(written > 0) { "graphics probe asset write made no progress" }
                    offset += written
                }
                channel.force(true)
            }
            verifyInstalled(temporary, pin)
            try {
                // No REPLACE_EXISTING/ATOMIC_MOVE (which may replace on Unix).
                // The publisher is serialized; unknown existing files remain untouched.
                Files.move(temporary.toPath(), target.toPath())
                ownedTemporary = false
            } catch (_: FileAlreadyExistsException) {
                verifyInstalled(target, pin)
            }
        } finally {
            if (ownedTemporary && !Files.isSymbolicLink(temporary.toPath())) Files.deleteIfExists(temporary.toPath())
        }
        val installed = verifyInstalled(target, pin)
        require(installed.sha256.equals(manifest.sha256, ignoreCase = true) && installed.size == manifest.size) {
            "graphics probe failed post-install verification"
        }
        return installed
    }

    internal fun ensureLogsDirectory(root: File): File {
        val canonical = fixedRoot(root)
        val logs = File(canonical, "Logs")
        if (!Files.exists(logs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(logs.toPath()) } catch (_: FileAlreadyExistsException) { }
        }
        require(Files.isDirectory(logs.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(logs.toPath()) && logs.canonicalFile.parentFile == canonical) {
            "graphics probe Logs must be a managed real directory"
        }
        return logs
    }

    private data class Manifest(val file: String, val size: Long, val sha256: String, val machine: Int)

    private fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "unsupported graphics probe manifest schema" }
        val file = json.getString("file")
        val size = json.getLong("size")
        val sha = json.getString("sha256")
        val machine = json.getInt("machine")
        require(file == FILE_NAME && size in 1..MAX_PROBE_BYTES && sha.matches(Regex("[0-9a-fA-F]{64}"))) {
            "malformed graphics probe manifest"
        }
        return Manifest(file, size, sha.lowercase(), machine)
    }

    private fun verifyInstalled(file: File, pin: ProbePin): VerifiedProbe {
        require(!Files.isSymbolicLink(file.toPath())) { "graphics probe is a symlink" }
        val first = fileIdentity(file)
        require(first.size == pin.size) { "graphics probe size does not match approved size" }
        val pe = readPeHeader(file)
        require(pe.machine == PE_MACHINE && pe.optionalMagic == PE_OPTIONAL_MAGIC) { "graphics probe PE identity is invalid" }
        val digest = sha256(file)
        val second = fileIdentity(file)
        require(first == second && digest.equals(pin.sha256, ignoreCase = true)) {
            "graphics probe changed during verification or has the wrong hash"
        }
        return VerifiedProbe(file.canonicalFile, digest.lowercase(), second.size, pe.machine, pe.optionalMagic, args = "")
    }

    private data class FileIdentity(val key: Any?, val size: Long, val modified: Long)
    private data class PeHeader(val machine: Int, val optionalMagic: Int)

    private fun fileIdentity(file: File): FileIdentity {
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "graphics probe is not a regular file" }
        require(attributes.size() in 1..MAX_PROBE_BYTES) { "graphics probe exceeds the bounded size" }
        return FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun readPeHeader(file: File): PeHeader {
        val attributes = Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "graphics probe is not a regular file" }
        require(attributes.size() in 1..MAX_PROBE_BYTES) { "graphics probe exceeds the bounded size" }
        val bytes = ByteArray(4096)
        var count = 0
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) {
                    val one = input.read()
                    if (one < 0) break
                    bytes[count++] = one.toByte()
                } else {
                    count += read
                }
            }
        }
        return readPeHeader(bytes, count)
    }

    private fun readPeHeader(bytes: ByteArray, count: Int = bytes.size): PeHeader {
        require(count >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) { "graphics probe is not an MZ image" }
        val offset = ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(offset >= 64 && offset <= count - 26 && offset <= bytes.size - 26) { "graphics probe PE header is outside the file" }
        require(bytes[offset] == 'P'.code.toByte() && bytes[offset + 1] == 'E'.code.toByte() && bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte()) {
            "graphics probe has an invalid PE signature"
        }
        return PeHeader(
            ByteBuffer.wrap(bytes, offset + 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff,
            ByteBuffer.wrap(bytes, offset + 24, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_PROBE_BYTES) { "graphics probe grew past bounded hash size" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            val requested = minOf(buffer.size, limit - output.size())
            val count = input.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                output.write(one)
            } else {
                output.write(buffer, 0, count)
            }
        }
        require(output.size() < limit || input.read() < 0) { "graphics probe asset exceeds the bounded size" }
        return output.toByteArray()
    }

    private fun fixedClientRoot(): File = fixedRoot(File(CustomGameScanner.defaultRootPath, "WoW Forever"))

    private fun fixedRoot(root: File): File {
        require(root.name == "WoW Forever") { "graphics probe root is not the fixed WoW Forever directory" }
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.toPath())) {
            "graphics probe client root is not a real directory"
        }
        return root.canonicalFile
    }

    private fun validateRunId(runId: String) {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "invalid graphics probe RUN" }
        require(runId.all { it.code in 0x20..0x7e && it != '"' && it != '\'' && !it.isWhitespace() }) {
            "graphics probe RUN must be strict ASCII without spaces or quotes"
        }
    }

    private fun parseArgs(value: String, expectedRunId: String? = null): List<String> {
        require(value.all { it.code < 128 && it != '"' && it != '\'' }) { "graphics probe arguments are not strict ASCII" }
        val result = value.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (expectedRunId != null) require(result == exactArgList(expectedRunId))
        return result
    }

    private fun String.isApprovedPath(): Boolean = equals(FILE_NAME, true) || equals(WINDOWS_TARGET, true) || equals("A:/$FILE_NAME", true)
}
