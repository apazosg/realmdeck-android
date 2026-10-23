package app.gamenative

import android.content.Context
import android.os.Process
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** The single, pre-launch d3dcompiler_47 repair approved by the compiler contract. */
object ForeverCompilerPrefixRepair {
    private const val FILE_NAME = ForeverBuiltinShaderCompiler.FILE_NAME
    private const val PREFIX_ROOT = "imagefs_shared/home"
    private const val PROTON_SOURCE =
        "imagefs_shared/proton/proton-11.0-90624-arm64ec/lib/wine/aarch64-windows/$FILE_NAME"
    private const val CONTROLS_ROOT = "forever/controls/compiler-prefix"
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val MAX_TRANSACTION_BYTES = 32 * 1024
    private const val MAX_PE_HEADER_BYTES = 4096 + 26
    private const val PE32_PLUS = 0x20b
    private const val PE_X64 = 0x8664
    private const val PE_AA64 = 0xaa64
    private const val ORIGINAL_SHA256 = "06dc23aaa86607e083ed1725f2e99cc40501caf93408569eb9f287b272b58e52"
    private const val ORIGINAL_SIZE = 4_453_888L
    private const val INSTALLED_SHA256 = ForeverBuiltinShaderCompiler.APPROVED_SHA256
    private const val INSTALLED_SIZE = ForeverBuiltinShaderCompiler.APPROVED_SIZE
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    internal data class Receipt(
        val prefixPath: String,
        val targetPath: String,
        val backupPath: String,
        val transactionPath: String,
        val originalSha256: String,
        val installedSha256: String,
        val reused: Boolean,
    )

    private data class Pin(val hash: String, val size: Long, val machine: Int)
    private data class Identity(
        val key: String?,
        val size: Long,
        val modified: Long,
        val uid: Int,
        val mode: Int,
        val links: Long,
    )
    private data class CheckedFile(val file: File, val hash: String, val identity: Identity)
    private data class Paths(
        val filesRoot: File,
        val prefix: File,
        val system32: File,
        val target: File,
        val source: File,
        val containerId: String,
    )

    /** Internal-only seam. Production never supplies hooks or arbitrary paths. */
    internal data class TestHooks(
        val beforeCommit: (() -> Unit)? = null,
        val failAfterBytes: Long? = null,
        val move: ((Path, Path) -> Unit)? = null,
        val failReceiptAfterCommit: Boolean = false,
        val duringSourceCopy: (() -> Unit)? = null,
        val failFsync: Boolean = false,
    )

    internal fun repair(
        context: Context,
        container: Container,
        env: EnvVars,
        runId: String,
        compiler: ForeverBuiltinShaderCompiler.Selection,
    ): Receipt {
        val paths = productionPaths(context, container, env, compiler)
        val pins = productionPins()
        return repairInternal(context.filesDir.canonicalFile, paths, runId, pins, hooks = TestHooks())
    }

    internal fun verifyBeforeSpawn(context: Context, runId: String, winePrefix: String): Unit {
        require(SAFE_ID.matches(runId)) { "invalid compiler repair RUN" }
        val filesRoot = realDirectory(context.filesDir.canonicalFile, "context files root")
        val transaction = existingTransactionPath(filesRoot, runId)
        val json = readTransaction(transaction)
        validateCompletedTransaction(json, runId)
        require(json.optString("transactionPath") == transaction.canonicalPath) {
            "compiler repair transaction path is not self-bound"
        }
        require(json.getString("originalSha256") == ORIGINAL_SHA256 &&
            json.getLong("originalSize") == ORIGINAL_SIZE &&
            json.getString("installedSha256") == INSTALLED_SHA256 &&
            json.getLong("installedSize") == INSTALLED_SIZE) {
            "compiler repair pins are not the production pins"
        }
        require(json.optInt("schema", -1) == 1 && json.optString("runId") == runId) {
            "compiler repair transaction is not bound to RUN"
        }
        require(json.optString("status") == "COMMITTED" || json.optString("status") == "REUSED") {
            "compiler repair transaction is not launchable"
        }
        val state = requireNotNull(ForeverRunStoreRegistry.get(context).readCurrent()) {
            "compiler repair has no current RUN"
        }
        require(state.runId == runId && state.status == ForeverRunStatus.ACTIVE &&
            state.stage != ForeverStage.STOPPING && !ForeverProcessDiagnostics.isLaunchCancelled(runId) && !state.launchAttempted) {
            "compiler repair RUN is stale, cancelled, or already attempted"
        }
        require(!Thread.currentThread().isInterrupted) { "compiler repair RUN was cancelled" }
        val prefix = securePrefix(filesRoot, json.getString("containerId"), File(winePrefix))
        require(prefix.path == json.getString("prefixPath")) { "WINEPREFIX differs from the repaired prefix" }
        val target = secureTarget(prefix)
        require(target.path == json.getString("targetPath")) { "compiler target differs from the receipt" }
        val installed = Pin(INSTALLED_SHA256, INSTALLED_SIZE, PE_AA64)
        val checked = verifyFile(target, installed)
        require(checked.hash == json.getString("installedSha256")) { "compiler target hash differs from receipt" }
        require(identityEquals(checked.identity, identityFrom(json, "installedIdentity"))) {
            "compiler target identity differs from receipt"
        }
    }

    /** Internal-only fixture seam for the Robolectric transaction tests. */
    internal fun verifyBeforeSpawnForTests(filesRoot: File, runId: String, winePrefix: File) {
        val transaction = existingTransactionPath(filesRoot.canonicalFile, runId)
        val json = readTransaction(transaction)
        validateCompletedTransaction(json, runId)
        require(json.optString("transactionPath") == transaction.canonicalPath)
        require(json.optString("status") == "COMMITTED" || json.optString("status") == "REUSED")
        val store = ForeverRunStoreRegistry.forDirectory(filesRoot.canonicalFile)
        val state = requireNotNull(store.readCurrent())
        require(state.runId == runId && state.status == ForeverRunStatus.ACTIVE && !state.launchAttempted)
        require(!Thread.currentThread().isInterrupted)
        val prefix = securePrefix(filesRoot.canonicalFile, json.getString("containerId"), winePrefix)
        require(prefix.path == json.getString("prefixPath"))
        val target = secureTarget(prefix)
        val checked = verifyFile(target, Pin(json.getString("installedSha256"), json.getLong("installedSize"), PE_AA64))
        require(checked.hash == json.getString("installedSha256") &&
            identityEquals(checked.identity, identityFrom(json, "installedIdentity")))
    }

    /** Internal-only fixture seam for rollback; production has no path-taking API. */
    internal fun rollbackForTests(
        filesRoot: File,
        runId: String,
        hooks: TestHooks = TestHooks(),
    ): Receipt {
        val root = filesRoot.canonicalFile
        val transaction = existingTransactionPath(root, runId)
        val json = readTransaction(transaction)
        validateCompletedTransaction(json, runId, rollbackIntent = true)
        require(json.optString("transactionPath") == transaction.canonicalPath)
        require(json.optString("status") in setOf("COMMITTED", "ROLLBACK_ATTEMPTED"))
        val store = ForeverRunStoreRegistry.forDirectory(root)
        val state = requireNotNull(store.read(runId))
        require(store.readCurrent()?.runId == runId && quiescentForRollback(state))
        val prefix = securePrefix(root, json.getString("containerId"), File(json.getString("prefixPath")))
        val target = secureTarget(prefix)
        return rollbackInternal(transaction, json, target, testPinsFrom(json), store, runId, hooks)
    }

    internal fun rollback(context: Context, runId: String): Receipt {
        require(SAFE_ID.matches(runId)) { "invalid compiler repair RUN" }
        val filesRoot = realDirectory(context.filesDir.canonicalFile, "context files root")
        val transaction = existingTransactionPath(filesRoot, runId)
        val json = readTransaction(transaction)
        validateCompletedTransaction(json, runId, rollbackIntent = true)
        require(json.optString("transactionPath") == transaction.canonicalPath)
        require(json.optString("status") in setOf("COMMITTED", "ROLLBACK_ATTEMPTED")) { "compiler repair is not rollback-eligible" }
        val store = ForeverRunStoreRegistry.get(context)
        val state = requireNotNull(store.read(runId)) { "compiler repair RUN is missing" }
        require(store.readCurrent()?.runId == runId) { "compiler repair RUN is not current" }
        require(quiescentForRollback(state)) { "compiler repair rollback requires a proven quiescent RUN" }
        val prefix = securePrefix(filesRoot, json.getString("containerId"), File(json.getString("prefixPath")))
        val target = secureTarget(prefix)
        return rollbackInternal(transaction, json, target,
            Pins(Pin(INSTALLED_SHA256, INSTALLED_SIZE, PE_AA64),
                Pin(ORIGINAL_SHA256, ORIGINAL_SIZE, PE_X64),
                Pin(INSTALLED_SHA256, INSTALLED_SIZE, PE_AA64)), store, runId, TestHooks())
    }

    private fun productionPaths(
        context: Context,
        container: Container,
        env: EnvVars,
        compiler: ForeverBuiltinShaderCompiler.Selection,
    ): Paths {
        val filesRoot = realDirectory(context.filesDir.canonicalFile, "context files root")
        val id = container.id
        require(SAFE_ID.matches(id)) { "container id is not safe for the managed prefix" }
        val expectedHome = File(filesRoot, "$PREFIX_ROOT/xuser-$id")
        val rawRoot = container.rootDir
        val allowedRoots = setOf(
            normalizedOwnedAppAlias(filesRoot, expectedHome),
            normalizedOwnedAppAlias(filesRoot, File(filesRoot, "imagefs/home/xuser-$id")),
            normalizedOwnedAppAlias(filesRoot, File(filesRoot, "imagefs/home/xuser")),
        )
        require(normalizedOwnedAppAlias(filesRoot, rawRoot) in allowedRoots && rawRoot.canonicalFile == fixedSharedPrefix(filesRoot, id).parentFile) {
            "container root is not the approved ImageFS home alias: id=$id root=${rawRoot.absolutePath} canonical=${rawRoot.canonicalPath} expected=${expectedHome.canonicalPath}"
        }
        val prefix = securePrefix(filesRoot, id, File(rawRoot, ".wine"))
        require(env.get("WINEPREFIX")?.let { allowedPrefixAlias(filesRoot, id, File(it), prefix) } == true) {
            "WINEPREFIX is not the selected canonical prefix"
        }
        val source = File(filesRoot, PROTON_SOURCE)
        requireNoSymlinkComponents(source, filesRoot)
        require(compiler.path == source.canonicalPath && compiler.sha256.equals(INSTALLED_SHA256, true) && compiler.size == INSTALLED_SIZE) {
            "builtin compiler selection is stale or outside the approved source"
        }
        return Paths(filesRoot, prefix, File(prefix, "drive_c/windows/system32"),
            File(prefix, "drive_c/windows/system32/$FILE_NAME"), source, id)
    }

    private fun productionPins(): Pins = Pins(
        source = Pin(INSTALLED_SHA256, INSTALLED_SIZE, PE_AA64),
        original = Pin(ORIGINAL_SHA256, ORIGINAL_SIZE, PE_X64),
        installed = Pin(INSTALLED_SHA256, INSTALLED_SIZE, PE_AA64),
    )

    private data class Pins(val source: Pin, val original: Pin, val installed: Pin)

    private fun repairInternal(
        filesRoot: File,
        paths: Paths,
        runId: String,
        pins: Pins,
        hooks: TestHooks,
    ): Receipt {
        require(SAFE_ID.matches(runId)) { "invalid compiler repair RUN" }
        realDirectory(paths.filesRoot, "managed files root")
        require(paths.filesRoot.canonicalFile == filesRoot.canonicalFile) { "repair root changed" }
        securePrefix(filesRoot, paths.containerId, paths.prefix)
        requireNoSymlinkComponents(paths.system32.canonicalFile, paths.prefix.canonicalFile)
        requireNoSymlinkComponents(paths.source.canonicalFile, filesRoot)
        require(paths.system32.canonicalFile == File(paths.prefix, "drive_c/windows/system32").canonicalFile) {
            "compiler system32 path changed"
        }
        realDirectory(paths.system32, "compiler system32")
        val source = verifyFile(paths.source, pins.source)
        val target = secureExistingTarget(paths.target)
        val store = ForeverRunStoreRegistry.forDirectory(filesRoot)
        requireActivePrelaunch(store, runId)
        val transaction = transactionPath(filesRoot, runId)
        if (Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val existing = readTransaction(transaction)
            if (existing.optString("status") == "ATTEMPTED") {
                require(existing.getInt("schema") == 1 && existing.getString("runId") == runId &&
                    existing.getString("containerId") == paths.containerId &&
                    existing.getString("prefixPath") == paths.prefix.canonicalPath &&
                    existing.getString("targetPath") == target.canonicalPath &&
                    existing.getString("sourcePath") == source.file.canonicalPath &&
                    existing.getString("transactionPath") == transaction.canonicalPath)
                requireReceiptPins(existing, pins)
                synchronized(store) {
                    requireActivePrelaunchLocked(store, runId)
                    val installed = verifyFile(target, pins.installed)
                    if (existing.getBoolean("reused")) {
                        require(identityEquals(installed.identity, identityFrom(existing, "originalIdentity"))) {
                            "incomplete reuse transaction does not own the unchanged compiler"
                        }
                        val completed = existing.put("status", "REUSED")
                            .put("installedIdentity", identityJson(installed.identity)).put("recoveredReceipt", true)
                        writeTransaction(completed)
                        return receipt(completed, installed.hash, reused = true)
                    }
                    require(identityEquals(installed.identity, identityFrom(existing, "preparedIdentity"))) {
                        "incomplete compiler transaction does not own the installed inode"
                    }
                    val backup = secureOwnedFile(File(existing.getString("backupPath")), transaction.parentFile)
                    val originalBackup = verifyFile(backup, pins.original)
                    require(identityEqualsMetadata(originalBackup.identity, identityFrom(existing, "originalIdentity")))
                    val completed = existing.put("status", "COMMITTED")
                        .put("installedIdentity", identityJson(installed.identity)).put("recoveredReceipt", true)
                    writeTransaction(completed)
                    return receipt(completed, installed.hash, reused = false)
                }
            }
            validateCompletedTransaction(existing, runId)
            require(existing.optString("transactionPath") == transaction.canonicalPath) {
                "compiler transaction path is not self-bound"
            }
            require(existing.optString("runId") == runId) { "compiler transaction RUN collision" }
            require(existing.optString("status") == "COMMITTED" || existing.optString("status") == "REUSED") {
                "compiler transaction has unknown status"
            }
            requireReceiptPins(existing, pins)
            synchronized(store) {
                requireActivePrelaunchLocked(store, runId)
                verifyReceiptTarget(filesRoot, existing, pins)
                return receipt(existing, existing.getString("installedSha256"), existing.optString("status") == "REUSED")
            }
        }

        val original = when {
            verifyMatches(target, pins.original) -> verifyFile(target, pins.original)
            verifyMatches(target, pins.installed) -> verifyFile(target, pins.installed)
            else -> error("compiler target is unknown; repair stopped")
        }
        val reused = original.hash == pins.installed.hash
        val initial = JSONObject()
            .put("schema", 1).put("runId", runId).put("containerId", paths.containerId)
            .put("status", "ATTEMPTED").put("prefixPath", paths.prefix.canonicalPath)
            .put("targetPath", target.canonicalPath).put("sourcePath", source.file.canonicalPath)
            .put("backupPath", "").put("originalSha256", pins.original.hash)
            .put("installedSha256", pins.installed.hash).put("installedSize", pins.installed.size)
            .put("originalSize", pins.original.size).put("reused", reused)
            .put("transactionPath", transaction.canonicalPath)
            .put("originalMode", original.identity.mode).put("originalUid", original.identity.uid)
            .put("originalIdentity", identityJson(original.identity))
            .put("sourceIdentity", identityJson(source.identity))
        createTransaction(transaction, initial)

        if (reused) {
            synchronized(store) {
                hooks.beforeCommit?.invoke()
                requireActivePrelaunchLocked(store, runId)
                val installed = verifyFile(target, pins.installed)
                if (hooks.failReceiptAfterCommit) throw IOException("injected reused receipt failure")
                val completed = initial.put("status", "REUSED")
                    .put("installedIdentity", identityJson(installed.identity))
                writeTransaction(completed)
                return receipt(completed, installed.hash, reused = true)
            }
        }

        val backup = secureNewFile(transaction.parentFile, "backup")
        copyPinned(target, backup, pins.original, hooks)
        setMode(backup, original.identity.mode)
        setModified(backup, original.identity.modified)
        forceMetadata(backup)
        val checkedBackup = verifyFile(backup, pins.original)
        require(identityEqualsMetadata(checkedBackup.identity, original.identity)) { "backup metadata changed" }
        writeTransaction(initial.put("backupPath", backup.canonicalPath))

        val temporary = temporaryFile(paths.system32, "install")
        copyPinned(source.file, temporary, pins.installed, hooks, sourceHook = true)
        setMode(temporary, original.identity.mode)
        setModified(temporary, original.identity.modified)
        forceMetadata(temporary)
        val prepared = verifyFile(temporary, pins.installed)
        require(prepared.identity.uid == original.identity.uid) { "prepared compiler owner differs" }
        writeTransaction(initial.put("preparedIdentity", identityJson(prepared.identity)))
        synchronized(store) {
            hooks.beforeCommit?.invoke()
            requireActivePrelaunchLocked(store, runId)
            val beforeMove = verifyFile(target, pins.original)
            require(identityEquals(beforeMove.identity, original.identity) && beforeMove.hash == pins.original.hash) {
                "compiler target changed before atomic commit"
            }
            atomicReplace(temporary, target, hooks)
            val installed = verifyFile(target, pins.installed)
            require(installed.identity.uid == original.identity.uid && installed.identity.mode == original.identity.mode) {
                "installed compiler metadata is not preserved"
            }
            requireActivePrelaunchLocked(store, runId)
            if (hooks.failReceiptAfterCommit) throw IOException("injected post-commit receipt failure")
            val completed = initial.put("status", "COMMITTED")
                .put("backupPath", backup.canonicalPath)
                .put("installedIdentity", identityJson(installed.identity))
            writeTransaction(completed)
            return receipt(completed, installed.hash, reused = false)
        }
    }

    /** Internal-only fixture seam; it never resolves production paths or executes a PE. */
    internal fun repairForTests(
        filesRoot: File,
        prefix: File,
        source: File,
        target: File,
        runId: String,
        pins: TestPins,
        hooks: TestHooks = TestHooks(),
    ): Receipt = repairInternal(filesRoot.canonicalFile,
        Paths(filesRoot.canonicalFile, prefix, target.parentFile, target, source, "fixture"), runId, pins.toPins(), hooks)

    internal data class TestPins(val source: ForeverBuiltinShaderCompiler.CompilerPin,
        val original: ForeverBuiltinShaderCompiler.CompilerPin,
        val installed: ForeverBuiltinShaderCompiler.CompilerPin,
        val sourceMachine: Int = PE_AA64,
        val originalMachine: Int = PE_X64,
        val installedMachine: Int = PE_AA64)

    private fun TestPins.toPins() = Pins(Pin(source.sha256.lowercase(), source.size, sourceMachine),
        Pin(original.sha256.lowercase(), original.size, originalMachine),
        Pin(installed.sha256.lowercase(), installed.size, installedMachine))

    private fun requireActivePrelaunch(store: ForeverRunStore, runId: String) = synchronized(store) {
        requireActivePrelaunchLocked(store, runId)
    }

    private fun requireActivePrelaunchLocked(store: ForeverRunStore, runId: String) {
        val state = requireNotNull(store.read(runId)) { "compiler repair RUN is missing" }
        require(state.status == ForeverRunStatus.ACTIVE && state.stage != ForeverStage.STOPPING &&
            !ForeverProcessDiagnostics.isLaunchCancelled(runId) &&
            store.readCurrent()?.runId == runId && !state.launchAttempted) {
            "compiler repair requires the active pre-launch RUN"
        }
        require(!Thread.currentThread().isInterrupted) { "compiler repair was cancelled" }
    }

    private fun quiescentForRollback(state: ForeverRunState): Boolean {
        if (runCatching { ForeverProcessDiagnostics.isProcessQuiescent(state) }.getOrDefault(false)) return true
        val boot = ForeverProcIdentityPolicy.bootId()
        return state.launchAttempted && ForeverProcIdentityPolicy.recoveredQuiescent(state, boot) &&
            ForeverProcIdentityPolicy.validBootId(state.bootId) && boot != null && !boot.equals(state.bootId, true)
    }

    private fun requireRollbackEligibleLocked(store: ForeverRunStore, runId: String) {
        val state = requireNotNull(store.read(runId)) { "compiler repair RUN is missing" }
        require(store.readCurrent()?.runId == runId && quiescentForRollback(state)) {
            "compiler repair RUN became non-quiescent before rollback commit"
        }
    }

    private fun verifyReceiptTarget(filesRoot: File, json: JSONObject, pins: Pins) {
        val prefix = securePrefix(filesRoot, json.getString("containerId"), File(json.getString("prefixPath")))
        require(prefix.path == json.getString("prefixPath"))
        val target = secureTarget(prefix)
        require(target.path == json.getString("targetPath"))
        val checked = verifyFile(target, pins.installed)
        require(checked.hash == json.getString("installedSha256") &&
            identityEquals(checked.identity, identityFrom(json, "installedIdentity")))
    }

    private fun requireReceiptPins(json: JSONObject, pins: Pins) {
        require(json.getString("originalSha256") == pins.original.hash &&
            json.getLong("originalSize") == pins.original.size &&
            json.getString("installedSha256") == pins.installed.hash &&
            json.getLong("installedSize") == pins.installed.size)
    }

    private fun rollbackInternal(
        transaction: File,
        json: JSONObject,
        target: File,
        pins: Pins,
        store: ForeverRunStore,
        runId: String,
        hooks: TestHooks,
    ): Receipt {
        val original = pins.original
        val installed = pins.installed
        if (json.getString("status") == "ROLLBACK_ATTEMPTED" && verifyMatches(target, original)) {
            synchronized(store) {
                requireRollbackEligibleLocked(store, runId)
                val restored = verifyFile(target, original)
                require(identityEquals(restored.identity, identityFrom(json, "rollbackPreparedIdentity")) &&
                    identityEqualsMetadata(restored.identity, identityFrom(json, "originalIdentity"))) {
                    "interrupted rollback does not own the restored target"
                }
                val completed = json.put("status", "ROLLED_BACK").put("recoveredRollbackReceipt", true)
                writeTransaction(completed)
                return receipt(completed, restored.hash, reused = false)
            }
        }
        val current = verifyFile(target, installed)
        require(identityEquals(current.identity, identityFrom(json, "installedIdentity")))
        require(current.identity.mode == json.getInt("originalMode"))
        val backup = secureOwnedFile(File(json.getString("backupPath")), transaction.parentFile)
        val checkedBackup = verifyFile(backup, original)
        require(checkedBackup.hash == json.getString("originalSha256"))
        require(identityEqualsMetadata(checkedBackup.identity, identityFrom(json, "originalIdentity")))
        val temporary = temporaryFile(target.parentFile, "rollback")
        copyPinned(backup, temporary, original, hooks)
        setMode(temporary, json.getInt("originalMode"))
        setModified(temporary, identityFrom(json, "originalIdentity").modified)
        forceMetadata(temporary)
        val restoration = verifyFile(temporary, original)
        synchronized(store) {
            requireRollbackEligibleLocked(store, runId)
            hooks.beforeCommit?.invoke()
            requireRollbackEligibleLocked(store, runId)
            val immediate = verifyFile(target, installed)
            require(identityEquals(immediate.identity, identityFrom(json, "installedIdentity"))) {
                "compiler target changed before rollback commit"
            }
            writeTransaction(json.put("status", "ROLLBACK_ATTEMPTED")
                .put("rollbackPreparedIdentity", identityJson(restoration.identity)))
            atomicReplace(temporary, target, hooks)
            val restored = verifyFile(target, original)
            require(identityEqualsMetadata(restored.identity, identityFrom(json, "originalIdentity")))
            if (hooks.failReceiptAfterCommit) throw IOException("injected rollback receipt failure")
            val completed = json.put("status", "ROLLED_BACK")
                .put("restoredIdentity", identityJson(restored.identity))
            writeTransaction(completed)
            return receipt(completed, restored.hash, reused = false)
        }
    }

    private fun testPinsFrom(json: JSONObject): Pins = Pins(
        Pin(json.getString("installedSha256"), json.getLong("installedSize"), PE_AA64),
        Pin(json.getString("originalSha256"), json.getLong("originalSize"), PE_X64),
        Pin(json.getString("installedSha256"), json.getLong("installedSize"), PE_AA64),
    )

    private fun receipt(json: JSONObject, installedHash: String, reused: Boolean): Receipt = Receipt(
        json.getString("prefixPath"), json.getString("targetPath"), json.optString("backupPath"),
        json.getString("transactionPath"), json.getString("originalSha256"), installedHash, reused)

    private fun securePrefix(filesRoot: File, id: String, candidate: File): File {
        require(SAFE_ID.matches(id)) { "invalid container id" }
        val expected = fixedSharedPrefix(filesRoot, id)
        val prefix = candidate.canonicalFile
        val allowedRaw = setOf(
            File(filesRoot, "$PREFIX_ROOT/xuser-$id/.wine"),
            File(filesRoot, "imagefs/home/xuser/.wine"),
            File(filesRoot, "imagefs/home/xuser-$id/.wine"),
        ).map { normalizedOwnedAppAlias(filesRoot, it) }.toSet()
        require(normalizedOwnedAppAlias(filesRoot, candidate) in allowedRaw &&
            prefix == expected && prefix.path.startsWith(filesRoot.path + File.separator)) {
            "prefix escaped the managed ImageFS home"
        }
        require(!Files.isSymbolicLink(candidate.toPath())) { "managed .wine leaf is a symlink" }
        realDirectory(prefix, "Wine prefix")
        return prefix
    }

    private fun fixedSharedPrefix(filesRoot: File, id: String): File {
        val home = File(filesRoot, "$PREFIX_ROOT/xuser-$id")
        requireNoSymlinkComponents(home, filesRoot)
        realDirectory(home, "managed shared container home")
        val prefix = File(home, ".wine")
        requireNoSymlinkComponents(prefix, filesRoot)
        return realDirectory(prefix, "managed shared Wine prefix")
    }

    private fun allowedPrefixAlias(filesRoot: File, id: String, raw: File, prefix: File): Boolean {
        val allowed = setOf(
            File(filesRoot, "$PREFIX_ROOT/xuser-$id/.wine"),
            File(filesRoot, "imagefs/home/xuser/.wine"),
            File(filesRoot, "imagefs/home/xuser-$id/.wine"),
        ).map { normalizedOwnedAppAlias(filesRoot, it) }.toSet()
        return normalizedOwnedAppAlias(filesRoot, raw) in allowed && raw.canonicalFile == prefix
    }

    internal fun normalizedOwnedAppAlias(filesRoot: File, candidate: File): String {
        val roots = setOf("/data/data/com.wowforever.arm64probe/files", "/data/user/0/com.wowforever.arm64probe/files")
        val owner = filesRoot.absoluteFile.normalizePath()
        val raw = candidate.absoluteFile.normalizePath()
        if (owner in roots) {
            roots.firstOrNull { raw == it || raw.startsWith("$it/") }?.let { alias ->
                return owner + raw.removePrefix(alias)
            }
        }
        return raw
    }

    private fun File.normalizePath(): String = absoluteFile.toPath().normalize().toString()

    private fun secureExistingTarget(target: File): File {
        require(target.name == FILE_NAME && !Files.isSymbolicLink(target.toPath())) { "compiler target is not a real leaf" }
        require(target.parentFile?.name == "system32") { "compiler target is outside system32" }
        return target.canonicalFile
    }

    private fun requireNoSymlinkComponents(candidate: File, root: File) {
        val rootPath = root.canonicalFile.toPath()
        val raw = candidate.toPath().toAbsolutePath().normalize()
        require(raw.startsWith(rootPath) && raw != rootPath) { "managed path escaped its owning root" }
        var current = rootPath
        rootPath.relativize(raw).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "managed path contains a symlink component" }
        }
    }

    private fun secureTarget(prefix: File): File {
        val target = File(prefix, "drive_c/windows/system32/$FILE_NAME")
        require(!Files.isSymbolicLink(target.toPath())) { "compiler target is a symlink" }
        requireNoSymlinkComponents(target.parentFile, prefix)
        realDirectory(target.parentFile, "compiler system32")
        return target.canonicalFile
    }

    private fun verifyFile(file: File, pin: Pin): CheckedFile {
        require(SHA256.matches(pin.hash) && pin.size > 0) { "malformed compiler pin" }
        require(!Files.isSymbolicLink(file.toPath()) && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "compiler file is missing or a symlink"
        }
        val before = identity(file)
        require(before.size == pin.size && before.uid == Process.myUid()) { "compiler file identity is not owned and bounded" }
        readPeHeader(file, pin.machine)
        val hash = digest(file, pin.size)
        val after = identity(file)
        require(before == after && hash == pin.hash) { "compiler file changed or has an unknown hash" }
        return CheckedFile(file.canonicalFile, hash, after)
    }

    private fun verifyMatches(file: File, pin: Pin): Boolean = runCatching { verifyFile(file, pin); true }.getOrDefault(false)

    private fun identity(file: File): Identity {
        val attr = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attr.isRegularFile && !attr.isSymbolicLink)
        val stat = android.system.Os.stat(file.absolutePath)
        require(stat.st_nlink == 1L) { "compiler component is unexpectedly hardlinked" }
        return Identity(attr.fileKey()?.toString(), attr.size(), attr.lastModifiedTime().toMillis(), stat.st_uid,
            stat.st_mode and 0x1ff, stat.st_nlink)
    }

    private fun digest(file: File, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            require(input.size() == size)
            val buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES)
            var total = 0L
            while (total < size) {
                buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), size - total).toInt())
                val read = input.read(buffer)
                require(read > 0) { "compiler read made no progress" }
                buffer.flip(); md.update(buffer); total += read
            }
            require(total == size)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyPinned(source: File, destination: File, pin: Pin, hooks: TestHooks, sourceHook: Boolean = false) {
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "owned temporary already exists" }
        val sourceBefore = identity(source)
        var written = 0L
        FileChannel.open(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            require(input.size() == pin.size) { "compiler source size changed before copy" }
            FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES)
                while (written < pin.size) {
                    require(input.size() <= pin.size) { "compiler source grew during copy" }
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), pin.size - written).toInt())
                    val read = input.read(buffer)
                    require(read > 0) { "compiler source ended during copy" }
                    buffer.flip(); writeFully(output, buffer); written += read
                    require(written <= pin.size) { "compiler copy exceeded the pinned size" }
                    if (sourceHook) hooks.duringSourceCopy?.invoke()
                    if (hooks.failAfterBytes != null && written >= hooks.failAfterBytes) throw IOException("injected partial write")
                }
                require(written == pin.size)
                if (hooks.failFsync) throw IOException("injected fsync failure")
                output.force(true)
            }
        }
        setOwner(destination)
        val sourceAfter = verifyFile(source, pin)
        require(sourceBefore == sourceAfter.identity) { "compiler source changed while it was copied" }
        verifyFile(destination, pin)
        forceDirectory(destination.parentFile)
    }

    private fun atomicReplace(source: File, destination: File, hooks: TestHooks) {
        try {
            hooks.move?.invoke(source.toPath(), destination.toPath()) ?: Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic compiler replacement is unsupported")
        }
        forceDirectory(destination.parentFile)
    }

    private fun writeFully(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) require(channel.write(bytes) > 0) { "compiler write made no progress" }
    }

    private fun setOwner(file: File) {
        Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }

    private fun setMode(file: File, mode: Int) {
        require(mode in 0..0x1ff)
        Files.setPosixFilePermissions(file.toPath(), buildSet {
            if (mode and 0x100 != 0) add(PosixFilePermission.OWNER_READ)
            if (mode and 0x80 != 0) add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0x40 != 0) add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0x20 != 0) add(PosixFilePermission.GROUP_READ)
            if (mode and 0x10 != 0) add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0x8 != 0) add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0x4 != 0) add(PosixFilePermission.OTHERS_READ)
            if (mode and 0x2 != 0) add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0x1 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
        })
    }

    private fun setModified(file: File, modified: Long) {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified))
    }

    private fun forceMetadata(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        forceDirectory(file.parentFile)
    }

    private fun temporaryFile(parent: File, kind: String): File {
        realDirectory(parent, "compiler temporary parent")
        val file = File(parent, ".$FILE_NAME.$kind.${UUID.randomUUID()}.part")
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        return file
    }

    private fun secureNewFile(parent: File, kind: String): File {
        val file = File(parent, "$kind-$FILE_NAME-${UUID.randomUUID()}.bak")
        require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        return file
    }

    private fun secureOwnedFile(file: File, parent: File): File {
        require(file.parentFile?.canonicalFile == parent.canonicalFile && !Files.isSymbolicLink(file.toPath()))
        require(file.name.startsWith("backup-$FILE_NAME-") && file.name.endsWith(".bak"))
        return file
    }

    private fun realDirectory(file: File, label: String): File {
        require(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "$label is not a real directory"
        }
        return file.canonicalFile
    }

    private fun controlsDirectory(filesRoot: File, runId: String): File {
        require(SAFE_ID.matches(runId))
        var current = filesRoot
        for (part in CONTROLS_ROOT.split('/')) {
            val next = File(current, part)
            require(next.parentFile?.canonicalFile == current.canonicalFile && !Files.isSymbolicLink(next.toPath()))
            if (!Files.exists(next.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(next.toPath())
            realDirectory(next, "compiler control directory")
            current = next
        }
        val run = File(current, runId)
        require(run.parentFile?.canonicalFile == current.canonicalFile && !Files.isSymbolicLink(run.toPath()))
        if (!Files.exists(run.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(run.toPath())
        return realDirectory(run, "compiler RUN directory")
    }

    private fun transactionPath(filesRoot: File, runId: String): File =
        File(controlsDirectory(filesRoot, runId), "transaction.json")

    private fun existingTransactionPath(filesRoot: File, runId: String): File {
        require(SAFE_ID.matches(runId))
        var current = filesRoot.canonicalFile
        for (part in CONTROLS_ROOT.split('/')) {
            val next = File(current, part)
            require(next.parentFile?.canonicalFile == current.canonicalFile &&
                !Files.isSymbolicLink(next.toPath()) && Files.isDirectory(next.toPath(), LinkOption.NOFOLLOW_LINKS))
            current = next.canonicalFile
        }
        val run = File(current, runId)
        require(run.parentFile?.canonicalFile == current.canonicalFile &&
            !Files.isSymbolicLink(run.toPath()) && Files.isDirectory(run.toPath(), LinkOption.NOFOLLOW_LINKS))
        val transaction = File(run, "transaction.json")
        require(!Files.isSymbolicLink(transaction.toPath()) && Files.isRegularFile(transaction.toPath(), LinkOption.NOFOLLOW_LINKS))
        return transaction
    }

    private fun createTransaction(file: File, json: JSONObject) {
        val bytes = boundedJson(json)
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            writeFully(output, ByteBuffer.wrap(bytes)); output.force(true)
        }
        forceDirectory(file.parentFile)
    }

    private fun writeTransaction(json: JSONObject) {
        val file = File(json.getString("transactionPath"))
        require(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "compiler receipt is not the existing owned file"
        }
        val bytes = boundedJson(json)
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            writeFully(output, ByteBuffer.wrap(bytes)); output.force(true)
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic compiler receipt update is unsupported")
        }
        forceDirectory(file.parentFile)
    }

    private fun readTransaction(file: File): JSONObject {
        require(!Files.isSymbolicLink(file.toPath()))
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(MAX_TRANSACTION_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
                if (read < 0) break
                require(read > 0)
                count += read
            }
            require(count <= MAX_TRANSACTION_BYTES)
            return JSONObject(String(bytes, 0, count, Charsets.UTF_8))
        }
    }

    private fun validateCompletedTransaction(json: JSONObject, runId: String, rollbackIntent: Boolean = false) {
        requireJsonNumber(json, "schema", positive = true)
        require(json.getInt("schema") == 1)
        requireJsonString(json, "runId")
        require(json.getString("runId") == runId)
        requireJsonString(json, "containerId")
        require(SAFE_ID.matches(json.getString("containerId")))
        requireJsonString(json, "status")
        require(json.getString("status") in setOf("COMMITTED", "REUSED") ||
            (rollbackIntent && json.getString("status") == "ROLLBACK_ATTEMPTED"))
        listOf("prefixPath", "targetPath", "sourcePath", "backupPath", "transactionPath",
            "originalSha256", "installedSha256").forEach { requireJsonString(json, it) }
        require(SHA256.matches(json.getString("originalSha256")) && SHA256.matches(json.getString("installedSha256")))
        requireJsonNumber(json, "originalSize", positive = true)
        requireJsonNumber(json, "installedSize", positive = true)
        requireJsonNumber(json, "originalMode", positive = false)
        require(json.getInt("originalMode") in 0..0x1ff)
        requireJsonNumber(json, "originalUid", positive = true)
        require(json.get("reused") is Boolean)
        requireIdentityObject(json, "originalIdentity")
        requireIdentityObject(json, "installedIdentity")
        requireIdentityObject(json, "sourceIdentity")
        if (json.getString("status") in setOf("COMMITTED", "ROLLBACK_ATTEMPTED")) require(json.getString("backupPath").isNotEmpty())
    }

    private fun requireJsonString(json: JSONObject, name: String) {
        require(json.has(name) && json.get(name) is String)
    }

    private fun requireJsonNumber(json: JSONObject, name: String, positive: Boolean) {
        val value = json.get(name)
        require((value is Int || value is Long) && (!positive || (value as Number).toLong() > 0L))
    }

    private fun requireIdentityObject(json: JSONObject, name: String) {
        val value = json.get(name)
        require(value is JSONObject)
        val key = value.get("key")
        require(key === JSONObject.NULL || key is String)
        listOf("size", "modified", "uid", "mode").forEach {
            requireJsonNumber(value, it, positive = false)
        }
    }

    private fun boundedJson(json: JSONObject): ByteArray {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TRANSACTION_BYTES) { "compiler transaction exceeds 32 KiB" }
        return bytes
    }

    private fun forceDirectory(directory: File?) {
        requireNotNull(directory)
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun readPeHeader(file: File, machine: Int) {
        val bytes = ByteArray(MAX_PE_HEADER_BYTES)
        var count = 0
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < bytes.size) {
                val read = input.read(ByteBuffer.wrap(bytes, count, bytes.size - count))
                if (read < 0) break
                require(read > 0); count += read
            }
        }
        require(count >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte())
        val offset = littleInt(bytes, 0x3c)
        require(offset >= 64 && offset <= 4096 && offset + 26 <= count)
        require(bytes[offset] == 'P'.code.toByte() && bytes[offset + 1] == 'E'.code.toByte() &&
            bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 0.toByte() &&
            littleShort(bytes, offset + 4) == machine && littleShort(bytes, offset + 24) == PE32_PLUS) {
            "compiler is not the pinned PE32+ machine"
        }
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun identityJson(identity: Identity): JSONObject = JSONObject()
        .put("key", identity.key ?: JSONObject.NULL).put("size", identity.size)
        .put("modified", identity.modified).put("uid", identity.uid).put("mode", identity.mode).put("links", identity.links)

    private fun identityFrom(json: JSONObject, name: String): Identity {
        val value = json.getJSONObject(name)
        return Identity(value.optString("key").takeUnless { it.isEmpty() || it == "null" },
            value.getLong("size"), value.getLong("modified"), value.getInt("uid"), value.getInt("mode"), value.getLong("links"))
    }

    private fun identityEquals(left: Identity, right: Identity): Boolean =
        left.key == right.key && left.size == right.size && left.modified == right.modified &&
            left.uid == right.uid && left.mode == right.mode && left.links == right.links

    private fun identityEqualsMetadata(left: Identity, right: Identity): Boolean =
        left.size == right.size && left.modified == right.modified && left.uid == right.uid && left.mode == right.mode && left.links == right.links
}
