package app.gamenative

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private typealias ForeverConnectionOpener = (String, Long, Long, Boolean) -> HttpURLConnection

/** Finite, catalog-driven provisioner for the approved ARM64 bootstrap. */
object ForeverClientProvisioner {
    private const val MAX_TRANSFER = 1L shl 30
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val SPACE_GUARD_BYTES = 64L * 1024 * 1024
    private const val PINNED_BUILD_CONFIG = "05215079e3905ef5922ae0b03ffefb73"
    private const val PINNED_CDN_CONFIG = "9b3c456dbb837d133a026d380c7c13e9"
    private val ALLOWED_HOSTS = setOf("level3.blizzard.com", "us.cdn.blizzard.com")
    private val REQUIRED_ROLES = setOf("root", "vfs-root", "encoding", "install", "download", "size")

    data class Plan(val downloadBytes: Long, val workspaceBytes: Long)

    fun plan(context: Context): Plan {
        val catalog = loadCatalog(context)
        val bounds = catalog.getJSONObject("bounds")
        return Plan(
            bounds.getLong("android_initial_transfer_bytes"),
            requiredSpace(catalog, existingCasc = false),
        )
    }

    suspend fun ensure(
        context: Context,
        installDir: File,
        cancelled: () -> Boolean,
        progress: (String) -> Unit,
    ): File {
        val catalog = loadCatalog(context)
        checkCancelled(cancelled)
        val root = checkedRoot(installDir)
        val cache = managedFile(root, ".forever-cache")
        val staging = managedFile(root, ".forever-staging")
        prepareManagedDirectory(cache, "encoded-cache-v1")
        prepareManagedDirectory(staging, "provisioner-staging-v1")

        val exeRelative = safePath(catalog.getJSONObject("executable").getString("relative_path"))
        val loose = catalog.getJSONArray("loose_files")
        val specs = runtimeSpecs(catalog)
        val data = managedFile(root, "Data")
        recoverPromotion(root, staging, data, catalog, specs, loose, cancelled)
        val existingCasc = data.exists()
        checkSpace(root, requiredSpace(catalog, existingCasc))
        if (existingCasc) {
            if (!data.isDirectory || Files.isSymbolicLink(data.toPath())) {
                throw IllegalStateException("existing Data is not a regular directory; refusing to replace it")
            }
            progress("client: verifying existing CASC store")
            ForeverCasc.verifyExisting(root, specs, cancelled)
            verifyExistingMetadata(root, catalog, cancelled)
            repairLoose(root, cache, loose, cancelled, progress)
            val verified = managedFile(root, exeRelative)
            verifyLooseFile(verified, catalog.getJSONObject("executable"), "executable")
            return verified
        }

        val managedStage = managedFile(staging, ".forever-provisioner-owned")
        if (!managedStage.exists()) writeOwnedText(managedStage, "managed-provisioner-v1\n")
        val configFiles = downloadConfigs(catalog, cache, staging, cancelled, progress)
        val indexFiles = downloadIndices(catalog, cache, staging, cancelled, progress)
        val runtimeObjects = downloadRuntimeObjects(catalog, cache, cancelled, progress)
        downloadLooseFiles(loose, root, cache, staging, cancelled, progress)

        checkCancelled(cancelled)
        progress("client: materializing verified CASC bootstrap")
        val buildInfo = buildInfoBytes(catalog)
        ForeverCasc.materialize(staging, buildInfo, configFiles, indexFiles, runtimeObjects, cancelled)
        ForeverCasc.verifyExisting(staging, specs, cancelled)
        verifyStagedLoose(staging, loose, cancelled)
        checkCancelled(cancelled)
        writePhase(staging, "READY_TO_PROMOTE")

        // Data is the one coherent CASC unit.  It is never replaced when an
        // existing install is found; this branch is only reached for a new
        // store, so an external Data directory is a hard conflict.
        if (data.exists()) throw IllegalStateException("Data appeared during provisioning; refusing to replace it")
        atomicMove(managedFile(staging, "Data"), data)
        writePhase(staging, "DATA_PROMOTED")
        val stageBuildInfo = managedFile(staging, ".build.info")
        atomicMove(stageBuildInfo, managedFile(root, ".build.info"))
        writePhase(staging, "METADATA_PROMOTED")
        promoteLooseFiles(root, staging, loose, cancelled)
        val verified = managedFile(root, exeRelative)
        verifyLooseFile(verified, catalog.getJSONObject("executable"), "executable")
        checkCancelled(cancelled)
        writeReadinessMarker(root, catalog)
        return verified
    }

    private fun loadCatalog(context: Context): JSONObject = ForeverClientCatalog.load(context)

    internal fun validateCatalog(catalog: JSONObject) {
        require(catalog.optString("schema") == "wowforever.arm64-client-catalog") { "unsupported client catalog schema" }
        require(catalog.optInt("schema_version") == 1) { "unsupported client catalog version" }
        val provenance = catalog.getJSONObject("provenance")
        require(provenance.getString("product") == "wow_classic_beta") { "wrong catalog product" }
        require(provenance.getString("platform") == "Windows" && provenance.getString("arch") == "arm64" && provenance.getString("locale") == "enUS") { "catalog is not Windows ARM64 enUS" }
        require(provenance.getString("version") == "1.60.1.70009") { "wrong pinned client version" }
        require(provenance.getString("cdn_path") == "tpr/wow") { "wrong CDN path" }
        require(provenance.getString("build_config_hash").equals(PINNED_BUILD_CONFIG, true)) { "wrong pinned build config" }
        require(provenance.getString("cdn_config_hash").equals(PINNED_CDN_CONFIG, true)) { "wrong pinned CDN config" }
        validateBuildFields(catalog)

        val bounds = catalog.getJSONObject("bounds")
        val actualBounds = recomputeBounds(catalog)
        require(bounds.getLong("android_initial_transfer_bytes") == actualBounds.transfer) { "catalog transfer bound does not match records" }
        require(bounds.getLong("workspace_encoded_bytes_without_full_game") == actualBounds.workspaceEncoded) { "catalog encoded workspace bound does not match records" }
        require(bounds.getLong("workspace_expanded_bytes_without_full_game") == actualBounds.workspaceExpanded) { "catalog expanded workspace bound does not match records" }
        val transfer = actualBounds.transfer
        require(transfer in 0..MAX_TRANSFER) { "catalog transfer exceeds 1 GiB cap" }
        require(bounds.getLong("workspace_encoded_bytes_without_full_game") >= 0 && bounds.getLong("workspace_expanded_bytes_without_full_game") >= 0) { "invalid catalog workspace bounds" }

        val paths = HashSet<String>()
        val indices = catalog.getJSONObject("casc_bootstrap").getJSONArray("cdn_indices")
        val indexHashes = HashSet<String>()
        val archiveHashes = HashSet<String>()
        val fileIndexHashes = HashSet<String>()
        for (index in 0 until indices.length()) {
            val item = indices.getJSONObject(index)
            val hash = item.getString("hash")
            requireHex(hash, 32, "CDN index hash")
            val normalized = hash.lowercase(Locale.US)
            require(indexHashes.add(normalized)) { "duplicate CDN index" }
            when (item.getString("index_role")) {
                "archive-index" -> {
                    require(item.getInt("offset_bytes") == 4) { "unsupported archive-index offset width" }
                    archiveHashes.add(normalized)
                }
                "file-index" -> {
                    require(item.getInt("offset_bytes") == 0) { "unsupported file-index offset width" }
                    fileIndexHashes.add(normalized)
                }
                else -> throw IllegalArgumentException("unsupported CDN index role")
            }
            addUnique(paths, safePath(item.getString("relative_path")), "catalog path")
            validatePresentSha(item, "CDN index")
            validateCdnUrl(item.getString("url"), hash, "index", ".index")
        }
        val loose = catalog.getJSONArray("loose_files")
        for (index in 0 until loose.length()) {
            val item = loose.getJSONObject(index)
            val path = safePath(item.getString("relative_path"))
            addUnique(paths, path, "catalog path")
            requireHex(item.getString("content_key"), 32, "loose content key")
            requireHex(item.optString("decoded_md5", item.getString("content_key")), 32, "loose decoded MD5")
            require(item.optString("decoded_md5", item.getString("content_key")).equals(item.getString("content_key"), true)) { "loose decoded MD5 does not match its content key" }
            require(item.getLong("decoded_size") >= 0) { "negative loose file size" }
            validateSources(item, "loose", archiveHashes, fileIndexHashes)
        }
        val executable = catalog.getJSONObject("executable")
        require(executable.getString("relative_path").equals("WowB-ARM64.exe", true)) { "wrong catalog executable" }
        require(executable.getJSONObject("architecture").getString("expected_machine").equals("0xaa64", true)) { "catalog does not require PE ARM64" }

        val configs = catalog.getJSONArray("required_configs")
        val configKinds = HashSet<String>()
        for (index in 0 until configs.length()) {
            val item = configs.getJSONObject(index)
            require(item.getString("kind") in setOf("build", "cdn")) { "unsupported config kind" }
            require(configKinds.add(item.getString("kind"))) { "duplicate config kind" }
            requireHex(item.getString("hash"), 32, "config hash")
            addUnique(paths, safePath(item.getString("relative_path")), "catalog path")
            validatePresentSha(item, "config")
            validateCdnUrl(item.getString("url"), item.getString("hash"), "config", "")
        }
        require(configKinds == setOf("build", "cdn")) { "build and CDN configs are required" }

        val buildInfo = catalog.getJSONObject("build_info")
        require(safePath(buildInfo.getString("relative_path")) == ".build.info") { "unsafe build-info path" }
        addUnique(paths, ".build.info", "catalog path")
        requireHex(buildInfo.getString("serialized_sha256"), 64, "build-info SHA-256")

        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        val roles = HashSet<String>()
        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            requireHex(item.getString("content_key"), 32, "runtime content key")
            require(item.optString("decoded_md5", item.getString("content_key")).equals(item.getString("content_key"), true)) { "runtime decoded MD5 does not match its content key" }
            require(item.getLong("decoded_size") >= 0 && item.getLong("encoded_size") > 0) { "invalid runtime object size" }
            val itemRoles = item.optJSONArray("roles") ?: JSONArray()
            for (roleIndex in 0 until itemRoles.length()) roles += itemRoles.getString(roleIndex)
            validateSources(item, "runtime object", archiveHashes, fileIndexHashes)
        }
        require(roles.containsAll(REQUIRED_ROLES)) { "catalog is missing required runtime roles" }
        require(catalog.getJSONObject("runtime_metadata").optBoolean("resolved_and_verified", false)) { "catalog runtime references are not resolved" }
        require(catalog.getJSONObject("completeness").optBoolean("launch_ready", false)) { "catalog is explicitly blocked" }
    }

    internal fun validatePathForTest(value: String): String = safePath(value)
    internal fun managedPathForTest(base: File, value: String): File = managedFile(base, value)
    internal fun checkedRootForTest(directory: File): File = checkedRoot(directory)
    internal fun requiredSpaceForTest(catalog: JSONObject, existingCasc: Boolean): Long = requiredSpace(catalog, existingCasc)
    internal fun downloadRecordForTest(record: JSONObject, cache: File, cancelled: () -> Boolean, opener: (String, Long, Long, Boolean) -> HttpURLConnection): File =
        downloadRecord(record, cache, cancelled, {}, opener)

    private fun validateBuildFields(catalog: JSONObject) {
        val fields = catalog.getJSONObject("build_info").getJSONObject("fields")
        require(fields.getString("product") == "wow_classic_beta" && fields.getString("architecture") == "arm64") { "build-info architecture/product mismatch" }
        require(fields.getString("platform") == "Windows" && fields.getString("locale") == "enUS") { "build-info tag mismatch" }
        val metadata = catalog.getJSONObject("build_metadata").getJSONObject("fields")
        for (name in listOf("root", "encoding", "install", "download", "size", "vfs_root")) {
            val value = metadata.optString(name)
            require(value.split(Regex("\\s+")).all { it.matches(Regex("[0-9a-fA-F]{32}")) }) { "malformed build reference: $name" }
        }
    }

    private data class Bounds(val transfer: Long, val workspaceEncoded: Long, val workspaceExpanded: Long)

    private fun recomputeBounds(catalog: JSONObject): Bounds {
        var transfer = 0L
        var workspaceEncoded = 0L
        var workspaceExpanded = 0L
        val loose = catalog.getJSONArray("loose_files")
        for (index in 0 until loose.length()) {
            val item = loose.getJSONObject(index)
            val decoded = item.getLong("decoded_size")
            val encoded = item.getLong("encoded_size")
            transfer = safeAdd(transfer, encoded)
            workspaceEncoded = safeAdd(workspaceEncoded, decoded)
            workspaceExpanded = safeAdd(workspaceExpanded, decoded)
        }
        val configs = catalog.getJSONArray("required_configs")
        for (index in 0 until configs.length()) transfer = safeAdd(transfer, configs.getJSONObject(index).getLong("observed_encoded_size"))
        val indices = catalog.getJSONObject("casc_bootstrap").getJSONArray("cdn_indices")
        var indexBytes = 0L
        for (index in 0 until indices.length()) indexBytes = safeAdd(indexBytes, indices.getJSONObject(index).getLong("observed_encoded_size"))
        transfer = safeAdd(transfer, indexBytes)
        workspaceEncoded = safeAdd(workspaceEncoded, indexBytes)
        workspaceExpanded = safeAdd(workspaceExpanded, indexBytes)
        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        val seenEkeys = HashSet<String>()
        var objectCount = 0L
        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            val roles = item.optJSONArray("roles") ?: continue
            if ((0 until roles.length()).none { roles.getString(it) in REQUIRED_ROLES || roles.getString(it).startsWith("vfs-") }) continue
            val ekey = item.getJSONArray("encoding_keys").getString(0).lowercase(Locale.US)
            if (!seenEkeys.add(ekey)) continue
            objectCount = safeAdd(objectCount, 1)
            transfer = safeAdd(transfer, item.getLong("encoded_size"))
            workspaceEncoded = safeAdd(workspaceEncoded, item.getLong("encoded_size"))
            workspaceExpanded = safeAdd(workspaceExpanded, item.getLong("decoded_size"))
        }
        workspaceEncoded = safeAdd(workspaceEncoded, safeMultiply(objectCount, 30L))
        workspaceExpanded = safeAdd(workspaceExpanded, safeMultiply(objectCount, 30L))
        val localIndexBytes = catalog.getJSONObject("bounds").getLong("local_index_bytes")
        require(localIndexBytes == safeMultiply(16L, 0x30000L)) { "unsupported local index bound" }
        workspaceEncoded = safeAdd(workspaceEncoded, localIndexBytes)
        workspaceExpanded = safeAdd(workspaceExpanded, localIndexBytes)
        val buildInfo = catalog.getJSONObject("build_info")
        val buildInfoBytes = buildInfo.getLong("serialized_size")
        transfer = safeAdd(transfer, buildInfoBytes)
        workspaceEncoded = safeAdd(workspaceEncoded, buildInfoBytes)
        workspaceExpanded = safeAdd(workspaceExpanded, buildInfoBytes)
        // Required configs are retained in the encoded cache and in the
        // materialized store, so both workspace estimates include them.
        var configBytes = 0L
        for (index in 0 until configs.length()) configBytes = safeAdd(configBytes, configs.getJSONObject(index).getLong("observed_encoded_size"))
        workspaceEncoded = safeAdd(workspaceEncoded, configBytes)
        workspaceExpanded = safeAdd(workspaceExpanded, configBytes)
        return Bounds(transfer, workspaceEncoded, workspaceExpanded)
    }

    private fun validateSources(item: JSONObject, description: String, archiveHashes: Set<String>, fileIndexHashes: Set<String>) {
        val sources = item.optJSONArray("sources") ?: throw IllegalArgumentException("$description has no source")
        val encodingKeys = item.getJSONArray("encoding_keys")
        require(sources.length() == 1 && encodingKeys.length() == 1) { "$description must have exactly one pinned source/encoding key" }
        requireHex(encodingKeys.getString(0), 32, "$description pinned encoding key")
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val kind = source.getString("kind")
            require(kind == "direct" || kind == "archive-range") { "unsupported $description source: $kind" }
            requireHex(source.getString("ekey"), 32, "$description encoding key")
            require(source.getString("ekey").equals(encodingKeys.getString(0), true)) { "$description source does not match its pinned encoding key" }
            val size = source.getLong("encoded_size")
            require(size > 0 && size == item.getLong("encoded_size")) { "invalid $description encoded size binding" }
            validateCdnUrl(source.getString("url"), if (kind == "direct") source.getString("ekey") else source.getString("archive"), "data", "")
            if (kind == "direct") {
                require(source.getString("ekey").lowercase(Locale.US) !in fileIndexHashes) { "CDN file-index cannot be used as a direct payload" }
                requireHex(source.getString("sha256"), 64, "$description encoded SHA-256")
            } else {
                val archive = source.getString("archive").lowercase(Locale.US)
                requireHex(archive, 32, "$description archive hash")
                require(archive !in fileIndexHashes) { "CDN file-index cannot be used as an archive payload" }
                require(archive in archiveHashes) { "archive source is not bound to a pinned archive-index" }
                val offset = source.getLong("offset")
                require(offset >= 0 && source.getLong("length") == size) { "invalid $description archive range" }
                val range = source.getString("range")
                require(range == "bytes=$offset-${safeAdd(offset, size) - 1}") { "archive range does not match its offset/length" }
            }
        }
    }

    private fun validatePresentSha(item: JSONObject, description: String) {
        requireHex(item.optString("present_sha256", item.optString("sha256")), 64, "$description SHA-256")
        require(item.getLong("observed_encoded_size") > 0) { "$description has no observed size" }
    }

    private fun validateCdnUrl(value: String, hash: String, kind: String, suffix: String) {
        val uri = URI(value)
        requireOfficialAuthority(uri)
        val expected = "/tpr/wow/${if (kind == "config") "config" else "data"}/${hash.substring(0, 2)}/${hash.substring(2, 4)}/${hash}$suffix"
        require(uri.path == expected) { "CDN URL does not match its hash" }
    }

    private fun runtimeSpecs(catalog: JSONObject): List<ForeverCasc.RuntimeObjectSpec> {
        val result = ArrayList<ForeverCasc.RuntimeObjectSpec>()
        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            val roles = item.optJSONArray("roles") ?: continue
            if ((0 until roles.length()).none { roles.getString(it) in REQUIRED_ROLES || roles.getString(it).startsWith("vfs-") }) continue
            result += ForeverCasc.RuntimeObjectSpec(item.getJSONArray("encoding_keys").getString(0), item.getString("content_key"), item.getLong("decoded_size"))
        }
        return result
    }

    private fun downloadConfigs(catalog: JSONObject, cache: File, staging: File, cancelled: () -> Boolean, progress: (String) -> Unit): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        val records = catalog.getJSONArray("required_configs")
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val record = records.getJSONObject(index)
            val hash = record.getString("hash")
            val cached = downloadRecord(record, cache, cancelled, progress)
            val target = managedFile(staging, safePath(record.getString("relative_path")).removePrefix("Data/"))
            copyVerified(cached, target, record.getLong("observed_encoded_size"), recordSha(record), cancelled)
            result += hash to target
        }
        return result
    }

    private fun downloadIndices(catalog: JSONObject, cache: File, staging: File, cancelled: () -> Boolean, progress: (String) -> Unit): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        val records = catalog.getJSONObject("casc_bootstrap").getJSONArray("cdn_indices")
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val record = records.getJSONObject(index)
            val hash = record.getString("hash")
            val cached = downloadRecord(record, cache, cancelled, progress)
            val target = managedFile(staging, safePath(record.getString("relative_path")).removePrefix("Data/"))
            copyVerified(cached, target, record.getLong("observed_encoded_size"), recordSha(record), cancelled)
            result += hash to target
        }
        return result
    }

    private fun downloadRuntimeObjects(catalog: JSONObject, cache: File, cancelled: () -> Boolean, progress: (String) -> Unit): List<ForeverCasc.RuntimeObject> {
        val result = ArrayList<ForeverCasc.RuntimeObject>()
        val seen = HashSet<String>()
        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        for (index in 0 until objects.length()) {
            checkCancelled(cancelled)
            val record = objects.getJSONObject(index)
            val roles = record.optJSONArray("roles") ?: continue
            if ((0 until roles.length()).none { roles.getString(it) in REQUIRED_ROLES || roles.getString(it).startsWith("vfs-") }) continue
            val ekey = record.getJSONArray("encoding_keys").getString(0)
            if (!seen.add(ekey)) continue
            val cached = downloadRecord(record, cache, cancelled, progress)
            ForeverBlte.verify(cached, record.getLong("decoded_size"), record.getString("content_key"), cancelled)
            result += ForeverCasc.RuntimeObject(record, cached)
        }
        return result
    }

    private fun downloadLooseFiles(records: JSONArray, root: File, cache: File, staging: File, cancelled: () -> Boolean, progress: (String) -> Unit) {
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val record = records.getJSONObject(index)
            val encoded = downloadRecord(record, cache, cancelled, progress)
            val relative = safePath(record.getString("relative_path"))
            val target = managedFile(staging, relative)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.decode.part")
            ForeverBlte.decode(encoded, temporary, record.getLong("decoded_size"), record.optString("decoded_md5", record.getString("content_key")), cancelled)
            checkCancelled(cancelled)
            atomicMove(temporary, target)
        }
    }

    private fun repairLoose(root: File, cache: File, records: JSONArray, cancelled: () -> Boolean, progress: (String) -> Unit) {
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val record = records.getJSONObject(index)
            val target = managedFile(root, safePath(record.getString("relative_path")))
            if (isValidLoose(target, record)) continue
            val encoded = downloadRecord(record, cache, cancelled, progress)
            val temporary = File(target.parentFile, ".${target.name}.repair.part")
            target.parentFile?.mkdirs()
            ForeverBlte.decode(encoded, temporary, record.getLong("decoded_size"), record.optString("decoded_md5", record.getString("content_key")), cancelled)
            checkCancelled(cancelled)
            atomicMove(temporary, target)
        }
    }

    private fun downloadRecord(record: JSONObject, cache: File, cancelled: () -> Boolean, progress: (String) -> Unit, opener: ForeverConnectionOpener = ::openHttp): File {
        val source = if (record.has("sources")) {
            record.getJSONArray("sources").getJSONObject(0)
        } else {
            JSONObject()
                .put("kind", "direct")
                .put("ekey", record.getString("hash"))
                .put("url", record.getString("url"))
                .put("encoded_size", record.getLong("observed_encoded_size"))
                .put("sha256", recordSha(record))
        }
        require(source.optString("kind") != "unresolved") { "catalog source is unresolved" }
        val ekey = source.getString("ekey")
        val expectedSize = source.optLong("encoded_size", record.optLong("encoded_size"))
        val final = managedFile(cache, ekey)
        val sha = source.optString("sha256").takeIf { it.isNotEmpty() }
        if (isValidEncoded(final, expectedSize, sha)) {
            if (source.getString("kind") != "archive-range" || verifyArchiveObject(final, record, cancelled)) return final
            // This is an owned cache object.  A same-sized range object can
            // still be corrupt; invalidate it before making a fresh request.
            final.delete()
        }
        if (final.exists()) final.delete()
        val part = managedFile(cache, "$ekey.part")
        if (part.length() > expectedSize) part.delete()
        // A complete .part is a resumable download only after its full
        // integrity check.  Never issue bytes=N-(N-1), and never preserve a
        // corrupt owned part that would poison every retry.
        if (part.length() == expectedSize) {
            if (isValidEncoded(part, expectedSize, sha) && (source.getString("kind") != "archive-range" || verifyArchiveObject(part, record, cancelled))) {
                atomicMove(part, final)
                return final
            }
            if (part.exists()) part.delete()
        }
        val offset = part.length()
        progress("client: downloading $ekey (${expectedSize} bytes)")
        try {
            streamHttp(source, expectedSize, offset, part, cancelled, opener)
        } catch (failure: Throwable) {
            if (failure !is CancellationException && part.isFile && part.length() >= expectedSize) part.delete()
            throw failure
        }
        checkCancelled(cancelled)
        if (!isValidEncoded(part, expectedSize, sha)) {
            if (part.exists()) part.delete()
            throw IllegalStateException("downloaded object failed size/SHA-256 verification: $ekey")
        }
        if (source.getString("kind") == "archive-range") {
            try {
                verifyArchiveObjectOrThrow(part, record, cancelled)
            } catch (failure: Throwable) {
                if (failure !is CancellationException && part.exists()) part.delete()
                throw failure
            }
        }
        atomicMove(part, final)
        return final
    }

    private fun streamHttp(source: JSONObject, expectedSize: Long, existing: Long, destination: File, cancelled: () -> Boolean, opener: ForeverConnectionOpener = ::openHttp) {
        val start = if (source.getString("kind") == "archive-range") source.getLong("offset") + existing else existing
        val end = if (source.getString("kind") == "archive-range") source.getLong("offset") + expectedSize - 1 else expectedSize - 1
        require(start <= end + 1) { "invalid resume range" }
        var url = source.getString("url")
        var connection: HttpURLConnection? = null
        try {
            repeat(4) { redirect ->
                val ranged = existing > 0 || source.getString("kind") == "archive-range"
                connection = opener(url, start, end, ranged)
                val response = connection!!.responseCode
                if (response in 300..399) {
                    val location = connection!!.getHeaderField("Location") ?: error("CDN redirect has no Location")
                    connection!!.disconnect()
                    val next = URL(url).toURI().resolve(location).toURL()
                    validateRedirectUrl(next.toURI(), URI(url).path)
                    url = next.toString()
                    if (redirect == 3) error("too many CDN redirects")
                } else {
                    require(response == if (ranged) HttpURLConnection.HTTP_PARTIAL else HttpURLConnection.HTTP_OK) {
                        "unexpected CDN HTTP status $response: kind=${source.getString("kind")} url=$url range=${if (ranged) "bytes=$start-$end" else "none"}"
                    }
                    if (ranged) validateContentRange(connection!!, start, end, if (source.getString("kind") == "direct") expectedSize else null)
                    val expectedNetworkLength = end - start + 1
                    require(connection!!.contentLengthLong == expectedNetworkLength) { "CDN Content-Length mismatch" }
                    val append = existing > 0
                    destination.parentFile?.mkdirs()
                    BufferedInputStream(connection!!.inputStream, BUFFER_SIZE).use { input ->
                        openOwnedOutput(destination, append).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var received = 0L
                            while (received < expectedNetworkLength) {
                                checkCancelled(cancelled)
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedNetworkLength - received).toInt())
                                if (n < 0) throw IllegalStateException("truncated CDN response")
                                if (n == 0) continue
                                output.write(buffer, 0, n)
                                received += n
                            }
                            require(input.read() < 0) { "CDN returned bytes beyond requested range" }
                        }
                    }
                    return
                }
            }
            error("CDN redirect loop")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openHttp(value: String, start: Long, end: Long, ranged: Boolean): HttpURLConnection {
        val uri = URI(value)
        requireOfficialAuthority(uri)
        val connection = ForeverWifiDownloads.open(URL(value))
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (ranged) connection.setRequestProperty("Range", "bytes=$start-$end")
        return connection
    }

    private fun validateContentRange(connection: HttpURLConnection, start: Long, end: Long, directSize: Long?) {
        val value = connection.getHeaderField("Content-Range") ?: error("range response omitted Content-Range")
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value)
            ?: throw IllegalArgumentException("invalid Content-Range")
        val actualStart = match.groupValues[1].toLongOrNull()
        val actualEnd = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        require(actualStart == start && actualEnd == end) { "CDN ignored or changed requested range" }
        require(total != null && total > end && (directSize == null || total == directSize)) { "CDN Content-Range total does not cover the pinned object" }
    }

    private fun validateRedirectUrl(next: URI, originalPath: String) {
        requireOfficialAuthority(next)
        require(next.path == originalPath) { "CDN redirect changed the object path" }
    }

    private fun requireOfficialAuthority(uri: URI) {
        require(uri.scheme == "https" && uri.host?.lowercase(Locale.US) in ALLOWED_HOSTS && uri.userInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null) {
            "CDN URL is outside the official HTTPS allowlist"
        }
    }

    private fun verifyExistingMetadata(root: File, catalog: JSONObject, cancelled: () -> Boolean) {
        val configs = catalog.getJSONArray("required_configs")
        for (index in 0 until configs.length()) {
            checkCancelled(cancelled)
            val item = configs.getJSONObject(index)
            verifyExistingMetadataFile(managedFile(root, safePath(item.getString("relative_path"))), item, item.getString("kind"))
        }
        val indices = catalog.getJSONObject("casc_bootstrap").getJSONArray("cdn_indices")
        for (index in 0 until indices.length()) {
            checkCancelled(cancelled)
            val item = indices.getJSONObject(index)
            verifyExistingMetadataFile(managedFile(root, safePath(item.getString("relative_path"))), item, "CDN index")
        }
        val buildInfo = catalog.getJSONObject("build_info")
        verifyExistingMetadataFile(managedFile(root, ".build.info"), buildInfo, "build-info")
    }

    private fun recoverPromotion(
        root: File,
        staging: File,
        data: File,
        catalog: JSONObject,
        specs: List<ForeverCasc.RuntimeObjectSpec>,
        loose: JSONArray,
        cancelled: () -> Boolean,
    ) {
        val owner = managedFile(staging, ".forever-provisioner-owned")
        if (!owner.isFile) return
        val stagedData = managedFile(staging, "Data")
        val stagedInfo = managedFile(staging, ".build.info")
        val rootInfo = managedFile(root, ".build.info")
        if (!data.exists() && stagedData.isDirectory && stagedInfo.isFile) {
            ForeverCasc.verifyExisting(staging, specs, cancelled)
            val buildInfo = catalog.getJSONObject("build_info")
            verifyExistingMetadataFile(stagedInfo, buildInfo, "staged build-info")
            verifyStagedLoose(staging, loose, cancelled)
            checkCancelled(cancelled)
            atomicMove(stagedData, data)
            writePhase(staging, "DATA_PROMOTED")
            atomicMove(stagedInfo, rootInfo)
            writePhase(staging, "METADATA_PROMOTED")
            promoteLooseFiles(root, staging, loose, cancelled)
            return
        }
        // Data may already have been promoted while the process died before
        // metadata/loose promotion.  Only consume the provisioner's own
        // verified staging; never overwrite an expanded game directory.
        if (data.isDirectory && !rootInfo.exists() && stagedInfo.isFile) {
            val buildInfo = catalog.getJSONObject("build_info")
            verifyExistingMetadataFile(stagedInfo, buildInfo, "staged build-info")
            verifyStagedLoose(staging, loose, cancelled)
            checkCancelled(cancelled)
            atomicMove(stagedInfo, rootInfo)
            writePhase(staging, "METADATA_PROMOTED")
            promoteLooseFiles(root, staging, loose, cancelled)
        }
    }

    private fun verifyExistingMetadataFile(file: File, item: JSONObject, description: String) {
        val expectedSize = item.optLong("observed_encoded_size", item.optLong("serialized_size"))
        val expectedSha = item.optString("present_sha256", item.optString("sha256", item.optString("serialized_sha256")))
        if (!isValidEncoded(file, expectedSize, expectedSha)) throw IllegalStateException("existing $description is corrupt or incomplete; refusing to destroy CASC data")
    }

    private fun buildInfoBytes(catalog: JSONObject): ByteArray {
        val buildInfo = catalog.getJSONObject("build_info")
        val bytes = ForeverCasc.buildInfo(buildInfo.getJSONObject("fields"))
        require(bytes.size == buildInfo.getInt("serialized_size")) { "generated .build.info size differs from catalog" }
        require(sha256(bytes) == buildInfo.getString("serialized_sha256")) { "generated .build.info hash differs from catalog" }
        return bytes
    }

    private fun verifyStagedLoose(staging: File, records: JSONArray, cancelled: () -> Boolean) {
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val record = records.getJSONObject(index)
            verifyLooseFile(managedFile(staging, safePath(record.getString("relative_path"))), record, "staged loose file")
        }
    }

    private fun promoteLooseFiles(root: File, staging: File, records: JSONArray, cancelled: () -> Boolean) {
        for (index in 0 until records.length()) {
            checkCancelled(cancelled)
            val relative = safePath(records.getJSONObject(index).getString("relative_path"))
            val source = managedFile(staging, relative)
            val target = managedFile(root, relative)
            target.parentFile?.mkdirs()
            if (isValidLoose(target, records.getJSONObject(index))) {
                source.delete()
            } else {
                atomicMove(source, target)
            }
        }
    }

    private fun writePhase(staging: File, phase: String) {
        val marker = managedFile(staging, ".promotion-phase")
        val temporary = managedFile(staging, ".promotion-phase.part")
        writeOwnedText(temporary, "$phase\n")
        atomicMove(temporary, marker)
    }

    private fun copyVerified(source: File, target: File, size: Long, hash: String, cancelled: () -> Boolean) {
        if (target.isFile && isValidEncoded(target, size, hash)) return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.part")
        BufferedInputStream(source.inputStream(), BUFFER_SIZE).use { input ->
                BufferedOutputStream(openOwnedOutput(temporary, false), BUFFER_SIZE).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkCancelled(cancelled)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) output.write(buffer, 0, n)
                }
            }
        }
        require(isValidEncoded(temporary, size, hash)) { "metadata download failed integrity" }
        checkCancelled(cancelled)
        atomicMove(temporary, target)
    }

    private fun isValidLoose(file: File, record: JSONObject): Boolean =
        isValidEncoded(file, record.getLong("decoded_size"), record.optString("decoded_md5", record.getString("content_key")), "MD5")

    private fun verifyLooseFile(file: File, record: JSONObject, description: String) {
        require(isValidLoose(file, record)) { "$description is missing or corrupt" }
    }

    private fun isValidEncoded(file: File, size: Long, expected: String?, algorithm: String = "SHA-256"): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != size) return false
        if (expected.isNullOrEmpty()) return true
        return digest(file, algorithm).equals(expected, true)
    }

    private fun recordSha(record: JSONObject): String = record.optString("present_sha256", record.optString("sha256"))

    private fun prepareManagedDirectory(directory: File, markerText: String) {
        if (directory.exists() && (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()))) throw IllegalStateException("managed provisioner path is not a regular directory: ${directory.name}")
        directory.mkdirs()
        val marker = managedFile(directory, ".owner")
        if (marker.exists() && marker.readText() != markerText + "\n") throw IllegalStateException("managed provisioner path has an incompatible owner")
        if (!marker.exists()) writeOwnedText(marker, markerText + "\n")
    }

    private fun checkedRoot(directory: File): File {
        directory.mkdirs()
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "install directory is not a regular directory" }
        return directory.canonicalFile
    }

    /** Join only through real, existing parent components under the managed root. */
    private fun managedFile(base: File, relative: String): File {
        val safe = safePath(relative)
        val canonicalBase = base.canonicalFile
        var cursor = base
        for (part in safe.split('/')) {
            cursor = File(cursor, part)
            if (cursor.exists() && Files.isSymbolicLink(cursor.toPath())) {
                throw IllegalStateException("managed path contains a symlink: $relative")
            }
        }
        val result = cursor.canonicalFile
        val prefix = canonicalBase.path + File.separator
        require(result.path == canonicalBase.path || result.path.startsWith(prefix)) { "managed path escaped its root: $relative" }
        return result
    }

    private fun writeReadinessMarker(root: File, catalog: JSONObject) {
        val marker = managedFile(root, ".forever-provisioned")
        val temporary = managedFile(root, ".forever-provisioned.part")
        writeOwnedText(temporary, "schema=1\ncatalog_sha256=${sha256(catalog.toString().toByteArray(Charsets.UTF_8))}\n")
        atomicMove(temporary, marker)
    }

    private fun atomicMove(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun openOwnedOutput(file: File, append: Boolean): java.io.OutputStream {
        rejectSymlinkParents(file)
        file.parentFile?.mkdirs()
        val options = if (append) {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        } else {
            arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        }
        return Files.newOutputStream(file.toPath(), *options)
    }

    private fun writeOwnedText(file: File, text: String) {
        openOwnedOutput(file, false).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun rejectSymlinkParents(file: File) {
        var parent = file.parentFile
        while (parent != null) {
            if (Files.isSymbolicLink(parent.toPath())) throw IllegalStateException("managed output path contains a symlink")
            parent = parent.parentFile
        }
    }

    private fun checkSpace(installDir: File, required: Long) {
        val stats = StatFs(installDir.absolutePath)
        require(hasSufficientSpace(stats.availableBytes, required)) { "insufficient free space for finite client bootstrap: ${stats.availableBytes} < $required" }
    }

    internal fun hasSufficientSpaceForTest(available: Long, required: Long): Boolean = hasSufficientSpace(available, required)

    private fun hasSufficientSpace(available: Long, required: Long): Boolean = available >= 0 && required >= 0 && available >= required

    private fun requiredSpace(catalog: JSONObject, existingCasc: Boolean): Long {
        val bounds = recomputeBounds(catalog)
        if (!existingCasc) return safeAdd(safeAdd(bounds.workspaceEncoded, bounds.workspaceExpanded), SPACE_GUARD_BYTES)
        var largestEncoded = 0L
        val objects = catalog.getJSONObject("casc_bootstrap").getJSONArray("objects")
        for (index in 0 until objects.length()) {
            val item = objects.getJSONObject(index)
            largestEncoded = maxOf(largestEncoded, item.getLong("encoded_size"))
        }
        var largestLooseEncoded = 0L
        var largestLooseDecoded = 0L
        val loose = catalog.getJSONArray("loose_files")
        for (index in 0 until loose.length()) {
            val item = loose.getJSONObject(index)
            largestLooseEncoded = maxOf(largestLooseEncoded, item.getLong("encoded_size"))
            largestLooseDecoded = maxOf(largestLooseDecoded, item.getLong("decoded_size"))
        }
        return safeAdd(maxOf(largestEncoded, safeAdd(largestLooseEncoded, largestLooseDecoded)), SPACE_GUARD_BYTES)
    }

    private fun verifyArchiveObject(file: File, record: JSONObject, cancelled: () -> Boolean): Boolean =
        try {
            ForeverBlte.verify(file, record.getLong("decoded_size"), record.optString("decoded_md5", record.getString("content_key")), cancelled)
            true
        } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (_: Throwable) {
            false
        }

    private fun verifyArchiveObjectOrThrow(file: File, record: JSONObject, cancelled: () -> Boolean) {
        try {
            ForeverBlte.verify(file, record.getLong("decoded_size"), record.optString("decoded_md5", record.getString("content_key")), cancelled)
        } catch (cancelledFailure: CancellationException) {
            throw cancelledFailure
        } catch (failure: Throwable) {
            throw IllegalArgumentException("archive-range BLTE verification failed: ${failure.message}", failure)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client provisioning cancelled")
    }

    private fun safePath(value: String): String {
        require(value.isNotEmpty() && !value.contains('\u0000')) { "empty or NUL-containing catalog path" }
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:($|/)").containsMatchIn(normalized)) { "absolute catalog path" }
        val parts = normalized.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "unsafe catalog path: $value" }
        return normalized
    }

    private fun addUnique(paths: MutableSet<String>, value: String, description: String) {
        require(paths.add(value.lowercase(Locale.US))) { "duplicate case-insensitive $description: $value" }
    }

    private fun requireHex(value: String, length: Int, description: String) {
        require(value.matches(Regex("[0-9a-fA-F]{$length}"))) { "invalid $description" }
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safeAdd(a: Long, b: Long): Long = Math.addExact(a, b)
    private fun safeMultiply(a: Long, b: Long): Long = Math.multiplyExact(a, b)
}
