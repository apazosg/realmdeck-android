package app.gamenative

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * A small, opt-in capability check for the two filesystems used by the updater.
 * It only writes into fresh app-private/app-external scratch directories and never
 * acquires, clears, or writes updater/runtime state.
 */
object ForeverClientUpdaterQualification {
    data class Result(
        val privateStorageOk: Boolean,
        val clientVolumeOk: Boolean,
        val runtimeKnown: Boolean,
        val runtimeIdle: Boolean,
        val transferOk: Boolean,
        val detail: String,
    )

    /** Blocking filesystem, /proc, catalog, and optional CDN work; call on a worker thread. */
    fun run(
        context: Context,
        storageParent: File,
        catalog: ForeverClientUpdateCatalog,
        cancelled: () -> Boolean,
    ): Result {
        var privateOk = false
        var clientOk = false
        var transferOk = false
        var observation: RuntimeObservation = RuntimeObservation.Unknown("not_observed")
        val details = ArrayList<String>(5)

        try {
            checkCancelled(cancelled)
            val appContext = context.applicationContext ?: context
            val privateRoot = existingSafeDirectory(appContext.cacheDir.canonicalFile)
            privateOk = privateRoot != null && runFilesystemProbe(privateRoot.toFile(), requirePrivateHardlink = true, cancelled)
            details += if (privateOk) "private_fs_pass" else "private_fs_fail"

            val clientParent = approvedExternalFilesParent(appContext, storageParent)
            clientOk = clientParent != null && runFilesystemProbe(clientParent.toFile(), requirePrivateHardlink = false, cancelled)
            details += if (clientOk) "client_volume_fs_pass" else "client_volume_fs_fail"

            checkCancelled(cancelled)
            observation = observeRuntime(appContext)
            if (privateOk && clientOk && observation === RuntimeObservation.Quiescent) {
                val candidate = try {
                    catalog.withObjects(cancelled) { records ->
                        records.firstOrNull { it.encodedSize in 1..MAX_QUALIFICATION_ENCODED_BYTES &&
                            it.decodedSize in 0..MAX_QUALIFICATION_DECODED_BYTES
                        }
                    }
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Exception) {
                    details += "cdn_probe_catalog_unavailable"
                    null
                }

                if (candidate == null) {
                    if (details.none { it == "cdn_probe_catalog_unavailable" }) {
                        details += "cdn_probe_no_eligible_object"
                    }
                } else {
                    // Catalog scanning may take time. Recheck immediately before any CDN request.
                    checkCancelled(cancelled)
                    observation = observeRuntime(appContext)
                    if (observation === RuntimeObservation.Quiescent) {
                        transferOk = runTinyTransfer(requireNotNull(privateRoot), candidate, cancelled)
                        details += if (transferOk) "cdn_probe_pass" else "cdn_probe_fail"
                    } else {
                        details += "cdn_probe_skipped_runtime"
                    }
                }
            } else {
                details += if (!privateOk || !clientOk) "cdn_probe_skipped_filesystem" else "cdn_probe_skipped_runtime"
            }
        } catch (_: CancellationException) {
            details += "cancelled"
        } catch (_: Exception) {
            // Keep diagnostics path-free and independent of exception messages/stacks.
            details += "qualification_failed"
        }

        details += when (observation) {
            RuntimeObservation.Quiescent -> "runtime_idle"
            is RuntimeObservation.Active -> "runtime_active"
            is RuntimeObservation.Unknown -> "runtime_unknown_${safeReasonCode(observation.reasonCode)}"
        }
        return Result(
            privateStorageOk = privateOk,
            clientVolumeOk = clientOk,
            runtimeKnown = observation !is RuntimeObservation.Unknown,
            runtimeIdle = observation === RuntimeObservation.Quiescent,
            transferOk = transferOk,
            detail = details.distinct().joinToString(";").take(MAX_DETAIL_CHARS),
        )
    }

    /**
     * Probe create-new/write+force, directory force, atomic no-replace-intent rename,
     * and (only for private cache) hard-link publication in a fresh child directory.
     * Unsupported operations fail closed. This function never makes a hard link on
     * the client volume.
     */
    internal fun runFilesystemProbe(
        directory: File,
        requirePrivateHardlink: Boolean,
        cancelled: () -> Boolean,
    ): Boolean {
        var scratch: OwnedScratch? = null
        var passed = false
        try {
            checkCancelled(cancelled)
            val parent = existingSafeDirectory(directory) ?: return false
            scratch = OwnedScratch.create(parent, cancelled)
            checkCancelled(cancelled)

            val original = scratch.createNew("created.bin", PROBE_BYTES)
            forceDirectory(scratch.path)
            if (!matchesSmallFile(original, PROBE_BYTES)) return false

            checkCancelled(cancelled)
            val pending = scratch.createNew("atomic-pending.bin", ATOMIC_BYTES)
            forceDirectory(scratch.path)
            val published = scratch.path.resolve("atomic-published.bin")
            if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) return false
            Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE)
            scratch.moved("atomic-pending.bin", "atomic-published.bin")
            forceDirectory(scratch.path)
            if (!matchesSmallFile(published, ATOMIC_BYTES)) return false

            if (requirePrivateHardlink) {
                checkCancelled(cancelled)
                val part = scratch.createNew("private-publish-part.bin", PROBE_BYTES)
                val publishedPrivate = scratch.path.resolve("private-published.bin")
                ForeverClientUpdateCatalog.publishVerifiedPrivateFile(publishedPrivate, part)
                scratch.moved("private-publish-part.bin", "private-published.bin")
                scratch.createdByTransfer(".forever-private-publish.lock")
                forceDirectory(scratch.path)
                if (!matchesSmallFile(publishedPrivate, PROBE_BYTES)) return false
                if (!matchesSmallFile(original, PROBE_BYTES)) return false
            }

            checkCancelled(cancelled)
            passed = true
        } catch (_: Exception) {
            passed = false
        } finally {
            if (scratch != null) {
                try {
                    if (!scratch.cleanup()) passed = false
                } catch (_: Exception) {
                    passed = false
                }
            }
        }
        return passed
    }

    private fun approvedExternalFilesParent(context: Context, requested: File): Path? {
        val candidate = existingSafeDirectory(requested) ?: return null
        val approved = try {
            context.getExternalFilesDirs(null).orEmpty().filterNotNull().mapNotNull(::existingSafeDirectory)
        } catch (_: Exception) {
            return null
        }
        return candidate.takeIf { it in approved }
    }

    private fun observeRuntime(context: Context): RuntimeObservation = try {
        ForeverClientRuntimeGate.forContext(context).observe()
    } catch (_: Exception) {
        RuntimeObservation.Unknown("observer_failed")
    }

    private fun runTinyTransfer(
        privateRoot: Path,
        record: ForeverClientUpdateCatalog.ObjectRecord,
        cancelled: () -> Boolean,
    ): Boolean {
        var scratch: OwnedScratch? = null
        var passed = false
        val partName = "${record.ekey}.part"
        val outputName = "${record.ekey}.blte"
        try {
            checkCancelled(cancelled)
            scratch = OwnedScratch.create(privateRoot, cancelled)
            if (Files.exists(scratch.path.resolve(outputName), LinkOption.NOFOLLOW_LINKS)) return false

            // Reserve the part with CREATE_NEW so the transfer can resume only in our
            // uniquely owned private scratch directory, never in client data.
            scratch.createNew(partName, ByteArray(0))
            forceDirectory(scratch.path)
            val output = ForeverClientUpdateTransfer().fetch(record, scratch.path.toFile(), cancelled) { }
            val expected = scratch.path.resolve(outputName).toAbsolutePath().normalize()
            if (output.toPath().toAbsolutePath().normalize() != expected ||
                !Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(expected) != record.encodedSize
            ) return false
            scratch.createdByTransfer(outputName)
            forceDirectory(scratch.path)
            checkCancelled(cancelled)
            passed = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            passed = false
        } finally {
            // The transfer may have made its output before a later cleanup/force failed.
            if (scratch != null) {
                try {
                    scratch.adoptRegularTransferOutput(outputName)
                    scratch.adoptRegularTransferOutput(".forever-private-publish.lock")
                    if (!scratch.cleanup()) passed = false
                } catch (_: Exception) {
                    passed = false
                }
            }
        }
        return passed
    }

    private class OwnedScratch private constructor(
        val path: Path,
        private val parent: Path,
        private val ownerMarkerBytes: ByteArray,
    ) {
        private val ownedNames = LinkedHashSet<String>()

        fun createNew(name: String, bytes: ByteArray): Path {
            requireSafeName(name)
            val file = path.resolve(name)
            FileChannel.open(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                ownedNames += name
                writeFully(channel, bytes)
                channel.force(true)
            }
            return file
        }

        fun moved(from: String, to: String) {
            ownedNames.remove(from)
            ownedNames += to
        }

        fun createdByLink(name: String) {
            ownedNames += name
        }

        fun createdByTransfer(name: String) {
            ownedNames += name
        }

        fun adoptRegularTransferOutput(name: String) {
            val file = path.resolve(name)
            if (!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                ownedNames += name
            }
        }

        fun removed(name: String) {
            ownedNames.remove(name)
        }

        /** Deletes only names created by this scratch owner; unknown entries are preserved. */
        fun cleanup(): Boolean {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
            val marker = path.resolve(OWNER_MARKER)
            if (!matchesSmallFile(marker, ownerMarkerBytes)) return false
            var clean = true
            for (name in ownedNames.toList().asReversed()) {
                val child = path.resolve(name)
                try {
                    if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                            clean = false
                        } else {
                            Files.delete(child)
                        }
                    }
                } catch (_: Exception) {
                    clean = false
                }
            }
            try {
                forceDirectory(path)
            } catch (_: Exception) {
                clean = false
            }
            val empty = try {
                Files.newDirectoryStream(path).use { children -> !children.iterator().hasNext() }
            } catch (_: Exception) {
                false
            }
            if (empty) {
                try {
                    Files.delete(path)
                } catch (_: Exception) {
                    clean = false
                }
                try {
                    forceDirectory(parent)
                } catch (_: Exception) {
                    clean = false
                }
            } else {
                clean = false
            }
            return clean
        }

        companion object {
            fun create(parent: Path, cancelled: () -> Boolean): OwnedScratch {
                checkCancelled(cancelled)
                val normalizedParent = existingSafeDirectory(parent.toFile())
                    ?: throw IOException("qualification parent is not a safe directory")
                val scratchPath = normalizedParent.resolve(".forever-updater-qualification-${UUID.randomUUID()}")
                Files.createDirectory(scratchPath)
                val marker = "fq-v1\n${UUID.randomUUID()}\n".toByteArray(Charsets.US_ASCII)
                val scratch = OwnedScratch(scratchPath, normalizedParent, marker)
                try {
                    forceDirectory(normalizedParent)
                    scratch.createNew(OWNER_MARKER, marker)
                    forceDirectory(scratchPath)
                    return scratch
                } catch (failure: Exception) {
                    scratch.cleanup()
                    throw failure
                }
            }
        }
    }

    private fun existingSafeDirectory(file: File): Path? {
        return try {
            val path = file.toPath().toAbsolutePath().normalize()
            val root = path.root ?: return null
            if (Files.isSymbolicLink(root)) return null
            var current = root
            for (part in path) {
                current = current.resolve(part)
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current) ||
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) return null
            }
            path
        } catch (_: Exception) {
            null
        }
    }

    private fun matchesSmallFile(path: Path, expected: ByteArray): Boolean {
        return try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(path) != expected.size.toLong()
            ) return false
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val actual = ByteArray(expected.size)
                val buffer = ByteBuffer.wrap(actual)
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    if (count <= 0) return false
                }
                channel.read(ByteBuffer.allocate(1)) == -1 && actual.contentEquals(expected)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw IOException("qualification file write made no progress")
        }
    }

    private fun requireSafeName(name: String) {
        require(name.matches(SAFE_NAME)) { "unsafe qualification scratch name" }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException()
    }

    private fun safeReasonCode(value: String): String = value.takeIf { it.matches(REASON_CODE) } ?: "invalid_reason"

    private const val OWNER_MARKER = ".qualification-owner"
    private const val MAX_QUALIFICATION_ENCODED_BYTES = 4_096L
    private const val MAX_QUALIFICATION_DECODED_BYTES = 65_536L
    private const val MAX_DETAIL_CHARS = 512
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,96}")
    private val REASON_CODE = Regex("[a-z0-9_]{1,64}")
    private val PROBE_BYTES = ByteArray(64) { (it * 37 + 11).toByte() }
    private val ATOMIC_BYTES = ByteArray(64) { (it * 19 + 3).toByte() }
}
