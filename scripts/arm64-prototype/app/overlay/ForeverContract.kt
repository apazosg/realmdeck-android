package app.gamenative

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.json.JSONObject

object ForeverContract {
    private const val ASSET = "forever/contract.json"
    private const val PRIVATE_NAME = "forever/contract.json"

    fun installPrivateCopy(context: Context): File {
        val target = File(context.filesDir, PRIVATE_NAME)
        val expected = context.assets.open(ASSET).use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes()).toHex()
        }
        if (!target.isFile || sha256(target) != expected) {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET).use { input -> target.outputStream().use(input::copyTo) }
        }
        check(sha256(target) == expected) { "private Forever contract copy failed verification" }
        return target
    }

    fun read(context: Context): JSONObject = JSONObject(installPrivateCopy(context).readText())

    fun writeWtf(context: Context, clientDir: File) {
        val contract = read(context)
        val configName = contract.getJSONObject("launcher").getString("config_name")
        val portal = contract.getJSONObject("server").getString("portal")
        val locale = contract.getJSONObject("launcher").getString("text_locale")
        writeManagedWtf(clientDir, configName, portal, locale)
    }

    internal fun writeManagedWtf(clientDir: File, configName: String, portal: String, locale: String) {
        require(configName == "BetaSuspendedTest.wtf") { "refusing to write an unmanaged WTF file" }
        require(listOf(portal, locale).none { it.any { c -> c == '"' || c == '\n' || c == '\r' } })
        require(!Files.isSymbolicLink(clientDir.toPath())) { "client root is a symlink" }
        val root = clientDir.canonicalFile
        val directory = File(root, "WTF")
        require(!Files.isSymbolicLink(directory.toPath())) { "WTF directory is a symlink" }
        check(directory.isDirectory || directory.mkdirs()) { "could not create WTF directory" }
        val target = File(directory, configName)
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile.parentFile == directory) { "WTF target escaped managed directory" }
        require(!target.exists() || target.length() <= 1024 * 1024) { "WTF file is unexpectedly large" }
        val existing = if (target.isFile) target.readLines(Charsets.UTF_8) else emptyList()
        val managed = mapOf("portal" to portal, "textLocale" to locale)
        val seen = mutableSetOf<String>()
        val result = existing.mapNotNull { line ->
            val match = Regex("^\\s*SET\\s+(\\w+)\\s+.*$", RegexOption.IGNORE_CASE).matchEntire(line)
            val key = managed.keys.firstOrNull { it.equals(match?.groupValues?.getOrNull(1), ignoreCase = true) }
            if (key != null && key in managed) {
                if (seen.add(key)) "SET $key \"${managed.getValue(key)}\"" else null
            } else {
                line
            }
        }.toMutableList()
        managed.forEach { (key, value) -> if (key !in seen) result += "SET $key \"$value\"" }
        val text = result.joinToString("\n", postfix = "\n")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        java.nio.channels.FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) output.write(bytes)
            output.force(true)
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!temporary.renameTo(target)) error("could not atomically replace ${target.name}")
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
