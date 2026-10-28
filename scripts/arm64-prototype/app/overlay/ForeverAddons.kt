package app.gamenative

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Caller owns the shared storage/runtime lease. No client-wide preferences are read or written. */
internal object ForeverAddons {
    data class Addon(val id: String, val title: String, val version: String?, val enabled: Boolean,
        val folders: List<String>, val projectId: Long? = null, val fileId: Long? = null,
        val manageable: Boolean = true)
    data class PackageMetadata(val projectId: Long, val fileId: Long, val title: String, val version: String?,
        val requiredProjectIds: List<Long> = emptyList())
    class AddonException(val code: String) : IllegalStateException(code)

    class Store(root: File, private val revalidate: () -> Unit = {}) {
        private val root = root.toPath().toAbsolutePath().normalize()
        private val addons = this.root.resolve("Interface/AddOns")
        private val home = this.root.resolve("Interface/.forever-addons")
        private val disabled = home.resolve("disabled")
        private val stage = home.resolve("stage")
        private val trash = home.resolve("trash")
        private val state = home.resolve("state.json")
        private val journal = home.resolve("journal.json")

        fun hasPendingTransaction(): Boolean = checked {
            exists(journal) || exists(home.resolve("journal.json.tmp")) || exists(home.resolve("state.json.tmp"))
        }

        /** Read-only: an interrupted mutation needs explicit recovery under the durable gate. */
        fun list(): List<Addon> = checked {
            requireClean()
            inventory(readState())
        }

        fun setEnabled(id: String, enabled: Boolean) = checked {
            requireClean()
            val old = readState()
            val addon = inventory(old).singleOrNull { it.id == id } ?: fail("not-found")
            if (!addon.manageable) fail("unknown-folder")
            if (addon.enabled != enabled) transact(if (enabled) "enable" else "disable", addon, old)
        }

        fun uninstall(id: String) = checked {
            requireClean()
            val old = readState()
            val addon = inventory(old).singleOrNull { it.id == id } ?: fail("not-found")
            if (!addon.manageable) fail("unknown-folder")
            transact("uninstall", addon, old)
        }

        fun install(zip: File, metadata: PackageMetadata, onProgress: (String) -> Unit = {}) = checked {
            requireClean()
            if (metadata.projectId <= 0 || metadata.fileId <= 0 || metadata.title.length > 200 ||
                metadata.requiredProjectIds.any { it <= 0 } || metadata.requiredProjectIds.size > 100) fail("metadata")
            val old = readState()
            val current = inventory(old)
            if (old.any { it.projectId == metadata.projectId }) fail("conflict")
            metadata.requiredProjectIds.forEach { id ->
                if (current.none { it.projectId == id && it.enabled }) fail("dependency-missing")
            }
            // A stage without a journal was never published; reclaim only this reserved tree.
            deleteTree(stage)
            mkdir(stage)
            var published = false
            try {
                onProgress("Validando archivo…")
                val folders = extract(zip)
                val occupied = (children(addons) + children(disabled)).map { key(it.fileName.toString()) }.toSet()
                if (folders.any { key(it) in occupied }) fail("conflict")
                val addon = Addon("project:${metadata.projectId}", metadata.title,
                    metadata.version?.take(200), true, folders, metadata.projectId, metadata.fileId)
                onProgress("Instalando…")
                published = true
                transact("install", addon, old)
            } finally {
                if (!published || !exists(journal)) deleteTree(stage)
            }
        }

        /** Completes the already-confirmed operation; never calls a partial publication success. */
        fun recover() = checked {
            val tempJournal = home.resolve("journal.json.tmp")
            val tempState = home.resolve("state.json.tmp")
            if (exists(tempJournal)) {
                if (exists(journal) || exists(tempState)) fail("unsafe-state")
                // No client move can precede publication of the journal.
                if (!Files.isRegularFile(tempJournal, NOFOLLOW_LINKS)) fail("unsafe-state")
                remove(tempJournal)
                deleteTree(stage)
            }
            if (exists(journal)) finish(JSONObject(read(journal, MAX_STATE).toString(Charsets.UTF_8)))
            else if (exists(tempState)) fail("unsafe-state")
            inventory(readState())
            Unit
        }

        private fun transact(op: String, addon: Addon, old: List<Addon>) {
            addon.folders.forEach { folder ->
                val source = if (op == "install") stage.resolve(folder)
                    else (if (addon.enabled) addons else disabled).resolve(folder)
                validateTree(source)
            }
            val next = old.filterNot { it.projectId == addon.projectId && addon.projectId != null }.toMutableList()
            if (addon.projectId != null && op != "uninstall") next += addon.copy(enabled = op != "disable")
            val record = JSONObject().put("op", op).put("addon", encodeAddon(addon))
                .put("old", encodeState(old)).put("next", encodeState(next))
            write(journal, record.toString().toByteArray())
            finish(record)
        }

        private fun finish(record: JSONObject) {
            val op = record.getString("op")
            if (op !in setOf("install", "enable", "disable", "uninstall")) fail("unsafe-state")
            val addon = decodeAddon(record.getJSONObject("addon"))
            val old = decodeState(record.getJSONArray("old"))
            val next = decodeState(record.getJSONArray("next"))
            val expected = old.filterNot { it.projectId == addon.projectId && addon.projectId != null }.toMutableList()
            if (addon.projectId != null && op != "uninstall") expected += addon.copy(enabled = op != "disable")
            if (next != expected || (op == "install" && addon.projectId == null) ||
                (op == "enable" && addon.enabled) || (op == "disable" && !addon.enabled)) fail("unsafe-state")
            if (addon.projectId != null && op != "install" && old.singleOrNull { it.id == addon.id } != addon)
                fail("unsafe-state")
            if (op == "install" && old.any { it.projectId == addon.projectId || it.folders.any { f -> addon.folders.any { key(f) == key(it) } } })
                fail("unsafe-state")
            val disk = readState()
            if (disk != old && disk != next) fail("unsafe-state")
            val temporaryState = home.resolve("state.json.tmp")
            if (exists(temporaryState)) {
                if (!Files.isRegularFile(temporaryState, NOFOLLOW_LINKS)) fail("unsafe-state")
                remove(temporaryState)
            }
            for (folder in addon.folders) {
                val from = if (op == "install") stage.resolve(folder)
                    else (if (addon.enabled) addons else disabled).resolve(folder)
                val to = when (op) {
                    "disable" -> disabled.resolve(folder)
                    "uninstall" -> trash.resolve(folder)
                    else -> addons.resolve(folder)
                }
                val a = exists(from)
                val b = exists(to)
                if (a && b) fail("conflict")
                // After state publication, a crash may interrupt trash cleanup.
                if (!a && !b && op == "uninstall" && disk == next) continue
                if (a == b) fail("unsafe-state")
                val present = if (a) from else to
                // Cleanup can have removed the TOC after the committed uninstall state.
                if (!(op == "uninstall" && !a && disk == next) && toc(present) == null) fail("unknown-folder")
                validateTree(present)
                if (a) move(from, to)
            }
            write(state, encodeState(next).toString().toByteArray())
            if (op == "uninstall") deleteTree(trash)
            if (op == "install") deleteTree(stage)
            remove(journal)
        }

        private fun inventory(managed: List<Addon>): List<Addon> {
            val owned = managed.flatMap { it.folders }.map(::key).toSet()
            managed.forEach { addon -> addon.folders.forEach { folder ->
                val expected = (if (addon.enabled) addons else disabled).resolve(folder)
                val opposite = (if (addon.enabled) disabled else addons).resolve(folder)
                if (!exists(expected) || exists(opposite) || !Files.isDirectory(expected, NOFOLLOW_LINKS) || toc(expected) == null)
                    fail("unsafe-state")
            } }
            val result = managed.toMutableList()
            val seen = HashSet<String>()
            for (base in listOf(addons, disabled)) for (dir in children(base)) {
                val folder = dir.fileName.toString()
                if (folder.startsWith("Blizzard_", true)) continue
                folder(folder)
                if (!seen.add(key(folder))) fail("conflict")
                if (key(folder) in owned) continue
                val info = toc(dir)
                result += Addon("folder:$folder", info?.first ?: folder, info?.second,
                    base == addons, listOf(folder), manageable = info != null)
            }
            return result.sortedBy { key(it.title) }
        }

        private fun readState(): List<Addon> = if (!exists(state)) emptyList()
            else decodeState(JSONArray(read(state, MAX_STATE).toString(Charsets.UTF_8)))
        private fun encodeState(list: List<Addon>) = JSONArray().also { array -> list.forEach { array.put(encodeAddon(it)) } }
        private fun encodeAddon(addon: Addon) = JSONObject().put("title", addon.title)
            .put("version", addon.version ?: JSONObject.NULL).put("enabled", addon.enabled)
            .put("folders", JSONArray(addon.folders)).put("project", addon.projectId ?: JSONObject.NULL)
            .put("file", addon.fileId ?: JSONObject.NULL)
        private fun decodeAddon(o: JSONObject): Addon {
            val array = o.getJSONArray("folders")
            if (array.length() !in 1..1000) fail("unsafe-state")
            val folders = (0 until array.length()).map { array.getString(it).also(::folder) }
            if (folders.map(::key).distinct().size != folders.size) fail("unsafe-state")
            val project = if (o.isNull("project")) null else o.getLong("project").also { if (it <= 0) fail("unsafe-state") }
            val file = if (o.isNull("file")) null else o.getLong("file").also { if (it <= 0) fail("unsafe-state") }
            if ((project == null) != (file == null) || project == null && folders.size != 1) fail("unsafe-state")
            return Addon(if (project == null) "folder:${folders.single()}" else "project:$project",
                o.getString("title").take(200), if (o.isNull("version")) null else o.getString("version").take(200),
                o.getBoolean("enabled"), folders, project, file)
        }
        private fun decodeState(array: JSONArray): List<Addon> {
            if (array.length() > 1000) fail("unsafe-state")
            val result = (0 until array.length()).map { decodeAddon(array.getJSONObject(it)) }
            val folders = result.flatMap { it.folders }.map(::key)
            if (result.any { it.projectId == null } || result.map { it.projectId }.distinct().size != result.size ||
                folders.distinct().size != folders.size) fail("unsafe-state")
            return result
        }

        private fun extract(file: File): List<String> {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 22..MAX_ZIP) fail("archive")
            val count = validateCentralDirectory(file)
            val names = HashSet<String>()
            val tops = linkedSetOf<String>()
            var expanded = 0L
            var actual = 0
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++actual > MAX_ENTRIES) fail("archive")
                    val raw = entry.name
                    if (raw.length > 1024 || raw.contains('\\') || raw.contains(':') || raw.startsWith('/') ||
                        raw.any { it.code < 32 }) fail("archive")
                    val parts = raw.removeSuffix("/").split('/')
                    if (parts.size > 32 || parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') })
                        fail("archive")
                    folder(parts.first())
                    if (!names.add(key(raw.removeSuffix("/")))) fail("archive")
                    tops += parts.first()
                    if (tops.size > 1000) fail("archive")
                    val target = stage.resolve(parts.joinToString("/"))
                    if (entry.isDirectory) { mkdir(target); continue }
                    if (parts.size < 2 || entry.size !in 0..MAX_ENTRY) fail("archive")
                    mkdir(target.parent)
                    if (exists(target)) fail("archive")
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { input -> FileOutputStream(target.toFile()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n; expanded += n
                            if (size > MAX_ENTRY || expanded > MAX_EXPANDED) fail("archive")
                            crc.update(buffer, 0, n); output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    } }
                    if (size != entry.size || crc.value != entry.crc) fail("archive")
                }
            }
            if (actual != count || tops.isEmpty() || tops.any { toc(stage.resolve(it)) == null }) fail("archive")
            val folded = tops.map(::key)
            if (folded.distinct().size != tops.size) fail("archive")
            return tops.toList()
        }

        /** Bounded random-access scan rejects ZIP64, encryption, symlinks and other special entries. */
        private fun validateCentralDirectory(file: File): Int = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val tail = ByteArray(minOf(length, 65557).toInt())
            input.seek(length - tail.size); input.readFully(tail)
            val b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            var end = -1
            for (i in tail.size - 22 downTo 0) if (b.getInt(i) == 0x06054b50 && i + 22 + u16(b, i + 20) == tail.size) {
                end = i; break
            }
            if (end < 0) fail("archive")
            val count = u16(b, end + 10)
            val size = u32(b, end + 12)
            val offset = u32(b, end + 16)
            val boundary = length - tail.size + end
            if (u16(b, end + 4) != 0 || u16(b, end + 6) != 0 || u16(b, end + 8) != count ||
                count !in 1..MAX_ENTRIES || size == 0xffffffffL || offset + size != boundary) fail("archive")
            var pos = offset
            repeat(count) {
                if (pos + 46 > boundary) fail("archive")
                input.seek(pos)
                val bytes = ByteArray(46); input.readFully(bytes)
                val c = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val name = u16(c, 28); val extra = u16(c, 30); val comment = u16(c, 32)
                val mode = (u32(c, 38) ushr 16).toInt() and 0xf000
                if (c.getInt(0) != 0x02014b50 || u16(c, 8) and 1 != 0 || u16(c, 34) != 0 ||
                    u32(c, 20) == 0xffffffffL || u32(c, 24) == 0xffffffffL || u32(c, 42) == 0xffffffffL ||
                    (u16(c, 4) ushr 8 == 3 && mode !in setOf(0, 0x4000, 0x8000))) fail("archive")
                pos += 46L + name + extra + comment
                if (pos > boundary) fail("archive")
                input.seek(pos - extra - comment)
                val data = ByteArray(extra); input.readFully(data)
                val e = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                var j = 0
                while (j < extra) {
                    if (j + 4 > extra || u16(e, j) == 1) fail("archive")
                    j += 4 + u16(e, j + 2)
                    if (j > extra) fail("archive")
                }
            }
            if (pos != boundary) fail("archive")
            count
        }

        private fun toc(dir: Path): Pair<String?, String?>? {
            safe(dir)
            if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) fail("unsafe-path")
            val candidates = dir.toFile().listFiles()?.filter { it.name.endsWith(".toc", true) } ?: fail("io")
            val file = candidates.sortedBy { it.name }.firstOrNull() ?: return null
            val text = read(file.toPath(), MAX_TOC).toString(Charsets.UTF_8)
            fun field(name: String): String? = text.lineSequence().mapNotNull { line ->
                Regex("^\\s*##\\s*$name\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
            }.firstOrNull()?.replace(Regex("\\|c[0-9a-fA-F]{8}|\\|r"), "")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            return field("Title") to field("Version")
        }

        private fun requireClean() { if (hasPendingTransaction()) fail("recovery-required") }
        private fun key(name: String) = name.lowercase(Locale.ROOT)
        private fun folder(name: String) {
            if (name.isBlank() || name.length > 255 || name in setOf(".", "..") || name.startsWith("Blizzard_", true) ||
                name.any { it.code < 32 || it in "/\\:" } || name.endsWith('.') || name.endsWith(' ')) fail("unsafe-folder")
        }
        private fun safe(path: Path) {
            if (!path.startsWith(root)) fail("unsafe-path")
            var current = path.root
            for (part in path) { current = current.resolve(part); if (Files.isSymbolicLink(current)) fail("unsafe-path") }
        }
        private fun exists(path: Path): Boolean { safe(path); return Files.exists(path, NOFOLLOW_LINKS) }
        private fun children(path: Path): List<Path> {
            if (!exists(path)) return emptyList()
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) fail("unsafe-path")
            val files = path.toFile().listFiles() ?: fail("io")
            if (files.size > MAX_ENTRIES) fail("unsafe-state")
            return files.map { it.toPath().also { p -> safe(p); if (!Files.isDirectory(p, NOFOLLOW_LINKS)) fail("unsafe-path") } }
        }
        private fun validateTree(path: Path) {
            var count = 0
            fun visit(p: Path, depth: Int) {
                safe(p)
                if (++count > MAX_ENTRIES || depth > 32) fail("unsafe-path")
                if (Files.isDirectory(p, NOFOLLOW_LINKS)) {
                    (p.toFile().listFiles() ?: fail("io")).forEach { visit(it.toPath(), depth + 1) }
                } else if (!Files.isRegularFile(p, NOFOLLOW_LINKS)) fail("unsafe-path")
            }
            visit(path, 0)
        }
        private fun mkdir(path: Path) { safe(path); revalidate(); Files.createDirectories(path); safe(path) }
        private fun syncDirectory(path: Path) {
            // Like the updater, fail closed rather than claiming durability on an unsupported filesystem.
            FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
        }
        private fun move(from: Path, to: Path) {
            safe(from); safe(to)
            if (!exists(from) || exists(to)) fail("conflict")
            mkdir(to.parent); revalidate()
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(from.parent); syncDirectory(to.parent)
        }
        private fun remove(path: Path) { safe(path); revalidate(); Files.delete(path); syncDirectory(path.parent) }
        private fun deleteTree(path: Path) {
            if (!exists(path)) return
            validateTree(path)
            fun delete(p: Path) {
                safe(p)
                if (Files.isDirectory(p, NOFOLLOW_LINKS)) (p.toFile().listFiles() ?: fail("io")).forEach { delete(it.toPath()) }
                remove(p)
            }
            delete(path)
        }
        private fun read(path: Path, max: Int): ByteArray {
            safe(path)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("unsafe-state")
            val out = ByteArrayOutputStream()
            FileInputStream(path.toFile()).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > max) fail("unsafe-state"); out.write(buffer, 0, n) }
            }
            return out.toByteArray()
        }
        private fun write(path: Path, bytes: ByteArray) {
            if (bytes.size > MAX_STATE) fail("unsafe-state")
            mkdir(path.parent)
            val temp = path.resolveSibling("${path.fileName}.tmp")
            safe(path); safe(temp)
            if (exists(temp)) fail("recovery-required")
            revalidate()
            FileOutputStream(temp.toFile()).use { it.write(bytes); it.fd.sync() }
            revalidate()
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(path.parent)
        }
        private fun <T> checked(block: () -> T): T = try {
            safe(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) fail("unsafe-root")
            revalidate()
            block()
        } catch (failure: AddonException) { throw failure }
          catch (_: Exception) { throw AddonException("io") }
    }

    fun message(failure: Throwable): String = when ((failure as? AddonException)?.code) {
        "dependency-missing" -> "Instala y activa primero las dependencias requeridas desde CurseForge."
        "conflict" -> "Ya existe este addon o una de sus carpetas. No se sobrescribirá."
        "archive" -> "El ZIP está dañado, no es un paquete de addons admitido o supera los límites de seguridad."
        "recovery-required" -> "Hay una operación de addons pendiente. Usa Recuperar antes de continuar."
        "unknown-folder" -> "No se puede identificar esta carpeta como addon con seguridad. No se modificará."
        "unsafe-root", "unsafe-path", "unsafe-folder", "unsafe-state" -> "El almacenamiento o los datos de addons no son seguros. No se continuará."
        else -> "No se pudo completar la operación. Comprueba el almacenamiento y recupera cualquier operación pendiente."
    }
    private fun fail(code: String): Nothing = throw AddonException(code)
    private fun u16(b: ByteBuffer, p: Int) = b.getShort(p).toInt() and 0xffff
    private fun u32(b: ByteBuffer, p: Int) = b.getInt(p).toLong() and 0xffffffffL
    private const val MAX_ZIP = 64L * 1024 * 1024
    private const val MAX_EXPANDED = 256L * 1024 * 1024
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_STATE = 1024 * 1024
    private const val MAX_TOC = 256 * 1024
}
