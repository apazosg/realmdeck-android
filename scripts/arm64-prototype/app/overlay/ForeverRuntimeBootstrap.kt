package app.gamenative

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.Callback
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import com.winlator.xenvironment.ImageFsInstaller.LATEST_VERSION
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val DEFAULT_INSTALL_TIMEOUT_MS = 30L * 60L * 1000L
private const val INSTALL_POLL_MS = 250L
private const val ELF_CLASS_64 = 2
private const val ELF_DATA_LSB = 1
private const val ELF_MACHINE_AARCH64 = 183

/**
 * The dependency bootstrap which must complete before the first Wine process.
 *
 * This deliberately delegates ImageFS installation to GameNative.  In
 * particular, this class does not copy a single redirect library: the stock
 * installer owns the complete Bionic image, guest libraries, and wrapper ICD.
 */
class ForeverRuntimeBootstrap(
    private val backend: Backend = ProductionBackend,
    private val installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS,
) {
    interface Backend {
        fun isImageFsInstallable(context: Context, variant: String): Boolean

        suspend fun downloadImageFs(
            context: Context,
            variant: String,
            progress: (Float) -> Unit,
        )

        fun installIfNeeded(
            context: Context,
            container: Container,
            progress: (Int) -> Unit,
        ): Future<Boolean>

        fun imageFs(context: Context): ImageFs

        fun evshim(context: Context): File
    }

    /** Ensures a complete, current Bionic ImageFS and its launch-critical files. */
    suspend fun ensure(
        context: Context,
        container: Container,
        progress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        require(container.containerVariant == Container.BIONIC) {
            "Forever ARM64 bootstrap requires the Bionic container variant"
        }
        checkCancelled(cancelled)
        validateManagedRoot(context, backend.imageFs(context).rootDir)
        val beforeVariant = readRecordedVariant(backend.imageFs(context).rootDir)
        require(beforeVariant.isEmpty() || beforeVariant == container.containerVariant) {
            "ImageFS variant $beforeVariant does not match ${container.containerVariant}"
        }

        // Acquire the Future before any backend call. ImageFsInstaller performs its legacy
        // migration synchronously before returning its own Future, so tracking only that inner
        // Future would leave a cancellation window with unowned prefix mutation.
        val operation = ownedOperation(context, container, progress)
        try {
            val installed = awaitOperation(operation, cancelled)
            check(installed) { "GameNative ImageFS installation failed" }
            checkCancelled(cancelled)

            validatePostconditions(context, container, backend.imageFs(context), backend.evshim(context))
            checkCancelled(cancelled)
            safeProgress(progress, "runtime: Bionic ImageFS ready")
        } finally {
            releaseCompletedOperation(operation)
        }
    }

    /**
     * Generation gate used before runtime profiles are imported. A cancelled caller may have
     * returned while the owned operation is still migrating/extracting; a new generation must
     * join that operation before touching the same ImageFS, home, or shared Proton roots.
     */
    suspend fun awaitPending(cancelled: () -> Boolean = { false }) {
        val pending = synchronized(this) { activeOperation } ?: return
        try {
            val installed = awaitOperation(pending, cancelled)
            check(installed) { "previous Bionic ImageFS operation failed" }
        } catch (failure: Throwable) {
            releaseCompletedOperation(pending)
            throw failure
        }
    }

    private fun readRecordedVariant(root: File): String {
        val marker = File(root, ".winlator/.variant").toPath()
        val bytes = ByteArray(129)
        var count = 0
        try {
            Files.newInputStream(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                while (count < bytes.size) {
                    val n = input.read(bytes, count, bytes.size - count)
                    if (n < 0) break
                    if (n == 0) {
                        val value = input.read()
                        if (value < 0) break
                        bytes[count++] = value.toByte()
                    } else count += n
                }
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            return ""
        }
        require(count <= 128) { "ImageFS variant marker exceeds bounded metadata read" }
        return String(bytes, 0, count, Charsets.UTF_8).trim()
    }

    private fun ownedOperation(
        context: Context,
        container: Container,
        progress: (String) -> Unit,
    ): OwnedOperation = synchronized(this) {
        val current = activeOperation
        if (current != null) {
            current
        } else {
            val startedAt = System.nanoTime()
            val task = FutureTask<Boolean>(java.util.concurrent.Callable {
                runOwnedOperation(context, container, progress)
            })
            val created = OwnedOperation(task, startedAt + TimeUnit.MILLISECONDS.toNanos(installTimeoutMs.coerceAtLeast(1L)))
            // Publish before execute: the task may begin immediately and must already be
            // visible to a concurrent generation before it invokes download/migration.
            activeOperation = created
            operationExecutor.execute(task)
            created
        }
    }

    /**
     * This is the one owned operation for download, legacy migration, and extraction. Future
     * cancellation is intentionally not attempted: native extraction can continue on another
     * executor. A cancelled caller returns without launching, while this instance retains the
     * operation so a retry cannot start a parallel download/migration/extraction.
     */
    private suspend fun awaitOperation(operation: OwnedOperation, cancelled: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            while (!operation.future.isDone) {
                checkCancelled(cancelled)
                val remainingNanos = operation.deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    throw IllegalStateException("Bionic ImageFS operation timed out; retry waits for the owned operation")
                }
                try {
                    val waitMs = minOf(
                        INSTALL_POLL_MS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                    )
                    operation.future.get(waitMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Re-check cancellation and the operation-start deadline on the next poll.
                } catch (_: ExecutionException) {
                    return@withContext finishOwnedOperation(operation)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("runtime bootstrap wait interrupted")
                }
            }
            checkCancelled(cancelled)
            finishOwnedOperation(operation)
        }

    private fun runOwnedOperation(
        context: Context,
        container: Container,
        progress: (String) -> Unit,
    ): Boolean {
        if (!backend.isImageFsInstallable(context, container.containerVariant)) {
            safeProgress(progress, "runtime: downloading Bionic ImageFS")
            runBlocking {
                backend.downloadImageFs(context, container.containerVariant) { fraction ->
                    safeProgress(progress, "runtime: ImageFS download ${(fraction.coerceIn(0f, 1f) * 100).toInt()}%")
                }
            }
        }

        safeProgress(progress, "runtime: installing and validating Bionic ImageFS")
        // The stock call migrates legacy directories synchronously before returning this inner
        // Future. It is inside the already-published outer operation and is awaited before the
        // outer Future can complete.
        val installer = backend.installIfNeeded(context, container) { value ->
            safeProgress(progress, "runtime: ImageFS install $value%")
        }
        return installer.get()
    }

    private fun finishOwnedOperation(operation: OwnedOperation): Boolean {
        try {
            return operation.future.get()
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    private fun releaseCompletedOperation(operation: OwnedOperation) {
        synchronized(this) {
            if (activeOperation === operation && operation.future.isDone) activeOperation = null
        }
    }

    private fun safeProgress(progress: (String) -> Unit, message: String) {
        runCatching { progress(message) }
    }

    private fun validatePostconditions(context: Context, container: Container, imageFs: ImageFs, evshim: File) {
        val root = validateManagedRoot(context, imageFs.rootDir)
        require(imageFs.isValid() && imageFs.version >= LATEST_VERSION) {
            "ImageFS is not valid at the required version $LATEST_VERSION"
        }
        val recordedVariant = imageFs.variant
        require(recordedVariant.isEmpty() || recordedVariant == container.containerVariant) {
            "ImageFS variant $recordedVariant does not match ${container.containerVariant}"
        }

        val sysvshm = criticalFile(root, "usr/lib/libandroid-sysvshm.so")
        val redirectWx = criticalFile(root, "usr/lib/libredirect-bionic-wx.so")
        criticalElf(sysvshm, "libandroid-sysvshm.so")
        criticalElf(redirectWx, "libredirect-bionic-wx.so")
        // The stock XServer graphics setup installs the selected wrapper/ICD
        // afterwards. Requiring its output at this earlier stage would reject
        // a valid fresh ImageFS before its producer has run.

        val nativeEvshim = evshim.canonicalFile
        require(nativeEvshim.isFile && nativeEvshim.length() > 0) {
            "app-native libevshim.so is missing or empty"
        }
        criticalElf(nativeEvshim, "app-native libevshim.so")
        // The pinned stock installer writes .img_version but the current Bionic
        // archive does not include .variant. Commit its selected variant only
        // after the owned installer and all launch-critical checks succeeded.
        if (recordedVariant.isEmpty()) {
            val marker = File(root, ".winlator/.variant")
            require(!Files.isSymbolicLink(marker.toPath())) { "ImageFS variant marker is a symlink" }
            imageFs.createVariantFile(container.containerVariant)
            require(imageFs.variant == container.containerVariant) { "ImageFS variant marker could not be persisted" }
        }
    }

    private fun validateManagedRoot(context: Context, candidate: File): File {
        val filesRoot = context.filesDir.canonicalFile
        require(!Files.isSymbolicLink(candidate.toPath())) {
            "ImageFS root is a symlink"
        }
        val root = candidate.canonicalFile
        require(root != filesRoot && root.toPath().startsWith(filesRoot.toPath())) {
            "ImageFS root is outside app-private files"
        }
        require(!root.exists() || root.isDirectory) {
            "ImageFS root is not a directory"
        }
        val sharedRoot = validateOwnedSharedDirectory(File(filesRoot, "imagefs_shared"), filesRoot, "imagefs_shared")
        val sharedHome = validateOwnedSharedDirectory(File(sharedRoot, "home"), sharedRoot, "imagefs_shared/home")
        validateOwnedSharedDirectory(File(sharedRoot, "proton"), sharedRoot, "imagefs_shared/proton")
        for (marker in listOf(".img_version", ".variant")) {
            require(!Files.isSymbolicLink(File(root, ".winlator/$marker").toPath())) {
                "ImageFS state marker is a symlink: $marker"
            }
        }

        // Check before the upstream installer can clear/migrate anything. Mutable roots must not
        // resolve to siblings, even when those siblings are still inside app-private files.
        for (name in listOf("usr", "opt", ".winlator")) {
            val target = File(root, name).canonicalFile
            require(target.toPath().startsWith(filesRoot.toPath())) {
                "ImageFS child escaped app-private files: $name"
            }
            require(target.toPath().startsWith(root.toPath()) && target != root) {
                "ImageFS child escaped its private root: $name"
            }
            require(!target.exists() || target.isDirectory) {
                "ImageFS mutable child is not a directory: $name"
            }
        }
        val homePath = File(root, "home")
        val home = homePath.canonicalFile
        require(home.toPath().startsWith(filesRoot.toPath())) {
            "ImageFS child escaped app-private files: home"
        }
        if (home == sharedHome) {
            // Stock ImageFsInstaller owns this exact shared-home symlink.
        } else {
            require(home.toPath().startsWith(root.toPath()) && home != root && !Files.isSymbolicLink(homePath.toPath())) {
                "ImageFS home escaped its private root"
            }
            require(!home.exists() || home.isDirectory) {
                "ImageFS home is not a directory"
            }
            require(!home.isDirectory || !sharedHome.exists()) {
                "ImageFS legacy/shared home conflict; refusing destructive stock migration"
            }
        }
        // The stock migrator deletes a real legacy Proton directory if a shared
        // one with the same name already exists. Do not assume these are identical.
        val opt = File(root, "opt")
        if (opt.isDirectory) {
            val entries = requireNotNull(opt.listFiles()) { "ImageFS opt directory is unreadable" }
            for (entry in entries) {
                if (entry.name.startsWith("proton-") && entry.isDirectory && !Files.isSymbolicLink(entry.toPath())) {
                    val contents = requireNotNull(entry.listFiles()) { "ImageFS legacy Proton directory is unreadable" }
                    // Stock Bionic archives recreate empty default Proton placeholders.
                    // Migrating/deleting an empty directory cannot discard profile bytes.
                    require(!File(File(sharedRoot, "proton"), entry.name).exists() || contents.isEmpty()) {
                        "ImageFS legacy/shared Proton conflict; refusing destructive stock migration"
                    }
                }
            }
        }
        return root
    }

    private fun validateOwnedSharedDirectory(path: File, parent: File, label: String): File {
        if (!path.exists() && !Files.isSymbolicLink(path.toPath())) return path.canonicalFile
        require(!Files.isSymbolicLink(path.toPath())) {
            "$label is a symlink"
        }
        val expected = File(parent.canonicalFile, path.name).canonicalFile
        val actual = path.canonicalFile
        require(actual == expected && actual.toPath().startsWith(parent.canonicalFile.toPath()) && actual.isDirectory) {
            "$label escaped its private root"
        }
        return actual
    }

    private fun criticalFile(root: File, relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root) {
            "ImageFS critical path escaped its private root: $relative"
        }
        require(file.isFile && file.length() > 0) {
            "ImageFS critical file is missing or empty: $relative"
        }
        return file
    }

    private fun criticalElf(file: File, label: String) {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                require(count > 0) { "$label is not a complete ELF file" }
                offset += count
            }
        }
        require(
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4].toInt() == ELF_CLASS_64 && header[5].toInt() == ELF_DATA_LSB,
        ) { "$label is not a little-endian 64-bit ELF" }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        require(machine == ELF_MACHINE_AARCH64) { "$label is not an AArch64 ELF" }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("runtime bootstrap cancelled")
    }

    private data class OwnedOperation(
        val future: Future<Boolean>,
        val deadlineNanos: Long,
    )

    private val operationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forever-runtime-bootstrap").apply { isDaemon = true }
    }
    private var activeOperation: OwnedOperation? = null

    private object ProductionBackend : Backend {
        override fun isImageFsInstallable(context: Context, variant: String): Boolean =
            app.gamenative.service.SteamService.isImageFsInstallable(context, variant)

        override suspend fun downloadImageFs(context: Context, variant: String, progress: (Float) -> Unit) {
            require(variant == Container.BIONIC) { "unsupported ImageFS variant: $variant" }
            app.gamenative.service.SteamService.fetchFileWithFallback(
                fileName = "imagefs_bionic.txz",
                dest = File(context.filesDir, "imagefs_bionic.txz"),
                context = context,
                onProgress = progress,
            )
        }

        override fun installIfNeeded(
            context: Context,
            container: Container,
            progress: (Int) -> Unit,
        ): Future<Boolean> = ImageFsInstaller.installIfNeededFuture(
            context,
            context.assets,
            container,
            object : Callback<Int> {
                override fun call(value: Int) = progress(value)
            },
        )

        override fun imageFs(context: Context): ImageFs = ImageFs.find(context)

        override fun evshim(context: Context): File =
            File(context.applicationInfo.nativeLibraryDir, "libevshim.so")
    }

}
