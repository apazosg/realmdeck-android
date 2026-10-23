package app.gamenative

import android.content.Context
import android.os.Process
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

enum class PanvkWsiStatus { PREPARING, READY, STARTING, RUNNING, RESULT, CANCELLED, UNKNOWN, ERROR }

data class PanvkWsiRun(
    val runId: String,
    val generation: Long,
    val pid: Int,
    val startTicks: Long?,
    val directory: File,
    val icdPath: File,
    val nativeJournalPath: File,
    val uid: Int = Process.myUid(),
)

data class PanvkWsiState(
    val runId: String,
    val generation: Long,
    val pid: Int,
    val startTicks: Long?,
    val status: PanvkWsiStatus,
    val uid: Int = Process.myUid(),
    val processName: String? = null,
    val rootPath: String? = null,
    val assetPath: String = PanvkWsiStore.ASSET_PATH,
    val assetSha256: String = PanvkWsiStore.APPROVED_SHA256,
    val assetSize: Long = PanvkWsiStore.APPROVED_SIZE,
    val icdPath: String? = null,
    val nativeJournalPath: String? = null,
    val surfaceWidth: Int? = null,
    val surfaceHeight: Int? = null,
    val holderWidth: Int? = null,
    val holderHeight: Int? = null,
    val available: Boolean? = null,
    val bounds: List<Int> = emptyList(),
    val nativeStatus: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
)

/** Owns only files under files/forever/controls/panvk-wsi. */
class PanvkWsiStore(private val context: Context) {
    companion object {
        const val ASSET_PATH = "forever/panvk-wsi/libvulkan_panfrost.so"
        const val ICD_FILE_NAME = "libvulkan_panfrost.so"
        const val ROOT_RELATIVE_PATH = "forever/controls/panvk-wsi"
        const val CURRENT_FILE_NAME = "current.json"
        const val APPROVED_SHA256 = "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
        const val APPROVED_SIZE = 31_142_504L
        const val MAX_JSON_BYTES = 64 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024

        private val generationCounter = AtomicLong(0L)
        private val runPattern = Regex("PWSI-[0-9a-f]{32}")
        internal fun verifyAssetBytes(bytes: ByteArray): Boolean =
            bytes.size.toLong() == APPROVED_SIZE && sha256(bytes) == APPROVED_SHA256

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val base = context.filesDir.canonicalFile
    private val root = File(base, ROOT_RELATIVE_PATH)
    private val mutex = Any()

    fun createRun(
        pid: Int = Process.myPid(),
        generation: Long = generationCounter.incrementAndGet(),
    ): PanvkWsiRun {
        require(pid > 0 && generation > 0) { "invalid WSI process identity" }
        synchronized(mutex) {
            val managed = ensureDirectories()
            val runId = newRunId()
            val directory = childDirectory(managed, runId)
            Files.createDirectory(directory.toPath())
            val icd = childFile(directory, ICD_FILE_NAME)
            val nativeJournal = childFile(directory, "native.jsonl")
            createExclusiveEmpty(nativeJournal)
            val run = PanvkWsiRun(runId, generation, pid, processStartTicks(pid), directory, icd, nativeJournal, Process.myUid())
            writeState(run, PanvkWsiState(runId, generation, pid, run.startTicks, PanvkWsiStatus.PREPARING, run.uid))
            writeCurrent(run, PanvkWsiStatus.PREPARING)
            appendEventLocked(run, "preparing")
            return run
        }
    }

    /** Copies and verifies the pinned ICD; no caller-supplied asset/path is accepted. */
    fun prepareIcd(run: PanvkWsiRun): File {
        lateinit var target: File
        var ownedFileCreated = false
        try {
            target = synchronized(mutex) {
                checkCurrentGeneration(run)
                val state = readStateLocked(run) ?: error("missing WSI state")
                require(state.status == PanvkWsiStatus.PREPARING) { "WSI run is not preparing" }
                childFile(run.directory, ICD_FILE_NAME).also {
                    require(!Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)) { "ICD destination already exists" }
                }
            }
            copyAndVerifyAsset(target) { ownedFileCreated = true }
            synchronized(mutex) {
                checkCurrentGeneration(run)
                val state = readStateLocked(run) ?: error("missing WSI state")
                require(state.status == PanvkWsiStatus.PREPARING) { "WSI run was cancelled during ICD preparation" }
                appendEventLocked(run, "icd_prepared")
            }
            return target
        } catch (failure: Throwable) {
            // CREATE_NEW failures do not establish ownership.  A successfully
            // created partial file is retained and can never be reused.
            synchronized(mutex) {
                markErrorLocked(run, boundedError(failure, ownedFileCreated))
            }
            throw failure
        }
    }

    fun publishReady(
        run: PanvkWsiRun,
        processName: String,
        surfaceWidth: Int,
        surfaceHeight: Int,
        holderWidth: Int,
        holderHeight: Int,
        available: Boolean,
        bounds: List<Int>,
    ) = synchronized(mutex) {
        checkCurrentGeneration(run)
        require(surfaceWidth == 320 && surfaceHeight == 240 && holderWidth == 320 && holderHeight == 240)
        require(bounds.size == 4 && available)
        val state = requireNotNull(readStateLocked(run))
        require(state.status == PanvkWsiStatus.PREPARING) { "WSI run is not preparing" }
        writeState(run, state.copy(
            status = PanvkWsiStatus.READY, processName = processName, icdPath = run.icdPath.absolutePath,
            nativeJournalPath = run.nativeJournalPath.absolutePath,
            rootPath = root.canonicalPath, assetPath = ASSET_PATH, assetSha256 = APPROVED_SHA256,
            assetSize = APPROVED_SIZE,
            surfaceWidth = surfaceWidth, surfaceHeight = surfaceHeight, holderWidth = holderWidth,
            holderHeight = holderHeight, available = available, bounds = bounds,
            events = boundedEvents(state.events + "surface_ready"),
        ))
        writeCurrent(run, PanvkWsiStatus.READY)
    }

    fun markStarting(run: PanvkWsiRun) = transition(run, PanvkWsiStatus.READY, PanvkWsiStatus.STARTING, "starting")

    fun markRunning(run: PanvkWsiRun) = transition(run, PanvkWsiStatus.STARTING, PanvkWsiStatus.RUNNING, "running")

    fun appendEvent(run: PanvkWsiRun, event: String): Boolean = synchronized(mutex) {
        if (!PanvkWsiPolicy.acceptedHook(event)) return false
        val state = readStateLocked(run) ?: return false
        if (!sameCurrent(state, run) || state.status in TERMINAL) return false
        appendEventLocked(run, event)
        true
    }

    fun cancel(run: PanvkWsiRun, reason: String): Boolean = synchronized(mutex) {
        val state = readStateLocked(run) ?: return false
        if (!sameCurrent(state, run) || state.status in TERMINAL) return false
        writeState(run, state.copy(status = PanvkWsiStatus.CANCELLED, error = boundedText(reason),
            events = boundedEvents(state.events + "cancel:$reason")))
        writeCurrent(run, PanvkWsiStatus.CANCELLED)
        true
    }

    fun markUnknown(run: PanvkWsiRun, reason: String): Boolean = synchronized(mutex) {
        val state = readStateLocked(run) ?: return false
        if (!sameCurrent(state, run) || state.status in TERMINAL) return false
        writeState(run, state.copy(status = PanvkWsiStatus.UNKNOWN, error = boundedText(reason),
            events = boundedEvents(state.events + "unknown:$reason")))
        writeCurrent(run, PanvkWsiStatus.UNKNOWN)
        true
    }

    fun finishNative(run: PanvkWsiRun, json: String): Boolean = synchronized(mutex) {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            markErrorLocked(run, "native result exceeds bound")
            return false
        }
        val state = readStateLocked(run) ?: return false
        if (!sameCurrent(state, run) || state.status in TERMINAL) return false
        val native = try {
            JSONObject(json).also {
                validateNativeBinding(it, run)
            }
        } catch (_: Throwable) {
            atomicWrite(childFile(run.directory, "rejected-native.json"), json)
            markErrorLocked(run, "invalid bounded native result")
            return false
        }
        val result = JSONObject().put("schema", "wowforever.android-panvk-wsi").put("version", 1)
            .put("runId", run.runId).put("pid", run.pid).put("uid", run.uid).put("generation", run.generation)
            .put("status", "RESULT")
            .put("androidPresentationObserved", false).put("native", native)
        if (result.toString().toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            markErrorLocked(run, "bounded native result wrapper exceeds limit")
            return false
        }
        atomicWrite(childFile(run.directory, "result.json"), result.toString())
        writeState(run, state.copy(status = PanvkWsiStatus.RESULT,
            nativeStatus = stringField(native, "status"), events = boundedEvents(state.events + "result")))
        writeCurrent(run, PanvkWsiStatus.RESULT)
        true
    }

    /** Retains UNKNOWN if the Activity was destroyed while native was in flight. */
    fun recordLateNativeReturn(run: PanvkWsiRun, json: String): Boolean = synchronized(mutex) {
        val state = readStateLocked(run) ?: return false
        if (!sameCurrent(state, run) || state.status != PanvkWsiStatus.UNKNOWN ||
            json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return false
        val native = runCatching {
            JSONObject(json).also {
                validateNativeBinding(it, run)
            }
        }.getOrNull() ?: return false
        // The native writer owns native.jsonl. Keeping state UNKNOWN is
        // deliberate; a late return is not proof that all driver work retired.
        val result = JSONObject().put("schema", "wowforever.android-panvk-wsi").put("version", 1)
            .put("runId", run.runId).put("pid", run.pid).put("uid", run.uid).put("generation", run.generation)
            .put("status", "UNKNOWN")
            .put("nativeReturned", true).put("androidPresentationObserved", false).put("native", native)
        if (result.toString().toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return false
        atomicWrite(childFile(run.directory, "result.json"), result.toString())
        true
    }

    fun readState(run: PanvkWsiRun): PanvkWsiState? = synchronized(mutex) {
        readStateLocked(run)?.also {
            require(it.runId == run.runId && it.generation == run.generation && it.pid == run.pid && it.uid == run.uid) {
                "WSI state identity mismatch"
            }
        }
    }

    internal fun rootForTests(): File = root

    private fun transition(run: PanvkWsiRun, from: PanvkWsiStatus, to: PanvkWsiStatus, event: String) = synchronized(mutex) {
        checkCurrentGeneration(run)
        val state = requireNotNull(readStateLocked(run))
        require(state.status == from) { "invalid WSI transition ${state.status} -> $to" }
        writeState(run, state.copy(status = to, events = boundedEvents(state.events + event)))
        writeCurrent(run, to)
    }

    private fun appendEventLocked(run: PanvkWsiRun, event: String) {
        val state = readStateLocked(run) ?: return
        if (state.status in TERMINAL) return
        writeState(run, state.copy(events = boundedEvents(state.events + boundedText(event))))
        writeCurrent(run, state.status)
    }

    private fun markErrorLocked(run: PanvkWsiRun, message: String) {
        val state = readStateLocked(run) ?: return
        if (!sameCurrent(state, run) || state.status in TERMINAL) return
        writeState(run, state.copy(status = PanvkWsiStatus.ERROR, error = boundedText(message),
            events = boundedEvents(state.events + "error")))
        writeCurrent(run, PanvkWsiStatus.ERROR)
    }

    private fun checkCurrentGeneration(run: PanvkWsiRun) {
        val state = readStateLocked(run)
        require(state != null && sameCurrent(state, run)) {
            "stale WSI generation"
        }
    }

    private fun sameCurrent(state: PanvkWsiState, run: PanvkWsiRun): Boolean =
        state.runId == run.runId && state.generation == run.generation && state.pid == run.pid && state.uid == run.uid &&
            run.directory.canonicalFile.parentFile == root.canonicalFile && currentMatches(run)

    private fun currentMatches(run: PanvkWsiRun): Boolean = runCatching {
        val marker = childFile(root, CURRENT_FILE_NAME)
        if (!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val json = JSONObject(readBounded(marker, MAX_JSON_BYTES).toString(Charsets.UTF_8))
        require(stringField(json, "schema") == "wowforever.android-panvk-wsi")
        require(longField(json, "version") == 1L)
        PanvkWsiStatus.valueOf(stringField(json, "status"))
        stringField(json, "runId") == run.runId && longField(json, "generation") == run.generation &&
            intField(json, "pid") == run.pid && intField(json, "uid") == run.uid
    }.getOrDefault(false)

    private fun writeState(run: PanvkWsiRun, state: PanvkWsiState) {
        val json = JSONObject().put("schema", "wowforever.android-panvk-wsi").put("version", 1)
            .put("runId", state.runId).put("generation", state.generation).put("pid", state.pid).put("uid", state.uid)
            .put("startTicks", state.startTicks ?: JSONObject.NULL).put("status", state.status.name)
            .put("processName", state.processName ?: JSONObject.NULL).put("icdPath", state.icdPath ?: JSONObject.NULL)
            .put("nativeJournalPath", state.nativeJournalPath ?: JSONObject.NULL)
            .put("rootPath", state.rootPath ?: JSONObject.NULL).put("assetPath", state.assetPath)
            .put("assetSha256", state.assetSha256).put("assetSize", state.assetSize)
            .put("surfaceWidth", state.surfaceWidth ?: JSONObject.NULL).put("surfaceHeight", state.surfaceHeight ?: JSONObject.NULL)
            .put("holderWidth", state.holderWidth ?: JSONObject.NULL).put("holderHeight", state.holderHeight ?: JSONObject.NULL)
            .put("available", state.available ?: JSONObject.NULL).put("bounds", JSONArray(state.bounds))
            .put("nativeStatus", state.nativeStatus ?: JSONObject.NULL).put("error", state.error ?: JSONObject.NULL)
            .put("events", JSONArray(state.events))
        atomicWrite(childFile(run.directory, "state.json"), json.toString())
    }

    private fun writeCurrent(run: PanvkWsiRun, status: PanvkWsiStatus) {
        val json = JSONObject().put("schema", "wowforever.android-panvk-wsi").put("version", 1)
            .put("runId", run.runId).put("pid", run.pid).put("uid", run.uid).put("generation", run.generation)
            .put("status", status.name).put("statePath", childFile(run.directory, "state.json").absolutePath)
            .put("resultPath", childFile(run.directory, "result.json").absolutePath)
            .put("icdPath", if (status == PanvkWsiStatus.PREPARING) JSONObject.NULL else run.icdPath.absolutePath)
            .put("nativeJournalPath", run.nativeJournalPath.absolutePath)
        atomicWrite(childFile(root, CURRENT_FILE_NAME), json.toString())
    }

    private fun readStateLocked(run: PanvkWsiRun): PanvkWsiState? {
        val file = childFile(run.directory, "state.json")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val json = JSONObject(readBounded(file, MAX_JSON_BYTES).toString(Charsets.UTF_8))
        require(stringField(json, "schema") == "wowforever.android-panvk-wsi")
        require(longField(json, "version") == 1L)
        val boundsJson = json.optJSONArray("bounds")
        require(boundsJson == null || boundsJson.length() <= 4)
        return PanvkWsiState(
            runId = stringField(json, "runId"), generation = longField(json, "generation"), pid = intField(json, "pid"),
            startTicks = nullableLongField(json, "startTicks"),
            status = PanvkWsiStatus.valueOf(stringField(json, "status")),
            uid = intField(json, "uid"),
            processName = nullableStringField(json, "processName"),
            rootPath = nullableStringField(json, "rootPath"),
            assetPath = stringField(json, "assetPath"),
            assetSha256 = stringField(json, "assetSha256"),
            assetSize = longField(json, "assetSize"),
            icdPath = nullableStringField(json, "icdPath"),
            nativeJournalPath = nullableStringField(json, "nativeJournalPath"),
            surfaceWidth = nullableIntField(json, "surfaceWidth"), surfaceHeight = nullableIntField(json, "surfaceHeight"),
            holderWidth = nullableIntField(json, "holderWidth"), holderHeight = nullableIntField(json, "holderHeight"),
            available = nullableBooleanField(json, "available"),
            bounds = boundsJson?.let { (0 until it.length()).map { i -> intValue(it.get(i), "bounds[$i]") } } ?: emptyList(),
            nativeStatus = nullableStringField(json, "nativeStatus"),
            error = nullableStringField(json, "error"),
            events = json.optJSONArray("events")?.let { array ->
                require(array.length() <= 64)
                (0 until array.length()).map { i -> stringValue(array.get(i), "events[$i]") }
            } ?: emptyList(),
        ).also {
            require(it.generation > 0L && it.pid > 0 && it.uid >= 0 && (it.startTicks == null || it.startTicks > 0L))
            require(it.assetPath == ASSET_PATH && it.assetSha256 == APPROVED_SHA256 && it.assetSize == APPROVED_SIZE)
            require((it.surfaceWidth == null || it.surfaceWidth > 0) && (it.surfaceHeight == null || it.surfaceHeight > 0))
        }
    }

    private fun ensureDirectories(): File {
        ensureDirectory(base)
        var current = base
        for (part in listOf("forever", "controls", "panvk-wsi")) {
            current = childDirectory(current, part)
            ensureDirectory(current)
        }
        return current
    }

    private fun ensureDirectory(directory: File) {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "WSI managed path is not a real directory"
        }
        require(directory.canonicalFile.path.startsWith(base.path + File.separator) || directory == base) {
            "WSI managed path escaped files directory"
        }
    }

    private fun childDirectory(parent: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val child = File(parent, name)
        require(child.parentFile?.canonicalFile == parent.canonicalFile) { "WSI path escaped parent" }
        return child
    }

    private fun childFile(parent: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val child = File(parent, name)
        require(child.parentFile?.canonicalFile == parent.canonicalFile) { "WSI path escaped parent" }
        require(!Files.isSymbolicLink(child.toPath())) { "WSI leaf is a symlink" }
        return child
    }

    private fun newRunId(): String {
        var id: String
        do {
            id = "PWSI-" + UUID.randomUUID().toString().replace("-", "").lowercase()
        } while (Files.exists(File(root, id).toPath(), LinkOption.NOFOLLOW_LINKS))
        require(runPattern.matches(id))
        return id
    }

    private fun createExclusiveEmpty(file: File) {
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        setOwnerOnly(file)
    }

    private fun atomicWrite(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "WSI record exceeds bound" }
        require(!Files.isSymbolicLink(file.toPath()))
        val temp = File(file.parentFile, ".${file.name}.tmp-${UUID.randomUUID()}")
        require(temp.parentFile?.canonicalFile == file.parentFile.canonicalFile) { "WSI atomic temp escaped parent" }
        try {
            FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            setOwnerOnly(temp)
            require(!Files.isSymbolicLink(temp.toPath()))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp.toPath()) }
    }

    private fun setOwnerOnly(file: File) {
        require(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false))
        require(file.setReadable(true, true) && file.setWritable(true, true))
        try {
            android.system.Os.chmod(file.absolutePath, 0x180)
            require((android.system.Os.stat(file.absolutePath).st_mode and 0x1ff) == 0x180) {
                "WSI file mode is not 0600"
            }
        } catch (failure: Throwable) {
            try {
                val ownerOnly = setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                )
                Files.setPosixFilePermissions(file.toPath(), ownerOnly)
                require(Files.getPosixFilePermissions(file.toPath()) == ownerOnly) {
                    "WSI file mode is not 0600"
                }
            } catch (fallback: Throwable) {
                throw IllegalStateException("cannot establish 0600 WSI file mode", failure)
            }
        }
    }

    private data class FileIdentity(val fileKey: Any?, val size: Long)

    private fun copyAndVerifyAsset(target: File, onExclusiveCreate: () -> Unit) {
        var identity: FileIdentity? = null
        context.assets.open(ASSET_PATH).use { input ->
            val channel = FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)
            onExclusiveCreate()
            channel.use {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    var count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        buffer[0] = one.toByte()
                        count = 1
                    }
                    total += count
                    require(total <= APPROVED_SIZE) { "pinned ICD exceeds approved size" }
                    digest.update(buffer, 0, count)
                    val bytes = java.nio.ByteBuffer.wrap(buffer, 0, count)
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
                require(total == APPROVED_SIZE && digest.digest().toHex() == APPROVED_SHA256) {
                    "pinned ICD hash or size mismatch"
                }
                channel.force(true)
                identity = fileIdentity(target)
                require(identity!!.size == APPROVED_SIZE)
            }
        }
        setOwnerOnly(target)
        val createdIdentity = requireNotNull(identity)
        verifyPrivateFile(target, createdIdentity)
    }

    private fun verifyPrivateFile(file: File, expected: FileIdentity) {
        childFile(file.parentFile, file.name)
        val actual = fileIdentity(file)
        if (expected.fileKey != null) require(actual.fileKey == expected.fileKey) { "ICD identity changed" }
        require(actual.size == APPROVED_SIZE)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == APPROVED_SIZE)
            require(readDigest(channel, APPROVED_SIZE).toHex() == APPROVED_SHA256) { "ICD hash changed" }
        }
        requireOwnerOnly(file)
    }

    private fun fileIdentity(file: File): FileIdentity {
        val attrs = Files.readAttributes(
            file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        require(attrs.isRegularFile && !Files.isSymbolicLink(file.toPath())) { "WSI path is not a regular file" }
        return FileIdentity(attrs.fileKey(), attrs.size())
    }

    private fun requireOwnerOnly(file: File) {
        try {
            require((android.system.Os.stat(file.absolutePath).st_mode and 0x1ff) == 0x180)
        } catch (failure: Throwable) {
            val ownerOnly = setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            )
            try {
                require(Files.getPosixFilePermissions(file.toPath()) == ownerOnly)
            } catch (fallback: Throwable) {
                throw IllegalStateException("cannot verify 0600 WSI file mode", failure)
            }
        }
    }

    private fun readDigest(channel: FileChannel, expectedSize: Long): ByteArray {
        require(channel.size() <= expectedSize)
        channel.position(0L)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(COPY_BUFFER_BYTES)
        var total = 0L
        var zeroReads = 0
        while (total < expectedSize) {
            buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), expectedSize - total).toInt())
            var count = channel.read(buffer)
            if (count == 0) {
                if (++zeroReads > 8) error("bounded WSI read made no progress")
                val one = java.nio.ByteBuffer.allocate(1)
                count = channel.read(one)
                if (count > 0) { one.flip(); digest.update(one); total += count }
            } else if (count > 0) {
                zeroReads = 0
                buffer.flip(); digest.update(buffer); total += count
            }
            if (count < 0) break
        }
        require(total == expectedSize) { "bounded WSI read ended early" }
        return digest.digest()
    }

    private fun readBounded(file: File, limit: Int): ByteArray {
        childFile(file.parentFile, file.name)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            require(channel.size() <= limit.toLong() + 1L) { "WSI record exceeds bound" }
            val out = java.io.ByteArrayOutputStream()
            val buffer = java.nio.ByteBuffer.allocate(8192)
            var total = 0L
            var zeroReads = 0
            while (true) {
                buffer.clear()
                var count = channel.read(buffer)
                if (count == 0) {
                    if (++zeroReads > 8) error("bounded WSI read made no progress")
                    val one = java.nio.ByteBuffer.allocate(1)
                    count = channel.read(one)
                    if (count > 0) { one.flip(); out.write(one.get().toInt()); total += count }
                } else if (count > 0) {
                    zeroReads = 0
                    buffer.flip(); val bytes = ByteArray(count); buffer.get(bytes); out.write(bytes); total += count
                }
                require(total <= limit.toLong()) { "WSI record exceeds bound" }
                if (count < 0) break
            }
            return out.toByteArray()
        }
    }

    private fun stringField(json: JSONObject, key: String): String = stringValue(json.get(key), key)

    private fun nullableStringField(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else stringField(json, key)

    private fun longField(json: JSONObject, key: String): Long = longValue(json.get(key), key)

    private fun nullableLongField(json: JSONObject, key: String): Long? =
        if (json.isNull(key)) null else longField(json, key)

    private fun intField(json: JSONObject, key: String): Int = {
        val value = longField(json, key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is out of range" }
        value.toInt()
    }()

    private fun nullableIntField(json: JSONObject, key: String): Int? =
        if (json.isNull(key)) null else intField(json, key)

    private fun nullableBooleanField(json: JSONObject, key: String): Boolean? =
        if (json.isNull(key)) null else booleanField(json, key)

    private fun booleanField(json: JSONObject, key: String): Boolean = {
        val value = json.get(key)
        require(value is Boolean) { "$key is not boolean" }
        value
    }()

    private fun validateNativeBinding(native: JSONObject, run: PanvkWsiRun) {
        require(stringField(native, "schema") == "wowforever.android-panvk-wsi")
        require(longField(native, "version") == 1L)
        require(stringField(native, "runId") == run.runId)
        require(intField(native, "pid") == run.pid)
        require(intField(native, "uid") == run.uid)
        require(longField(native, "generation") == run.generation)
        val nativeScopeValid = booleanField(native, "scopeValid")
        require(stringField(native, "icdPath") == run.icdPath.absolutePath)
        val nativeStatus = stringField(native, "status")
        val loaded = booleanField(native, "loadLoaded")
        booleanField(native, "loadAttempted")
        for (field in listOf("icdOwner", "icdOwnerBefore", "icdOwnerAfter")) {
            val owner = stringField(native, field)
            require(owner.isEmpty() || icdOwnerMatches(owner, run.icdPath.absolutePath))
        }
        val beforeHash = stringField(native, "icdSha256")
        val afterHash = stringField(native, "icdAfterSha256")
        require(beforeHash.isEmpty() || beforeHash == APPROVED_SHA256)
        require(afterHash.isEmpty() || afterHash == APPROVED_SHA256)
        require(longField(native, "icdBytes") in 0..APPROVED_SIZE)
        if (loaded) require(beforeHash == APPROVED_SHA256 && longField(native, "icdBytes") == APPROVED_SIZE)
        requireNativeSurface(native.getJSONObject("surface"))
        val frames = native.getJSONObject("frames")
        require(intField(frames, "max") == 3)
        require(intField(frames, "count") in 0..3)
        require(native.get("apiPresentSuccess") is Boolean)
        require(native.get("cancelRequested") is Boolean)
        val cleanup = native.getJSONObject("cleanup")
        require(cleanup.get("queueWaitIdleCalled") is Boolean)
        require(cleanup.get("complete") is Boolean)
        require(cleanup.get("unknownResourceRetained") is Boolean)
        require(!booleanField(native, "androidPresentationObserved"))
        require(!booleanField(native, "wine"))
        require(!booleanField(native, "d3d"))
        require(!booleanField(native, "wow"))
        require(nativeStatus in NATIVE_STATUSES)
        require(intField(native, "nativeExit") in 0..1)
        val pfns = native.getJSONArray("pfns")
        require(pfns.length() <= 64)
        val names = HashSet<String>()
        for (index in 0 until pfns.length()) {
            val pfn = pfns.getJSONObject(index)
            require(names.add(stringField(pfn, "name")))
            require(booleanField(pfn, "ownerExact") && icdOwnerMatches(stringField(pfn, "path"), run.icdPath.absolutePath))
        }
        val journal = native.getJSONObject("journal")
        require(intField(journal, "rowCapacity") == 256 && intField(journal, "rows") in 0..256)
        require(intField(journal, "byteCapacity") == MAX_JSON_BYTES && intField(journal, "bytes") in 0..MAX_JSON_BYTES)
        if (nativeStatus == "API_PRESENT_COMPLETED") {
            val owners = listOf("icdOwner", "icdOwnerBefore", "icdOwnerAfter").map { stringField(native, it) }
            require(owners.all { it.isNotEmpty() } && owners.distinct().size == 1) { "completed native ICD owners disagree" }
            require(nativeScopeValid && booleanField(native, "procValid") && loaded && booleanField(native, "clockValid"))
            require(booleanField(native, "icdIdentityStable") && afterHash == APPROVED_SHA256)
            require(icdOwnerMatches(stringField(native, "icdOwnerAfter"), run.icdPath.absolutePath))
            require(intField(native, "nativeExit") == 0 && booleanField(native, "apiPresentSuccess"))
            require(intField(frames, "count") == 3 && intField(frames, "presentSuccessCount") == 3)
            val swapCount = intField(native.getJSONObject("swapchain"), "imageCount")
            require(swapCount in 2..4)
            val frameItems = frames.getJSONArray("items")
            require(frameItems.length() == 3)
            for (index in 0..2) {
                val frame = frameItems.getJSONObject(index)
                require(intField(frame, "slot") == index && intField(frame, "imageIndex") in 0 until swapCount)
                for (stage in listOf("acquire", "submit", "present", "fence")) {
                    require(booleanField(frame, stage + "Called") && intField(frame, stage + "Result") == 0)
                }
            }
            require(booleanField(cleanup, "complete") && booleanField(cleanup, "queueRetired") && !booleanField(cleanup, "unknownResourceRetained"))
            require(booleanField(cleanup, "queueWaitIdleCalled") && intField(cleanup, "queueWaitIdleResult") == 0)
            require(!booleanField(journal, "ioFailed") && !booleanField(native, "cancelRequested"))
        } else require(intField(native, "nativeExit") == 1)
    }

    internal fun icdOwnerMatches(actual: String, requested: String): Boolean {
        if (actual == requested) return true
        val packageRoots = listOf("/data/data/com.wowforever.arm64probe/", "/data/user/0/com.wowforever.arm64probe/")
        for (root in packageRoots) if (requested.startsWith(root)) {
            return packageRoots.any { actual == it + requested.removePrefix(root) }
        }
        return false
    }

    private fun requireNativeSurface(surface: JSONObject) {
        require(surface.get("fromSurface") is Boolean)
        require(intField(surface, "width") in 0..320)
        require(intField(surface, "height") in 0..240)
        nullableIntField(surface, "createResult")
    }

    private fun stringValue(value: Any, key: String): String {
        require(value is String) { "$key is not string" }
        return value
    }

    private fun longValue(value: Any, key: String): Long {
        require(value is Number && value !is Float && value !is Double) { "$key is not integer" }
        return value.toLong()
    }

    private fun intValue(value: Any, key: String): Int {
        val long = longValue(value, key)
        require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is out of range" }
        return long.toInt()
    }

    private fun processStartTicks(pid: Int): Long? = runCatching {
        val path = Paths.get("/proc/$pid/stat")
        val bytes = java.io.ByteArrayOutputStream()
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(1024)
            var total = 0
            while (true) {
                buffer.clear()
                var count = channel.read(buffer)
                if (count == 0) {
                    val one = java.nio.ByteBuffer.allocate(1)
                    count = channel.read(one)
                    if (count > 0) { one.flip(); bytes.write(one.get().toInt()) }
                }
                if (count < 0) break
                require(count > 0 && total + count <= 16 * 1024)
                if (buffer.position() > 0) {
                    buffer.flip()
                    val chunk = ByteArray(buffer.remaining()); buffer.get(chunk); bytes.write(chunk)
                }
                total += count
            }
        }
        val text = bytes.toString(Charsets.US_ASCII.name())
        val close = text.lastIndexOf(") ")
        require(close > 0)
        text.substring(close + 2).trim().split(Regex("\\s+"))[19].toLong().also { require(it > 0) }
    }.getOrNull()

    private fun boundedText(value: String): String = value.replace(Regex("[\\r\\n\\u0000]"), " ").take(256)
    private fun boundedError(failure: Throwable, ownedFileCreated: Boolean = false): String =
        boundedText((if (ownedFileCreated) "owned ICD write retained: " else "") +
            (failure.message ?: failure::class.java.simpleName))
    private fun boundedEvents(events: List<String>): List<String> = events.takeLast(64).map(::boundedText)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val TERMINAL = setOf(PanvkWsiStatus.RESULT, PanvkWsiStatus.CANCELLED, PanvkWsiStatus.UNKNOWN, PanvkWsiStatus.ERROR)
    private val NATIVE_STATUSES = setOf("API_PRESENT_COMPLETED", "VK_ERROR", "UNSUPPORTED", "CANCELLED", "UNKNOWN")
}
