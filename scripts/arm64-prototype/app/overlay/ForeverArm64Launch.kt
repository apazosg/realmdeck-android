package app.gamenative

import android.content.Context
import com.winlator.container.Container
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

/**
 * The only managed route which omits GameNative's Windows winhandler wrapper.
 *
 * The parent XServer hook calls this at the start of the Custom Game branch,
 * after it has discovered the A: drive and before empty-executable
 * auto-detection, only when foreverManaged is true. This object deliberately only
 * selects and records a command; it does not launch, save, or patch anything.
 */
object ForeverArm64Launch {
    const val ROUTE = "verified-aa64-direct"

    private const val TARGET_NAME = "WowB-ARM64.exe"
    private const val WINDOWS_TARGET = "A:\\WowB-ARM64.exe"
    private const val MAX_HEADER_BYTES = 4096
    private const val MAX_EXECUTABLE_BYTES = 94L * 1024L * 1024L
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private val EXPECTED_ARGS = listOf("-config", "BetaSuspendedTest.wtf", "-d3d11")

    data class CatalogExecutableRecord(
        val relativePath: String,
        val decodedMd5: String,
        val decodedSize: Long,
    )

    /** Explicit, Android-free input to the security-sensitive validator. */
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
        val requestedArgs: String,
        val verifiedFile: File,
        val cwd: File,
        val catalogRecord: CatalogExecutableRecord,
    )

    /** Context gate shared by the official client and the fixed graphics probe. */
    internal data class ManagedContextInput(
        val managed: Boolean,
        val runId: String,
        val currentRunId: String?,
        val currentStatus: ForeverRunStatus?,
        val registeredGameFolder: File?,
        val gameFolder: File?,
        val aDriveFolders: List<File>,
        val cwd: File,
    )

    internal data class ValidatedLaunch(
        val command: String,
        val canonicalCwd: String,
        val md5: String,
        val decodedSize: Long,
    )

    /**
     * Selects the direct route for the already managed custom game.
     * The returned string is intentionally only the fixed Windows executable;
     * the upstream hook appends the canonical arguments separately.
     */
    fun command(
        context: Context,
        container: com.winlator.container.Container,
        appId: String,
        gameFolderPath: String?,
        executablePath: String,
        requestedArgs: String,
    ): String {
        require(container.getExtra("foreverManaged", "") == "true") {
            "direct ARM64 route requires the managed Forever container"
        }
        val runId = container.getExtra("foreverRunId", "")
        require(runId.isNotEmpty()) { "managed container has no Forever run id" }

        val store = ForeverRunStoreRegistry.get(context)
        val current = store.readCurrent()
        require(current != null && current.runId == runId && current.status == ForeverRunStatus.ACTIVE) {
            "direct ARM64 route requires the current active Forever run"
        }

        require(!gameFolderPath.isNullOrBlank()) { "managed direct route requires the discovered A: folder" }
        if (ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED) {
            require(container.getExtra("foreverLaunchKind", "") == ForeverGraphicsProbe.PROFILE) {
                "frozen graphics probe route is not configured on the managed container"
            }
            return ForeverGraphicsProbe.command(context, container, appId, gameFolderPath, executablePath, requestedArgs)
        }
        val registered = CustomGameScanner.getFolderPathFromAppId(appId)
        val gameFolder = File(requireNotNull(gameFolderPath))
        require(gameFolder.canonicalFile == File(CustomGameScanner.defaultRootPath, "WoW Forever").canonicalFile) {
            "direct route folder is not the dedicated managed installation"
        }
        val verifiedExtra = container.getExtra("foreverVerifiedExe", "")
        val input = ValidationInput(
            managed = true,
            runId = runId,
            currentRunId = current.runId,
            currentStatus = current.status,
            registeredGameFolder = registered?.let(::File),
            gameFolder = gameFolder,
            aDriveFolders = Container.drivesIterator(container.drives).asSequence()
                .filter { it.size >= 2 && it[0].equals("A", ignoreCase = true) }
                .map { File(it[1]) }
                .toList(),
            executablePath = executablePath,
            verifiedExecutableExtra = verifiedExtra,
            requestedArgs = requestedArgs,
            verifiedFile = File(gameFolder, TARGET_NAME),
            cwd = gameFolder,
            catalogRecord = catalogRecordFromOfficialAsset(context),
        )
        val validated = validate(input)

        // This is selection evidence only.  It intentionally contains no
        // render, login, game-PID, or process-start claim.
        store.appendRaw(runId, JSONObject()
            .put("event", "launch_route_selected")
             .put("route", ROUTE)
            .put("launchMode", "wine-client-desktop")
            .put("windowsExe", WINDOWS_TARGET)
            .put("canonicalCwd", validated.canonicalCwd)
            .put("peMachine", "0xaa64")
            .put("peMagic", "0x20b")
            .put("md5", validated.md5)
            .put("decodedSize", validated.decodedSize))
        return validated.command
    }

    /** Pure validation path used by JVM fixtures; it cannot execute a PE. */
    internal fun validate(input: ValidationInput): ValidatedLaunch {
        val canonicalGame = validateManagedContext(ManagedContextInput(
            managed = input.managed,
            runId = input.runId,
            currentRunId = input.currentRunId,
            currentStatus = input.currentStatus,
            registeredGameFolder = input.registeredGameFolder,
            gameFolder = input.gameFolder,
            aDriveFolders = input.aDriveFolders,
            cwd = input.cwd,
        ))

        require(input.executablePath.isApprovedExecutablePath()) {
            "executable path is not the approved WowB ARM64 target"
        }
        val expectedFile = File(canonicalGame, TARGET_NAME)
        val canonicalExpectedFile = expectedFile.canonicalFile
        require(canonicalExpectedFile.parentFile == canonicalGame &&
            canonicalExpectedFile.name.equals(TARGET_NAME, ignoreCase = true)) {
            "managed executable escaped the canonical game directory"
        }
        require(input.verifiedFile.canonicalFile == canonicalExpectedFile) {
            "verified executable is not cwd/WowB-ARM64.exe"
        }
        require(input.verifiedExecutableExtra == canonicalExpectedFile.path) {
            "container verified executable does not exactly match the canonical target"
        }
        require(!Files.isSymbolicLink(expectedFile.toPath())) {
            "managed executable path contains a symlink"
        }
        require(parseArguments(input.requestedArgs) == EXPECTED_ARGS) {
            "managed direct route arguments are not the canonical Forever flags"
        }
        require(input.catalogRecord.relativePath.equals(TARGET_NAME, ignoreCase = true)) {
            "catalog record is not the official target executable"
        }
        require(input.catalogRecord.decodedMd5.matches(Regex("[0-9a-fA-F]{32}"))) {
            "catalog executable MD5 is malformed"
        }
        require(input.catalogRecord.decodedSize in 1..MAX_EXECUTABLE_BYTES) {
            "catalog executable size is outside the bounded launch size"
        }

        val identityBefore = fileIdentity(expectedFile)
        require(identityBefore.size == input.catalogRecord.decodedSize) {
            "executable size does not match the official catalog"
        }
        val header = readPeHeader(expectedFile, identityBefore.size)
        require(header.machine == 0xaa64) { "managed executable is not PE ARM64" }
        require(header.optionalMagic == 0x20b) { "managed executable is not PE32+" }
        val md5 = md5(expectedFile)
        val identityAfter = fileIdentity(expectedFile)
        require(identityBefore == identityAfter) { "executable changed while it was verified" }
        require(md5.equals(input.catalogRecord.decodedMd5, ignoreCase = true)) {
            "executable MD5 does not match the official catalog"
        }

        return ValidatedLaunch(WINDOWS_TARGET, canonicalGame.path, md5, identityAfter.size)
    }

    /** Format only after command() has verified the managed PE and RUN context.
     * No shell, Explorer or literal quotes: ProcessHelper passes split tokens to exec.
     */
    fun wineCommand(verifiedTarget: String, requestedArgs: String): String {
        if (ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED && verifiedTarget == ForeverGraphicsProbe.WINDOWS_TARGET) {
            return ForeverGraphicsProbe.wineCommand(verifiedTarget, requestedArgs)
        }
        require(verifiedTarget == WINDOWS_TARGET) { "Wine target is not the verified managed target" }
        require(parseArguments(requestedArgs) == EXPECTED_ARGS) { "Wine flags are not the canonical Forever flags" }
        return (listOf("wine", WINDOWS_TARGET) + EXPECTED_ARGS).joinToString(" ")
    }

    fun useDirectRoute(managed: Boolean, customGame: Boolean, bootToContainer: Boolean, testGraphics: Boolean): Boolean =
        managed && customGame && !bootToContainer && !testGraphics

    /** Parses and uniquely selects the loose-file record from the embedded catalog. */
    private fun catalogRecordFromOfficialAsset(context: Context): CatalogExecutableRecord {
        // Same bounded structural/provenance gate as provisioning. No network
        // or cached metadata is consulted by this route.
        return uniqueCatalogRecord(ForeverClientCatalog.load(context))
    }

    internal fun catalogRecordForTest(catalog: JSONObject): CatalogExecutableRecord = uniqueCatalogRecord(catalog)

    private fun uniqueCatalogRecord(catalog: JSONObject): CatalogExecutableRecord {
        val loose = catalog.optJSONArray("loose_files") ?: error("catalog has no loose files")
        val matches = mutableListOf<JSONObject>()
        for (index in 0 until loose.length()) {
            val record = loose.getJSONObject(index)
            if (record.optString("relative_path").equals(TARGET_NAME, ignoreCase = true)) matches += record
        }
        require(matches.size == 1) { "official catalog must contain exactly one WowB-ARM64.exe loose record" }
        val record = matches.single()
        val md5 = record.optString("decoded_md5")
        require(md5.matches(Regex("[0-9a-fA-F]{32}"))) { "catalog executable MD5 is missing or malformed" }
        val size = record.optLong("decoded_size", -1L)
        require(size in 1..MAX_EXECUTABLE_BYTES) { "catalog executable size is invalid" }
        return CatalogExecutableRecord(record.getString("relative_path"), md5.lowercase(), size)
    }

    private data class PeHeader(val machine: Int, val optionalMagic: Int)

    private data class FileIdentity(
        val canonicalPath: String,
        val fileKey: Any?,
        val size: Long,
        val modified: Long,
    )

    private fun canonicalRealDirectory(file: File, label: String): File {
        require(Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "$label is not a directory" }
        return file.canonicalFile
    }

    internal fun validateManagedContext(input: ManagedContextInput): File {
        require(input.managed) { "direct ARM64 route is not valid for an unmanaged container" }
        require(input.runId.isNotEmpty() && input.runId == input.currentRunId) {
            "container run is not the current run"
        }
        require(input.currentStatus == ForeverRunStatus.ACTIVE) { "container run is not active" }
        val registered = requireNotNull(input.registeredGameFolder) { "app id is not a registered custom game" }
        val gameFolder = requireNotNull(input.gameFolder) { "game folder is required" }
        val canonicalRegistered = canonicalRealDirectory(registered, "registered custom game")
        val canonicalGame = canonicalRealDirectory(gameFolder, "A: game folder")
        require(canonicalRegistered == canonicalGame) { "app id folder and discovered A: folder differ" }
        require(canonicalRealDirectory(input.cwd, "launch cwd") == canonicalGame) {
            "launch cwd is not the canonical managed game directory"
        }
        require(input.aDriveFolders.size == 1) { "managed direct route requires exactly one A: drive" }
        require(canonicalRealDirectory(input.aDriveFolders.single(), "A: drive") == canonicalGame) {
            "A: drive does not map to the registered game directory"
        }
        return canonicalGame
    }

    private fun hasSymlinkInPath(file: File): Boolean {
        var cursor: File? = file.absoluteFile
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor.toPath())) return true
            cursor = cursor.parentFile
        }
        return false
    }

    private fun fileIdentity(file: File): FileIdentity {
        val path = file.toPath()
        val attributes = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "executable is not a regular non-symlink file" }
        require(attributes.size() in 1..MAX_EXECUTABLE_BYTES) { "executable exceeds the bounded size" }
        return FileIdentity(file.canonicalFile.path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun readPeHeader(file: File, size: Long): PeHeader {
        require(size <= MAX_EXECUTABLE_BYTES) { "executable exceeds the bounded size" }
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var count = 0
        java.nio.channels.FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) break
            }
            count = buffer.position()
        }
        require(count >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "executable is not an MZ image"
        }
        val peOffset = littleInt(bytes, 0x3c)
        require(peOffset >= 64 && peOffset <= count - 26 && peOffset + 26 <= MAX_HEADER_BYTES) {
            "PE header is outside the bounded header"
        }
        require(bytes[peOffset] == 'P'.code.toByte() && bytes[peOffset + 1] == 'E'.code.toByte() &&
            bytes[peOffset + 2] == 0.toByte() && bytes[peOffset + 3] == 0.toByte()) {
            "invalid PE signature"
        }
        return PeHeader(littleShort(bytes, peOffset + 4), littleShort(bytes, peOffset + 24))
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        java.nio.channels.FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_EXECUTABLE_BYTES) { "executable grew past bounded hash size" }
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun String.isApprovedExecutablePath(): Boolean =
        equals(TARGET_NAME, ignoreCase = true) ||
            equals(WINDOWS_TARGET, ignoreCase = true) ||
            equals("A:/${TARGET_NAME}", ignoreCase = true)

    private fun parseArguments(value: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        for (character in value) {
            when {
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> {
                    quote = character
                    tokenStarted = true
                }
                quote == null && character.isWhitespace() -> {
                    if (tokenStarted) {
                        result += current.toString()
                        current = StringBuilder()
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(character)
                    tokenStarted = true
                }
            }
        }
        require(quote == null) { "unterminated managed launch argument quote" }
        if (tokenStarted) result += current.toString()
        return result
    }
}
