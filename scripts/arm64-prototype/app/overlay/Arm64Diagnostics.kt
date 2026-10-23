package app.gamenative

import android.content.Context
import android.net.Uri
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Small, isolated setup and report exporter for native-AA64 probe custom games. */
object Arm64Diagnostics {
    private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
    private const val DXVK_VERSION = "dxvk-2.4.1-wow-aarch64-test"
    private const val MARKER = "arm64-diagnostics/installed.json"
    private const val SESSION_DIR = "arm64-diagnostics/sessions"
    private const val APP_ASSET_ROOT = "arm64-diagnostics"
    private const val MAX_LOG_BYTES = 512 * 1024

    private data class ProbeSpec(
        val label: String,
        val executable: String,
        val folderLabel: String,
        val expectedExit: Int,
    )

    private data class ImportedProfile(
        val profile: ContentProfile,
        val entryName: String,
    )

    private val probes = listOf(
        ProbeSpec("HelloArm64", "HelloArm64.exe", "Hello (exit 42 expected)", 42),
        ProbeSpec("ApiArm64", "ApiArm64.exe", "API (exit 0 expected)", 0),
        ProbeSpec("DynCodeArm64", "DynCodeArm64.exe", "DynCode (exit 0 expected)", 0),
    )

    private val mandatoryStages = mapOf(
        "HelloArm64" to setOf("architecture"),
        "ApiArm64" to setOf(
            "load-library-search-system32",
            "load-library-default",
            "get-module-kernel32",
            "get-proc-initialize-critical-section-ex",
            "get-proc-load-library-ex",
            "initialize-critical-section",
            "free-library-search-system32",
            "free-library-default",
        ),
        "DynCodeArm64" to setOf(
            "private-rw-rx",
            "shared-section",
            "shared-views",
            "shared-first-call",
            "shared-second-call",
            "shared-cleanup",
        ),
    )

    fun isPrepared(context: Context): Boolean = File(context.filesDir, MARKER).isFile

    fun latestSessionId(context: Context): String = readMarker(context).optString("runId", "not-prepared")

    suspend fun installAndRegister(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val runId = "RUN-${System.currentTimeMillis()}-${System.nanoTime()}"
            val proton = importProfile(context, "runtime/proton.wcp", PROTON_VERSION)
            val dxvk = importProfile(context, "runtime/dxvk.wcp", DXVK_VERSION)
            val contentsManager = ContentsManager(context).also { it.syncContents() }
            val resolvedProton = contentsManager.getProfileByEntryName(proton.entryName)
            check(resolvedProton != null && sameProfile(resolvedProton, proton.profile)) {
                "resolved Wine profile differs from imported profile ${proton.entryName}"
            }
            val wineInfo = com.winlator.core.WineInfo.fromIdentifier(
                context,
                contentsManager,
                ContentsManager.getEntryName(proton.profile),
            )
            check(wineInfo.isArm64EC()) {
                "resolved Wine profile is not ARM64EC: ${proton.entryName}"
            }
            val root = File(CustomGameScanner.defaultRootPath)
            val session = JSONArray()
            val assets = runtimeAssetManifest(context)
            for (probe in probes) {
                check(assets.getJSONObject("probes/${probe.executable}").optString("peMachine") == "0xaa64") {
                    "probe is not PE/AA64: ${probe.executable}"
                }
            }

            for (probe in probes) {
                val gameFolder = File(root, "ARM64 diagnostics - $runId - ${probe.folderLabel}")
                check(!gameFolder.exists()) { "refusing to reuse custom-game folder: $gameFolder" }
                check(gameFolder.mkdirs()) { "could not create custom-game folder: $gameFolder" }
                preflightRegistrationCollision(gameFolder)
                copyAsset(context, "$APP_ASSET_ROOT/probes/${probe.executable}", File(gameFolder, probe.executable))
                val logFile = File(gameFolder, "logs/$runId-${probe.label}.log").apply { parentFile?.mkdirs() }

                val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
                manualFolders += gameFolder.absolutePath
                PrefManager.customGameManualFolders = manualFolders
                CustomGameScanner.invalidateCache()

                val item = CustomGameScanner.createLibraryItemFromFolder(gameFolder.absolutePath)
                    ?: error("custom-game scan rejected ${gameFolder.absolutePath}")
                val actualId = item.appId.substringAfterLast('_').toIntOrNull()
                check(actualId == candidateGameId(gameFolder)) {
                    "custom-game ID collision changed registration for ${gameFolder.absolutePath}"
                }
                val registeredFolder = CustomGameScanner.getFolderPathFromAppId(item.appId)
                check(registeredFolder != null && sameFile(File(registeredFolder), gameFolder)) {
                    "custom-game registration does not point at ${gameFolder.absolutePath}"
                }
                val container = ContainerUtils.getOrCreateContainer(context, item.appId)
                configureContainer(container, probe.executable, runId, logFile, proton, dxvk)
                session.put(
                    JSONObject()
                        .put("appId", item.appId)
                        .put("name", gameFolder.name)
                        .put("folder", gameFolder.absolutePath)
                        .put("executable", probe.executable)
                        .put("label", probe.label)
                        .put("expectedExit", probe.expectedExit)
                        .put("log", logFile.absolutePath)
                        .put("windowsLog", "A:\\logs\\$runId-${probe.label}.log"),
                )
            }

            val emulator = "FEXCore"
            val emulatorConfig = JSONObject()
                .put("configuredEmulator", emulator)
                .put("hodll", "libwow64fex.dll")
                .put("purpose", "x86 auxiliary binaries under the paired arm64ec Wine runtime")
                .put("probeExecution", "native PE/AA64")
            val sessionMetadata = JSONObject()
                .put("runId", runId)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("wineProfile", proton.entryName)
                .put("wineVersion", proton.profile.verName)
                .put("wineSelector", "arm64ec")
                .put("wineIsArm64EC", wineInfo.isArm64EC())
                .put("dxvkProfile", dxvk.entryName)
                .put("dxvkVersion", dxvk.profile.verName)
                .put("containerVariant", Container.BIONIC)
                .put("graphicsDriver", "wrapper/System")
                .put("renderer", "android-system-vulkan")
                .put("emulatorConfig", emulatorConfig)
                .put("runtimeAssets", assets)
                .put("probes", session)
            writeJsonAtomically(sessionFile(context, runId), sessionMetadata)
            writeJsonAtomically(File(context.filesDir, MARKER), sessionMetadata)
        }
    }

    suspend fun exportLatest(context: Context, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val latest = readMarker(context)
            val marker = readSession(context, latest.optString("runId")) ?: JSONObject(latest.toString())
            val report = buildReport(context, marker)
            context.contentResolver.openOutputStream(destination)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    addZipText(zip, "report.json", report.toString(2))
                    addZipText(zip, "runtime-manifest.json", marker.toString(2))
                    val entries = marker.optJSONArray("probes") ?: JSONArray()
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        val file = File(entry.optString("log"))
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry("logs/${file.name}"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } ?: error("SAF destination could not be opened")
        }
    }

    private fun configureContainer(
        container: Container,
        executable: String,
        runId: String,
        logFile: File,
        proton: ImportedProfile,
        dxvk: ImportedProfile,
    ) {
        container.setExecutablePath(executable)
        container.setExecArgs("--log \"A:\\logs\\$runId-${executable.removeSuffix(".exe")}.log\" --run-id $runId")
        container.setWineVersion(proton.entryName)
        container.setDXWrapper("dxvk-${dxvk.profile.verName}")
        container.setDXWrapperConfig(
            "version=${dxvk.profile.verName},framerate=0,maxDeviceMemory=0,async=0,asyncCache=0",
        )
        container.setEmulator("FEXCore")
        container.putExtra("arm64DiagnosticsPinnedDxvk", dxvk.entryName)
        container.setContainerVariant(Container.BIONIC)
        container.setGraphicsDriver("wrapper")
        container.setGraphicsDriverVersion("System")
        container.setDisplayRenderer("vulkan")
        container.setAudioDriver("pulseaudio")
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL)
        container.setEnvVars("WINEESYNC=0")
        container.saveData()
        check(logFile.parentFile?.isDirectory == true) { "log directory was not created" }
    }

    private fun buildReport(context: Context, marker: JSONObject): JSONObject {
        val probesReport = JSONArray()
        val entries = marker.optJSONArray("probes") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val expected = entry.optInt("expectedExit", -1)
            val file = File(entry.optString("log"))
            probesReport.put(parseProbeLog(file, marker.optString("runId"), expected, entry.optString("label")))
        }
        return JSONObject()
            .put("runId", marker.optString("runId", "not-prepared"))
            .put("status", if (probesReport.length() == entries.length() && (0 until probesReport.length()).all { probesReport.getJSONObject(it).optString("status") == "PASS" }) "PASS" else "INCOMPLETE_OR_FAILED")
            .put("runtimeAssets", marker.optJSONObject("runtimeAssets") ?: JSONObject())
            .put("probes", probesReport)
            .put("note", "HelloArm64 exit 42 is expected; missing or incomplete logs are never reported as PASS.")
            .put("exportSource", context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
    }

    internal fun parseProbeLogForTest(file: File, runId: String, expectedExit: Int, expectedName: String): JSONObject =
        parseProbeLog(file, runId, expectedExit, expectedName)

    private fun parseProbeLog(file: File, runId: String, expectedExit: Int, expectedName: String): JSONObject {
        if (!file.isFile) return JSONObject().put("status", "MISSING").put("path", file.absolutePath)
        if (file.length() > MAX_LOG_BYTES) return JSONObject().put("status", "FAILED").put("reason", "log-too-large").put("path", file.absolutePath)
        if (runId.isBlank() || expectedName !in mandatoryStages) return failedLog(file, "unknown-probe")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return failedLog(file, "log-not-utf8")
        }
        if (text.isEmpty() || !text.endsWith("\n")) return failedLog(file, "log-truncated")
        val lines = text.split('\n').dropLast(1).map { it.removeSuffix("\r") }
        if (lines.any { it.isBlank() }) return failedLog(file, "blank-log-line")
        val prefix = "RUN-ID=$runId "
        if (lines.any { !it.startsWith(prefix) }) return failedLog(file, "wrong-run-id")

        val beginPattern = Regex("^PROBE-BEGIN name=([A-Za-z0-9-]+) pe_machine=0xAA64$")
        val stageBeginPattern = Regex("^STAGE-BEGIN name=([^ ]+)$")
        val stageEndPattern = Regex("^STAGE-END name=([^ ]+) returncode=(-?\\d+)$")
        val probeEndPattern = Regex("^PROBE-END name=([A-Za-z0-9-]+) returncode=(-?\\d+)$")
        var probeBeginCount = 0
        var probeEndCount = 0
        var actualExit: Int? = null
        val stageBegins = mutableSetOf<String>()
        val stageEnds = mutableMapOf<String, Int>()
        var notIncludedCount = 0

        for ((index, line) in lines.withIndex()) {
            val body = line.removePrefix(prefix)
            when {
                body.startsWith("PROBE-BEGIN ") -> {
                    val match = beginPattern.matchEntire(body) ?: return failedLog(file, "invalid-probe-begin")
                    if (++probeBeginCount != 1 || match.groupValues[1] != expectedName || index != 0) return failedLog(file, "probe-begin-conflict")
                }
                body.startsWith("STAGE-BEGIN ") -> {
                    val match = stageBeginPattern.matchEntire(body) ?: return failedLog(file, "invalid-stage-begin")
                    val name = match.groupValues[1]
                    if (name !in mandatoryStages[expectedName].orEmpty() || !stageBegins.add(name)) return failedLog(file, "stage-begin-conflict")
                }
                body.startsWith("STAGE-END ") -> {
                    val match = stageEndPattern.matchEntire(body) ?: return failedLog(file, "invalid-stage-end")
                    val name = match.groupValues[1]
                    val code = match.groupValues[2].toIntOrNull() ?: return failedLog(file, "invalid-stage-code")
                    if (name !in stageBegins || stageEnds.put(name, code) != null) return failedLog(file, "stage-end-conflict")
                }
                body.startsWith("STAGE-NOT-INCLUDED ") -> {
                    val expected = "STAGE-NOT-INCLUDED name=syscall-copied reason=no-arbitrary-or-offset-fragile-byte-copy"
                    if (expectedName != "DynCodeArm64" || body != expected || ++notIncludedCount != 1) return failedLog(file, "unexpected-not-included-stage")
                }
                body.startsWith("PROBE-END ") -> {
                    val match = probeEndPattern.matchEntire(body) ?: return failedLog(file, "invalid-probe-end")
                    actualExit = match.groupValues[2].toIntOrNull() ?: return failedLog(file, "invalid-probe-exit")
                    if (++probeEndCount != 1 || match.groupValues[1] != expectedName || index != lines.lastIndex) return failedLog(file, "probe-end-conflict")
                }
                body.contains("PROBE-BEGIN") || body.contains("PROBE-END") || body.contains("STAGE-BEGIN") || body.contains("STAGE-END") ->
                    return failedLog(file, "malformed-marker")
            }
        }

        val expectedStages = mandatoryStages.getValue(expectedName)
        val stageFailures = stageEnds.values.count { it != 0 }
        val complete = probeBeginCount == 1 && probeEndCount == 1 &&
            stageBegins == expectedStages && stageEnds.keys == expectedStages &&
            (expectedName != "DynCodeArm64" || notIncludedCount == 1) &&
            actualExit == expectedExit && stageFailures == 0
        return JSONObject()
            .put("status", if (complete) "PASS" else "FAILED")
            .put("path", file.absolutePath)
            .put("expectedExit", expectedExit)
            .put("actualExit", actualExit ?: JSONObject.NULL)
            .put("stageFailures", stageFailures)
            .put("stageCount", stageEnds.size)
    }

    private fun runtimeAssetManifest(context: Context): JSONObject {
        val result = JSONObject()
        for (path in listOf("runtime/proton.wcp", "runtime/dxvk.wcp") + probes.map { "probes/${it.executable}" }) {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open("$APP_ASSET_ROOT/$path").use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
            val pe = if (path.startsWith("probes/")) {
                context.assets.open("$APP_ASSET_ROOT/$path").use { peMachine(it.readBytes()) }
            } else null
            val entry = JSONObject()
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                .put("bytes", size)
                .put("peMachine", pe ?: JSONObject.NULL)
            if (path == "runtime/proton.wcp") {
                entry.put("selectedProfile", PROTON_VERSION).put("selector", "arm64ec")
                    .put("payloadRole", "Wine/Proton with paired x86 auxiliary support")
            } else if (path == "runtime/dxvk.wcp") {
                entry.put("selectedProfile", DXVK_VERSION).put("selector", "plain AA64 system32 DLLs")
                    .put("dlls", JSONArray().put("dxgi.dll").put("d3d11.dll"))
                    .put("dllArchitecture", "AA64 PE")
            } else {
                entry.put("selectedArchitecture", "AA64 PE")
            }
            result.put(path, entry)
        }
        return result
    }

    private fun peMachine(bytes: ByteArray): String? {
        if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return null
        val offset = littleEndianInt(bytes, 0x3c)
        if (offset < 0 || offset + 6 > bytes.size || bytes[offset] != 'P'.code.toByte() || bytes[offset + 1] != 'E'.code.toByte()) return null
        return "0x%04x".format(littleEndianShort(bytes, offset + 4))
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int = littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)
    private fun addZipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    private fun readMarker(context: Context): JSONObject = File(context.filesDir, MARKER).takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject().put("probes", JSONArray())

    private fun readSession(context: Context, runId: String): JSONObject? {
        if (runId.isBlank() || runId.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') }) return null
        return sessionFile(context, runId).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
    }

    private fun sessionFile(context: Context, runId: String): File = File(context.filesDir, "$SESSION_DIR/$runId.json")

    private fun writeJsonAtomically(destination: File, value: JSONObject) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            error("atomic metadata replace is not supported for ${destination.absolutePath}")
        }
    }

    private fun candidateGameId(folder: File): Int = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }

    private fun preflightRegistrationCollision(folder: File) {
        val candidate = candidateGameId(folder)
        val owner = CustomGameScanner.findCustomGameById(candidate)
        check(owner == null || sameFile(File(owner), folder)) {
            "custom-game ID collision $candidate: ${File(owner!!).absolutePath} already owns it"
        }
    }

    private fun sameFile(left: File, right: File): Boolean = try {
        left.canonicalFile == right.canonicalFile
    } catch (_: Exception) {
        left.absoluteFile == right.absoluteFile
    }

    private fun sameProfile(left: ContentProfile, right: ContentProfile): Boolean =
        left.type == right.type && left.verName == right.verName && left.verCode == right.verCode

    private fun failedLog(file: File, reason: String): JSONObject =
        JSONObject().put("status", "FAILED").put("reason", reason).put("path", file.absolutePath)

    private fun importProfile(context: Context, asset: String, version: String): ImportedProfile {
        val manager = ContentsManager(context)
        manager.syncContents()
        manager.getProfileByEntryName(version)?.let { return ImportedProfile(it, ContentsManager.getEntryName(it)) }
        val staged = File(context.cacheDir, "arm64-diagnostics-${File(asset).name}")
        copyAsset(context, "$APP_ASSET_ROOT/$asset", staged)
        var profile: ContentProfile? = null
        var failure: String? = null
        manager.extraContentFile(Uri.fromFile(staged), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) { failure = "$reason${e?.message?.let { ": $it" } ?: ""}" }
            override fun onSucceed(value: ContentProfile) { profile = value }
        })
        check(failure == null) { "import $version failed: $failure" }
        val imported = checkNotNull(profile) { "import $version returned no profile" }
        var finishFailure: String? = null
        manager.finishInstallContent(imported, object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) { finishFailure = "$reason${e?.message?.let { ": $it" } ?: ""}" }
            override fun onSucceed(value: ContentProfile) = Unit
        })
        staged.delete()
        check(finishFailure == null) { "install $version failed: $finishFailure" }
        manager.syncContents()
        val resolved = manager.getProfileByEntryName(ContentsManager.getEntryName(imported))
            ?: error("installed profile not resolvable: $version")
        check(sameProfile(resolved, imported)) { "installed profile metadata changed: $version" }
        return ImportedProfile(resolved, ContentsManager.getEntryName(resolved))
    }

    private fun copyAsset(context: Context, asset: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
        destination.setExecutable(true, false)
    }
}
