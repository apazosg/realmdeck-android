package app.gamenative

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one historical pre-launch admission.  This is deliberately a closed
 * source contract, rather than a token read from app-private storage: a host
 * cannot manufacture an authorization for another UNKNOWN run.
 */
internal object ForeverNativePrelaunchDisposition {
    private const val MAX_BYTES = 64 * 1024L
    private const val RUN_ID = "NS-b0a3fe48f925d7d656f597f740b4b413"
    private const val NONCE = "83ebf2726bda3c43258cd9523c465f59"
    private const val ATTEMPT_BOOT_ID = "66f3530d-a8e6-4a94-9b62-44393cdc75c0"
    private const val CURRENT_SHA256 = "73428e2c77bdeafc5687a5860821b67b7f1489966f1cae62d04a12318c57244d"
    private const val EVENTS_SHA256 = "e81a47a4da7aa10ec844f14d821e6109b4584495c381459a49a96d5ceaaa4693"
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val LEGACY56_CURRENT_SHA256 = "c9c899f579314d193a91a7bd935be5df16d6dcc1ece39d42863a9ab15064685c"
    private const val APK57_SHA256 = "8d0c0a86b1ca1571736e399d08f9ae7e36b9b7ec2f6b73075867c0a5fb747572"
    private const val APK57_BYTES = 370931339L
    private const val APK57_PROVENANCE_SHA256 = "5630f86fe7265fcd42d45023153aec9acac21f8f27df4b6b92b2f82ded3ec5c4"
    private const val APK57_CONTROLLER_SHA256 = "c445ee04bc76ad0f9fcdea19a66bcd9897d950dd5111b8cdf98cc73820c75ba9"
    private const val APK57_POLICY_SHA256 = "1d4c59fdeed66452f77baf97ce72592db5b6eccf5479aad090659f67b7432d79"
    private const val APK57_STORE_SHA256 = "ce858104d6d6f97ee71db630f72cf388090b5f459493d24acef3a7ef545b6ea7"
    private const val SEAL_SHA256 = "f426bbd4ff2fd836efd9e0086e7ac742b134fc1fdffed27442426620126c23c2"
    private const val CLOSURE_SEAL_SHA256 = "0d297fb669a4a6233b81870ec3266f10b8450c3e4dd05c27db2f7543aae26bb0"
    private const val PROVENANCE_SHA256 = "4b9ab321c7aca6dae9f676ea5887a2c44ad1f064d5c3476046dca97b18fb9e72"
    private const val COLLECTOR_SHA256 = "ee920762b57af16418a6b7d8c4a7f22c9e914f619874ae50eb1a67813dd89b00"
    private const val SOURCE_SHA256 = "423ed396ac54c9caa5edf43c06ec645dc5b68275928003c064729b85ecb44c62"

    internal fun interface BootIdProvider { fun read(): String }

    internal object KernelBootIdProvider : BootIdProvider {
        override fun read(): String {
            val file = File("/proc/sys/kernel/random/boot_id")
            rejectSymlinkAncestors(file)
            val path = file.toPath()
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "kernel boot_id is not a regular file" }
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.size() <= 128L) { "kernel boot_id is too large" }
            val output = ByteArrayOutputStream()
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(32)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0 && output.size() + count <= 128) { "kernel boot_id read exceeded bound" }
                    output.write(buffer, 0, count)
                }
            }
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(before.size() == after.size() && before.fileKey() == after.fileKey()) { "kernel boot_id changed while reading" }
            val value = output.toByteArray().toString(Charsets.US_ASCII).trim()
            require(BOOT_ID.matches(value)) { "invalid kernel boot_id" }
            return value
        }
    }

    private val BOOT_ID = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    internal data class Admission(
        val priorRunId: String,
        val currentArchiveSha256: String,
        val dispositionSha256: String,
        val consumptionSha256: String,
        val historicalAdmission: JSONObject,
    )

    /**
     * Reserves the exact historical UNKNOWN, or returns null for a non-UNKNOWN
     * current.  A non-matching UNKNOWN is intentionally not recoverable.
     * Every receipt is CREATE_NEW; an interrupted receipt therefore remains a
     * quarantine marker instead of becoming a resumable transaction.
     */
    internal fun reserve(
        base: File,
        current: File,
        previous: JSONObject,
        newRunId: String,
        newNonce: String,
        newScenario: String,
        bootProvider: BootIdProvider,
        beforeStep: (String) -> Unit = {},
    ): Admission? {
        if (previous.optString("status") != "UNKNOWN") return null
        val currentBytes = readPinned(current, 64 * 1024, CURRENT_SHA256, 380L)
        val priorRunId = previous.optString("runId")
        require(priorRunId == RUN_ID)
        require(typedHistoricalCurrent(previous))
        val priorDirectory = File(base, priorRunId)
        requireRegularDirectory(priorDirectory)
        val pinned = mapOf(
            "run.json" to (380L to CURRENT_SHA256),
            "events.jsonl" to (51L to EVENTS_SHA256),
        )
        val entries = Files.list(priorDirectory.toPath()).use { stream ->
            stream.iterator().asSequence().map { it.fileName.toString() }.toList()
        }
        require(entries.toSet() == pinned.keys)
        pinned.forEach { (name, pin) -> readPinned(File(priorDirectory, name), 64 * 1024, pin.second, pin.first) }
        listOf("stdout", "stderr", "witness-stdout", "witness-stderr").forEach {
            require(!Files.exists(File(priorDirectory, it).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "historical stream must remain absent: $it"
            }
        }
        require(File(priorDirectory, "run.json").readBytes().contentEquals(currentBytes))

        // The immediately preceding v1 UNKNOWN is a separate immutable guard.
        val legacyCurrent = File(base.parentFile, "native-supervisor/current.json")
        readPinned(legacyCurrent, 64 * 1024, LEGACY56_CURRENT_SHA256, null)

        val freshBootId = bootProvider.read()
        require(BOOT_ID.matches(freshBootId) && freshBootId != ATTEMPT_BOOT_ID) { "historical attempt boot is still active" }
        val archive = File(priorDirectory, "current-before-disposition.json")
        val disposition = File(priorDirectory, "prelaunch-disposition.json")
        val consumption = File(priorDirectory, "prelaunch-consumption.json")
        listOf(archive, disposition, consumption).forEach {
            require(!Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)) { "historical reservation already exists" }
        }

        beforeStep("prelaunch_archive")
        writeCreateNew(archive, currentBytes)
        verifyOldPins(base, current, previous, currentBytes, priorDirectory, legacyCurrent)

        val dispositionJson = JSONObject()
            .put("schema", 1)
            .put("kind", "PRELAUNCH_ABORT")
            .put("variant", "native-run-supervisor-v2")
            .put("priorRunId", RUN_ID)
            .put("priorNonce", NONCE)
            .put("priorStatus", "UNKNOWN")
            .put("priorCurrentSha256", CURRENT_SHA256)
            .put("priorRunSha256", CURRENT_SHA256)
            .put("priorEventsSha256", EVENTS_SHA256)
            .put("apk57Sha256", APK57_SHA256)
            .put("apk57Bytes", APK57_BYTES)
            .put("attemptBootId", ATTEMPT_BOOT_ID)
            .put("freshBootId", freshBootId)
            .put("processCreate", "NOT_REACHED_BY_PINNED_SOURCE")
            .put("echild", false)
            .put("reason", "access failed: EACCES (Permission denied)")
            .put("legacy56CurrentSha256", LEGACY56_CURRENT_SHA256)
            .put("sourceSha256", SOURCE_SHA256)
            .put("sealSha256", SEAL_SHA256)
            .put("closureSealSha256", CLOSURE_SEAL_SHA256)
            .put("provenanceSha256", PROVENANCE_SHA256)
            .put("collectorSha256", COLLECTOR_SHA256)
            .put("apk57ProvenanceSha256", APK57_PROVENANCE_SHA256)
            .put("apk57ControllerSha256", APK57_CONTROLLER_SHA256)
            .put("apk57PolicySha256", APK57_POLICY_SHA256)
            .put("apk57StoreSha256", APK57_STORE_SHA256)
        val streamPins = listOf(EMPTY_SHA256, EMPTY_SHA256, EMPTY_SHA256, EMPTY_SHA256)
        dispositionJson
            .put("priorStdoutSha256", streamPins[0])
            .put("priorStderrSha256", streamPins[1])
            .put("priorWitnessStdoutSha256", streamPins[2])
            .put("priorWitnessStderrSha256", streamPins[3])
            .put("priorStreamPresence", JSONObject()
                .put("stderr", "MISSING")
                .put("stdout", "MISSING")
                .put("witness-stderr", "MISSING")
                .put("witness-stdout", "MISSING"))
        val dispositionBytes = dispositionJson.toString().toByteArray(Charsets.UTF_8)
        beforeStep("prelaunch_disposition")
        writeCreateNew(disposition, dispositionBytes)
        verifyOldPins(base, current, previous, currentBytes, priorDirectory, legacyCurrent)

        val consumptionJson = JSONObject()
            .put("schema", 1)
            .put("kind", "RESERVED_PRELAUNCH_CONSUMPTION")
            .put("priorRunId", RUN_ID)
            .put("newRunId", newRunId)
            .put("newNonce", newNonce)
            .put("newScenario", newScenario)
            .put("freshBootId", freshBootId)
            .put("dispositionSha256", sha256(dispositionBytes))
            .put("sourceSha256", SOURCE_SHA256)
            .put("sealSha256", SEAL_SHA256)
        val consumptionBytes = consumptionJson.toString().toByteArray(Charsets.UTF_8)
        beforeStep("prelaunch_consumption")
        writeCreateNew(consumption, consumptionBytes)
        verifyOldPins(base, current, previous, currentBytes, priorDirectory, legacyCurrent)
        require(bootProvider.read() == freshBootId) { "boot changed during historical reservation" }
        return Admission(RUN_ID, sha256(currentBytes), sha256(dispositionBytes), sha256(consumptionBytes),
            JSONObject()
                .put("priorRunId", RUN_ID)
                .put("currentArchiveSha256", sha256(currentBytes))
                .put("dispositionSha256", sha256(dispositionBytes))
                .put("consumptionSha256", sha256(consumptionBytes)))
    }

    /** Rechecks the immutable historical evidence after consumption and before current.json moves. */
    internal fun verifyConsumed(
        base: File,
        current: File,
        previous: JSONObject,
        admission: Admission,
    ) {
        val currentBytes = readPinned(current, 64 * 1024, CURRENT_SHA256, 380L)
        require(sha256(currentBytes) == admission.currentArchiveSha256)
        require(previous.optString("runId") == RUN_ID && typedHistoricalCurrent(previous))
        val priorDirectory = File(base, RUN_ID)
        val legacyCurrent = File(base.parentFile, "native-supervisor/current.json")
        verifyOldPins(base, current, previous, currentBytes, priorDirectory, legacyCurrent)
        require(readPinned(File(priorDirectory, "current-before-disposition.json"), 64 * 1024,
            CURRENT_SHA256, 380L).contentEquals(currentBytes))
        val disposition = readBoundedPinned(File(priorDirectory, "prelaunch-disposition.json"))
        val consumption = readBoundedPinned(File(priorDirectory, "prelaunch-consumption.json"))
        require(sha256(disposition) == admission.dispositionSha256)
        require(sha256(consumption) == admission.consumptionSha256)
    }

    private fun verifyOldPins(base: File, current: File, previous: JSONObject, currentBytes: ByteArray,
        priorDirectory: File, legacyCurrent: File) {
        require(sha256(readPinned(current, 64 * 1024, CURRENT_SHA256, 380L)) == CURRENT_SHA256)
        require(typedHistoricalCurrent(previous))
        require(readPinned(File(priorDirectory, "run.json"), 64 * 1024, CURRENT_SHA256, 380L).contentEquals(currentBytes))
        require(readPinned(File(priorDirectory, "events.jsonl"), 64 * 1024, EVENTS_SHA256, 51L).isNotEmpty())
        require(readPinned(File(priorDirectory, "current-before-disposition.json"), 64 * 1024,
            CURRENT_SHA256, 380L).contentEquals(currentBytes))
        listOf("stdout", "stderr", "witness-stdout", "witness-stderr").forEach {
            require(!Files.exists(File(priorDirectory, it).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "historical stream must remain absent: $it"
            }
        }
        readPinned(legacyCurrent, 64 * 1024, LEGACY56_CURRENT_SHA256, null)
        require(base.canonicalFile == base.absoluteFile)
    }

    private fun typedHistoricalCurrent(value: JSONObject): Boolean {
        require(value.keys().asSequence().toSet() == setOf("schema", "variant", "runId", "nonce", "scenario", "status",
            "nativeExit", "witnessProof", "identities", "echild", "errors"))
        require(value.getInt("schema") == 1 && value.getString("variant") == "native-run-supervisor-v2")
        require(value.getString("runId") == RUN_ID && value.getString("nonce") == NONCE && value.getString("scenario") == "normal")
        require(value.getString("status") == "UNKNOWN" && value.isNull("nativeExit") && !value.getBoolean("echild"))
        val witness = value.getJSONObject("witnessProof")
        require(witness.keys().asSequence().toSet() == setOf("status") && witness.getString("status") == "UNPROVEN")
        require(value.getJSONArray("identities").length() == 0)
        val errors = value.getJSONArray("errors")
        require(errors.length() == 1 && errors.getString(0) == "access failed: EACCES (Permission denied)")
        return true
    }

    private fun readPinned(file: File, limit: Long, expectedSha256: String, expectedBytes: Long?): ByteArray {
        rejectSymlinkAncestors(file)
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "pinned evidence is not a regular file" }
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.size() <= limit && (expectedBytes == null || before.size() == expectedBytes))
        val bytes = Files.readAllBytes(path)
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.size() == after.size() && before.fileKey() == after.fileKey())
        require(sha256(bytes) == expectedSha256)
        return bytes
    }

    private fun readBoundedPinned(file: File): ByteArray {
        rejectSymlinkAncestors(file)
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "receipt is not a regular file" }
        require(Files.size(path) <= MAX_BYTES)
        return Files.readAllBytes(path)
    }

    private fun writeCreateNew(file: File, bytes: ByteArray) {
        rejectSymlinkAncestors(file)
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        setPrivateMode(file)
        forceDirectory(requireNotNull(file.parentFile))
    }

    private fun requireRegularDirectory(directory: File) {
        rejectSymlinkAncestors(directory)
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun rejectSymlinkAncestors(file: File) {
        var cursor: File? = file.absoluteFile
        while (cursor != null) {
            if (Files.exists(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) require(!Files.isSymbolicLink(cursor.toPath()))
            cursor = cursor.parentFile
        }
    }

    private fun setPrivateMode(file: File) {
        try {
            android.system.Os.chmod(file.absolutePath, 0x180)
            require((android.system.Os.stat(file.absolutePath).st_mode and 0x1ff) == 0x180)
        } catch (failure: Throwable) {
            try {
                Files.setPosixFilePermissions(file.toPath(), setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            } catch (fallback: Throwable) {
                throw IllegalStateException("cannot establish private receipt mode", failure)
            }
        }
    }

    private fun forceDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
