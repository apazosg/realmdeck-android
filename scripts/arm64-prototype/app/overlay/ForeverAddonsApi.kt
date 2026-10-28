package app.gamenative

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Public addon routes only. No diagnostics capability or CurseForge key is used here. */
internal object ForeverAddonsApi {
    private const val BASE = "https://wowforever-diagnostics.vercel.app/api/addons/"
    private const val MAX_ZIP = 64L * 1024 * 1024
    data class Project(val id: Long, val name: String, val summary: String, val authors: List<String>, val websiteUrl: String)
    data class Search(val projects: List<Project>, val index: Int, val nextIndex: Int?)
    data class Dependency(val projectId: Long, val name: String)
    data class Package(val projectId: Long, val fileId: Long, val name: String, val fileName: String,
        val size: Long, val sha1: String, val requiredDependencies: List<Dependency>)
    class ApiException(val code: String) : IllegalStateException(code)

    private fun connection(route: String): HttpURLConnection = (URL(BASE + route).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Accept", "application/json")
    }

    private fun error(connection: HttpURLConnection): Nothing {
        val code = runCatching {
            connection.errorStream?.use { input ->
                val data = bounded(input, 8192)
                if (data.size > 8192) null else JSONObject(String(data, Charsets.UTF_8)).optString("error")
            }
        }.getOrNull()
        throw ApiException(when (code) {
            "service-not-configured", "access-unavailable", "rate-limited", "no-compatible-file",
            "distribution-disabled", "compatibility-unverified", "upstream-timeout", "upstream-unavailable" -> code!!
            else -> when (connection.responseCode) {
                401, 403 -> "access-unavailable"
                429 -> "rate-limited"
                503 -> "service-not-configured"
                else -> "service-unavailable"
            }
        })
    }

    private fun json(route: String): JSONObject {
        val conn = connection(route)
        try {
            if (conn.responseCode != 200) error(conn)
            return conn.inputStream.use { input ->
                val bytes = bounded(input, 2 * 1024 * 1024)
                if (bytes.size > 2 * 1024 * 1024) throw ApiException("invalid-response")
                JSONObject(String(bytes, Charsets.UTF_8))
            }
        } finally { conn.disconnect() }
    }

    fun search(query: String, index: Int = 0): Search {
        require(query.trim().length in 1..100 && index in 0..10_000)
        val result = json("search?query=${URLEncoder.encode(query.trim(), "UTF-8")}&index=$index")
        if (result.getInt("index") != index) throw ApiException("invalid-response")
        val projects = result.getJSONArray("projects")
        if (projects.length() > 20) throw ApiException("invalid-response")
        return Search((0 until projects.length()).map { i ->
            val item = projects.getJSONObject(i)
            val authors = item.optJSONArray("authors") ?: JSONArray()
            val link = item.optString("websiteUrl").takeIf {
                it.startsWith("https://www.curseforge.com/wow/addons/")
            }.orEmpty()
            Project(positive(item, "id"), item.getString("name").take(200), item.optString("summary").take(1000),
                (0 until minOf(authors.length(), 10)).map { authors.getString(it).take(100) }, link)
        }, result.getInt("index"), if (result.isNull("nextIndex")) null else result.getInt("nextIndex").also {
            if (it <= index || it > 10_000) throw ApiException("invalid-response")
        })
    }

    fun file(projectId: Long): Package {
        require(projectId > 0)
        val item = json("file?projectId=$projectId")
        if (!item.getBoolean("compatible") || positive(item, "projectId") != projectId) throw ApiException("invalid-response")
        val size = item.getLong("size")
        val hash = item.getString("sha1").lowercase()
        if (size !in 1..MAX_ZIP || !hash.matches(Regex("[a-f0-9]{40}"))) throw ApiException("invalid-response")
        val deps = item.getJSONArray("requiredDependencies")
        if (deps.length() > 100) throw ApiException("invalid-response")
        return Package(projectId, positive(item, "fileId"), item.getString("name").take(200),
            item.getString("fileName").take(200), size, hash, (0 until deps.length()).map { i ->
                val dep = deps.getJSONObject(i)
                Dependency(positive(dep, "projectId"), dep.getString("name").take(200))
            })
    }

    /** The caller deletes the returned private-cache ZIP in finally, after Store.install. */
    fun download(cacheDir: File, file: Package, onProgress: (Long, Long) -> Unit): File {
        val target = File.createTempFile("forever-addon-", ".zip", cacheDir)
        val conn = connection("download?projectId=${file.projectId}&fileId=${file.fileId}")
        val started = System.nanoTime()
        try {
            conn.setRequestProperty("Accept", "application/zip")
            if (conn.responseCode != 200) error(conn)
            val advertised = conn.getHeaderField("Content-Length")?.toLongOrNull()
            if (advertised != null && advertised != file.size) throw ApiException("download-invalid")
            val hash = MessageDigest.getInstance("SHA-1")
            var count = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 120_000_000_000L)
                            throw ApiException("upstream-timeout")
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > file.size || count > MAX_ZIP) throw ApiException("download-invalid")
                        output.write(buffer, 0, n)
                        hash.update(buffer, 0, n)
                        onProgress(count, file.size)
                    }
                    output.fd.sync()
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            if (count != file.size || actual != file.sha1) throw ApiException("download-invalid")
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally { conn.disconnect() }
    }

    private fun positive(value: JSONObject, key: String): Long = value.getLong(key).also {
        if (it <= 0) throw ApiException("invalid-response")
    }

    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + 60_000_000_000L
        while (true) {
            if (System.nanoTime() > deadline) throw ApiException("upstream-timeout")
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) throw ApiException("invalid-response")
            out.write(buffer, 0, n)
        }
    }

    fun message(failure: Throwable): String = when ((failure as? ApiException)?.code) {
        "service-not-configured" -> "La búsqueda de CurseForge aún no está configurada. La gestión local sigue disponible."
        "access-unavailable" -> "CurseForge no ha autorizado el acceso. Inténtalo cuando el servicio esté habilitado."
        "rate-limited" -> "Se ha alcanzado el límite temporal de CurseForge. Espera antes de reintentar."
        "no-compatible-file" -> "No hay un archivo admitido para Forever 1.60.1. No se instalará otra variante."
        "compatibility-unverified" -> "No se ha podido confirmar el catálogo de Forever 1.60.1. No se ofrecerán otras variantes."
        "distribution-disabled" -> "El autor no permite esta descarga desde aplicaciones de terceros."
        "download-invalid", "invalid-response" -> "La descarga o respuesta no supera la verificación. No se ha instalado el addon."
        "upstream-timeout" -> "El servicio ha tardado demasiado. Comprueba la conexión y vuelve a intentarlo."
        else -> if (failure is java.io.IOException) "No se pudo conectar. Comprueba tu conexión e inténtalo de nuevo."
            else "No se pudo completar la consulta de CurseForge. Inténtalo de nuevo más tarde."
    }
}
