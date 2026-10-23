package app.gamenative

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Private PanVK selection for the already-qualified Wine graphics probe. */
object ForeverPanvkWine {
    const val ASSET_PATH = "forever/panvk-wsi/libvulkan_panfrost.so"
    const val FILE_NAME = "libvulkan_panfrost.so"
    const val CONTROL_KIND = "panvk-wine-selection"
    const val PROFILE = ForeverGraphicsProbe.PROFILE

    const val APPROVED_SHA256 = PanvkWsiStore.APPROVED_SHA256
    const val APPROVED_SIZE = PanvkWsiStore.APPROVED_SIZE
    const val EXPECTED_WRAPPER_SHA256 = "b598a54166790a6fc071f08495f141f92dc192f1eb339e029bfc7a303c7486ce"
    const val EXPECTED_WRAPPER_SIZE = 19_021_832L

    private const val FIXED_PACKAGE = "com.wowforever.arm64probe"
    private const val RUN_ROOT = "forever/controls/panvk-wine"
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val MAX_STREAM_BYTES = APPROVED_SIZE + 1L
    private const val MAX_ELF_HEADER_BYTES = 64
    private const val MAX_WRAPPER_BYTES = 20L * 1024L * 1024L
    private const val ELF_AARCH64 = 183
    private const val ELF_ET_DYN = 3
    private const val EXPECTED_WINE_ENTRY = "Proton-proton-11.0-90624-arm64ec-1"
    private const val EXPECTED_DXVK_ENTRY = "dxvk-2.4.1-wow-aarch64-test"
    private const val EXPECTED_DXVK_CONFIG = "version=2.4.1-wow-aarch64-test,framerate=0,maxDeviceMemory=0,async=0,asyncCache=0"
    private val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private val provisioningMutex = Any()

    internal data class AssetPin(val sha256: String, val size: Long)

    internal data class EnvironmentSelection(
        val driverPath: String,
        val driverName: String,
        val hooksPath: String,
        val assetSha256: String,
        val wrapperSha256: String,
    )

    private data class PrivateAsset(val file: File, val sha256: String, val size: Long)
    private data class ImageFsSelection(val hooksPath: File, val wrapperSha256: String)
    private data class FileIdentity(val key: Any?, val size: Long, val modified: Long)

    /** Final Bionic environment hook. Inapplicable routes return before assets/store IO. */
    @JvmStatic
    fun applyManagedEnvironment(context: Context, container: Container, env: EnvVars) {
        if (!ForeverLaunchDebug.PANVK_WINE_PROBE) return
        applyForPolicy(context, container, env, enabled = true)
    }

    internal fun applyForPolicy(context: Context, container: Container, env: EnvVars, enabled: Boolean) {
        if (!enabled) return
        if (container.getExtra("foreverManaged", "") != "true") return
        if (!ForeverLaunchDebug.GRAPHICS_PROBE_ENABLED) return
        if (container.getExtra("foreverLaunchKind", "") != PROFILE) return
        if (container.getExtra(ForeverRuntimeSetup.PANVK_WINE_PROBE_OPT_IN_EXTRA, "") != "true") return

        val runId = validateLaunchScope(context, container, env)
        val compiler = if (ForeverLaunchDebug.BUILTIN_SHADER_COMPILER) {
            // This is read-only qualification of a component already installed in
            // the selected Proton. It deliberately runs before the run-store lock.
            ForeverBuiltinShaderCompiler.verify(context)
        } else {
            null
        }
        val asset = synchronized(provisioningMutex) {
            // Asset IO and hashing are deliberately outside the run-store snapshot lock.
            prepareAsset(context, runId)
        }
        val imageFs = validateImageFs(context)
        val store = ForeverRunStoreRegistry.get(context)
        if (ForeverLaunchDebug.COMPILER_PREFIX_REPAIR) {
            requireNotNull(compiler) { "compiler prefix repair requires the pinned builtin" }
            store.appendRaw(runId, JSONObject().put("event", "compiler_prefix_repair_requested")
                .put("runId", runId).put("module", ForeverBuiltinShaderCompiler.FILE_NAME)
                .put("sourceSha256", compiler.sha256))
            val repaired = ForeverCompilerPrefixRepair.repair(context, container, env, runId, compiler)
            synchronized(store) {
                requireActiveRun(store, runId)
                store.appendRaw(runId, JSONObject().put("event", "compiler_prefix_repair_verified")
                    .put("runId", runId).put("prefixPath", repaired.prefixPath)
                    .put("targetPath", repaired.targetPath).put("backupPath", repaired.backupPath)
                    .put("transactionPath", repaired.transactionPath)
                    .put("originalSha256", repaired.originalSha256)
                    .put("installedSha256", repaired.installedSha256).put("reused", repaired.reused))
            }
        }

        val selection = EnvironmentSelection(
            driverPath = asset.file.parentFile.canonicalPath + File.separator,
            driverName = FILE_NAME,
            hooksPath = imageFs.hooksPath.canonicalPath,
            assetSha256 = asset.sha256,
            wrapperSha256 = imageFs.wrapperSha256,
        )
        commitSelection(store, runId, env, selection, compiler)
    }

    private fun validateLaunchScope(context: Context, container: Container, env: EnvVars): String {
        require(context.packageName == FIXED_PACKAGE) { "PanVK Wine probe package is not approved" }
        require(BuildConfig.APPLICATION_ID == FIXED_PACKAGE && BuildConfig.DEBUG) {
            "PanVK Wine probe requires the fixed debug application"
        }
        require((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "PanVK Wine probe requires a debuggable APK"
        }
        require(ForeverLaunchDebug.SCOPED_NO_OPENGL && container.getExtra("foreverScopedNoOpenGL", "") == "true") {
            "PanVK Wine probe requires the frozen no-OpenGL control"
        }
        require(container.getContainerVariant() == Container.BIONIC &&
            container.getGraphicsDriver() == "wrapper" &&
            container.getGraphicsDriverVersion() == "System" &&
            container.getDisplayRenderer() == "vulkan" &&
            container.getWineVersion() == EXPECTED_WINE_ENTRY &&
            container.getDXWrapper() == EXPECTED_DXVK_ENTRY &&
            container.getDXWrapperConfig() == EXPECTED_DXVK_CONFIG
        ) { "PanVK Wine probe runtime profile is not the frozen Bionic/Wine/DXVK profile" }
        require(container.getExecutablePath() == ForeverGraphicsProbe.FILE_NAME) {
            "PanVK Wine probe requires GraphicsProbe-ARM64.exe"
        }
        val runId = container.getExtra("foreverRunId", "")
        require(RUN_ID.matches(runId)) { "invalid PanVK Wine probe RUN" }
        require(env.get("WOWFOREVER_RUN_ID") == runId) {
            "PanVK Wine probe RUN differs from the final environment"
        }
        validateGraphicsProbeSource(container, runId)
        requireActiveRun(ForeverRunStoreRegistry.get(context), runId)
        return runId
    }

    private fun requireActiveRun(store: ForeverRunStore, runId: String) {
        val current = store.readCurrent()
        require(activeRunMatches(current, runId)) {
            "PanVK Wine probe requires the current active Forever run"
        }
    }

    internal fun activeRunMatches(current: ForeverRunState?, runId: String): Boolean =
        current != null && current.runId == runId && current.status == ForeverRunStatus.ACTIVE

    internal fun commitSelection(
        store: ForeverRunStore,
        runId: String,
        env: EnvVars,
        selection: EnvironmentSelection,
        compiler: ForeverBuiltinShaderCompiler.Selection? = null,
    ) {
        // Parse/validate the raw upstream value before the journal or any env key
        // is touched. EnvVars values are intentionally kept opaque here.
        val compilerOverrides = compiler?.let {
            ForeverBuiltinShaderCompiler.mergeWineDllOverrides(env.get("WINEDLLOVERRIDES"))
        }
        synchronized(store) {
            // Selection evidence only: no mapping, loader, D3D, presentation,
            // or compatibility claim is made here. Asset/ImageFS IO is already
            // complete; this lock covers the final active/journal/env sequence.
            requireActiveRun(store, runId)
            store.appendRaw(runId, JSONObject()
                .put("event", "panvk_wine_selection")
                .put("runId", runId)
                .put("path", selection.driverPath)
                .put("hash", selection.assetSha256)
                .put("hooksPath", selection.hooksPath)
                .put("expectedWrapperSha256", EXPECTED_WRAPPER_SHA256)
                .put("controlKind", CONTROL_KIND))
            compiler?.let {
                store.appendRaw(runId, JSONObject()
                    .put("event", "builtin_shader_compiler_selection")
                    .put("runId", runId)
                    .put("path", it.path)
                    .put("hash", it.sha256)
                    .put("size", it.size)
                    .put("module", ForeverBuiltinShaderCompiler.FILE_NAME)
                    .put("override", "d3dcompiler_47=b"))
            }
            requireActiveRun(store, runId)
            // The only environment mutation site: exactly three keys are selected.
            applySelectionEnvironment(env, selection)
            compiler?.let {
                env.put(
                    "WINEDLLOVERRIDES",
                    compilerOverrides!!,
                )
            }
        }
    }

    private fun validateGraphicsProbeSource(container: Container, runId: String) {
        val root = fixedClientRoot()
        val rawExpected = File(root, ForeverGraphicsProbe.FILE_NAME)
        require(!Files.isSymbolicLink(rawExpected.toPath()) && rawExpected.parentFile?.canonicalFile == root) {
            "graphics probe executable path is not a private fixed-root file"
        }
        val expected = rawExpected.canonicalFile
        require(expected.parentFile == root) { "graphics probe executable escaped the fixed root" }
        require(container.getExtra("foreverVerifiedExe", "") == expected.path) {
            "graphics probe verified executable is not the fixed WoW Forever target"
        }
        val expectedArgs = ForeverGraphicsProbe.exactArgs(runId)
        require(container.getExecArgs() == expectedArgs && container.getExtra("foreverGraphicsProbeArgs", "") == expectedArgs) {
            "graphics probe arguments are not the frozen arguments"
        }
        require(container.getExtra("foreverGraphicsProbeLog", "") == ForeverGraphicsProbe.logPath(runId)) {
            "graphics probe log descriptor does not match the RUN"
        }
        val actual = verifyGraphicsProbe(expected, ForeverGraphicsProbe.publicPin())
        require(container.getExtra("foreverGraphicsProbeSha256", "").equals(actual.sha256, ignoreCase = true) &&
            container.getExtra("foreverGraphicsProbeSize", "").toLongOrNull() == actual.size &&
            container.getExtra("foreverGraphicsProbeMachine", "") == ForeverGraphicsProbe.MANIFEST_MACHINE.toString()
        ) { "graphics probe descriptors do not match the pinned PE" }

        val aDriveFolders = Container.drivesIterator(container.drives).asSequence()
            .filter { it.size >= 2 && it[0].equals("A", ignoreCase = true) }
            .map { File(it[1]) }
            .toList()
        require(aDriveFolders.size == 1 && realDirectory(aDriveFolders.single()) == root) {
            "graphics probe requires one A: drive mapped to the fixed WoW Forever root"
        }
        // The XServer caller has already validated registered app source, cwd,
        // A: discovery and the public PE route. This final hook rechecks the
        // durable container descriptors without inventing an appId field.
    }

    private data class VerifiedGraphicsProbe(val sha256: String, val size: Long)

    private fun fixedClientRoot(): File {
        val raw = File(CustomGameScanner.defaultRootPath, "WoW Forever")
        require(!Files.isSymbolicLink(raw.toPath()) && Files.isDirectory(raw.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "graphics probe source directory is not a real directory"
        }
        return raw.canonicalFile
    }

    private fun realDirectory(file: File): File {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "graphics probe source directory is not a real directory"
        }
        return file.canonicalFile
    }

    private fun verifyGraphicsProbe(file: File, pin: ForeverGraphicsProbe.ProbePin): VerifiedGraphicsProbe {
        require(!Files.isSymbolicLink(file.toPath())) { "graphics probe executable is a symlink" }
        val before = fileIdentity(file, pin.size)
        readProbeHeader(file, before.size)
        val digest = digestFile(file, pin.size)
        val after = fileIdentity(file, pin.size)
        require(before == after && digest.equals(pin.sha256, ignoreCase = true)) {
            "graphics probe PE changed or does not match the public pin"
        }
        return VerifiedGraphicsProbe(digest.lowercase(), after.size)
    }

    internal fun applySelectionEnvironment(env: EnvVars, selection: EnvironmentSelection) {
        env.put("ADRENOTOOLS_DRIVER_PATH", selection.driverPath)
        env.put("ADRENOTOOLS_DRIVER_NAME", selection.driverName)
        env.put("ADRENOTOOLS_HOOKS_PATH", selection.hooksPath)
    }

    private fun prepareAsset(context: Context, runId: String): PrivateAsset {
        val root = privateRoot(context.filesDir)
        val runDirectory = ensureDirectory(root, runId, root)
        val target = secureFile(runDirectory, FILE_NAME, root)
        val pin = AssetPin(APPROVED_SHA256, APPROVED_SIZE)

        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return verifyPrivateAsset(target, pin)

        var ownedTemporary = false
        val temporary = secureFile(runDirectory, ".${FILE_NAME}.${UUID.randomUUID()}.part", root)
        try {
            context.assets.open(ASSET_PATH).use { input ->
                writeAsset(input, temporary, pin)
                ownedTemporary = true
            }
            // No REPLACE_EXISTING or ATOMIC_MOVE: a raced target is never replaced.
            try {
                Files.move(temporary.toPath(), target.toPath())
                ownedTemporary = false
            } catch (_: FileAlreadyExistsException) {
                return verifyPrivateAsset(target, pin)
            }
            return verifyPrivateAsset(target, pin)
        } finally {
            // Provider/rename failures never publish an environment value. Any
            // destination left by an unknown failure is treated as an unknown
            // existing file on the next invocation and must verify from scratch.
            if (ownedTemporary) Files.deleteIfExists(temporary.toPath())
        }
    }

    /** Test-only fixture seam; production uses the APK asset and fixed pin above. */
    internal fun installFixture(root: File, bytes: ByteArray, pin: AssetPin): File {
        return installFixtureStream(root, bytes.inputStream(), pin)
    }

    /** Test-only stream seam for short/zero-read bounded-reader fixtures. */
    internal fun installFixtureStream(root: File, input: InputStream, pin: AssetPin): File {
        require(pin.sha256.matches(Regex("[0-9a-f]{64}")) && pin.size > 0)
        val canonical = privateRoot(root)
        val runDirectory = ensureDirectory(canonical, "fixture-run", canonical)
        val target = secureFile(runDirectory, FILE_NAME, canonical)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return verifyPrivateAsset(target, pin).file
        var ownedTemporary = false
        val temporary = secureFile(runDirectory, ".${FILE_NAME}.${UUID.randomUUID()}.part", canonical)
        try {
            writeAsset(input, temporary, pin)
            ownedTemporary = true
            try {
                Files.move(temporary.toPath(), target.toPath())
                ownedTemporary = false
            } catch (_: FileAlreadyExistsException) {
                return verifyPrivateAsset(target, pin).file
            }
            return verifyPrivateAsset(target, pin).file
        } finally {
            if (ownedTemporary) Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun validateImageFs(context: Context): ImageFsSelection {
        val filesRoot = requireRealDirectory(context.filesDir.canonicalFile, "context files root")
        val imageRoot = containedRealDirectory(ImageFs.find(context).rootDir, filesRoot, "ImageFS root")
        val lib = containedRealDirectory(File(imageRoot, "usr/lib"), imageRoot, "ImageFS usr/lib")
        val wrapper = secureFile(lib, "libvulkan_wrapper.so", imageRoot)
        val hooks = listOf("libhook_impl.so" to 40_968L, "libmain_hook.so" to 7_560L)
        val wrapperDigest = verifyElfFile(wrapper, EXPECTED_WRAPPER_SIZE, EXPECTED_WRAPPER_SHA256, MAX_WRAPPER_BYTES)
        hooks.forEach { (name, size) -> verifyElfFile(secureFile(lib, name, imageRoot), size, null, size) }
        return ImageFsSelection(lib, wrapperDigest)
    }

    internal fun validateElfBytesForTests(bytes: ByteArray): Boolean {
        verifyElfHeader(bytes, bytes.size)
        return true
    }

    private fun privateRoot(filesDirectory: File): File {
        val root = requireRealDirectory(filesDirectory.canonicalFile, "context files root")
        var current = root
        for (part in RUN_ROOT.split('/')) current = ensureDirectory(current, part, root)
        return current
    }

    private fun ensureDirectory(parent: File, name: String, root: File): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "invalid private path component" }
        val directory = File(parent, name)
        require(directory.parentFile?.canonicalFile == parent.canonicalFile) { "private path escaped its parent" }
        require(!Files.isSymbolicLink(directory.toPath())) { "private directory is a symlink" }
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(directory.toPath()) } catch (_: FileAlreadyExistsException) { }
        }
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory.toPath())) {
            "private directory is not a real directory"
        }
        requireContained(directory, root)
        return directory.canonicalFile
    }

    private fun secureFile(parent: File, name: String, root: File): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "invalid private file component" }
        val file = File(parent, name)
        require(file.parentFile?.canonicalFile == parent.canonicalFile) { "private file escaped its parent" }
        requireContained(file, root)
        require(!Files.isSymbolicLink(file.toPath())) { "private file is a symlink" }
        return file
    }

    private fun requireRealDirectory(file: File, label: String): File {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "$label is not a real directory"
        }
        return file
    }

    private fun containedRealDirectory(candidate: File, root: File, label: String): File {
        val canonical = candidate.canonicalFile
        requireContained(canonical, root)
        return requireRealDirectory(canonical, label)
    }

    private fun requireContained(candidate: File, root: File) {
        val path = candidate.canonicalFile.path
        val rootPath = root.canonicalFile.path
        require(path == rootPath || path.startsWith(rootPath + File.separator)) { "private path escaped its owning root" }
    }

    private fun writeAsset(input: InputStream, target: File, pin: AssetPin) {
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "private temporary already exists" }
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(MAX_ELF_HEADER_BYTES)
        var headerBytes = 0
        var total = 0L
        var created = false
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                created = true
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val requested = minOf(buffer.size.toLong(), MAX_STREAM_BYTES - total).toInt()
                    require(requested > 0) { "asset exceeds bounded size" }
                    var count = input.read(buffer, 0, requested)
                    if (count == 0) {
                        val one = input.read()
                        if (one < 0) break
                        buffer[0] = one.toByte()
                        count = 1
                    }
                    if (count < 0) break
                    total += count
                    require(total <= pin.size) { "asset exceeds approved size" }
                    digest.update(buffer, 0, count)
                    if (headerBytes < header.size) {
                        val copied = minOf(count, header.size - headerBytes)
                        buffer.copyInto(header, headerBytes, 0, copied)
                        headerBytes += copied
                    }
                    writeFully(output, ByteBuffer.wrap(buffer, 0, count))
                }
                require(total == pin.size) { "asset ended before the approved size" }
                require(digest.digest().toHex() == pin.sha256) { "asset hash does not match the approved pin" }
                verifyElfHeader(header, headerBytes)
                output.force(true)
            }
            setOwnerOnly(target)
            verifyPrivateAsset(target, pin)
        } catch (failure: Throwable) {
            if (created) Files.deleteIfExists(target.toPath())
            throw failure
        }
    }

    private fun verifyPrivateAsset(file: File, pin: AssetPin): PrivateAsset {
        val before = fileIdentity(file, pin.size)
        requireMode(file, 0x180)
        val digest = digestFile(file, pin.size)
        val header = readHeader(file)
        val after = fileIdentity(file, pin.size)
        require(before == after && digest == pin.sha256) { "private PanVK asset changed or has the wrong hash" }
        verifyElfHeader(header, header.size)
        return PrivateAsset(file.canonicalFile, digest, after.size)
    }

    private fun verifyElfFile(file: File, expectedSize: Long, expectedSha: String?, maximum: Long): String {
        val before = fileIdentity(file, expectedSize, maximum)
        requireOwner(file)
        val digest = digestFile(file, maximum)
        val header = readHeader(file)
        val after = fileIdentity(file, expectedSize, maximum)
        require(before == after) { "ImageFS ELF changed while it was read" }
        require(expectedSha == null || digest == expectedSha) { "ImageFS wrapper hash is not pinned" }
        verifyElfHeader(header, header.size)
        return digest
    }

    private fun fileIdentity(file: File, expectedSize: Long, maximum: Long = expectedSize): FileIdentity {
        require(!Files.isSymbolicLink(file.toPath())) { "managed file is a symlink" }
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == expectedSize && attributes.size() <= maximum) {
            "managed file has an unexpected bounded identity"
        }
        return FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun digestFile(file: File, maximum: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            require(size <= maximum)
            val buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES)
            var total = 0L
            while (total < size) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), size - total).toInt())
                val count = input.read(buffer)
                require(count > 0) { "bounded managed-file read made no progress" }
                buffer.flip()
                digest.update(buffer)
                total += count
            }
            require(total == size)
        }
        return digest.digest().toHex()
    }

    private fun readHeader(file: File): ByteArray {
        val result = ByteArray(MAX_ELF_HEADER_BYTES)
        var count = 0
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < result.size) {
                val read = input.read(ByteBuffer.wrap(result, count, result.size - count))
                if (read < 0) break
                require(read > 0) { "bounded ELF header read made no progress" }
                count += read
            }
        }
        return result.copyOf(count)
    }

    private fun readProbeHeader(file: File, size: Long) {
        require(size in 1..ForeverGraphicsProbe.APPROVED_PROBE_SIZE)
        val bytes = ByteArray(4096)
        var count = 0
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < bytes.size) {
                val read = input.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
                if (read < 0) break
                require(read > 0) { "bounded PE header read made no progress" }
                count += read
            }
        }
        require(count >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "graphics probe is not an MZ image"
        }
        val offset = littleInt(bytes, 0x3c)
        require(offset >= 64 && offset <= count - 26 && offset + 26 <= bytes.size) {
            "graphics probe PE header is outside the bounded header"
        }
        require(bytes[offset] == 'P'.code.toByte() && bytes[offset + 1] == 'E'.code.toByte() &&
            bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte()
        ) { "graphics probe has an invalid PE signature" }
        require(littleShort(bytes, offset + 4) == ForeverGraphicsProbe.PE_MACHINE &&
            littleShort(bytes, offset + 24) == ForeverGraphicsProbe.PE_OPTIONAL_MAGIC
        ) { "graphics probe is not the pinned AA64 PE32+ image" }
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun verifyElfHeader(bytes: ByteArray, count: Int) {
        require(count >= 20 && bytes.size >= count && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "managed file is not ELF" }
        require(bytes[4].toInt() == 2 && bytes[5].toInt() == 1) { "managed file is not ELF64 little-endian" }
        val type = ByteBuffer.wrap(bytes, 16, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val machine = ByteBuffer.wrap(bytes, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(type == ELF_ET_DYN && machine == ELF_AARCH64) { "managed file is not an AArch64 ET_DYN" }
    }

    private fun setOwnerOnly(file: File) {
        Files.setPosixFilePermissions(file.toPath(), setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        ))
        requireMode(file, 0x180)
    }

    private fun requireMode(file: File, mode: Int) {
        val stat = android.system.Os.stat(file.absolutePath)
        require(stat.st_uid == Process.myUid()) { "managed file is not owned by this app" }
        require((stat.st_mode and 0x1ff) == mode) { "managed file is not mode 0600" }
    }

    private fun requireOwner(file: File) {
        require(android.system.Os.stat(file.absolutePath).st_uid == Process.myUid()) {
            "managed ImageFS file is not owned by this app"
        }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) require(channel.write(bytes) > 0) { "managed asset write made no progress" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
