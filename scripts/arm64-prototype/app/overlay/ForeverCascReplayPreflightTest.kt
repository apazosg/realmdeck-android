package app.gamenative

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Protocol tests for the physical preflight gate. The host JVM stands in for
 * the USB and private parents with real directories, symlinks, and atomic
 * moves; only the reader and the three POSIX edges (exclusive write, symlink
 * creation, NOFOLLOW proof) are faked. The default [android.system.Os] edges
 * are never invoked here: actual `O_NOFOLLOW` / `ELOOP` behavior is proven on
 * device, not from a host fake. Production `Os` write/close ownership is
 * covered by structural inspection, not by host emulation of `Os.write`.
 */
class ForeverCascReplayPreflightTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preflightPassesAndLeavesNoScratch() {
        val appRoot = temporary.newFolder("app-files").toPath()
        val privateRoot = temporary.newFolder("private").toPath()
        val noFollowCalls = ArrayList<String>()
        val elapsedMs = ForeverCascReplayPreflight.run(
            appRoot.toFile(),
            privateRoot.toFile(),
            { false },
            readerFactory = { cancelled -> ProbeFakeReader(cancelled) },
            writeExclusive = ::fakeWriteExclusive,
            requireNoFollow = { link ->
                noFollowCalls += link.absolutePath
                if (!Files.isSymbolicLink(link.toPath())) throw IllegalStateException("FAKE NOFOLLOW expected a symlink probe")
            },
            createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
        )
        assertTrue("preflight must report one aggregate timing", elapsedMs >= 0)
        assertEquals("the NOFOLLOW edge must be exercised once", 1, noFollowCalls.size)
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightFailsClosedWhenReaderMissesSwap() {
        val appRoot = temporary.newFolder("app-miss").toPath()
        val privateRoot = temporary.newFolder("private-miss").toPath()
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled, enforceBinding = false) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("a missed swap must be reported, not any error", error.message.orEmpty().contains("undetected"))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightFailsClosedWhenReaderFollowsSymlink() {
        val appRoot = temporary.newFolder("app-link").toPath()
        val privateRoot = temporary.newFolder("private-link").toPath()
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled, rejectSymlinks = false) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("a followed symlink must be reported", error.message.orEmpty().contains("symlink"))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightPropagatesCancellationAndCleansUp() {
        val appRoot = temporary.newFolder("app-cancel").toPath()
        val privateRoot = temporary.newFolder("private-cancel").toPath()
        assertThrows(CancellationException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { true },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
            )
        }
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightPreservesForeignFileOnExclusiveCreateConflict() {
        val appRoot = temporary.newFolder("app-eexist").toPath()
        val privateRoot = temporary.newFolder("private-eexist").toPath()
        val foreignBytes = "foreign-owner-not-us".toByteArray()
        var calls = 0
        val error = assertThrows(FileAlreadyExistsException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled) },
                writeExclusive = { path, bytes, cancelled ->
                    if (calls++ == 0) {
                        Files.write(Path.of(path), foreignBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        throw FileAlreadyExistsException(path)
                    } else {
                        fakeWriteExclusive(path, bytes, cancelled)
                    }
                },
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        // The foreign name was never owned: it must survive with its bytes,
        // and the leftover scratch directory must fail closed as suppressed
        // evidence instead of being cleaned around the foreign file.
        assertTrue(error.suppressed.any { it is java.nio.file.DirectoryNotEmptyException })
        val survivors = regularFilesUnder(appRoot)
        assertEquals("a foreign file must survive the failed gate", 1, survivors.size)
        assertArrayEquals(foreignBytes, Files.readAllBytes(survivors.single()))
        deleteRecursively(appRoot)
    }

    @Test fun preflightPreservesForeignFileOnSymlinkConflict() {
        val appRoot = temporary.newFolder("app-linkconflict").toPath()
        val privateRoot = temporary.newFolder("private-linkconflict").toPath()
        val foreignBytes = "foreign-link-owner".toByteArray()
        val error = assertThrows(FileAlreadyExistsException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { _, newPath ->
                    Files.write(Path.of(newPath), foreignBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    throw FileAlreadyExistsException(newPath)
                },
            )
        }
        assertTrue(error.suppressed.any { it is java.nio.file.DirectoryNotEmptyException })
        val survivors = regularFilesUnder(privateRoot)
        assertEquals("a foreign symlink-path file must survive the failed gate", 1, survivors.size)
        assertArrayEquals(foreignBytes, Files.readAllBytes(survivors.single()))
        deleteRecursively(privateRoot)
    }

    @Test fun preflightCloseFailureOnSuccessFailsGate() {
        val appRoot = temporary.newFolder("app-closefail").toPath()
        val privateRoot = temporary.newFolder("private-closefail").toPath()
        val closeError = IllegalStateException("FAKE close failure")
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled, closeError = closeError) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { link ->
                    if (!Files.isSymbolicLink(link.toPath())) throw IllegalStateException("FAKE NOFOLLOW expected a symlink probe")
                },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertSame("a close failure after success must fail the gate itself", closeError, error)
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightBodyFailureKeepsCloseFailureSuppressed() {
        val appRoot = temporary.newFolder("app-bodysup").toPath()
        val privateRoot = temporary.newFolder("private-bodysup").toPath()
        val closeError = IllegalStateException("FAKE close failure")
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled, enforceBinding = false, closeError = closeError) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("the body verdict must stay primary", error.message.orEmpty().contains("undetected"))
        assertTrue("the close failure must be suppressed, not masking", error.suppressed.contains(closeError))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightCancellationOutranksCloseFailure() {
        val appRoot = temporary.newFolder("app-cancelsup").toPath()
        val privateRoot = temporary.newFolder("private-cancelsup").toPath()
        val closeError = IllegalStateException("FAKE close failure")
        val error = assertThrows(CancellationException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled, cancelDuringReads = true, closeError = closeError) },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("cancellation must keep its exact type", error is CancellationException)
        assertTrue("the close failure must be suppressed into the cancellation", error.suppressed.contains(closeError))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightRejectsOversizedFreshContentWithinBound() {        val appRoot = temporary.newFolder("app-oversize").toPath()
        val privateRoot = temporary.newFolder("private-oversize").toPath()
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled -> ProbeFakeReader(cancelled) },
                writeExclusive = { path, bytes, cancelled ->
                    if (path.endsWith("segment-b.bin")) {
                        fakeWriteExclusive(path, ByteArray(8192), cancelled)
                    } else {
                        fakeWriteExclusive(path, bytes, cancelled)
                    }
                },
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("an oversized fresh file must fail bounded, not materialize", error.message.orEmpty().contains("changed size"))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    @Test fun preflightExactSealedGuardIsStrongerThanCoveringProof() {
        val appRoot = temporary.newFolder("app-sealed").toPath()
        val privateRoot = temporary.newFolder("private-sealed").toPath()
        // A reader whose exact-size proof is broken must fail the gate at the
        // positive pre-swap sealed check, even though the covering per-object
        // proof would still pass.
        val error = assertThrows(IllegalStateException::class.java) {
            ForeverCascReplayPreflight.run(
                appRoot.toFile(),
                privateRoot.toFile(),
                { false },
                readerFactory = { cancelled ->
                    val probe = ProbeFakeReader(cancelled)
                    object : CascReplayReader by probe {
                        override fun checkSealedBinding(exactSize: Long) {
                            throw IllegalStateException("FAKE sealed proof is broken")
                        }

                        override fun close() = probe.close()
                    }
                },
                writeExclusive = ::fakeWriteExclusive,
                requireNoFollow = { _ -> },
                createSymlink = { oldPath, newPath -> Files.createSymbolicLink(Path.of(newPath), Path.of(oldPath)) },
            )
        }
        assertTrue("a broken sealed proof must fail the gate", error.message.orEmpty().contains("sealed"))
        assertNoScratchRemains(appRoot)
        assertNoScratchRemains(privateRoot)
    }

    private fun assertNoScratchRemains(root: Path) {        Files.newDirectoryStream(root).use { children ->
            for (child in children) {
                assertTrue(
                    "preflight must remove its scratch entries: ${child.fileName}",
                    !child.fileName.toString().startsWith(".forever-casc-preflight-"),
                )
            }
        }
    }

    private fun regularFilesUnder(root: Path): List<Path> {
        Files.walk(root).use { stream ->
            return stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun fakeWriteExclusive(path: String, bytes: ByteArray, cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("cancelled")
        Files.write(Path.of(path), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private class ProbeFakeReader(
        private val cancelled: () -> Boolean = { false },
        private val enforceBinding: Boolean = true,
        private val rejectSymlinks: Boolean = true,
        private val cancelDuringReads: Boolean = false,
        private val closeError: Throwable? = null,
    ) : CascReplayReader {
        private var path: String? = null
        private var input: RandomAccessFile? = null
        private var key: String? = null
        private var length = -1L
        var closeCalls = 0
            private set

        override fun ensureSegment(file: File) {
            if (cancelled()) throw CancellationException("cancelled")
            if (rejectSymlinks && Files.isSymbolicLink(file.toPath())) {
                throw IllegalStateException("FAKE probe segment is a symlink")
            }
            if (input != null && path == file.absolutePath) return
            input?.close()
            input = null
            path = null
            if (!file.isFile) throw IllegalStateException("FAKE probe segment is not a regular file")
            path = file.absolutePath
            input = RandomAccessFile(file, "r")
            key = identityOf(file)
            length = file.length()
        }

        override fun checkBinding(endExclusive: Long) {
            if (cancelled()) throw CancellationException("cancelled")
            val open = input ?: throw IllegalStateException("FAKE probe reader is closed")
            open.seek(0)
            val current = File(requireNotNull(path) { "FAKE probe reader has no bound segment" })
            if (Files.isSymbolicLink(current.toPath()) || !current.isFile) {
                throw CascReplayBindingMismatchException("FAKE probe staged binding changed under its reader")
            }
            if (enforceBinding && (identityOf(current) != key || current.length() != length)) {
                throw CascReplayBindingMismatchException("FAKE probe staged binding changed under its reader")
            }
            if (current.length() < endExclusive) throw IllegalStateException("FAKE probe segment no longer covers its object end")
        }

        override fun checkSealedBinding(exactSize: Long) {
            if (cancelled()) throw CancellationException("cancelled")
            val open = input ?: throw IllegalStateException("FAKE probe reader is closed")
            open.seek(0)
            val current = File(requireNotNull(path) { "FAKE probe reader has no bound segment" })
            if (Files.isSymbolicLink(current.toPath()) || !current.isFile) {
                throw CascReplayBindingMismatchException("FAKE probe staged binding changed under its reader")
            }
            if (enforceBinding && (identityOf(current) != key || current.length() != length)) {
                throw CascReplayBindingMismatchException("FAKE probe staged binding changed under its reader")
            }
            if (current.length() != exactSize) throw IllegalStateException("FAKE probe sealed size is not its frozen end")
        }

        override fun readAt(buffer: ByteArray, off: Int, len: Int, position: Long): Int {
            if (off < 0 || len < 0 || off > buffer.size || len > buffer.size - off) {
                throw IndexOutOfBoundsException("FAKE probe read is outside its buffer")
            }
            if (position < 0) throw IllegalArgumentException("negative FAKE probe read position")
            val open = input ?: throw IllegalStateException("FAKE probe reader is closed")
            if (len == 0) return 0
            if (cancelDuringReads || cancelled()) throw CancellationException("cancelled")
            open.seek(position)
            val count = open.read(buffer, off, len)
            return if (count < 0) 0 else count
        }

        override fun close() {
            closeCalls++
            try {
                input?.close()
            } finally {
                input = null
                path = null
            }
            closeError?.let { throw it }
        }

        private fun identityOf(file: File): String {
            val attributes = Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            return attributes.fileKey().toString() + ":" + file.length()
        }
    }
}
