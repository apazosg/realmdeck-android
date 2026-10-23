package app.gamenative

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.min

/** Portable writer for the bounded V7 local CASC store used by the bootstrap. */
object ForeverCasc {
    private const val LOCAL_HEADER_SIZE = 30
    private const val SEGMENT_HEADER_SIZE = 480
    private const val ARCHIVE_LIMIT = 1L shl 30
    private const val INDEX_SIZE = 0x30000
    private const val BUFFER_SIZE = 64 * 1024
    private val CHECKSUM_B_LUT = intArrayOf(
        0x049396B8, 0x72A82A9B.toInt(), 0xEE626CCA.toInt(), 0x9917754F.toInt(),
        0x15DE40B1, 0xF5A8A9B6.toInt(), 0x421EAC7E, 0xA9D55C9A.toInt(),
        0x317FD40C, 0x04FAF80D, 0x3D6BE971, 0x52933CFD, 0x27F64B7D,
        0xC6F5C11B.toInt(), 0xD5757E3A.toInt(), 0x6C388745,
    )

    internal data class RuntimeObject(val record: org.json.JSONObject, val raw: File)

    internal fun materialize(
        staging: File,
        buildInfo: ByteArray,
        configs: List<Pair<String, File>>,
        indices: List<Pair<String, File>>,
        objects: List<RuntimeObject>,
        cancelled: () -> Boolean,
    ) {
        val data = File(staging, "Data")
        val ownership = File(staging, ".forever-materializer-owned")
        if (listOf(data, ownership).any { Files.isSymbolicLink(it.toPath()) }) throw IllegalStateException("CASC staging contains an unexpected symlink")
        if (data.exists() && !ownership.isFile) {
            throw IllegalStateException("unmanaged CASC staging already exists; refusing to overwrite it")
        }
        if (data.exists()) data.deleteRecursively()
        val work = File(staging, "Data.new")
        if (Files.isSymbolicLink(work.toPath())) throw IllegalStateException("CASC partial staging contains an unexpected symlink")
        if (work.exists() && !ownership.isFile) {
            throw IllegalStateException("unmanaged CASC partial staging already exists; refusing to overwrite it")
        }
        if (work.exists()) work.deleteRecursively()
        staging.mkdirs()
        if (!ownership.exists()) writeOwnedText(ownership, "managed-casc-materializer-v1\n")
        if (!work.mkdirs()) throw IllegalStateException("cannot create CASC staging")
        val dataFiles = File(work, "data").apply { mkdirs() }
        val indexFiles = File(work, "indices").apply { mkdirs() }
        val configFiles = File(work, "config").apply { mkdirs() }
        if (listOf(dataFiles, indexFiles, configFiles).any { Files.isSymbolicLink(it.toPath()) }) throw IllegalStateException("CASC staging contains an unexpected symlink")
        try {
            configs.forEach { (hash, source) ->
                checkCancelled(cancelled)
                val target = File(configFiles, "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash")
                target.parentFile?.mkdirs()
                copyFile(source, target, cancelled)
            }
            indices.forEach { (hash, source) ->
                checkCancelled(cancelled)
                copyFile(source, File(indexFiles, "$hash.index"), cancelled)
            }
            val entries = Array(16) { mutableListOf<IndexEntry>() }
            var archiveId = 0
            // Every archive starts with the 16 reconstruction records emitted
            // by openArchive.  The first real object therefore begins at 480,
            // not at offset zero.
            var offset = SEGMENT_HEADER_SIZE.toLong()
            var archive = openArchive(dataFiles, archiveId, entries, cancelled)
            try {
                objects.forEach { item ->
                    checkCancelled(cancelled)
                    val ekey = item.record.getJSONArray("encoding_keys").getString(0).lowercase().hexBytes()
                    val decodedSize = item.record.getLong("decoded_size")
                    if (ekey.size != 16 || decodedSize < 0) throw IllegalArgumentException("invalid CASC runtime object")
                    val rawLength = item.raw.length()
                    if (rawLength > ARCHIVE_LIMIT - SEGMENT_HEADER_SIZE - LOCAL_HEADER_SIZE) throw IllegalArgumentException("CASC object exceeds archive limit")
                    val total = LOCAL_HEADER_SIZE + rawLength
                    if (offset + total > ARCHIVE_LIMIT) {
                        archive.close()
                        archiveId += 1
                        offset = SEGMENT_HEADER_SIZE.toLong()
                        archive = openArchive(dataFiles, archiveId, entries, cancelled)
                    }
                    val globalOffset = archiveId * ARCHIVE_LIMIT + offset
                    val header = localHeader(ekey, total, globalOffset, 0)
                    archive.write(header)
                    copyFile(item.raw, archive, cancelled)
                    val entry = IndexEntry(ekey.copyOfRange(0, 9), archiveId, offset, total)
                    entries[computeBucket(ekey)].add(entry)
                    offset += total
                }
            } finally {
                archive.close()
            }
            repeat(16) { bucket ->
                checkCancelled(cancelled)
                writeIndex(File(dataFiles, "%02x%08x.idx".format(bucket, 1)), bucket, entries[bucket])
            }
            if (!work.renameTo(data)) throw IllegalStateException("CASC staging promotion failed")
            val info = File(staging, ".build.info")
            openOwnedOutput(info, false).use { it.write(buildInfo) }
        } catch (t: Throwable) {
            // Data.new is exclusively ours.  It is deliberately retained on
            // failure so a caller never mistakes a partial store for success.
            throw t
        }
    }

    /** Verify a previously promoted store without rewriting or repairing it. */
    internal fun verifyExisting(
        installDir: File,
        objects: List<RuntimeObjectSpec>,
        cancelled: () -> Boolean,
    ) {
        val dataFiles = File(installDir, "Data/data")
        if (Files.isSymbolicLink(dataFiles.toPath())) throw IllegalStateException("existing CASC data directory is a symlink")
        val indexFiles = activeIndexFiles(dataFiles)
        if (indexFiles.size != 16) throw IllegalStateException("existing CASC local V7 store is incomplete; refusing to erase it")
        val entries = HashMap<String, IndexEntry>()
        // Keep every valid generation available for lookup.  The highest
        // generation is the active one for a bucket, but an older generation
        // can still contain a seeded entry when the game has appended a new
        // generation rather than rewriting the original file.
        allValidIndexFiles(dataFiles).forEach { (bucket, file) ->
            checkCancelled(cancelled)
            val bytes = file.readBytes()
            validateIndexHeader(bytes, bucket)
            val blockSize = u32(bytes, 0x20).toLong()
            if (blockSize < 0 || 0x28L + blockSize > bytes.size) throw IllegalStateException("existing CASC index is truncated")
            var at = 0x28
            while (at + 18 <= 0x28 + blockSize.toInt()) {
                val key = bytes.copyOfRange(at, at + 9)
                if (key.any { it.toInt() != 0 }) {
                    val locationWord = u32be(bytes, at + 10)
                    val archive = ((bytes[at + 9].toInt() and 0xff) shl 2) or ((locationWord ushr 30) and 3)
                    val location = locationWord and 0x3fffffff
                    val size = u32(bytes, at + 14)
                    entries[key.hex()] = IndexEntry(key, archive, location.toLong(), size.toLong())
                }
                at += 18
            }
        }
        val temporary = File(installDir, ".forever-casc-verify.tmp")
        try {
            objects.forEach { objectSpec ->
                checkCancelled(cancelled)
                val entry = entries[objectSpec.ekey.substring(0, 18)]
                    ?: throw IllegalStateException("existing CASC is missing ${objectSpec.ekey}")
                if (entry.size < LOCAL_HEADER_SIZE) throw IllegalStateException("existing CASC entry is truncated")
                val archive = File(dataFiles, "data.%03d".format(entry.archiveId))
                if (!archive.isFile || Files.isSymbolicLink(archive.toPath()) || entry.archiveOffset + entry.size > archive.length()) throw IllegalStateException("existing CASC entry is outside its archive")
                RandomAccessFile(archive, "r").use { source ->
                    source.seek(entry.archiveOffset)
                    val header = ByteArray(LOCAL_HEADER_SIZE)
                    source.readFully(header)
                    val encodedSize = u32(header, 0x10).toLong()
                    val globalOffset = entry.archiveId * ARCHIVE_LIMIT + entry.archiveOffset
                    if (encodedSize != entry.size || !header.copyOfRange(0, 16).reversedArray().contentEquals(objectSpec.ekey.hexBytes()) ||
                        u32(header, 0x16) != hashlittle(header.copyOfRange(0, 0x16), 0x3D6BE971) ||
                        u32(header, 0x1a) != checksumB(header, globalOffset)
                    ) {
                        throw IllegalStateException("existing CASC local header mismatch")
                    }
                    openOwnedOutput(temporary, false).use { out ->
                        var left = entry.size - LOCAL_HEADER_SIZE
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (left > 0) {
                            checkCancelled(cancelled)
                            val n = source.read(buffer, 0, min(buffer.size.toLong(), left).toInt())
                            if (n <= 0) throw IllegalStateException("existing CASC archive is truncated")
                            out.write(buffer, 0, n)
                            left -= n
                        }
                    }
                }
                ForeverBlte.verify(temporary, objectSpec.decodedSize, objectSpec.contentKey, cancelled)
                if (!temporary.delete()) throw IllegalStateException("cannot remove verification temporary")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    internal data class RuntimeObjectSpec(val ekey: String, val contentKey: String, val decodedSize: Long)

    internal fun buildInfo(fields: org.json.JSONObject): ByteArray {
        val branch = fields.getString("branch")
        val build = fields.getString("build_key")
        val cdn = fields.getString("cdn_key")
        val install = fields.getString("install_key")
        val path = fields.getString("cdn_path")
        val hostsArray = fields.getJSONArray("cdn_hosts")
        val hosts = (0 until hostsArray.length()).map { hostsArray.getString(it) }
        val servers = hosts.flatMap { listOf("http://$it/?maxhosts=4", "https://$it/?maxhosts=4&fallback=1") }.joinToString(" ")
        val version = fields.getString("version")
        val product = fields.getString("product")
        val platform = fields.getString("platform")
        val arch = fields.getString("architecture")
        val locale = fields.getString("locale")
        val region = fields.getString("region").uppercase()
        val tags = "$platform $arch ${region}? $locale speech?:$platform $arch ${region}? $locale text?"
        val header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|Product!STRING:0"
        val values = listOf(branch, "1", build, cdn, install, "", path, hosts.joinToString(" "), servers, tags, "", "", version, product)
        return (header + "\n" + values.joinToString("|") + "\n").toByteArray(Charsets.UTF_8)
    }

    private data class IndexEntry(val key: ByteArray, val archiveId: Int, val archiveOffset: Long, val size: Long)

    private fun activeIndexFiles(dataFiles: File): List<File> =
        allValidIndexFiles(dataFiles)
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (_, files) -> files.maxByOrNull { generationOf(it.name) } }

    private fun allValidIndexFiles(dataFiles: File): List<Pair<Int, File>> {
        val candidates = dataFiles.listFiles { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) && Regex("^[0-9a-fA-F]{10}\\.idx$").matches(file.name) }
            ?.mapNotNull { file ->
                val bucket = file.name.substring(0, 2).toIntOrNull(16) ?: return@mapNotNull null
                if (bucket !in 0..15 || file.length() != INDEX_SIZE.toLong()) return@mapNotNull null
                runCatching { validateIndexHeader(file.readBytes(), bucket) }.getOrNull() ?: return@mapNotNull null
                bucket to file
            } ?: emptyList()
        return candidates.sortedWith(compareBy<Pair<Int, File>> { it.first }.thenBy { generationOf(it.second.name) })
    }

    private fun generationOf(name: String): Long = name.substring(2, 10).toLong(16)

    private fun validateIndexHeader(bytes: ByteArray, bucket: Int) {
        if (bytes.size != INDEX_SIZE || u16(bytes, 8) != 7 || (bytes[10].toInt() and 0xff) != bucket ||
            (bytes[12].toInt() and 0xff) != 4 || (bytes[13].toInt() and 0xff) != 5 ||
            (bytes[14].toInt() and 0xff) != 9 || (bytes[15].toInt() and 0xff) != 30 ||
            u32(bytes, 0x00) != 16 || u32(bytes, 0x04) != hashlittle(bytes.copyOfRange(8, 24), 0)
        ) throw IllegalStateException("existing CASC index header is incompatible")
        val blockSize = u32(bytes, 0x20).toLong()
        if (blockSize < 0 || 0x28L + blockSize > bytes.size) throw IllegalStateException("existing CASC index is truncated")
        var pc = 0
        var pb = 0
        var at = 0x28
            while (at.toLong() + 18L <= 0x28L + blockSize) {
            val hashes = hashlittle2(bytes.copyOfRange(at, at + 18), pc, pb)
            pc = hashes.first
            pb = hashes.second
            at += 18
        }
        if (at.toLong() != 0x28L + blockSize || u32(bytes, 0x24) != pc) {
            throw IllegalStateException("existing CASC index sorted-section checksum is invalid")
        }
    }

    private fun openArchive(dataFiles: File, archiveId: Int, entries: Array<MutableList<IndexEntry>>, cancelled: () -> Boolean): BufferedOutputStream {
        checkCancelled(cancelled)
        val file = File(dataFiles, "data.%03d".format(archiveId))
        val stream = BufferedOutputStream(openOwnedOutput(file, false), BUFFER_SIZE)
        val segment = segmentHeader(archiveId, MessageDigest.getInstance("MD5").digest(dataFiles.absolutePath.toByteArray(Charsets.UTF_8)))
        stream.write(segment)
        repeat(16) { slot ->
            val local = segment.copyOfRange(slot * LOCAL_HEADER_SIZE, (slot + 1) * LOCAL_HEADER_SIZE)
            val key = local.copyOfRange(0, 16).reversedArray()
            val bucket = computeBucket(key, 1)
            entries[bucket].add(IndexEntry(key.copyOfRange(0, 9), archiveId, (slot * LOCAL_HEADER_SIZE).toLong(), LOCAL_HEADER_SIZE.toLong()))
        }
        return stream
    }

    private fun segmentHeader(segmentIndex: Int, pathHash: ByteArray): ByteArray = ByteArray(SEGMENT_HEADER_SIZE).also { result ->
        repeat(16) { bucket ->
            val key = pathHash.copyOf()
            key[1] = (segmentIndex and 0xff).toByte()
            key[2] = ((segmentIndex ushr 8) and 0xff).toByte()
            var probe = 0
            while (probe < 256) {
                key[0] = probe.toByte()
                if (computeBucket(key, 1) == bucket) break
                probe++
            }
            val header = localHeader(key, LOCAL_HEADER_SIZE.toLong(), segmentIndex * ARCHIVE_LIMIT + bucket * LOCAL_HEADER_SIZE, 1)
            header.copyInto(result, bucket * LOCAL_HEADER_SIZE)
        }
    }

    private fun localHeader(encodingKey: ByteArray, size: Long, offset: Long, flags: Int): ByteArray {
        if (encodingKey.size != 16 || size !in 0..0xffffffffL) throw IllegalArgumentException("invalid CASC local header")
        val result = ByteArray(LOCAL_HEADER_SIZE)
        encodingKey.reversedArray().copyInto(result, 0)
        putU32(result, 0x10, size)
        putU16(result, 0x14, flags)
        putU32(result, 0x16, hashlittle(result.copyOfRange(0, 0x16), 0x3D6BE971))
        putU32(result, 0x1a, checksumB(result, offset))
        return result
    }

    private fun checksumB(header: ByteArray, offset: Long): Int {
        val accum = IntArray(4)
        for (i in 0 until 0x1a) {
            val slot = offset.toInt().plus(i) and 3
            accum[slot] = accum[slot] xor (header[i].toInt() and 0xff)
        }
        val plus = ((offset + LOCAL_HEADER_SIZE) and 0xffffffffL).toInt()
        val mask = CHECKSUM_B_LUT[plus and 0xf] xor plus
        val maskBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mask).array()
        val result = ByteArray(4)
        for (i in 0 until 4) {
            val slot = offset.toInt().plus(0x1a + i) and 3
            result[i] = (accum[slot] xor (maskBytes[slot].toInt() and 0xff)).toByte()
        }
        return ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun writeIndex(file: File, bucket: Int, entries: List<IndexEntry>) {
        val sorted = entries.sortedWith { a, b -> compareBytes(a.key, b.key) }
        val entryBytes = ByteArray(sorted.size * 18)
        sorted.forEachIndexed { index, entry ->
            val at = index * 18
            entry.key.copyInto(entryBytes, at)
            entryBytes[at + 9] = (entry.archiveId ushr 2).toByte()
            putU32BE(entryBytes, at + 10, ((entry.archiveId and 3) shl 30) or (entry.archiveOffset.toInt() and 0x3fffffff))
            putU32(entryBytes, at + 14, entry.size)
        }
        val updateStart = (0x28 + entryBytes.size + 0xfff) and -0x1000
        if (updateStart > INDEX_SIZE) throw IllegalArgumentException("CASC index exceeds V7 size")
        val result = ByteArray(INDEX_SIZE)
            putU32(result, 0, 16)
            putU16(result, 8, 7)
        result[10] = bucket.toByte()
        result[12] = 4
        result[13] = 5
        result[14] = 9
        result[15] = 30
            putU64(result, 16, 256L shl 30)
            // The guarded header checksum covers the completed header fields;
            // computing it before bucket/format/segment fields are filled
            // produces an index that cascette correctly rejects.
            putU32(result, 4, hashlittle(result.copyOfRange(8, 24), 0))
        putU32(result, 0x20, entryBytes.size)
        var pc = 0
        var pb = 0
        sorted.forEach { entry ->
            val bytes = ByteArray(18)
            entry.key.copyInto(bytes, 0)
            bytes[9] = (entry.archiveId ushr 2).toByte()
            putU32BE(bytes, 10, ((entry.archiveId and 3) shl 30) or (entry.archiveOffset.toInt() and 0x3fffffff))
            putU32(bytes, 14, entry.size)
            val hashes = hashlittle2(bytes, pc, pb)
            pc = hashes.first
            pb = hashes.second
        }
        putU32(result, 0x24, pc)
        entryBytes.copyInto(result, 0x28)
        openOwnedOutput(file, false).use { it.write(result) }
    }

    private fun copyFile(source: File, target: File, cancelled: () -> Boolean) {
        target.parentFile?.mkdirs()
        openOwnedOutput(target, false).use { out -> copyFile(source, out, cancelled) }
    }

    private fun copyFile(source: File, out: OutputStream, cancelled: () -> Boolean) {
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) throw IllegalStateException("CASC source is not a regular file")
        BufferedInputStream(source.inputStream(), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(cancelled)
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) out.write(buffer, 0, n)
            }
        }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until min(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun computeBucket(key: ByteArray, seed: Int = 0): Int {
        var xor = 0
        repeat(min(9, key.size)) { xor = xor xor (key[it].toInt() and 0xff) }
        return (((xor ushr 4) xor xor) + seed) and 0xf
    }

    private fun putU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Long) {
        repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() }
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Int) = putU32(bytes, at, value.toLong() and 0xffffffffL)

    private fun putU32BE(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun putU64(bytes: ByteArray, at: Int, value: Long) = putU32(bytes, at, value).also { putU32(bytes, at + 4, value ushr 32) }

    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)
    private fun u32be(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)

    private fun Int.hex() = toUInt().toString(16).padStart(8, '0')
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw java.util.concurrent.CancellationException("client provisioning cancelled") }

    private fun openOwnedOutput(file: File, append: Boolean): OutputStream {
        rejectSymlinkParents(file)
        file.parentFile?.mkdirs()
        val options = if (append) {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        } else {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        }
        return Files.newOutputStream(file.toPath(), *options)
    }

    private fun writeOwnedText(file: File, text: String) = openOwnedOutput(file, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }

    private fun rejectSymlinkParents(file: File) {
        var parent = file.parentFile
        while (parent != null) {
            if (Files.isSymbolicLink(parent.toPath())) throw IllegalStateException("CASC output path contains a symlink")
            parent = parent.parentFile
        }
    }

    private fun rot(value: Int, shift: Int) = (value shl shift) or (value ushr (32 - shift))
    private fun mix(values: IntArray) {
        var a = values[0]; var b = values[1]; var c = values[2]
        a -= c; a = a xor rot(c, 4); c += b; b -= a; b = b xor rot(a, 6); a += c
        c -= b; c = c xor rot(b, 8); b += a; a -= c; a = a xor rot(c, 16); c += b
        b -= a; b = b xor rot(a, 19); a += c; c -= b; c = c xor rot(b, 4); b += a
        values[0] = a; values[1] = b; values[2] = c
    }
    private fun finish(values: IntArray) {
        var a = values[0]; var b = values[1]; var c = values[2]
        c = c xor b; c -= rot(b, 14); a = a xor c; a -= rot(c, 11); b = b xor a; b -= rot(a, 25)
        c = c xor b; c -= rot(b, 16); a = a xor c; a -= rot(c, 4); b = b xor a; b -= rot(a, 14)
        c = c xor b; c -= rot(b, 24); values[0] = a; values[1] = b; values[2] = c
    }
    private fun hashlittle(data: ByteArray, seed: Int): Int {
        var offset = 0; var length = data.size; val v = intArrayOf(-559038737 + length + seed, -559038737 + length + seed, -559038737 + length + seed)
        while (length > 12) { addWord(v, 0, data, offset); addWord(v, 1, data, offset + 4); addWord(v, 2, data, offset + 8); mix(v); offset += 12; length -= 12 }
        for (i in 0 until min(length, 4)) v[0] += (data[offset + i].toInt() and 0xff) shl (8 * i)
        for (i in 4 until min(length, 8)) v[1] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 4))
        for (i in 8 until length) v[2] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 8))
        finish(v); return v[2]
    }
    private fun hashlittle2(data: ByteArray, pc: Int, pb: Int): Pair<Int, Int> {
        var offset = 0; var length = data.size; val initial = -559038737 + length + pc; val v = intArrayOf(initial, initial, initial + pb)
        while (length > 12) { addWord(v, 0, data, offset); addWord(v, 1, data, offset + 4); addWord(v, 2, data, offset + 8); mix(v); offset += 12; length -= 12 }
        for (i in 0 until min(length, 4)) v[0] += (data[offset + i].toInt() and 0xff) shl (8 * i)
        for (i in 4 until min(length, 8)) v[1] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 4))
        for (i in 8 until length) v[2] += (data[offset + i].toInt() and 0xff) shl (8 * (i - 8))
        finish(v); return v[2] to v[1]
    }
    private fun addWord(values: IntArray, index: Int, data: ByteArray, at: Int) { values[index] += (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or ((data[at + 2].toInt() and 0xff) shl 16) or (data[at + 3].toInt() shl 24) }
}
