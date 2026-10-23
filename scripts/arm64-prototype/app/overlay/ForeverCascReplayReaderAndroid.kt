package app.gamenative

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CancellationException

/**
 * Android owner of one staged-segment FD for cold replay verification.
 *
 * This is the only [CascReplayReader] implementation: it owns its FD
 * exclusively through the public [Os] API (open/fstat/lstat/pread/close,
 * all available since API 21; the prototype floor is 26). It never uses
 * `FileInputStream(FileDescriptor)` (whose public constructor does not take
 * FD ownership), NIO channels, reflection, JNI, or `Files.fileKey`: identity
 * is always the fresh `st_dev`/`st_ino` pair from [Os.fstat]/[Os.lstat].
 *
 * The open protocol per segment is: reject symlink parents, lstat the path
 * (regular, bounded size), open `O_RDONLY|O_NOFOLLOW|O_CLOEXEC`, then require
 * the pre-open lstat, the FD fstat, and a fresh post-open lstat to agree on
 * device, inode, regular kind, and size. A partial open closes its FD
 * exactly once before propagating. Per object, [checkBinding] repeats fresh
 * path-plus-FD stats and requires the same device/inode, regular kind on
 * both sides, and both sizes covering the object end. A stale FD alone
 * never authorizes: every object requires fresh path-plus-FD agreement,
 * while ABA and race windows remain and are not claimed away.
 *
 * `Os.pread` returning 0 with bytes outstanding is end-of-file, reported as
 * a zero return for the caller to treat as truncation. `EINTR` retries only
 * after consulting cancellation; every other errno fails closed. Close is
 * idempotent, detaches before the single `Os.close` attempt (close is never
 * retried), and a read after close fails. There is deliberately no silent
 * fallback to any other mode: the factory stays null until the FUSE gate
 * proves the physical binding, so production keeps the copy verifier.
 */
internal class ForeverCascReplayReaderAndroid(
    private val cancelled: () -> Boolean,
) : CascReplayReader {
    private var fd: FileDescriptor? = null
    private var boundPath: String? = null
    private var boundDev: Long = -1L
    private var boundIno: Long = -1L

    override fun ensureSegment(file: File) {
        checkCancelled()
        val path = file.absoluteFile.path
        if (fd != null && boundPath == path) return
        closeOwned()
        rejectSymlinkParentsOs(file)
        val pre = lstatOf(path, "segment")
        requireRegular(pre, path)
        requireSizeBound(pre.st_size, path)
        val fresh = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            throw IllegalStateException("cannot open CASC staged segment: $path", e)
        }
        try {
            val openStat = fstatOf(fresh, path)
            val post = lstatOf(path, "segment")
            requireRegular(openStat, path)
            requireRegular(post, path)
            requireSizeBound(openStat.st_size, path)
            requireSizeBound(post.st_size, path)
            if (openStat.st_dev != post.st_dev || openStat.st_ino != post.st_ino || openStat.st_size != post.st_size) {
                throw IllegalStateException("CASC staged segment changed while opening: $path")
            }
            if (pre.st_dev != openStat.st_dev || pre.st_ino != openStat.st_ino) {
                throw IllegalStateException("CASC staged segment changed while opening: $path")
            }
            fd = fresh
            boundPath = path
            boundDev = openStat.st_dev
            boundIno = openStat.st_ino
        } catch (t: Throwable) {
            // The partial FD is closed exactly once, never retried; a close
            // failure never replaces the opening failure and is only kept as
            // a suppressed diagnostic (EINTR included).
            try {
                Os.close(fresh)
            } catch (closeError: ErrnoException) {
                t.addSuppressed(IllegalStateException("cannot close CASC staged segment after a failed open", closeError))
            }
            throw t
        }
    }

    override fun checkBinding(endExclusive: Long) {
        checkCancelled()
        if (endExclusive < 0 || endExclusive > ARCHIVE_LIMIT) {
            throw IllegalStateException("CASC replay binding is outside the archive bound: $endExclusive")
        }
        val open = fd ?: throw IllegalStateException("CASC replay reader is closed")
        val path = requireNotNull(boundPath) { "CASC replay reader has no bound segment" }
        val current = lstatOf(path, "segment")
        val live = fstatOf(open, path)
        requireRegular(current, path)
        requireRegular(live, path)
        if (current.st_dev != boundDev || current.st_ino != boundIno ||
            live.st_dev != boundDev || live.st_ino != boundIno
        ) {
            throw CascReplayBindingMismatchException("CASC staged segment binding changed under its reader: $path")
        }
        requireSizeBound(current.st_size, path)
        requireSizeBound(live.st_size, path)
        if (current.st_size < endExclusive || live.st_size < endExclusive) {
            throw IllegalStateException("CASC staged segment no longer covers its object end: $path")
        }
    }

    override fun checkSealedBinding(exactSize: Long) {
        checkCancelled()
        if (exactSize < 0 || exactSize > ARCHIVE_LIMIT) {
            throw IllegalStateException("CASC sealed binding is outside the archive bound: $exactSize")
        }
        val open = fd ?: throw IllegalStateException("CASC replay reader is closed")
        val path = requireNotNull(boundPath) { "CASC replay reader has no bound segment" }
        val current = lstatOf(path, "segment")
        val live = fstatOf(open, path)
        requireRegular(current, path)
        requireRegular(live, path)
        if (current.st_dev != boundDev || current.st_ino != boundIno ||
            live.st_dev != boundDev || live.st_ino != boundIno
        ) {
            throw CascReplayBindingMismatchException("CASC sealed segment binding changed under its reader: $path")
        }
        requireSizeBound(current.st_size, path)
        requireSizeBound(live.st_size, path)
        if (current.st_size != exactSize || live.st_size != exactSize) {
            throw IllegalStateException("CASC sealed segment size is not its frozen group end: $path")
        }
    }

    override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int {
        if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
            throw IndexOutOfBoundsException("CASC replay read is outside its buffer")
        }
        if (position < 0) throw IllegalArgumentException("negative CASC replay read position")
        val open = fd ?: throw IllegalStateException("CASC replay reader is closed")
        if (len == 0) return 0
        checkCancelled()
        var offset = off
        var remaining = len
        var at = position
        while (remaining > 0) {
            checkCancelled()
            val served = try {
                Os.pread(open, buffer, offset, remaining, at)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw IllegalStateException("cannot read CASC staged segment", e)
            }
            if (served < 0) throw IllegalStateException("cannot read CASC staged segment")
            if (served == 0) return len - remaining
            offset += served
            at += served
            remaining -= served
        }
        return len
    }

    override fun close() {
        closeOwned()
    }

    private fun closeOwned() {
        val open = fd ?: return
        fd = null
        boundPath = null
        boundDev = -1L
        boundIno = -1L
        try {
            Os.close(open)
        } catch (e: ErrnoException) {
            // A single close attempt per FD, never retried: retrying could
            // close an unrelated recycled FD. EINTR leaves nothing safely
            // retryable, so it is absorbed after the detach above; any other
            // errno still surfaces.
            if (e.errno != OsConstants.EINTR) throw IllegalStateException("cannot close CASC staged segment", e)
        }
    }

    private fun lstatOf(path: String, what: String): StructStat = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        throw IllegalStateException("cannot stat CASC staged $what: $path", e)
    }

    private fun fstatOf(open: FileDescriptor, path: String): StructStat = try {
        Os.fstat(open)
    } catch (e: ErrnoException) {
        throw IllegalStateException("cannot stat CASC staged open file: $path", e)
    }

    private fun requireRegular(stat: StructStat, path: String) {
        if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) {
            throw IllegalStateException("CASC staged segment is not a regular file: $path")
        }
    }

    private fun requireSizeBound(size: Long, path: String) {
        if (size < 0 || size > ARCHIVE_LIMIT) {
            throw IllegalStateException("CASC staged segment size is outside the archive bound: $path")
        }
    }

    private fun rejectSymlinkParentsOs(file: File) {
        var parent = file.absoluteFile.parentFile
        while (parent != null) {
            val stat = lstatOf(parent.path, "parent directory")
            if ((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK) {
                throw IllegalStateException("CASC staged segment path contains a symlink")
            }
            parent = parent.parentFile
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw CancellationException("client provisioning cancelled")
    }

    private companion object {
        /** Shared V7 staged-segment bound; the writer enforces the same limit. */
        const val ARCHIVE_LIMIT = 1L shl 30
    }
}

/**
 * Proves a fresh path-plus-FD check observed a device/inode replacement of the
 * bound segment. Thrown only for that proven mismatch, never for plain IO
 * failures, so the preflight gate can require this type instead of matching
 * message text. It still extends [IllegalStateException] so existing
 * fail-closed callers keep their behavior.
 */
internal class CascReplayBindingMismatchException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
