package app.gamenative

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Physical capability gate for the cold-replay reader.
 *
 * Runs once per [ForeverClientUpdateService.execute] inside the storage IO
 * lease, before any writer exists, on a fresh UUID scratch directory owned by
 * this invocation. The scratch lives directly under the admitted app
 * namespace, outside the client root, `CustomGames`, `Data`, and any
 * checkpoint; the NOFOLLOW probe lives in a separate fresh UUID directory
 * under the app-private parent. Sizes are a few KiB per file: this proves
 * capability, never performance.
 *
 * The probe exercises the SAME reader implementation that production would
 * use, over its actual namespace: regular open plus positional reads at
 * alternating offsets, an atomic replace under a held FD, an
 * unlink/recreate under a held FD, and symlink rejection with a direct
 * `O_NOFOLLOW` open proving `ELOOP`. Every binding check must fail with
 * [CascReplayBindingMismatchException]; any other error, or a missed swap,
 * fails the gate. Cancellation propagates, nothing falls back, no proof is
 * persisted, and cleanup removes only entries this probe created.
 */
internal object ForeverCascReplayPreflight {
    private val diagnostics = Logger.getLogger("ForeverClientUpdate")
    private const val SCRATCH_PREFIX = ".forever-casc-preflight-"
    private const val NOFOLLOW_PREFIX = ".forever-casc-preflight-nofollow-"
    private const val PROBE_BYTES = 4096
    private const val MAX_LOG_CHARS = 300

    internal fun run(
        admittedAppFilesDirectory: File,
        privateDirectory: File,
        cancelled: () -> Boolean,
        readerFactory: ((() -> Boolean) -> CascReplayReader) = { c -> ForeverCascReplayReaderAndroid(c) },
        writeExclusive: (String, ByteArray, () -> Boolean) -> Unit = ::osWriteExclusive,
        requireNoFollow: (File) -> Unit = ::osRequireNoFollowRejectsSymlink,
        createSymlink: (String, String) -> Unit = ::osCreateSymlink,
    ): Long {
        val startedMs = System.currentTimeMillis()
        var appRoot: Path? = null
        var privateRoot: Path? = null
        try {
            checkCancelled(cancelled)
            appRoot = admittedAppFilesDirectory.absoluteFile.toPath().normalize()
            privateRoot = privateDirectory.absoluteFile.toPath().normalize()
            requireUsableDirectory(appRoot, "USB app namespace")
            requireUsableDirectory(privateRoot, "private preflight parent")
            checkCancelled(cancelled)
            val appFilesRoot: Path = requireNotNull(appRoot)
            val privateFilesRoot: Path = requireNotNull(privateRoot)
            val scratch = appFilesRoot.resolve(SCRATCH_PREFIX + UUID.randomUUID())
            require(scratch.parent == appFilesRoot) { "preflight scratch escaped its admitted parent" }
            val tracked = ArrayDeque<Path>()
            val readers = ArrayList<CascReplayReader>()
            var primary: Throwable? = null
            try {
                createExclusiveDirectory(scratch, tracked, cancelled)
                probeRegularAndSwaps(scratch, tracked, readers, cancelled, readerFactory, writeExclusive)
                checkCancelled(cancelled)
                probeNoFollow(privateFilesRoot, tracked, readers, cancelled, readerFactory, writeExclusive, requireNoFollow, createSymlink)
                checkCancelled(cancelled)
                requireOnlyTracked(scratch, tracked)
            } catch (primaryFailure: Throwable) {
                primary = primaryFailure
                throw primaryFailure
            } finally {
                cleanup(tracked, readers, primary)
            }
            val elapsedMs = System.currentTimeMillis() - startedMs
            diagnostics.info("CASC replay preflight result=PASS elapsedMs=$elapsedMs probeBytes=$PROBE_BYTES")
            return elapsedMs
        } catch (cancelledFailure: CancellationException) {
            diagnostics.info("CASC replay preflight result=CANCELLED")
            throw cancelledFailure
        } catch (failure: Exception) {
            diagnostics.warning("CASC replay preflight result=FAIL reason=${sanitize(failure, appRoot, privateRoot)}")
            throw failure
        }
    }

    private fun probeRegularAndSwaps(
        scratch: Path,
        tracked: ArrayDeque<Path>,
        readers: MutableList<CascReplayReader>,
        cancelled: () -> Boolean,
        readerFactory: ((() -> Boolean) -> CascReplayReader),
        writeExclusive: (String, ByteArray, () -> Boolean) -> Unit,
    ) {
        val contentA = probeBytes(0xa5)
        val contentB = probeBytes(0x5a)
        // Scenario 1: regular capability, then an atomic replace under the
        // held FD. Both files are created owned and tracked; replacing our
        // own A with our own B needs no backup name and keeps one rename.
        val fileA = childPath(scratch, "segment-a.bin")
        val fileB = childPath(scratch, "segment-b.bin")
        writeExclusive(fileA.toString(), contentA, cancelled)
        tracked.addLast(fileA)
        writeExclusive(fileB.toString(), contentB, cancelled)
        tracked.addLast(fileB)
        useOwnedReader(readers, readerFactory, cancelled) { reader ->
            checkCancelled(cancelled)
            reader.ensureSegment(fileA.toFile())
            reader.checkBinding(contentA.size.toLong())
            // The exact-size guard must accept the frozen probe size before
            // any swap: same device/inode/kind agreement as the per-object
            // guard, plus both sizes exactly equal, not merely covering.
            reader.checkSealedBinding(contentA.size.toLong())
            verifyReads(reader, contentA, cancelled)
            reader.checkBinding(contentA.size.toLong())
            reader.checkSealedBinding(contentA.size.toLong())
            checkCancelled(cancelled)
            Files.move(fileB, fileA, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertOldReads(reader, contentA, cancelled)
            requireFreshContent(fileA, contentB, cancelled)
            requireBindingMismatch(reader, contentA.size.toLong())
        }
        // Scenario 2: the bound path is deleted and recreated while the old
        // FD stays open. The old inode cannot be recycled while open, so the
        // fresh path must disagree with the held FD.
        checkCancelled(cancelled)
        val fileC = childPath(scratch, "segment-c.bin")
        writeExclusive(fileC.toString(), contentA, cancelled)
        tracked.addLast(fileC)
        useOwnedReader(readers, readerFactory, cancelled) { second ->
            checkCancelled(cancelled)
            second.ensureSegment(fileC.toFile())
            assertOldReads(second, contentA, cancelled)
            checkCancelled(cancelled)
            Files.delete(fileC)
            writeExclusive(fileC.toString(), contentB, cancelled)
            assertOldReads(second, contentA, cancelled)
            requireFreshContent(fileC, contentB, cancelled)
            requireBindingMismatch(second, contentA.size.toLong())
        }
        checkCancelled(cancelled)
        requireOnlyTracked(scratch, tracked)
    }

    private fun probeNoFollow(
        privateRoot: Path,
        tracked: ArrayDeque<Path>,
        readers: MutableList<CascReplayReader>,
        cancelled: () -> Boolean,
        readerFactory: ((() -> Boolean) -> CascReplayReader),
        writeExclusive: (String, ByteArray, () -> Boolean) -> Unit,
        requireNoFollow: (File) -> Unit,
        createSymlink: (String, String) -> Unit,
    ) {
        checkCancelled(cancelled)
        val probeDir = privateRoot.resolve(NOFOLLOW_PREFIX + UUID.randomUUID())
        require(probeDir.parent == privateRoot) { "preflight NOFOLLOW probe escaped its private parent" }
        createExclusiveDirectory(probeDir, tracked, cancelled)
        val target = childPath(probeDir, "target.bin")
        writeExclusive(target.toString(), probeBytes(0x3c), cancelled)
        tracked.addLast(target)
        val link = childPath(probeDir, "link.bin")
        checkCancelled(cancelled)
        createSymlink(target.toString(), link.toString())
        tracked.addLast(link)
        useOwnedReader(readers, readerFactory, cancelled) { reader ->
            checkCancelled(cancelled)
            var rejected = false
            try {
                reader.ensureSegment(link.toFile())
            } catch (cancelledFailure: CancellationException) {
                throw cancelledFailure
            } catch (_: IllegalStateException) {
                rejected = true
            }
            if (!rejected) throw IllegalStateException("preflight reader followed a private symlink")
            checkCancelled(cancelled)
            requireNoFollow(link.toFile())
        }
        checkCancelled(cancelled)
        requireOnlyTracked(probeDir, tracked)
    }

    private fun verifyReads(reader: CascReplayReader, expected: ByteArray, cancelled: () -> Boolean) {
        val size = expected.size
        // Alternating, out-of-order coverage including prefix and tail.
        readSlice(reader, expected, 3072, 1024, cancelled)
        readSlice(reader, expected, 0, 64, cancelled)
        readSlice(reader, expected, 512, 2048, cancelled)
        readSlice(reader, expected, size - 128, 128, cancelled)
        readSlice(reader, expected, 128, 1024, cancelled)
        checkCancelled(cancelled)
        if (reader.readAt(ByteArray(16), 0, 16, size.toLong()) != 0) {
            throw IllegalStateException("preflight reader did not report end-of-file at its bound")
        }
    }

    private fun readSlice(reader: CascReplayReader, expected: ByteArray, position: Int, length: Int, cancelled: () -> Boolean) {
        val buffer = ByteArray(length)
        var served = 0
        while (served < length) {
            checkCancelled(cancelled)
            val count = reader.readAt(buffer, served, length - served, (position + served).toLong())
            if (count < 0) throw IllegalStateException("preflight reader reported an invalid read")
            if (count == 0) throw IllegalStateException("preflight reader truncated its probe file")
            served += count
        }
        if (!buffer.contentEquals(expected.copyOfRange(position, position + length))) {
            throw IllegalStateException("preflight reader returned unexpected probe bytes")
        }
    }

    private fun assertOldReads(reader: CascReplayReader, expectedOld: ByteArray, cancelled: () -> Boolean) {
        // The pre-swap FD must still serve the old bytes; this, together with
        // the fresh path holding the new bytes, proves only a binding-mismatch
        // verdict is acceptable below.
        checkCancelled(cancelled)
        val buffer = ByteArray(64)
        val served = reader.readAt(buffer, 0, buffer.size, 0L)
        if (served != buffer.size || !buffer.contentEquals(expectedOld.copyOfRange(0, buffer.size))) {
            throw IllegalStateException("preflight reader lost its held file identity")
        }
    }

    private fun requireBindingMismatch(reader: CascReplayReader, endExclusive: Long) {
        try {
            reader.checkBinding(endExclusive)
        } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (mismatch: CascReplayBindingMismatchException) {
            // The per-object guard caught the swap; the sealed guard must
            // agree on the same held FD, or the exact-size proof is weaker
            // than the covering proof it is meant to strengthen.
            try {
                reader.checkSealedBinding(endExclusive)
            } catch (sealedMismatch: CascReplayBindingMismatchException) {
                return
            } catch (cancelledFailure: CancellationException) {
                throw cancelledFailure
            } catch (other: IllegalStateException) {
                throw IllegalStateException("preflight sealed swap failed without a binding proof", other)
            }
            throw IllegalStateException("preflight sealed swap went undetected by its reader")
        } catch (other: IllegalStateException) {
            throw IllegalStateException("preflight swap failed without a binding proof", other)
        }
        throw IllegalStateException("preflight swap went undetected by its reader")
    }

    private fun requireFreshContent(path: Path, expected: ByteArray, cancelled: () -> Boolean) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("preflight probe path is not a regular file")
        }
        // Bounded by construction: at most expected.size + 1 bytes are ever
        // allocated, so an oversized fresh file fails here instead of being
        // materialized. Reaching exactly expected.size below EOF proves both
        // the content and its end within the same bound.
        val bound = ByteArray(expected.size + 1)
        var total = 0
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (total < bound.size) {
                checkCancelled(cancelled)
                val count = input.read(bound, total, bound.size - total)
                if (count < 0) break
                if (count == 0) throw IllegalStateException("preflight probe read made no progress")
                total += count
            }
        }
        if (total != expected.size) {
            throw IllegalStateException("preflight probe path changed size")
        }
        if (!bound.copyOf(total).contentEquals(expected)) {
            throw IllegalStateException("preflight probe path holds unexpected bytes")
        }
    }

    private fun requireOnlyTracked(directory: Path, tracked: ArrayDeque<Path>) {
        val known = tracked.toSet()
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) {
                if (child !in known) throw IllegalStateException("preflight scratch holds an unknown entry")
            }
        }
    }

    private fun childPath(directory: Path, name: String): Path {
        require(name.isNotEmpty() && '/' !in name && '\u0000' !in name && name != "." && name != "..") {
            "preflight child name is unsafe"
        }
        val child = directory.resolve(name).normalize()
        require(child.parent == directory) { "preflight child escaped its scratch directory" }
        return child
    }

    private fun createExclusiveDirectory(directory: Path, tracked: ArrayDeque<Path>, cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        try {
            Files.createDirectory(directory)
        } catch (failure: java.nio.file.FileAlreadyExistsException) {
            throw IllegalStateException("preflight scratch already exists", failure)
        }
        tracked.addLast(directory)
    }

    private fun requireUsableDirectory(directory: Path, what: String) {
        rejectSymlinkAncestors(directory)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("preflight $what is not a usable directory")
        }
    }

    private fun rejectSymlinkAncestors(path: Path) {
        var current = path.root ?: throw IllegalArgumentException("preflight path is not absolute")
        if (Files.isSymbolicLink(current)) throw IllegalStateException("preflight path contains a symbolic link")
        for (part in path) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IllegalStateException("preflight path contains a symbolic link")
        }
    }

    /**
     * Runs [body] with one owned reader. A body failure (including
     * cancellation and binding verdicts) always propagates as the primary;
     * a close failure is suppressed into it and never masks it. A close
     * failure after a successful body fails the gate outright. The reader is
     * detached exactly once and never retried.
     */
    private fun <T> useOwnedReader(
        readers: MutableList<CascReplayReader>,
        readerFactory: ((() -> Boolean) -> CascReplayReader),
        cancelled: () -> Boolean,
        body: (CascReplayReader) -> T,
    ): T {
        val reader = readerFactory(cancelled).also { readers += it }
        var primary: Throwable? = null
        try {
            return body(reader)
        } catch (bodyFailure: Throwable) {
            primary = bodyFailure
            throw bodyFailure
        } finally {
            try {
                reader.close()
            } catch (closeFailure: Throwable) {
                if (primary == null) throw closeFailure
                primary.addSuppressed(closeFailure)
            } finally {
                readers.remove(reader)
            }
        }
    }

    private fun cleanup(tracked: ArrayDeque<Path>, readers: List<CascReplayReader>, primary: Throwable?) {
        var cleanupFailure: Throwable? = null
        for (reader in readers.toList()) {
            try {
                reader.close()
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
            }
        }
        while (tracked.isNotEmpty()) {
            val path = tracked.removeLast()
            try {
                Files.deleteIfExists(path)
            } catch (_: java.nio.file.NoSuchFileException) {
                // Already gone (e.g. a rename-swap source); tracked paths are
                // still all accounted for, so keep removing the rest.
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
            }
        }
        if (cleanupFailure != null) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }

    private fun probeBytes(seed: Int): ByteArray = ByteArray(PROBE_BYTES) { ((it * 31 + seed) and 0xff).toByte() }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client update operation cancelled")
    }

    private fun sanitize(failure: Throwable, vararg roots: Path?): String {
        var text = failure.javaClass.simpleName + ": " + failure.message.orEmpty()
        val cause = failure.cause
        if (cause != null) {
            text += " caused by " + cause.javaClass.simpleName + ": " + cause.message.orEmpty()
            if (cause is ErrnoException) text += " errno=" + cause.errno
        }
        for (root in roots) {
            if (root != null) text = text.replace(root.toString(), "<sandbox>")
        }
        if (text.length > MAX_LOG_CHARS) text = text.take(MAX_LOG_CHARS)
        return text.filter { it.code >= 0x20 && it.code != 0x7f }
    }

    private fun osWriteExclusive(path: String, bytes: ByteArray, cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        val fd = try {
            Os.open(
                path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                384 /* 0o600 */,
            )
        } catch (failure: ErrnoException) {
            // The open itself failed (notably EEXIST on a foreign name): this
            // call created nothing, so it deletes and tracks nothing.
            throw IllegalStateException("preflight scratch file is not exclusively creatable", failure)
        }
        var closeAttempted = false
        try {
            var written = 0
            while (written < bytes.size) {
                checkCancelled(cancelled)
                val count = try {
                    Os.write(fd, bytes, written, bytes.size - written)
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.EINTR) continue
                    throw IllegalStateException("preflight scratch write failed", failure)
                }
                if (count <= 0) throw IllegalStateException("preflight scratch write made no progress")
                written += count
            }
            closeAttempted = true
            try {
                Os.close(fd)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EINTR) throw IllegalStateException("preflight scratch file did not close", failure)
            }
        } catch (primaryFailure: Throwable) {
            if (!closeAttempted) {
                try {
                    Os.close(fd)
                } catch (closeFailure: ErrnoException) {
                    primaryFailure.addSuppressed(IllegalStateException("preflight scratch descriptor not closed after a failed write", closeFailure))
                }
            }
            // Only the partial this call created exclusively is removed; an
            // EEXIST open failure never reaches here, so foreign names survive.
            try {
                Files.deleteIfExists(Path.of(path))
            } catch (deleteFailure: Throwable) {
                primaryFailure.addSuppressed(deleteFailure)
            }
            throw primaryFailure
        }
    }

    private fun osCreateSymlink(oldPath: String, newPath: String) {
        try {
            Os.symlink(oldPath, newPath)
        } catch (failure: ErrnoException) {
            throw IllegalStateException("private symlink probe is not creatable", failure)
        }
    }

    private fun osRequireNoFollowRejectsSymlink(link: File) {
        val fd = try {
            Os.open(link.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (expected: ErrnoException) {
            if (expected.errno == OsConstants.ELOOP) return
            throw IllegalStateException("preflight NOFOLLOW probe failed with an unexpected error", expected)
        }
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
            // Already open against expectation; the gate fails below, the
            // descriptor must still not leak.
        }
        throw IllegalStateException("preflight NOFOLLOW open followed a symlink")
    }
}
