package app.gamenative

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.winlator.container.Container
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameMetadata
import app.gamenative.utils.GameMetadataManager
import app.gamenative.service.DownloadService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import org.json.JSONObject

/** Actual client/Data storage selection. Paths are resolved from Android volumes, never prefs. */
object ForeverFunctionalClientStorage {
    const val EXECUTABLE_SIZE = 94_286_544L
    const val EXECUTABLE_SHA256 = "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59"
    const val DATA_DIRECTORY = "Data"
    const val ORIGINAL_WTF = "WTF/BetaSuspendedTest.wtf"

    private const val PREFS = "forever_functional_client_storage"
    private const val SELECTED_ID = "selected_id"
    private const val SELECTED_LABEL = "selected_label"
    private const val SELECTED_DESCRIPTOR = "selected_descriptor"
    private const val REGISTRY = "validated_registry"
    private const val MARKER = ".forever-client-storage"
    private const val MARKER_VERSION = "forever-client-storage-v1"
    private const val MAX_MARKER_BYTES = 512
    private const val MAX_MOUNTINFO_BYTES = 256 * 1024
    private const val MAX_PASTED_CLIENT_README_BYTES = 64 * 1024
    private const val MAX_PASTED_CLIENT_README_ATTEMPTS = 3
    // Preparation may inspect the client tree, but it must remain metadata-only
    // and bounded even when Data is empty or hostile. Snapshot never calls this.
    private const val MAX_CLIENT_TREE_DEPTH = 64
    private const val MAX_CLIENT_TREE_ENTRIES = 100_000
    private val processRevision = AtomicLong(0)
    private val revalidationDiagnostics = RevalidationDiagnostics()

    internal data class RevalidationDiagnosticSnapshot(
        val calls: Long, val directed: Long, val fallback: Long,
        val failures: Long, val cancellations: Long,
        val totalNanos: Long, val selectionRegistryNanos: Long,
        val freshMetadataNanos: Long, val namespaceNanos: Long,
        val candidateInspectionNanos: Long, val resolveNanos: Long,
        val pathCanonicalNanos: Long, val markerNanos: Long,
        val identityMountNanos: Long, val finalCompareNanos: Long,
    )

    private class RevalidationDiagnostics {
        private val values = LongArray(15)
        @Synchronized fun record(timing: DirectedRevalidationTiming?, fallback: Boolean, failure: Throwable?) {
            values[0]++
            if (fallback) values[2]++ else values[1]++
            if (failure != null && failure !is CancellationException) values[3]++
            if (failure is CancellationException) values[4]++
            if (timing != null) {
                values[5] += timing.total
                values[6] += timing.selectionRegistry
                values[7] += timing.freshMetadata
                values[8] += timing.namespace
                values[9] += timing.candidateInspection
                values[10] += timing.resolve
                values[11] += timing.pathCanonical
                values[12] += timing.marker
                values[13] += timing.identityMount
                values[14] += timing.finalCompare
            }
            if (values[0] % 256L == 0L) runCatching {
                revalidationLogger.info(
                    "storage revalidation scope=process calls=${values[0]} directed=${values[1]} fallback=${values[2]} " +
                        "failures=${values[3]} cancellations=${values[4]} directedTotalMs=${values[5] / 1_000_000} " +
                        "selectionRegistryMs=${values[6] / 1_000_000} metadataMs=${values[7] / 1_000_000} " +
                        "namespaceMs=${values[8] / 1_000_000} candidateMs=${values[9] / 1_000_000} " +
                        "resolveInclusiveMs=${values[10] / 1_000_000} pathsMs=${values[11] / 1_000_000} " +
                        "markerMs=${values[12] / 1_000_000} identityMountMs=${values[13] / 1_000_000} " +
                        "compareMs=${values[14] / 1_000_000}",
                )
            }
        }
        @Synchronized fun snapshot() = RevalidationDiagnosticSnapshot(
            values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
            values[8], values[9], values[10], values[11], values[12], values[13], values[14],
        )
    }

    private class DirectedRevalidationTiming {
        var total = 0L
        var selectionRegistry = 0L
        var freshMetadata = 0L
        var namespace = 0L
        var candidateInspection = 0L
        var resolve = 0L
        var pathCanonical = 0L
        var marker = 0L
        var identityMount = 0L
        var finalCompare = 0L
    }

    private val revalidationLogger = Logger.getLogger("ForeverClientUpdate")

    internal fun revalidationDiagnosticSnapshot(): RevalidationDiagnosticSnapshot = revalidationDiagnostics.snapshot()

    internal fun formatAvailableBytes(value: Long): String {
        if (value <= 0) return "No disponible"
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
        var amount = value.toDouble()
        var index = 0
        while (amount >= 1024.0 && index < units.lastIndex) {
            amount /= 1024.0
            index++
        }
        return "%.1f %s".format(java.util.Locale.ROOT, amount, units[index])
    }

    internal fun selectionFailureMessage(failure: Throwable): String {
        val code = (failure as? StorageException)?.code
        return when {
            failure is SecurityException || failure is java.nio.file.AccessDeniedException ||
                code in setOf("storage_namespace_not_writable", "storage_not_writable") ->
                "Android no permite escribir directamente en este destino. No se cambiarán permisos ni se copiará el cliente automáticamente."
            code == "storage_busy" -> "Espera a que termine la preparación o la partida antes de cambiar el destino."
            code in setOf("storage_unavailable", "storage_mount_unproven", "storage_base_unavailable") ->
                "El destino no está disponible o Android no expone un acceso directo compatible. Reconecta la unidad y vuelve a comprobar."
            code == "storage_selection_not_saved" ->
                "No se ha podido confirmar el destino guardado. Revisa la ubicación seleccionada antes de preparar WoW."
            else -> "No se pudo usar este destino. Comprueba sus permisos y espacio libre; los datos existentes no se han movido."
        }
    }

    enum class Kind { PRIMARY, REMOVABLE, OTHER }

    /** Android metadata plus an injectable mount identity for Robolectric tests. */
    data class VolumeCandidate(
        val externalFilesDir: File,
        val uuid: String?,
        val isPrimary: Boolean,
        val isRemovable: Boolean,
        val state: String?,
        val description: String?,
        val mountIdentity: String? = null,
        /** Public StorageVolume.directory anchor for transient USB (API 30+). */
        val nativeVolumeRoot: File? = null,
    )

    internal data class NativeVolumeMetadata(
        val directory: File?, val uuid: String?, val primary: Boolean,
        val removable: Boolean, val state: String?, val description: String?,
        val mountIdentity: String? = null,
    )

    internal fun mergeVolumeCandidates(
        packageName: String,
        contextCandidates: List<VolumeCandidate>,
        nativeVolumes: List<NativeVolumeMetadata>,
    ): List<VolumeCandidate> {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        val result = contextCandidates.toMutableList()
        for (volume in nativeVolumes) {
            val directory = volume.directory ?: continue // Never guess /storage/<UUID>.
            val appFiles = File(directory, "Android/data/$packageName/files")
            val existing = result.indexOfFirst {
                if (volume.primary) it.isPrimary else
                    (volume.uuid != null && it.uuid == volume.uuid) ||
                        it.externalFilesDir.absoluteFile.toPath().normalize() == appFiles.absoluteFile.toPath().normalize()
            }
            if (existing >= 0) {
                val candidate = result[existing]
                // A matching UUID is not itself a path grant. Enrich only an
                // exact non-primary app namespace derived from the public API.
                if (!volume.primary && candidate.externalFilesDir.absoluteFile.toPath().normalize() == appFiles.absoluteFile.toPath().normalize()) {
                    result[existing] = candidate.copy(nativeVolumeRoot = directory,
                        mountIdentity = candidate.mountIdentity ?: volume.mountIdentity ?: mountIdentityFor(directory),
                        uuid = volume.uuid ?: candidate.uuid, isRemovable = volume.removable,
                        state = volume.state ?: candidate.state, description = volume.description ?: candidate.description)
                }
                continue
            }
            result += VolumeCandidate(appFiles, volume.uuid, volume.primary, volume.removable,
                volume.state, volume.description, volume.mountIdentity ?: mountIdentityFor(directory), directory)
        }
        return result.distinctBy { it.externalFilesDir.absoluteFile.toPath().normalize() }
    }

    data class StorageIdentity(val id: String, val kind: Kind)

    data class FileIdentity(val key: String)

    /** Folder and PC instructions for importing an existing ARM64 client. */
    data class PastedClientFolder(
        val created: Boolean,
        val clientRootPath: String,
        val pcPath: String,
        val readmePath: String,
        val readmeWritten: Boolean,
    )

    data class ClientInspection(
        val state: State,
        val executablePresent: Boolean,
        val originalConfigPresent: Boolean,
        val dataPresent: Boolean,
        val hashVerified: Boolean = false,
    ) {
        enum class State { NOT_INSTALLED, INCOMPLETE, PRESENT_NOT_VERIFIED, PASTED_SOURCE, READY }

        val label: String
            get() = when (state) {
                State.NOT_INSTALLED -> "Cliente no instalado"
                State.INCOMPLETE -> "Cliente incompleto"
                State.PRESENT_NOT_VERIFIED -> "Datos presentes; integridad no verificada"
                State.PASTED_SOURCE -> "Cliente pegado detectado; sin verificar"
                State.READY -> "Cliente preparado para comprobar"
            }
    }

    data class StorageOption(
        val identity: StorageIdentity,
        val label: String,
        val descriptor: String,
        /** Lexical API-derived path; it is canonicalized only after segment checks. */
        val root: File?,
        val mounted: Boolean,
        val writable: Boolean?,
        val availableBytes: Long,
        val client: ClientInspection,
        val legacy: Boolean = false,
        val trustedBase: File? = null,
        val mountIdentity: String? = null,
        val trusted: Boolean = false,
        val nativeVolumeRoot: File? = null,
    ) {
        /** Snapshot availability is not a writability claim; Usar destino probes it. */
        val available: Boolean
            get() = mounted && writable != false && trusted && root != null &&
                (identity.kind == Kind.PRIMARY || mountIdentity != null)
        val route: String get() = root?.absolutePath ?: "No conectado"
        val writeState: String
            get() = when (writable) {
                true -> "permiso observado"
                false -> "solo lectura o no disponible"
                null -> "permiso por comprobar al usar destino"
            }
    }

    data class Snapshot(val selected: StorageOption?, val options: List<StorageOption>, val revision: Long) {
        val removableOptions: List<StorageOption> get() = options.filter { it.identity.kind == Kind.REMOVABLE }
    }

    data class ResolvedTarget(
        val identity: StorageIdentity,
        val root: File,
        val revision: Long,
        val rootIdentity: FileIdentity? = null,
        val baseIdentity: FileIdentity? = null,
        val executableIdentity: FileIdentity? = null,
        val originalWtfIdentity: FileIdentity? = null,
        val mountIdentity: String? = null,
    )

    /** Destination admission for the updater; deliberately contains no client-build claim. */
    data class UpdateTarget(
        val identity: StorageIdentity,
        val root: File,
        val revision: Long,
        val rootIdentity: FileIdentity?,
        val baseIdentity: FileIdentity?,
        val mountIdentity: String?,
        val legacy: Boolean,
        internal val admittedUuid: String? = null,
        internal val admittedVolumeDirectory: File? = null,
        internal val admittedAppFilesDirectory: File? = null,
        internal val admittedLexicalRoot: File? = null,
    ) {
        internal fun hasDirectedProof(): Boolean =
            admittedUuid != null && admittedVolumeDirectory != null &&
                admittedAppFilesDirectory != null && admittedLexicalRoot != null

        internal fun hasPartialProof(): Boolean =
            admittedUuid != null || admittedVolumeDirectory != null ||
                admittedAppFilesDirectory != null || admittedLexicalRoot != null
    }

    open class StorageException(val code: String) : IllegalStateException(code)
    class SelectionCorruptException : StorageException("storage_selection_corrupt")
    class SelectionCancelledException : StorageException("storage_selection_cancelled")
    class SelectionCommittedException : StorageException("storage_selection_already_committed")

    /** A single process-wide owner. Closing an old lease cannot release a newer owner. */
    object ProcessGuard {
        private enum class Owner { IO, SELECTION, RECOVERY }
        private var owner: Owner? = null
        private var ownerToken = 0L
        private var nextToken = 0L
        private var guest: GuestReservation? = null

        class IoLease internal constructor(internal val token: Long) : AutoCloseable {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    ProcessGuard.releaseIo(token)
                }
            }
        }

        class GuestReservation internal constructor(internal val token: Long) {
            internal val state = AtomicReference(GuestState.RESERVED)
            internal enum class GuestState { RESERVED, STARTED, RELEASED }
        }

        @Synchronized fun acquireIo(): IoLease? {
            if (owner != null || guest != null) return null
            val token = ++nextToken
            owner = Owner.IO
            ownerToken = token
            return IoLease(token)
        }

        @Synchronized fun acquireSelection(): IoLease? {
            if (owner != null || guest != null) return null
            val token = ++nextToken
            owner = Owner.SELECTION
            ownerToken = token
            return IoLease(token)
        }

        @Synchronized private fun releaseIo(token: Long) {
            if (ownerToken == token) {
                owner = null
                ownerToken = 0L
            }
        }

        @Synchronized fun reserveGuest(io: IoLease): GuestReservation? {
            if (owner != Owner.IO || ownerToken != io.token || guest != null) return null
            return GuestReservation(io.token).also { guest = it }
        }

        @Synchronized fun markGuestStarted(reservation: GuestReservation): Boolean {
            if (guest !== reservation || reservation.state.get() != GuestReservation.GuestState.RESERVED) return false
            if (owner != null && (owner != Owner.IO || ownerToken != reservation.token)) return false
            return reservation.state.compareAndSet(
                GuestReservation.GuestState.RESERVED,
                GuestReservation.GuestState.STARTED,
            )
        }

        @Synchronized fun rollbackGuest(reservation: GuestReservation): Boolean {
            if (guest !== reservation) return false
            if (owner != null && (owner != Owner.IO || ownerToken != reservation.token)) return false
            if (!reservation.state.compareAndSet(
                    GuestReservation.GuestState.RESERVED,
                    GuestReservation.GuestState.RELEASED,
                )) return false
            guest = null
            return true
        }

        @Synchronized fun releaseGuest(reservation: GuestReservation): Boolean {
            if (guest !== reservation) return false
            if (owner != null && (owner != Owner.IO || ownerToken != reservation.token)) return false
            reservation.state.set(GuestReservation.GuestState.RELEASED)
            guest = null
            return true
        }

        /**
         * Serialize explicit guest recovery against selection, IO, launch callbacks,
         * and stale shutdown callbacks. Failure releases only the recovery lock; the
         * exact local guest reservation remains held.
         */
        @Synchronized fun recoverGuest(
            reservation: GuestReservation,
            recoverDurableTicket: () -> Boolean,
        ): Boolean {
            if (guest !== reservation || reservation.state.get() == GuestReservation.GuestState.RELEASED) return false
            val ownsRecoverySlot = when {
                owner == null -> {
                    owner = Owner.RECOVERY
                    ownerToken = ++nextToken
                    true
                }
                owner == Owner.IO && ownerToken == reservation.token -> false
                else -> return false
            }
            try {
                if (!recoverDurableTicket()) return false
                if (guest !== reservation) return false
                reservation.state.set(GuestReservation.GuestState.RELEASED)
                guest = null
                if (ownsRecoverySlot) {
                    owner = null
                    ownerToken = 0L
                }
                return true
            } finally {
                if (ownsRecoverySlot && owner == Owner.RECOVERY) {
                    owner = null
                    ownerToken = 0L
                }
            }
        }

        @Synchronized fun resetForTests() {
            owner = null
            ownerToken = 0L
            guest = null
            processRevision.set(0)
        }
    }

    val androidVolumeEnumerator = (object : (Context) -> List<VolumeCandidate> {
        override fun invoke(context: Context): List<VolumeCandidate> {
            val storage = context.getSystemService(StorageManager::class.java)
            val appDirectories = context.getExternalFilesDirs(null).mapNotNull { directory ->
                if (directory == null) return@mapNotNull null
                val volume = if (Build.VERSION.SDK_INT >= 24) storage?.getStorageVolume(directory) else null
                VolumeCandidate(
                    externalFilesDir = directory,
                    uuid = if (Build.VERSION.SDK_INT >= 24) volume?.uuid else null,
                    isPrimary = volume?.isPrimary ?: directory.absolutePath.contains("/emulated/"),
                    isRemovable = volume?.isRemovable == true,
                    state = runCatching { Environment.getExternalStorageState(directory) }.getOrNull(),
                    description = if (Build.VERSION.SDK_INT >= 24) volume?.getDescription(context) else null,
                    mountIdentity = mountIdentityFor(directory),
                )
            }
            // Context app-directory enumeration excludes transient USB on many
            // Android builds. Public native volume paths can be considered too,
            // but access is still proved only by explicit app-sandbox preflight.
            val native = if (Build.VERSION.SDK_INT >= 30) {
                storage?.storageVolumes.orEmpty().map { volume ->
                    NativeVolumeMetadata(volume.directory, volume.uuid, volume.isPrimary,
                        volume.isRemovable, volume.state, volume.getDescription(context))
                }
            } else emptyList()
            return mergeVolumeCandidates(context.packageName, appDirectories, native)
        }
    })

    /** Fresh public volume metadata; never cached, never a snapshot substitute. Production leaves mountIdentity null so each directed object reads the mount from its own sandbox, and description null so no UI getter runs per directed object (the full enumerator above is unchanged). */
    private val androidFreshNativeVolumes: (Context) -> List<NativeVolumeMetadata> = { context ->
        if (Build.VERSION.SDK_INT < 30) emptyList()
        else context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().map { volume ->
            NativeVolumeMetadata(volume.directory, volume.uuid, volume.isPrimary,
                volume.isRemovable, volume.state, null)
        }
    }

    fun service(context: Context): Service = Service(
        context.applicationContext,
        androidVolumeEnumerator,
        { defaultLegacyRootReadOnly(it) },
        PinnedExecutableVerifier,
        androidFreshNativeVolumes,
    )

    /** Test-only seam; production callers cannot replace the pinned verifier or roots. */
    internal fun testService(
        context: Context,
        volumes: (Context) -> List<VolumeCandidate>,
        legacyRoot: (Context) -> File,
        executableVerifier: ExecutableVerifier = PinnedExecutableVerifier,
        nativeVolumes: (Context) -> List<NativeVolumeMetadata> = { emptyList() },
    ): Service = Service(context.applicationContext, volumes, legacyRoot, executableVerifier, nativeVolumes)

    fun interface ExecutableVerifier {
        fun verify(file: File, calculateHash: Boolean): Boolean
    }

    object PinnedExecutableVerifier : ExecutableVerifier {
        override fun verify(file: File, calculateHash: Boolean): Boolean {
            if (!isRegular(file) || file.length() != EXECUTABLE_SIZE) return false
            if (!isPeArm64(file)) return false
            return !calculateHash || sha256(file) == EXECUTABLE_SHA256
        }
    }

    class SelectionRequest internal constructor(
        private val service: Service,
        val identity: StorageIdentity,
        private val lease: ProcessGuard.IoLease,
    ) : AutoCloseable {
        enum class State { PENDING, COMMITTING, COMMITTED, CANCELLED }
        private val stateRef = AtomicReference(State.PENDING)
        val state: State get() = stateRef.get()

        fun cancel(): Boolean {
            return stateRef.compareAndSet(State.PENDING, State.CANCELLED)
        }

        /** Cancellation is checked before the irreversible COMMITTING claim. */
        fun commit(beforeCommit: () -> Boolean = { true }) {
            if (stateRef.get() != State.PENDING) throw SelectionCancelledException()
            try {
                val option = service.snapshot().options.firstOrNull { it.identity == identity }
                    ?: throw SelectionCorruptException()
                service.requireSelectable(option)
                service.ensureOwnedTarget(option)
                service.validateStorage(option)
                if (!beforeCommit() || !stateRef.compareAndSet(State.PENDING, State.COMMITTING)) {
                    stateRef.compareAndSet(State.PENDING, State.CANCELLED)
                    throw SelectionCancelledException()
                }
                service.commitSelection(option)
                stateRef.set(State.COMMITTED)
            } catch (failure: Throwable) {
                stateRef.compareAndSet(State.COMMITTING, State.CANCELLED)
                throw failure
            } finally {
                lease.close()
            }
        }

        override fun close() {
            if (stateRef.get() == State.PENDING) stateRef.compareAndSet(State.PENDING, State.CANCELLED)
            lease.close()
        }
    }

    class Service internal constructor(
        private val context: Context,
        private val volumes: (Context) -> List<VolumeCandidate>,
        private val legacyRoot: (Context) -> File,
        private val executableVerifier: ExecutableVerifier,
        private val freshNativeVolumes: (Context) -> List<NativeVolumeMetadata>,
    ) {
        /**
         * Test-only migration seams; production leaves them null. The gate
         * seam replaces the durable operation-gate verdict, the probes fire
         * once each inside a single migration (after proof, after publish)
         * so mid-migration replacement is deterministic in tests.
         */
        internal var migrationGateBlockForTests: (() -> String?)? = null
        internal var migrationAfterProofProbeForTests: (() -> Unit)? = null
        internal var migrationAfterPublishProbeForTests: (() -> Unit)? = null

        /**
         * Durable operation-gate verdict, consulted only by legacy admission
         * migration: any unfinished updater/guest operation (whatever its
         * storage identity) or any unreadable gate/runtime state blocks
         * minting a new admission. A pending journal is never discarded or
         * rebased here; it fails closed with its own recovery diagnostic.
         */
        private fun migrationGateBlock(root: File, storageId: String): String? = runCatching {
            val override = migrationGateBlockForTests
            val reason = if (override != null) override() else
                ForeverClientRuntimeGate.forContext(context).operations.blockingReason()
            reason ?: if (ForeverClientUpdateService.hasPendingInstallTransaction(
                    context.noBackupFilesDir.canonicalFile, root, storageId,
                )) "update_recovery_required" else null
        }.getOrDefault("operation_state_unreadable")

        fun snapshot(): Snapshot = snapshot(includeClientMetadata = true)

        private fun snapshot(includeClientMetadata: Boolean): Snapshot {
            registryEntries()
            val persisted = persistedSelection()
            val options = volumes(context).asSequence()
                .filter { it.externalFilesDir.path.isNotBlank() }
                .map { inspectCandidate(it, includeClientMetadata) }
                .distinctBy { it.identity.id }
                .filter { it.available || it.mounted || it.legacy || it.identity.id == persisted?.first }
                .toMutableList()
            val selectedId = persisted?.first
            if (selectedId != null && options.none { it.identity.id == selectedId }) {
                val registry = registryEntries()
                val prior = registry.firstOrNull { it.id == selectedId } ?: throw SelectionCorruptException()
                options += StorageOption(
                    StorageIdentity(selectedId, prior.kind),
                    persisted.second ?: "Almacenamiento seleccionado",
                    persisted.third ?: "No conectado",
                    null, false, false, 0L,
                    ClientInspection(ClientInspection.State.NOT_INSTALLED, false, false, false),
                )
            }
            val selected = if (selectedId != null) {
                options.firstOrNull { it.identity.id == selectedId } ?: throw SelectionCorruptException()
            } else {
                options.firstOrNull { it.legacy } ?: throw StorageException("primary_storage_unavailable")
            }
            return Snapshot(selected, options.sortedBy { it.identity.id }, processRevision.get())
        }

        fun beginSelection(identity: StorageIdentity): SelectionRequest {
            val lease = ProcessGuard.acquireSelection() ?: throw StorageException("storage_busy")
            return SelectionRequest(this, identity, lease)
        }

        fun select(identity: StorageIdentity, beforeCommit: () -> Boolean = { true }) {
            val request = beginSelection(identity)
            try {
                request.commit(beforeCommit)
            } finally {
                request.close()
            }
        }

        fun acquireIoLease(): ProcessGuard.IoLease? = ProcessGuard.acquireIo()
        fun reserveGuest(lease: ProcessGuard.IoLease): ProcessGuard.GuestReservation? = ProcessGuard.reserveGuest(lease)
        fun markGuestStarted(reservation: ProcessGuard.GuestReservation): Boolean = ProcessGuard.markGuestStarted(reservation)
        fun rollbackGuest(reservation: ProcessGuard.GuestReservation): Boolean = ProcessGuard.rollbackGuest(reservation)
        fun releaseGuest(reservation: ProcessGuard.GuestReservation): Boolean = ProcessGuard.releaseGuest(reservation)

        fun resolveForPrepare(): ResolvedTarget {
            val selected = snapshot().selected ?: throw StorageException("storage_unselected")
            requireSelectable(selected)
            validateStorage(selected)
            val root = checkedCanonicalRoot(selected)
            try {
                validateClient(selected, root, calculateHash = true)
            } catch (failure: StorageException) {
                // The pinned verifier admits only 1.60.1.70009. An
                // updater-managed install (e.g. 1.60.1.70450) is governed
                // instead by its managed-install admission proof.
                if (failure.code != "client_executable_invalid") throw failure
                verifyManagedExecutable(selected, root)
            }
            val target = captureTarget(selected, root)
            rememberValidated(target, selected)
            return target
        }

        /**
         * Resolve the selected update destination without requiring an installed PE,
         * reading client configuration, creating directories, or probing writability.
         * Build/catalog admission and all mutation remain the updater's responsibility.
         */
        fun resolveForUpdate(): UpdateTarget {
            val selected = snapshot(includeClientMetadata = false).selected ?: throw StorageException("storage_unselected")
            return bindDirectedProof(selected, resolveForUpdate(selected))
        }

        /**
         * Prepare the selected removable volume for an out-of-band client paste.
         * Holds the process IO lease across admission, directory creation and
         * the instruction write, so a download, a selection change or a running
         * game cannot race this mutation (they fail with storage_busy instead).
         * Instructions are created next to (never inside) the client root, so
         * the updater sees only client-owned entries when it admits the source.
         */
        fun preparePastedClientFolder(): PastedClientFolder {
            val lease = ProcessGuard.acquireIo() ?: throw StorageException("storage_busy")
            try {
                val selected = snapshot(includeClientMetadata = false).selected
                    ?: throw StorageException("storage_unselected")
                if (selected.identity.kind != Kind.REMOVABLE) {
                    throw StorageException("storage_paste_requires_removable")
                }
                requireSelectable(selected)
                val target = resolveForUpdate(selected)
                revalidateForUpdate(target)

                val root = target.root
                val base = selected.trustedBase ?: throw StorageException("storage_base_unavailable")
                require(selected.trusted && lexicalWithin(root, base) && safeSegments(base, root)) {
                    throw StorageException("storage_path_invalid")
                }
                val existed = Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (existed) {
                    require(isDirectory(root)) { throw StorageException("storage_not_directory") }
                } else {
                    require(root.mkdirs() || Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw StorageException("storage_target_create_failed")
                    }
                }
                require(safeSegments(base, root) && isDirectory(root)) {
                    throw StorageException("storage_path_invalid")
                }
                // Refresh the admission after creating the root: a created root
                // carries a new identity, and the selection must still be ours.
                val reselected = snapshot(includeClientMetadata = false).selected
                    ?: throw StorageException("storage_unselected")
                require(reselected.identity == target.identity) {
                    throw StorageException("storage_selection_changed")
                }
                val fresh = resolveForUpdate(reselected)
                require(fresh.root == target.root) { throw StorageException("storage_root_changed") }
                revalidateForUpdate(fresh)
                verifyMarker(fresh.root, selected.identity)

                val parent = fresh.root.parentFile ?: throw StorageException("storage_path_invalid")
                require(safeSegments(base, parent) && isDirectory(parent)) {
                    throw StorageException("storage_path_invalid")
                }
                val clientPath = fresh.root.canonicalFile.absolutePath
                val pcPath = pastedClientPcPath(base, fresh.root)
                val readme = writePastedClientInstructions(parent, clientPath, pcPath)
                return PastedClientFolder(
                    created = !existed,
                    clientRootPath = clientPath,
                    pcPath = pcPath,
                    readmePath = readme.absolutePath,
                    readmeWritten = true,
                )
            } finally {
                lease.close()
            }
        }

        /** Resolve only the selection from this call's fresh snapshot, never a cached option. */
        private fun resolveForUpdate(selected: StorageOption, timing: DirectedRevalidationTiming? = null): UpdateTarget {
            val pathStarted = if (timing != null) System.nanoTime() else 0L
            val lexicalRoot: File
            val base: File
            val canonicalRoot: File
            val canonicalBase: File
            try {
                requireSelectable(selected)
                lexicalRoot = selected.root ?: throw StorageException("storage_unavailable")
                base = selected.trustedBase ?: throw StorageException("storage_base_unavailable")
                require(selected.trusted && lexicalWithin(lexicalRoot, base) && safeSegments(base, lexicalRoot)) {
                    throw StorageException("storage_path_invalid")
                }
                canonicalBase = base.canonicalFile
                canonicalRoot = lexicalRoot.canonicalFile
                require(lexicalWithin(canonicalRoot, canonicalBase)) { throw StorageException("storage_path_escape") }
                require(isDirectory(canonicalBase)) { throw StorageException("storage_base_unavailable") }
            } finally {
                if (timing != null) timing.pathCanonical += System.nanoTime() - pathStarted
            }
            val exists = Files.exists(canonicalRoot.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (exists) {
                require(isDirectory(canonicalRoot)) { throw StorageException("storage_not_directory") }
                val markerStarted = if (timing != null) System.nanoTime() else 0L
                try {
                    verifyMarker(canonicalRoot, selected.identity)
                } finally {
                    if (timing != null) timing.marker += System.nanoTime() - markerStarted
                }
            }
            val identityStarted = if (timing != null) System.nanoTime() else 0L
            val baseIdentity: FileIdentity?
            val rootIdentity: FileIdentity?
            val mount: String?
            try {
                baseIdentity = fileIdentity(canonicalBase, selected.legacy)
                rootIdentity = if (exists) fileIdentity(canonicalRoot, selected.legacy) else null
                mount = mountIdentity(selected)
                require(selected.legacy || (baseIdentity != null && mount != null && (!exists || rootIdentity != null))) {
                    throw StorageException("storage_identity_unproven")
                }
            } finally {
                if (timing != null) timing.identityMount += System.nanoTime() - identityStarted
            }
            return UpdateTarget(
                selected.identity,
                canonicalRoot,
                processRevision.get(),
                rootIdentity,
                baseIdentity,
                mount,
                selected.legacy,
            )
        }

        /** Re-resolve the same destination; a created/replaced root requires fresh admission. */
        fun revalidateForUpdate(target: UpdateTarget) {
            val directed = target.hasDirectedProof()
            val timing = if (directed) DirectedRevalidationTiming() else null
            val started = if (timing != null) System.nanoTime() else 0L
            var failure: Throwable? = null
            try {
                if (!directed) {
                    if (target.hasPartialProof()) throw StorageException("storage_unavailable")
                    val selected = snapshot(includeClientMetadata = false).selected ?: throw StorageException("storage_unselected")
                    require(selected.identity == target.identity && target.revision == processRevision.get()) {
                        throw StorageException("storage_selection_changed")
                    }
                    requireSelectable(selected)
                    val current = resolveForUpdate(selected)
                    require(current.root == target.root) { throw StorageException("storage_root_changed") }
                    require(current.rootIdentity == target.rootIdentity) { throw StorageException("storage_root_replaced") }
                    require(current.baseIdentity == target.baseIdentity) { throw StorageException("storage_base_replaced") }
                    require(current.mountIdentity == target.mountIdentity) { throw StorageException("storage_mount_changed") }
                    return
                }
                revalidateDirected(
                    target,
                    requireNotNull(target.admittedUuid),
                    requireNotNull(target.admittedVolumeDirectory),
                    requireNotNull(target.admittedAppFilesDirectory),
                    requireNotNull(target.admittedLexicalRoot),
                    requireNotNull(timing),
                )
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                if (timing != null) {
                    timing.total = System.nanoTime() - started
                    runCatching { revalidationDiagnostics.record(timing, fallback = false, failure) }
                } else {
                    runCatching { revalidationDiagnostics.record(null, fallback = true, failure) }
                }
            }
        }

        /**
         * Directed revalidation for a USB admission bound to fresh public metadata.
         * Exactly one fresh metadata query per object, zero full snapshots. Any
         * mismatch closes the operation; there is no fallback, retry, or readmission
         * inside this path. Guards stay identical: the same inspectCandidate(false)
         * trusted checks and the same physical resolveForUpdate(selected).
         */
        private fun revalidateDirected(
            target: UpdateTarget,
            admittedUuid: String,
            admittedVolumeDirectory: File,
            admittedAppFilesDirectory: File,
            admittedLexicalRoot: File,
            timing: DirectedRevalidationTiming,
        ) {
            // Fresh selection/registry/revision, exactly as today but without a snapshot.
            val selectionStarted = System.nanoTime()
            try {
                val persisted = persistedSelection() ?: throw StorageException("storage_selection_changed")
                require(persisted.first == target.identity.id) { throw StorageException("storage_selection_changed") }
                registryEntries().firstOrNull { it.id == target.identity.id }?.let { entry ->
                    require(entry.kind == target.identity.kind) { throw StorageException("storage_selection_changed") }
                }
                require(target.revision == processRevision.get()) { throw StorageException("storage_selection_changed") }
                require(target.identity.id == "volume:$admittedUuid") { throw StorageException("storage_unavailable") }
            } finally {
                timing.selectionRegistry += System.nanoTime() - selectionStarted
            }

            val metadataStarted = System.nanoTime()
            val fresh = try {
                freshNativeVolumes(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw StorageException("storage_unavailable")
            } finally {
                timing.freshMetadata += System.nanoTime() - metadataStarted
            }
            var volume: NativeVolumeMetadata? = null
            var directory: File? = null
            var actualAppFiles: File? = null
            val namespaceStarted = System.nanoTime()
            try {
                val matches = fresh.filter { it.uuid == admittedUuid }
                val foundVolume = when {
                    matches.isEmpty() -> throw StorageException("storage_mount_unproven")
                    matches.size > 1 -> throw StorageException("storage_unavailable")
                    else -> matches.single()
                }
                volume = foundVolume
                val foundDirectory = foundVolume.directory ?: throw StorageException("storage_mount_unproven")
                directory = foundDirectory
                if (foundVolume.primary || !foundVolume.removable) throw StorageException("storage_selection_changed")
                if (foundVolume.state != Environment.MEDIA_MOUNTED) throw StorageException("storage_unavailable")
                if (foundDirectory.absoluteFile.toPath().normalize().toString() !=
                    admittedVolumeDirectory.absoluteFile.toPath().normalize().toString()
                ) throw StorageException("storage_root_changed")
                val foundAppFiles = File(foundDirectory, "Android/data/${context.packageName}/files")
                actualAppFiles = foundAppFiles
                if (foundAppFiles.absoluteFile.toPath().normalize().toString() !=
                    admittedAppFilesDirectory.absoluteFile.toPath().normalize().toString()
                ) throw StorageException("storage_root_changed")
                val expectedLexical = File(foundAppFiles, "CustomGames/${ForeverFunctionalPoc.GAME_FOLDER_NAME}")
                if (expectedLexical.absoluteFile.toPath().normalize().toString() !=
                    admittedLexicalRoot.absoluteFile.toPath().normalize().toString()
                ) throw StorageException("storage_root_changed")
            } finally {
                timing.namespace += System.nanoTime() - namespaceStarted
            }

            val candidateStarted = System.nanoTime()
            val selected = try {
                inspectCandidate(
                    buildDirectedCandidate(requireNotNull(volume), requireNotNull(directory), requireNotNull(actualAppFiles)),
                    includeClientMetadata = false,
                )
            } finally {
                timing.candidateInspection += System.nanoTime() - candidateStarted
            }
            require(selected.identity == target.identity) { throw StorageException("storage_selection_changed") }
            val resolveStarted = System.nanoTime()
            val current = try {
                resolveForUpdate(selected, timing)
            } finally {
                timing.resolve += System.nanoTime() - resolveStarted
            }
            val compareStarted = System.nanoTime()
            try {
                require(current.root == target.root) { throw StorageException("storage_root_changed") }
                require(current.rootIdentity == target.rootIdentity) { throw StorageException("storage_root_replaced") }
                require(current.baseIdentity == target.baseIdentity) { throw StorageException("storage_base_replaced") }
                require(current.mountIdentity == target.mountIdentity) { throw StorageException("storage_mount_changed") }
                require(current.revision == target.revision) { throw StorageException("storage_selection_changed") }
            } finally {
                timing.finalCompare += System.nanoTime() - compareStarted
            }
        }

        /** Sandbox candidate from the current public directory; trusts nothing until inspected. */
        private fun buildDirectedCandidate(
            volume: NativeVolumeMetadata,
            directory: File,
            appFiles: File,
        ): VolumeCandidate = VolumeCandidate(
            externalFilesDir = appFiles,
            uuid = volume.uuid,
            isPrimary = volume.primary,
            isRemovable = volume.removable,
            state = volume.state,
            description = volume.description,
            mountIdentity = volume.mountIdentity,
            nativeVolumeRoot = directory,
        )

        /**
         * Bind the ephemeral namespace proof at admission: one full snapshot plus
         * the physical resolve above, then one extra fresh metadata query. Static
         * ineligibility (old API, legacy/primary, non-removable, no native root,
         * path-based identity) keeps the previous route. Once preliminary
         * preconditions exist, a query that proves change, ambiguity, or failure
         * rejects admission instead of hiding behind the fallback.
         */
        private fun bindDirectedProof(selected: StorageOption, admitted: UpdateTarget): UpdateTarget {
            if (admitted.legacy || admitted.identity.kind != Kind.REMOVABLE) return admitted
            if (Build.VERSION.SDK_INT < 30) return admitted
            val nativeRoot = selected.nativeVolumeRoot ?: return admitted
            val uuid = admitted.identity.id.takeIf { it.startsWith("volume:") }
                ?.removePrefix("volume:")?.takeIf { it.isNotBlank() } ?: return admitted
            val fresh = try {
                freshNativeVolumes(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw StorageException("storage_unavailable")
            }
            val matches = fresh.filter { it.uuid == uuid }
            val volume = when {
                matches.isEmpty() -> throw StorageException("storage_mount_unproven")
                matches.size > 1 -> throw StorageException("storage_unavailable")
                else -> matches.single()
            }
            val directory = volume.directory ?: throw StorageException("storage_mount_unproven")
            if (volume.primary || !volume.removable) throw StorageException("storage_selection_changed")
            if (volume.state != Environment.MEDIA_MOUNTED) throw StorageException("storage_unavailable")
            if (directory.absoluteFile.toPath().normalize().toString() !=
                nativeRoot.absoluteFile.toPath().normalize().toString()
            ) throw StorageException("storage_root_changed")
            val actualAppFiles = File(directory, "Android/data/${context.packageName}/files")
            if (actualAppFiles.absoluteFile.toPath().normalize().toString() !=
                selected.trustedBase?.absoluteFile?.toPath()?.normalize().toString()
            ) throw StorageException("storage_root_changed")
            val lexical = selected.root ?: throw StorageException("storage_unavailable")
            val expectedLexical = File(actualAppFiles, "CustomGames/${ForeverFunctionalPoc.GAME_FOLDER_NAME}")
            if (lexical.absoluteFile.toPath().normalize().toString() !=
                expectedLexical.absoluteFile.toPath().normalize().toString()
            ) throw StorageException("storage_root_changed")
            return admitted.copy(
                admittedUuid = uuid,
                admittedVolumeDirectory = directory.absoluteFile.toPath().normalize().toFile(),
                admittedAppFilesDirectory = actualAppFiles.absoluteFile.toPath().normalize().toFile(),
                admittedLexicalRoot = lexical.absoluteFile.toPath().normalize().toFile(),
            )
        }

        /** Stable, bounded namespace ID shared by guest and updater leases; does not alter storage prefs. */
        fun operationStorageId(target: ResolvedTarget): String = operationStorageId(
            target.identity,
            target.root,
        )

        fun operationStorageId(target: UpdateTarget): String = operationStorageId(
            target.identity,
            target.root,
        )

        private fun operationStorageId(
            identity: StorageIdentity,
            root: File,
        ): String {
            // Durable identity only: the selected volume identity plus the
            // canonical client-root path inside the app namespace. Filesystem
            // fileKeys (inode/dev) are deliberately excluded: on FAT/FUSE
            // removable volumes they are transient cache artifacts that get
            // reassigned across runs without any directory replacement, which
            // used to orphan the admission after every rotation. Root, base,
            // mount and file replacement is still checked strictly by the
            // frozen target revalidation within the same operation/lease.
            val material = listOf(
                identity.id,
                ForeverClientUpdateService.stableRootPath(root),
            ).joinToString("\u0000")
            return "storage-${sha256Bytes(material.toByteArray(Charsets.UTF_8))}"
        }

        fun prepareContainer(target: ResolvedTarget): Container =
            prepareContainerInternal(target, fullTraversal = true)

        /**
         * Same-operation container gate for the preparation chain: identical
         * to [prepareContainer] except the frozen-target check skips the full
         * client-tree traversal (selection/mount/root/base and frozen file
         * identities are still re-proved). Only ForeverFunctionalPoc.prepare
         * may use it, while holding the process IO lease from resolve through
         * the final [revalidate] gate in beforeLaunch.
         */
        fun prepareContainerPrepared(target: ResolvedTarget): Container =
            prepareContainerInternal(target, fullTraversal = false)

        private fun prepareContainerInternal(target: ResolvedTarget, fullTraversal: Boolean): Container {
            if (fullTraversal) revalidate(target) else revalidatePrepared(target)
            val container = ContainerUtils.getContainer(context, ForeverFunctionalPoc.APP_ID)
            val entries = driveEntries(container.drives)
            val aEntries = entries.filter { it.first.equals("A", ignoreCase = true) }
            require(aEntries.size <= 1) { throw StorageException("container_duplicate_a_drive") }
            val currentA = aEntries.singleOrNull()?.second?.let { File(it).canonicalFile }
            if (currentA != null && currentA != target.root.canonicalFile && !isAllowedPriorRoot(currentA)) {
                throw StorageException("container_a_drive_unknown")
            }
            val updated = replaceDriveA(container.drives, target.root.canonicalPath)
            if (updated != container.drives) {
                container.setDrives(updated)
                container.saveData()
            }
            return container
        }

        /**
         * First-run container bootstrap for the fixed functional client. The
         * executable/root proof ([resolveForPrepare]) must already have run:
         * registration and creation happen only for the proved root, and the
         * strict [prepareContainer] gate below still validates the resulting
         * A: mapping. An existing container is reused untouched; nothing is
         * reset and no version is changed.
         */
        fun ensureContainer(
            target: ResolvedTarget,
            lookup: () -> String? = { CustomGameScanner.getFolderPathFromAppId(ForeverFunctionalPoc.APP_ID) },
            register: () -> String = { registerCustomGame(target.root) },
        ): Container = ensureContainerInternal(target, fullTraversal = true, lookup, register)

        /**
         * Same-operation bootstrap for the preparation chain: identical to
         * [ensureContainer] except the frozen-target checks skip the full
         * client-tree traversal. Only ForeverFunctionalPoc.prepare may use
         * it, while holding the process IO lease from resolve through the
         * final [revalidate] gate in beforeLaunch.
         */
        fun ensureContainerPrepared(
            target: ResolvedTarget,
            lookup: () -> String? = { CustomGameScanner.getFolderPathFromAppId(ForeverFunctionalPoc.APP_ID) },
            register: () -> String = { registerCustomGame(target.root) },
        ): Container = ensureContainerInternal(target, fullTraversal = false, lookup, register)

        private fun ensureContainerInternal(
            target: ResolvedTarget,
            fullTraversal: Boolean,
            lookup: () -> String?,
            register: () -> String,
        ): Container {
            // Fail a stale target (e.g. swapped USB during the async
            // bootstrap join or profile import above) before any
            // registration mutation; the strict terminal gate inside
            // prepareContainer stays.
            if (fullTraversal) revalidate(target) else revalidatePrepared(target)
            resolveContainerRegistration(target.root, lookup, register)
            val container = ContainerUtils.getOrCreateContainer(context, ForeverFunctionalPoc.APP_ID)
            require(container.id == ForeverFunctionalPoc.APP_ID) { "custom-game container identity changed" }
            return prepareContainerInternal(target, fullTraversal)
        }

        /**
         * Guarded registration verdict for [ensureContainer]. Pure except for
         * the injected [lookup]/[register] seams. A non-null mapping that is
         * not this exact root (foreign owner) and a failing lookup both fail
         * before any registration call, so the mismatch copy stays truthful
         * (nothing mutated at this point). Only a cache miss (null) may
         * register, followed by a round-trip confirmation.
         */
        internal fun resolveContainerRegistration(
            root: File,
            lookup: () -> String?,
            register: () -> String,
        ) {
            val canonical = root.canonicalFile
            val registered = try {
                lookup()
            } catch (_: Exception) {
                throw StorageException("container_registration_mismatch")
            }
            if (registered != null) {
                val same = runCatching { File(registered).canonicalFile == canonical }.getOrDefault(false)
                require(same) { throw StorageException("container_registration_mismatch") }
                return
            }
            val appId = register()
            require(appId == ForeverFunctionalPoc.APP_ID) { throw StorageException("container_registration_mismatch") }
            val confirmed = runCatching {
                lookup()?.let { File(it).canonicalFile == canonical } ?: false
            }.getOrDefault(false)
            require(confirmed) { throw StorageException("container_registration_mismatch") }
        }

        /** Manual-folder registration through the established scanner flow. */
        private fun registerCustomGame(root: File): String {
            val canonical = root.canonicalFile
            val pinnedNew = pinFunctionalIdentity(canonical)
            val previous = PrefManager.customGameManualFolders
            try {
                val folders = previous.toMutableSet()
                val existing = folders.firstOrNull { runCatching { File(it).canonicalFile == canonical }.getOrDefault(false) }
                if (existing == null) {
                    folders += canonical.absolutePath
                    PrefManager.customGameManualFolders = folders
                    CustomGameScanner.invalidateCache()
                }
                val item = CustomGameScanner.createLibraryItemFromFolder(canonical.absolutePath)
                    ?: throw StorageException("container_registration_mismatch")
                // The stock detection may extract `<exe>.extracted.ico` beside
                // the executable; that accepted residual is existing behavior,
                // not new logic, and needs no rollback.
                require(item.gameSource == GameSource.CUSTOM_GAME && item.appId == ForeverFunctionalPoc.APP_ID) {
                    throw StorageException("container_registration_mismatch")
                }
                val registered = CustomGameScanner.getFolderPathFromAppId(item.appId)
                    ?: throw StorageException("container_registration_mismatch")
                require(File(registered).canonicalFile == canonical) { throw StorageException("container_registration_mismatch") }
                return item.appId
            } catch (failure: Throwable) {
                if (pinnedNew) rollbackPin(canonical)
                runCatching {
                    PrefManager.customGameManualFolders = previous
                    CustomGameScanner.invalidateCache()
                }
                throw failure
            }
        }

        /**
         * Binds the proved functional root to the fixed functional identity
         * through the stock game-metadata pin (`.gamenative`). Hashing this
         * USB root would deterministically yield a different scanner ID, so
         * the pin is required, never arbitrary; the stock scanner itself
         * persists derived IDs the same way. Runs after the fresh
         * [revalidate] at the top of [ensureContainer], on the canonical
         * proved root, with NOFOLLOW guards at the pin itself.
         *
         * States: absent pins via the stock writer (returns true); the exact
         * identity is a noop (returns false); a link, a non-regular node, an
         * unparseable file, or a foreign identity all fail closed with zero
         * writes. The writer swallows IO failures, so the read-back gates
         * fail closed when the pin did not stick.
         */
        internal fun pinFunctionalIdentity(canonical: File): Boolean {
            val numericId = ForeverFunctionalPoc.APP_ID.removePrefix("CUSTOM_GAME_").toInt()
            val pin = File(canonical, ".gamenative")
            val path = pin.toPath()
            require(!Files.isSymbolicLink(path)) { throw StorageException("container_registration_mismatch") }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                GameMetadataManager.write(canonical, GameMetadata(numericId))
                require(runCatching { GameMetadataManager.getAppId(canonical) }.getOrNull() == numericId) {
                    throw StorageException("container_registration_mismatch")
                }
                return true
            }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw StorageException("container_registration_mismatch") }
            require(parsePinIdentity(readBounded(pin)) == numericId) { throw StorageException("container_registration_mismatch") }
            require(runCatching { GameMetadataManager.getAppId(canonical) }.getOrNull() == numericId) {
                throw StorageException("container_registration_mismatch")
            }
            return false
        }

        /** Read-only pin parse (stock JSON and legacy integer shapes); never migrates. */
        private fun parsePinIdentity(bytes: ByteArray?): Int? {
            val text = bytes?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.startsWith("{")) {
                return runCatching { JSONObject(text).optInt("appId", -1) }.getOrNull()?.takeIf { it > 0 }
            }
            return text.toIntOrNull()?.takeIf { it > 0 }
        }

        /** Removes only a pin this run created, and only while it still declares this identity. */
        private fun rollbackPin(canonical: File) {
            val numericId = ForeverFunctionalPoc.APP_ID.removePrefix("CUSTOM_GAME_").toInt()
            val path = File(canonical, ".gamenative").toPath()
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
            if (parsePinIdentity(readBounded(File(canonical, ".gamenative")) ?: return) != numericId) return
            runCatching { Files.deleteIfExists(path) }
        }

        /**
         * Same-operation intermediate gate for the preparation chain. Proves
         * the exact same frozen bindings as [revalidate] (selection/revision,
         * mount, canonical root/base, marker, fixed nodes, executable/Data/WTF
         * presence and frozen executable/WTF identities, including the managed
         * admission fallback) except the bounded full client-tree traversal,
         * which already ran in [resolveForPrepare] and runs again in the final
         * beforeLaunch gate. Only the preparation chain
         * may use it, while holding the process IO lease acquired for that
         * same operation; every other consumer keeps calling [revalidate].
         */
        fun revalidatePrepared(target: ResolvedTarget) {
            val selected = snapshot().selected ?: throw StorageException("storage_unselected")
            require(selected.identity == target.identity && target.revision == processRevision.get()) {
                throw StorageException("storage_selection_changed")
            }
            requireSelectable(selected)
            // Diagnose a replaced volume/root before checking its client
            // contents; a new empty root must never be mistaken for this run.
            require(selected.root?.canonicalFile == target.root.canonicalFile) {
                throw StorageException("storage_root_changed")
            }
            validateStorage(selected, performProbe = false, traverseTree = false)
            val root = checkedCanonicalRoot(selected)
            require(root == target.root.canonicalFile) { throw StorageException("storage_root_changed") }
            require(fileIdentity(root, selected.legacy) == target.rootIdentity) { throw StorageException("storage_root_replaced") }
            require(fileIdentity(requireNotNull(selected.trustedBase), selected.legacy) == target.baseIdentity) {
                throw StorageException("storage_base_replaced")
            }
            val mount = mountIdentity(selected)
            require(mount == target.mountIdentity) { throw StorageException("storage_mount_changed") }
            try {
                validateClient(selected, root, calculateHash = false, expected = target)
            } catch (failure: StorageException) {
                // Same managed-build fallback as resolveForPrepare, without
                // re-hashing: the full proof ran at resolve time and the
                // frozen executable/WTF identities are compared below.
                if (failure.code != "client_executable_invalid") throw failure
                verifyManagedFrozenExecutable(selected, root, target)
            }
        }

        fun revalidate(target: ResolvedTarget) {
            val selected = snapshot().selected ?: throw StorageException("storage_unselected")
            require(selected.identity == target.identity && target.revision == processRevision.get()) {
                throw StorageException("storage_selection_changed")
            }
            requireSelectable(selected)
            // Diagnose a replaced volume/root before checking its client
            // contents; a new empty root must never be mistaken for this run.
            require(selected.root?.canonicalFile == target.root.canonicalFile) {
                throw StorageException("storage_root_changed")
            }
            validateStorage(selected, performProbe = false)
            val root = checkedCanonicalRoot(selected)
            require(root == target.root.canonicalFile) { throw StorageException("storage_root_changed") }
            require(fileIdentity(root, selected.legacy) == target.rootIdentity) { throw StorageException("storage_root_replaced") }
            require(fileIdentity(requireNotNull(selected.trustedBase), selected.legacy) == target.baseIdentity) {
                throw StorageException("storage_base_replaced")
            }
            val mount = mountIdentity(selected)
            require(mount == target.mountIdentity) { throw StorageException("storage_mount_changed") }
            try {
                validateClient(selected, root, calculateHash = false, expected = target)
            } catch (failure: StorageException) {
                // Same managed-build fallback as resolveForPrepare, without
                // re-hashing: the full proof ran at resolve time and the
                // frozen executable/WTF identities are compared below.
                if (failure.code != "client_executable_invalid") throw failure
                verifyManagedFrozenExecutable(selected, root, target)
            }
        }

        fun isAllowedPriorRoot(root: File): Boolean {
            val legacy = runCatching { root.canonicalFile == legacyRoot(context).canonicalFile }.getOrDefault(false)
            if (legacy) return true
            val derivedBase = root.parentFile?.parentFile ?: return false
            if (isDirectory(derivedBase) && !safeSegments(derivedBase, root)) return false
            val hash = pathFingerprint(root)
            val baseHash = pathFingerprint(derivedBase)
            return registryEntries().any { entry ->
                entry.rootFingerprint == hash && entry.baseFingerprint == baseHash
            }
        }

        private fun inspectCandidate(candidate: VolumeCandidate, includeClientMetadata: Boolean = true): StorageOption {
            val primary = candidate.isPrimary
            val root = if (primary) legacyRoot(context).absoluteFile else
                File(candidate.externalFilesDir.absoluteFile, "CustomGames/${ForeverFunctionalPoc.GAME_FOLDER_NAME}")
            val base = if (primary) candidate.externalFilesDir.absoluteFile.parentFile else candidate.externalFilesDir.absoluteFile
            val kind = when { primary -> Kind.PRIMARY; candidate.isRemovable -> Kind.REMOVABLE; else -> Kind.OTHER }
            val mount = candidate.mountIdentity ?: mountIdentityFor(candidate.externalFilesDir)
            val rootShapeValid = !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS) || isDirectory(root)
            val nativeRoot = candidate.nativeVolumeRoot
            val nativeNamespaceValid = nativeRoot != null &&
                candidate.externalFilesDir.absoluteFile.toPath().normalize() ==
                File(nativeRoot, "Android/data/${context.packageName}/files").absoluteFile.toPath().normalize()
            val trusted = base != null && rootShapeValid && lexicalWithin(root, base) &&
                if (nativeRoot != null) {
                    // The root walk includes the exact app-files base and all
                    // its ancestors; a second prefix walk repeats the same I/O.
                    nativeNamespaceValid && safeSegments(nativeRoot, root)
                } else {
                    base.canWrite() && safeSegments(base, root)
                }
            val existing = Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)
            val writable: Boolean? = if (candidate.state == Environment.MEDIA_MOUNTED_READ_ONLY) false else null
            val client = if (includeClientMetadata && trusted && existing) inspectClientMetadata(root) else
                ClientInspection(ClientInspection.State.NOT_INSTALLED, false, false, false)
            return StorageOption(
                StorageIdentity(stableId(candidate, kind), kind),
                candidate.description?.takeIf { it.isNotBlank() }
                    ?: if (primary) "Almacenamiento interno" else if (candidate.isRemovable) "Almacenamiento extraíble" else "Almacenamiento externo",
                candidate.uuid?.let { "UUID $it" } ?: if (primary) "Volumen principal" else "Volumen externo",
                root,
                candidate.state == Environment.MEDIA_MOUNTED || candidate.state == Environment.MEDIA_MOUNTED_READ_ONLY,
                writable,
                if (includeClientMetadata) availableBytes(root) else 0L,
                client,
                primary,
                base,
                mount,
                trusted,
                nativeRoot,
            )
        }

        internal fun requireSelectable(option: StorageOption) {
            require(option.available) { throw StorageException(if (option.identity.kind == Kind.REMOVABLE && option.mountIdentity == null) "storage_mount_unproven" else "storage_unavailable") }
        }

        internal fun ensureOwnedTarget(option: StorageOption) {
            val root = option.root ?: throw StorageException("storage_unavailable")
            val base = option.trustedBase ?: throw StorageException("storage_base_unavailable")
            if (!Files.exists(base.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val volumeRoot = option.nativeVolumeRoot ?: throw StorageException("storage_base_unavailable")
                val expectedBase = File(volumeRoot, "Android/data/${context.packageName}/files")
                val expected = if (option.legacy) expectedBase.parentFile else expectedBase
                require(base.absoluteFile.toPath().normalize() == expected?.absoluteFile?.toPath()?.normalize() &&
                    safeSegments(volumeRoot, base) && option.available) { throw StorageException("storage_path_invalid") }
                // This is a user-confirmed selection, not a snapshot or a
                // permission workaround. Android may reject raw USB access.
                require(base.mkdirs() || isDirectory(base)) { throw StorageException("storage_namespace_not_writable") }
                require(safeSegments(volumeRoot, base)) { throw StorageException("storage_path_invalid") }
            }
            require(option.available && Files.isDirectory(base.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw StorageException("storage_unavailable")
            }
            require(safeSegments(base, root)) { throw StorageException("storage_path_invalid") }
            if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(root.mkdirs() || Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw StorageException("storage_target_create_failed")
                }
                require(safeSegments(base, root)) { throw StorageException("storage_path_invalid") }
                val marker = File(root, MARKER)
                Files.write(marker.toPath(), markerBytes(option.identity), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } else {
                require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) { throw StorageException("storage_not_directory") }
                verifyMarker(root, option.identity)
            }
        }

        internal fun validateStorage(option: StorageOption, performProbe: Boolean = true, traverseTree: Boolean = true) {
            requireSelectable(option)
            val root = checkedCanonicalRoot(option)
            validateOptionalFixedNodes(root, traverseTree)
            verifyMarker(root, option.identity)
            if (performProbe) require(probeWritable(root)) { throw StorageException("storage_not_writable") }
        }

        private fun validateClient(option: StorageOption, root: File, calculateHash: Boolean, expected: ResolvedTarget? = null) {
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val data = File(root, DATA_DIRECTORY)
            val wtf = File(root, ORIGINAL_WTF)
            require(isRegular(executable) && isDirectory(data) && isRegular(wtf)) {
                val any = Files.exists(executable.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(data.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(wtf.toPath(), LinkOption.NOFOLLOW_LINKS)
                throw StorageException(if (any) "client_incomplete" else "client_not_installed")
            }
            require(executableVerifier.verify(executable, calculateHash)) { throw StorageException("client_executable_invalid") }
            if (expected != null) {
                require(fileIdentity(executable, option.legacy) == expected.executableIdentity) { throw StorageException("client_executable_replaced") }
                require(fileIdentity(wtf, option.legacy) == expected.originalWtfIdentity) { throw StorageException("client_wtf_replaced") }
            }
        }

        private fun captureTarget(option: StorageOption, root: File): ResolvedTarget {
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val wtf = File(root, ORIGINAL_WTF)
            val rootIdentity = fileIdentity(root, option.legacy)
            val baseIdentity = fileIdentity(requireNotNull(option.trustedBase), option.legacy)
            val executableIdentity = fileIdentity(executable, option.legacy)
            val wtfIdentity = fileIdentity(wtf, option.legacy)
            require(option.legacy || (rootIdentity != null && baseIdentity != null && executableIdentity != null && wtfIdentity != null && mountIdentity(option) != null)) {
                throw StorageException("storage_identity_unproven")
            }
            return ResolvedTarget(
                option.identity, root, processRevision.get(),
                rootIdentity,
                baseIdentity,
                executableIdentity,
                wtfIdentity,
                mountIdentity(option),
            )
        }

        /**
         * Managed-build fallback for [resolveForPrepare].
         *
         * The pinned verifier admits only 1.60.1.70009. An updater-managed
         * install (e.g. 1.60.1.70450) is governed instead by its
         * managed-install admission record, read and strictly validated by
         * the updater gate itself: storage/root binding, admitted target,
         * byte-exact .build.info bound to the admitted build config (never an
         * arbitrary .build.info), and executable size plus MD5 content key
         * plus ARM64 PE (never size-only trust). No CASC scan and no
         * loose-file sweep: the updater verified those at commit and the
         * admission is the proof. Native validation is neither consulted nor
         * claimed.
         */
        private fun verifyManagedExecutable(option: StorageOption, root: File) {
            val record = managedInstallRecord(option, root, allowMigration = true)
            val target = record.targetBuild
            val admittedExe = record.looseFiles.singleOrNull { it.path.equals(target.executable.path, ignoreCase = true) }
            if (admittedExe == null || admittedExe.decodedBytes != target.executable.decodedBytes ||
                !admittedExe.contentKey.equals(target.executable.contentKey, ignoreCase = true)
            ) throw StorageException("client_executable_invalid")
            // Manifest proof first, mirroring the updater's verification
            // order; only then is the .build.info fingerprint adopted.
            if (!verifyManagedExecutableBytes(root, admittedExe.decodedBytes, admittedExe.contentKey)) {
                throw StorageException("client_executable_invalid")
            }
            // Client-relative name bound by the admission (updater BUILD_INFO_PATH).
            val buildInfo = readBoundedManagedFile(File(root, ".build.info"), record.buildInfoBytes)
                ?: throw StorageException("client_executable_invalid")
            if (!sha256Bytes(buildInfo).equals(record.buildInfoSha256, ignoreCase = true)) {
                throw StorageException("client_executable_invalid")
            }
            if (ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                    buildInfo, target.version, target.buildConfig.hash, target.cdnConfig.hash, manifestVerified = true,
                ) == null
            ) throw StorageException("client_executable_invalid")
        }

        /**
         * Managed-build counterpart of the frozen [revalidate] check: the
         * frozen executable/WTF identities must be unchanged, and the
         * admission must still bind this destination to the same admitted
         * build. No hashing here, mirroring the legacy revalidation which
         * compares identities after the full proof at resolve time.
         */
        private fun verifyManagedFrozenExecutable(option: StorageOption, root: File, expected: ResolvedTarget) {
            val record = managedInstallRecord(option, root, allowMigration = false)
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val wtf = File(root, ORIGINAL_WTF)
            if (!isRegular(executable) || !isRegular(wtf)) throw StorageException("client_incomplete")
            if (fileIdentity(executable, option.legacy) != expected.executableIdentity) {
                throw StorageException("client_executable_replaced")
            }
            if (fileIdentity(wtf, option.legacy) != expected.originalWtfIdentity) {
                throw StorageException("client_wtf_replaced")
            }
            // The frozen identities match the resolve-time fully-proved
            // files; the admission must still bind the same admitted build.
            val target = record.targetBuild
            val buildInfo = readBoundedManagedFile(File(root, ".build.info"), record.buildInfoBytes)
                ?: throw StorageException("client_executable_invalid")
            if (ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                    buildInfo, target.version, target.buildConfig.hash, target.cdnConfig.hash, manifestVerified = true,
                ) == null
            ) throw StorageException("client_executable_invalid")
        }

        /**
         * Operation-frozen proof inputs for a legacy migration: every
         * filesystem object the content proof reads, captured before the
         * proof and compared after it, before publication and after
         * publication. Transient rotation and mid-proof replacement both
         * change these keys; the stable destination id alone cannot tell.
         */
        private data class MigrationFrozenIdentities(
            val root: FileIdentity,
            val base: FileIdentity,
            val mount: String,
            val executable: FileIdentity,
            val buildInfo: FileIdentity,
            /** Lowercased relative path to (relative path, identity key). */
            val looseFiles: Map<String, Pair<String, String>>,
        )

        /** Safe NOFOLLOW resolution of one admitted loose file; null when unsafe or absent. */
        private fun resolveManagedLooseFile(root: File, relative: String): Path? {
            if (!isSafeManagedRelativePath(relative)) return null
            val base = root.toPath().toAbsolutePath().normalize()
            val path = base.resolve(relative).normalize()
            if (!path.startsWith(base)) return null
            if (hasManagedSymlink(path)) return null
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            return path
        }

        private fun freezeMigrationCore(
            root: File,
            base: File,
            mount: String,
            legacy: Boolean,
        ): MigrationFrozenIdentities {
            return MigrationFrozenIdentities(
                root = fileIdentity(root, legacy) ?: throw StorageException("client_executable_invalid"),
                base = fileIdentity(base, legacy) ?: throw StorageException("client_executable_invalid"),
                mount = mount,
                executable = fileIdentity(File(root, ForeverFunctionalPoc.EXECUTABLE_NAME), legacy)
                    ?: throw StorageException("client_executable_invalid"),
                buildInfo = fileIdentity(File(root, ".build.info"), legacy)
                    ?: throw StorageException("client_executable_invalid"),
                looseFiles = emptyMap(),
            )
        }

        private fun revalidateMigrationFrozen(
            root: File,
            base: File,
            frozen: MigrationFrozenIdentities,
            legacy: Boolean,
        ) {
            if (fileIdentity(root, legacy) != frozen.root) throw StorageException("client_executable_invalid")
            if (fileIdentity(base, legacy) != frozen.base) throw StorageException("client_executable_invalid")
            if (fileIdentity(File(root, ForeverFunctionalPoc.EXECUTABLE_NAME), legacy) != frozen.executable) {
                throw StorageException("client_executable_invalid")
            }
            if (fileIdentity(File(root, ".build.info"), legacy) != frozen.buildInfo) {
                throw StorageException("client_executable_invalid")
            }
            for ((_, entry) in frozen.looseFiles) {
                val path = resolveManagedLooseFile(root, entry.first)
                    ?: throw StorageException("client_executable_invalid")
                if (fileIdentity(path.toFile(), legacy)?.key != entry.second) {
                    throw StorageException("client_executable_invalid")
                }
            }
        }

        /** Post-proof durable selection check; any change fails closed, never migrates. */
        private fun currentMigrationSelection(
            option: StorageOption,
            root: File,
            startRevision: Long,
        ): StorageOption {
            val current = snapshot().selected ?: throw StorageException("client_executable_invalid")
            require(current.identity == option.identity && startRevision == processRevision.get()) {
                throw StorageException("client_executable_invalid")
            }
            require(current.root?.canonicalFile == root.canonicalFile) {
                throw StorageException("client_executable_invalid")
            }
            verifyMarker(root, current.identity)
            return current
        }

        /**
         * Derive the updater admission coordinates without another snapshot:
         * the stable storage id plus the explicit durable destination binding.
         * Storage, marker and path checks already ran in [validateStorage]
         * immediately before either caller. A present-but-unreadable record
         * for this exact stable destination is corruption or tampering, never
         * a missing admission: it fails closed without legacy migration.
         * Clean absence on the resolve path migrates at most one linked
         * legacy record (see [migrateLegacyManagedAdmission]) and otherwise
         * reports an unavailable admission, which is distinct from a corrupt
         * executable. The frozen revalidation path never migrates: anything
         * that changed since resolve fails closed.
         */
        private fun managedInstallRecord(
            option: StorageOption,
            root: File,
            allowMigration: Boolean,
        ): ForeverClientUpdateService.ManagedInstallAdmissionRecord {
            val storageId = operationStorageId(option.identity, root)
            val storageIdentity = option.identity.id
            val rootPath = ForeverClientUpdateService.stableRootPath(root)
            val anchor = runCatching { context.noBackupFilesDir.canonicalFile }.getOrNull()
                ?: throw StorageException("client_executable_invalid")
            val direct = try {
                ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, rootPath)
            } catch (_: Exception) {
                null
            }
            if (direct != null) return direct
            if (managedInstallAdmissionPresent(anchor, storageId)) throw StorageException("client_executable_invalid")
            if (!allowMigration) throw StorageException("client_executable_invalid")
            // One-time bounded migration of the fileKey-bound legacy record,
            // followed by a re-read that proves the persisted write before
            // anything is trusted.
            migrateLegacyManagedAdmission(option, root, storageId, storageIdentity, rootPath, anchor)
            return try {
                ForeverClientUpdateService.readManagedInstallAdmission(anchor, storageId, storageIdentity, rootPath)
            } catch (_: Exception) {
                null
            } ?: throw StorageException("client_executable_invalid")
        }

        private fun managedInstallAdmissionPresent(anchor: File, storageId: String): Boolean {
            return try {
                val file = ForeverClientUpdateService.managedInstallAdmissionFile(anchor, storageId)
                Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                // Fail closed: an unreadable expected path is not an absent admission.
                true
            }
        }

        /**
         * One-time bounded migration of a schema-1 fileKey-bound admission to
         * the stable schema-2 record, for the first launch after an inode
         * rotation. The legacy record is linked ONLY through the private
         * selection registry for this exact destination (identity plus
         * canonical root/base path fingerprints) together with its legacy
         * root fileKey; there is no search for arbitrary same-executable
         * records.
         *
         * Proof boundaries are frozen, not inferred: root/base/mount plus the
         * critical executable/.build.info and every admitted loose identity
         * are captured before the content proof and compared after it, before
         * publication and after publication. A pending updater/guest
         * operation (any storage identity) or unreadable gate state blocks
         * minting before any proof and again before the write; the journal is
         * never discarded or rebased. The admitted content is fully re-proved
         * (byte-exact .build.info bound to the admitted build config and to
         * the exact legacy SHA, executable size plus MD5 content key plus
         * ARM64 PE, every admitted loose file by size plus MD5), never a CASC
         * re-scan. The legacy file is preserved untouched; the versioned
         * record is created new-only. A post-publication mismatch rolls back
         * only the record provably created by this call (persisted bytes
         * still equal our deterministic encoding); a concurrent winner or
         * foreign record is never deleted. Any ambiguity, conflict,
         * alteration or unrelated pending state fails closed instead of being
         * rebased. Every consumer re-proves content at consumption, so this
         * record never certifies CASC integrity.
         */
        private fun migrateLegacyManagedAdmission(
            option: StorageOption,
            root: File,
            storageId: String,
            storageIdentity: String,
            rootPath: String,
            anchor: File,
        ) {
            val startRevision = processRevision.get()
            val selected = snapshot().selected ?: throw StorageException("client_admission_unavailable")
            require(selected.identity == option.identity && selected.identity.id == storageIdentity) {
                throw StorageException("client_admission_unavailable")
            }
            requireSelectable(selected)
            val base = selected.trustedBase ?: throw StorageException("client_admission_unavailable")
            require(selected.trusted && selected.root?.canonicalFile == root.canonicalFile) {
                throw StorageException("client_admission_unavailable")
            }
            verifyMarker(root, selected.identity)
            val mount = mountIdentity(selected) ?: throw StorageException("client_admission_unavailable")
            if (migrationGateBlock(root, storageId) != null) throw StorageException("client_update_pending")
            val registryMatches = registryEntries().filter { it.id == storageIdentity }
            if (registryMatches.isEmpty()) throw StorageException("client_admission_unavailable")
            if (registryMatches.size > 1) throw StorageException("client_executable_invalid")
            val entry = registryMatches.single()
            if (entry.rootFingerprint != pathFingerprint(root) ||
                entry.baseFingerprint != pathFingerprint(base)
            ) throw StorageException("client_admission_unavailable")
            val legacyRootKey = entry.rootKey?.takeIf { it.isNotBlank() }
                ?: throw StorageException("client_admission_unavailable")
            val files = try {
                ForeverClientUpdateService.listManagedInstallAdmissionFiles(anchor)
            } catch (_: Exception) {
                throw StorageException("client_executable_invalid")
            }
            val candidates = ArrayList<ForeverClientUpdateService.Companion.LegacyManagedInstallAdmission>(1)
            for (file in files) {
                val bytes = try {
                    ForeverClientUpdateService.readManagedInstallAdmissionBytes(file)
                } catch (_: Exception) {
                    throw StorageException("client_executable_invalid")
                } ?: throw StorageException("client_executable_invalid")
                val legacy = ForeverClientUpdateService.parseLegacyManagedInstallAdmissionRecord(bytes)
                    ?: continue
                if (legacy.storageId == storageId) continue
                if (legacy.rootIdentity != legacyRootKey) continue
                if (file.name != "managed-install-admission-" +
                    sha256Bytes(legacy.storageId.toByteArray(Charsets.UTF_8)) + ".json"
                ) throw StorageException("client_executable_invalid")
                candidates += legacy
            }
            if (candidates.isEmpty()) throw StorageException("client_admission_unavailable")
            if (candidates.size > 1) throw StorageException("client_executable_invalid")
            val legacy = candidates.single()
            val frozen = freezeMigrationCore(root, base, mount, option.legacy)
            val frozenWithLoose = frozen.copy(
                looseFiles = freezeMigrationLooseFiles(root, legacy.looseFiles, option.legacy),
            )
            if (!proveLegacyManagedContent(root, legacy)) throw StorageException("client_executable_invalid")
            migrationAfterProofProbeForTests?.invoke()
            revalidateMigrationFrozen(root, base, frozenWithLoose, option.legacy)
            val reselected = currentMigrationSelection(option, root, startRevision)
            require(mountIdentity(reselected) == frozenWithLoose.mount) {
                throw StorageException("client_executable_invalid")
            }
            if (migrationGateBlock(root, storageId) != null) throw StorageException("client_update_pending")
            val buildInfo = readBoundedManagedFile(File(root, ".build.info"), legacy.buildInfoBytes)
                ?: throw StorageException("client_executable_invalid")
            require(sha256Bytes(buildInfo).equals(legacy.buildInfoSha256, ignoreCase = true)) {
                throw StorageException("client_executable_invalid")
            }
            val recordBytes = try {
                ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
                    legacy.targetBuild.originalJsonCopy(),
                    legacy.catalogSha256,
                    storageId,
                    storageIdentity,
                    rootPath,
                    buildInfo,
                    legacy.looseFiles,
                )
            } catch (_: Exception) {
                throw StorageException("client_executable_invalid")
            }
            val publishedFile = try {
                ForeverClientUpdateService.publishMigratedInstallAdmission(anchor, storageId, recordBytes)
            } catch (_: Exception) {
                throw StorageException("client_executable_invalid")
            }
            migrationAfterPublishProbeForTests?.invoke()
            val persisted = (try {
                ForeverClientUpdateService.readManagedInstallAdmissionBytes(publishedFile)
            } catch (_: Exception) {
                null
            }) ?: throw StorageException("client_executable_invalid")
            if (!persisted.contentEquals(recordBytes)) throw StorageException("client_executable_invalid")
            try {
                revalidateMigrationFrozen(root, base, frozenWithLoose, option.legacy)
                val published = currentMigrationSelection(option, root, startRevision)
                require(mountIdentity(published) == frozenWithLoose.mount) {
                    throw StorageException("client_executable_invalid")
                }
                if (migrationGateBlock(root, storageId) != null) throw StorageException("client_update_pending")
            } catch (failure: Exception) {
                runCatching {
                    val current = try {
                        ForeverClientUpdateService.readManagedInstallAdmissionBytes(publishedFile)
                    } catch (_: Exception) {
                        null
                    }
                    if (current != null && current.contentEquals(recordBytes)) {
                        Files.deleteIfExists(publishedFile.toPath())
                    }
                }
                throw failure
            }
        }

        private fun freezeMigrationLooseFiles(
            root: File,
            looseFiles: List<ForeverClientUpdateService.ManagedAdmissionLooseFile>,
            legacy: Boolean,
        ): Map<String, Pair<String, String>> {
            val frozenLoose = LinkedHashMap<String, Pair<String, String>>()
            for (file in looseFiles) {
                val lower = file.path.lowercase(java.util.Locale.ROOT)
                if (frozenLoose.containsKey(lower)) throw StorageException("client_executable_invalid")
                val path = resolveManagedLooseFile(root, file.path)
                    ?: throw StorageException("client_executable_invalid")
                val key = fileIdentity(path.toFile(), legacy)?.key
                    ?: throw StorageException("client_executable_invalid")
                frozenLoose[lower] = file.path to key
            }
            return frozenLoose
        }

        /**
         * Content proof for a linked legacy admission, mirroring the
         * steady-state gate plus every admitted loose file: byte-exact
         * .build.info bound to the admitted build config (never an arbitrary
         * .build.info), and executable size plus MD5 content key plus ARM64
         * PE (never size-only trust). No CASC scan and no native validation
         * claim: this re-proves the updater's commit-time proof after an
         * identity rotation, it does not recertify CASC integrity.
         */
        private fun proveLegacyManagedContent(
            root: File,
            legacy: ForeverClientUpdateService.Companion.LegacyManagedInstallAdmission,
        ): Boolean {
            return try {
                val target = legacy.targetBuild
                val admittedExe = legacy.looseFiles.singleOrNull {
                    it.path.equals(target.executable.path, ignoreCase = true)
                }
                if (admittedExe == null || admittedExe.decodedBytes != target.executable.decodedBytes ||
                    !admittedExe.contentKey.equals(target.executable.contentKey, ignoreCase = true)
                ) return false
                if (!verifyManagedExecutableBytes(root, admittedExe.decodedBytes, admittedExe.contentKey)) return false
                val buildInfo = readBoundedManagedFile(File(root, ".build.info"), legacy.buildInfoBytes)
                    ?: return false
                if (!sha256Bytes(buildInfo).equals(legacy.buildInfoSha256, ignoreCase = true)) return false
                if (ForeverClientUpdateService.verifiedBuildInfoFingerprint(
                        buildInfo, target.version, target.buildConfig.hash, target.cdnConfig.hash, manifestVerified = true,
                    ) == null
                ) return false
                verifyManagedAdmissionLooseFiles(root, legacy.looseFiles)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Proves every admitted loose file by exact size plus MD5 content
         * key. Paths were bounded at admission parse time but are still
         * resolved safely here: no link following, no escape from the client
         * root, no protected WTF paths. Any failure fails closed.
         */
        private fun verifyManagedAdmissionLooseFiles(
            root: File,
            looseFiles: List<ForeverClientUpdateService.ManagedAdmissionLooseFile>,
        ): Boolean {
            return try {
                val seen = HashSet<String>()
                for (file in looseFiles) {
                    if (!seen.add(file.path.lowercase(java.util.Locale.ROOT))) return false
                    val path = resolveManagedLooseFile(root, file.path) ?: return false
                    if (runCatching { Files.size(path) }.getOrNull() != file.decodedBytes) return false
                    val md5 = md5ManagedBytes(path, file.decodedBytes) ?: return false
                    if (!md5.equals(file.contentKey, ignoreCase = true)) return false
                    if (runCatching { Files.size(path) }.getOrNull() != file.decodedBytes) return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun isSafeManagedRelativePath(relative: String): Boolean =
            relative.isNotEmpty() && relative.length <= 4_096 && '\u0000' !in relative && '\\' !in relative &&
                !relative.startsWith('/') && relative.split('/').all {
                    it.isNotEmpty() && it != "." && it != ".." && it.none { ch -> ch.code < 0x20 || ch.code == 0x7f }
                } && !relative.substringBefore('/').equals("WTF", ignoreCase = true)

        /**
         * Prove only the executable (and Data presence) for launch, without
         * requiring the original WTF. Gates contract-owned WTF provisioning:
         * no write happens unless the executable is fully proved here, so an
         * arbitrary unverified root never receives configuration. Throws the
         * precise storage code otherwise.
         */
        internal fun provenExecutableRoot(): File {
            val selected = snapshot().selected ?: throw StorageException("storage_unselected")
            requireSelectable(selected)
            validateStorage(selected)
            val root = checkedCanonicalRoot(selected)
            verifyLaunchExecutable(selected, root)
            return root
        }

        private fun verifyLaunchExecutable(option: StorageOption, root: File) {
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val data = File(root, DATA_DIRECTORY)
            if (!isRegular(executable) || !isDirectory(data)) throw StorageException("client_incomplete")
            if (!executableVerifier.verify(executable, true)) verifyManagedExecutable(option, root)
        }

        private fun checkedCanonicalRoot(option: StorageOption): File {
            val lexical = option.root ?: throw StorageException("storage_unavailable")
            val base = option.trustedBase ?: throw StorageException("storage_base_unavailable")
            require(option.trusted && lexicalWithin(lexical, base)) { throw StorageException("storage_path_invalid") }
            require(safeSegments(base, lexical)) { throw StorageException("storage_path_invalid") }
            val canonicalBase = base.canonicalFile
            val canonicalRoot = lexical.canonicalFile
            require(lexicalWithin(canonicalRoot, canonicalBase)) { throw StorageException("storage_path_escape") }
            require(isDirectory(canonicalRoot)) { throw StorageException("storage_not_directory") }
            return canonicalRoot
        }

        private fun validateOptionalFixedNodes(root: File, traverseTree: Boolean = true) {
            require(isDirectory(root)) { throw StorageException("storage_not_directory") }
            val data = File(root, DATA_DIRECTORY)
            val wtfDir = File(root, "WTF")
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val sourceWtf = File(root, ORIGINAL_WTF)
            if (Files.exists(data.toPath(), LinkOption.NOFOLLOW_LINKS)) require(isDirectory(data)) { throw StorageException("storage_data_invalid") }
            if (Files.exists(wtfDir.toPath(), LinkOption.NOFOLLOW_LINKS)) require(isDirectory(wtfDir)) { throw StorageException("storage_wtf_invalid") }
            if (Files.exists(executable.toPath(), LinkOption.NOFOLLOW_LINKS)) require(isRegular(executable)) { throw StorageException("storage_executable_invalid") }
            if (Files.exists(sourceWtf.toPath(), LinkOption.NOFOLLOW_LINKS)) require(isRegular(sourceWtf)) { throw StorageException("storage_wtf_invalid") }
            if (traverseTree) validateClientTree(root)
        }

        /**
         * Validate every existing client-root entry using NOFOLLOW metadata only.
         * This is deliberately iterative: aliases and special nodes fail closed,
         * while depth and entry limits prevent preparation from becoming an
         * unbounded filesystem walk. Snapshot stays on direct metadata checks.
         */
        private fun validateClientTree(root: File) {
            data class PendingDirectory(val path: Path, val depth: Int)

            val pending = ArrayDeque<PendingDirectory>()
            pending.addLast(PendingDirectory(root.toPath(), 0))
            var entries = 0

            while (pending.isNotEmpty()) {
                val directory = pending.removeLast()
                if (directory.depth > MAX_CLIENT_TREE_DEPTH) {
                    throw StorageException("storage_tree_too_deep")
                }
                try {
                    Files.newDirectoryStream(directory.path).use { children ->
                        for (child in children) {
                            entries++
                            if (entries > MAX_CLIENT_TREE_ENTRIES) {
                                throw StorageException("storage_tree_too_large")
                            }
                            val attributes = try {
                                Files.readAttributes(
                                    child,
                                    java.nio.file.attribute.BasicFileAttributes::class.java,
                                    LinkOption.NOFOLLOW_LINKS,
                                )
                            } catch (_: Exception) {
                                throw StorageException("storage_tree_invalid")
                            }
                            if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                                throw StorageException("storage_tree_invalid")
                            }
                            if (attributes.isDirectory) {
                                val childDepth = directory.depth + 1
                                if (childDepth > MAX_CLIENT_TREE_DEPTH) {
                                    throw StorageException("storage_tree_too_deep")
                                }
                                pending.addLast(PendingDirectory(child, childDepth))
                            }
                        }
                    }
                } catch (failure: StorageException) {
                    throw failure
                } catch (_: Exception) {
                    throw StorageException("storage_tree_unreadable")
                }
            }
        }

        private fun inspectClientMetadata(root: File): ClientInspection {
            if (!isDirectory(root)) return ClientInspection(ClientInspection.State.NOT_INSTALLED, false, false, false)
            val executable = File(root, ForeverFunctionalPoc.EXECUTABLE_NAME)
            val wtf = File(root, ORIGINAL_WTF)
            val data = File(root, DATA_DIRECTORY)
            val e = isRegular(executable)
            val w = isRegular(wtf)
            val d = isDirectory(data)
            val any = Files.exists(executable.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(wtf.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.exists(data.toPath(), LinkOption.NOFOLLOW_LINKS)
            val pastedCascData = isDirectory(File(data, "data"))
            val executableAbsent = !Files.exists(executable.toPath(), LinkOption.NOFOLLOW_LINKS)
            val state = when {
                e && w && d -> ClientInspection.State.PRESENT_NOT_VERIFIED
                executableAbsent && pastedCascData -> ClientInspection.State.PASTED_SOURCE
                any -> ClientInspection.State.INCOMPLETE
                else -> ClientInspection.State.NOT_INSTALLED
            }
            return ClientInspection(state, e, w, d)
        }

        private fun pastedClientPcPath(base: File, root: File): String {
            val basePath = base.absoluteFile.toPath().normalize()
            val rootPath = root.absoluteFile.toPath().normalize()
            require(rootPath.startsWith(basePath)) { throw StorageException("storage_path_invalid") }
            val relative = basePath.relativize(rootPath).map { it.toString() }
            return (listOf("Android", "data", context.packageName, "files") + relative)
                .filter(String::isNotEmpty)
                .joinToString("/")
        }

        private fun writePastedClientInstructions(parent: File, clientPath: String, pcPath: String): File {
            val pcParent = pcPath.substringBeforeLast('/', "")
            val targetLine = pastedClientTargetLine()
            val content = """
                |Preparar la instalación de WoW Classic Beta para Android
                |
                |Carpeta de pegado (vista desde el PC):
                |$pcPath
                |
                |Carpeta de pegado (ruta en el móvil):
                |$clientPath
                |
                |Qué pegar (recomendado para cualquier build):
                |- Solo la carpeta Data/ del cliente ARM64 (con sus datos e índices locales) y el fichero .build.info del mismo cliente.
                |- Si tu cliente es exactamente la versión objetivo de esta app, también puedes incluir WowB-ARM64.exe y sus dependencias. Si el build no coincide, los archivos sueltos no se aprovechan y pueden rechazarse.
                |- No se sobrescribe nada de lo que ya haya en la carpeta.
                |
                |Qué NO pegar:
                |- No pegues un cliente x86_64: esta app ejecuta el cliente ARM64.
                |- No pegues WTF/ ni ficheros de configuración.
                |- No pegues ficheros que no pertenezcan al cliente ni otros ficheros ocultos que empiezan por punto (aparte del .build.info obligatorio).
                |- En particular, no copies .forever-client-storage, .forever-client-update/ ni .forever-update-staging/: son datos internos de la app y el marcador pertenece a un volumen concreto.
                |
                |$targetLine
                |
                |Pasos:
                |1. Desconecta el USB del móvil y conéctalo al PC.
                |2. Pega lo indicado en la carpeta exacta que aparece arriba.
                |3. Expulsa el USB del PC y vuelve a conectarlo al móvil.
                |4. En la app, pulsa «Revisar cliente pegado».
                |
                |En Android 11 o posterior, esta ruta puede no aparecer en el explorador del PC conectado por MTP.
                |Si el PC no permite abrirla, puedes copiar los archivos con adb push a la ruta en el móvil indicada arriba.
                |Este fichero de instrucciones está fuera de la raíz del cliente, en: ${pcParent.ifEmpty { "el directorio del volumen" }}
                |""".trimMargin()
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PASTED_CLIENT_README_BYTES) {
                throw StorageException("pasted_client_readme_too_large")
            }

            for (suffix in 1..MAX_PASTED_CLIENT_README_ATTEMPTS) {
                val name = if (suffix == 1) "LEEME-INSTALACION.txt" else "LEEME-INSTALACION-$suffix.txt"
                val destination = File(parent, name)
                try {
                    Files.newOutputStream(
                        destination.toPath(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    return destination
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Never replace user or app data; choose a fresh sibling name.
                } catch (failure: Exception) {
                    throw StorageException("pasted_client_readme_create_failed")
                }
            }
            throw StorageException("pasted_client_readme_create_failed")
        }

        /** Target line from the APK-packaged build admission; generic when unreadable. */
        private fun pastedClientTargetLine(): String {
            val target = runCatching { ForeverClientTargetBuild.load(context) }.getOrNull()
            return if (target != null) {
                "Versión objetivo de esta app: ${target.version} (${target.platform} ${target.architecture}, ${target.locale}). " +
                    "La revisión dirá si lo pegado coincide; cuánto se reutiliza y qué falta por descargar se calcula después, al preparar la actualización."
            } else {
                "Versión objetivo: la que esta app tiene configurada (la revisión dirá si lo pegado coincide). " +
                    "Cuánto se reutiliza y qué falta por descargar se calcula después, al preparar la actualización."
            }
        }

        private fun verifyMarker(root: File, identity: StorageIdentity) {
            val marker = File(root, MARKER)
            if (!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            require(isRegular(marker)) { throw StorageException("storage_marker_invalid") }
            require(readBounded(marker)?.contentEquals(markerBytes(identity)) == true) { throw StorageException("storage_marker_invalid") }
        }

        private fun probeWritable(root: File): Boolean {
            val location = if (isDirectory(root)) root else root.parentFile?.takeIf { isDirectory(it) } ?: return false
            val probe = File(location, ".forever-probe-${UUID.randomUUID()}")
            val renamed = File(location, ".forever-probe-${UUID.randomUUID()}")
            return try {
                Files.write(probe.toPath(), ByteArray(16) { it.toByte() }, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Files.move(probe.toPath(), renamed.toPath())
                Files.readAllBytes(renamed.toPath()).contentEquals(ByteArray(16) { it.toByte() })
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { Files.deleteIfExists(probe.toPath()) }
                runCatching { Files.deleteIfExists(renamed.toPath()) }
            }
        }

        internal fun commitSelection(option: StorageOption) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val targetRoot = option.root ?: throw StorageException("storage_unavailable")
            require(option.legacy || (fileIdentity(targetRoot, false) != null &&
                fileIdentity(requireNotNull(option.trustedBase), false) != null && option.mountIdentity != null)) {
                throw StorageException("storage_identity_unproven")
            }
            val entry = RegistryEntry(
                option.identity.id,
                option.identity.kind,
                pathFingerprint(targetRoot),
                pathFingerprint(requireNotNull(option.trustedBase)),
                fileIdentity(targetRoot, option.legacy)?.key,
            )
            val entries = registryWithEntry(entry)
            check(prefs.edit().putString(SELECTED_ID, option.identity.id).putString(SELECTED_LABEL, option.label)
                .putString(SELECTED_DESCRIPTOR, option.descriptor).putStringSet(REGISTRY, entries.map { it.encode() }.toSet()).commit()) {
                throw StorageException("storage_selection_not_saved")
            }
            processRevision.incrementAndGet()
        }

        private fun rememberValidated(target: ResolvedTarget, option: StorageOption) {
            require(target.identity == option.identity && target.root == option.root?.canonicalFile)
            val entry = RegistryEntry(target.identity.id, target.identity.kind,
                pathFingerprint(target.root), pathFingerprint(requireNotNull(option.trustedBase)),
                target.rootIdentity?.key)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().putStringSet(REGISTRY, registryWithEntry(entry).map { it.encode() }.toSet()).commit()) {
                throw StorageException("storage_registry_not_saved")
            }
        }

        private fun registryWithEntry(entry: RegistryEntry): Set<RegistryEntry> {
            val previous = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all[SELECTED_ID] as? String
            return (registryEntries().filterNot { it.id == entry.id }
                .sortedBy { if (it.id == previous) 1 else 0 }.takeLast(15) + entry).toSet()
        }

        private data class RegistryEntry(val id: String, val kind: Kind, val rootFingerprint: String, val baseFingerprint: String, val rootKey: String?) {
            fun encode(): String = listOf(id, kind.name, rootFingerprint, baseFingerprint, rootKey ?: "").joinToString("|")
            companion object {
                fun decode(value: String): RegistryEntry {
                    return runCatching {
                        val p = value.split('|')
                        require(
                            p.size == 5 && p[0].length <= 160 &&
                                (p[0].matches(Regex("primary:[A-Za-z0-9._-]+")) ||
                                    p[0].matches(Regex("volume:[A-Za-z0-9._-]+")) ||
                                    p[0].matches(Regex("volume-path:[0-9a-f]{64}"))) &&
                                p[2].matches(Regex("[0-9a-f]{64}")) && p[3].matches(Regex("[0-9a-f]{64}")))
                        require(p[4].length <= 512)
                        RegistryEntry(p[0], Kind.valueOf(p[1]), p[2], p[3], p[4].ifEmpty { null })
                    }.getOrElse { throw SelectionCorruptException() }
                }
            }
        }

        private fun registryEntries(): Set<RegistryEntry> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all[REGISTRY] ?: return emptySet()
            val values = raw as? Set<*> ?: throw SelectionCorruptException()
            if (values.size > 16 || values.any { it !is String || it.length > 1024 }) throw SelectionCorruptException()
            return values.map { it as? String ?: throw SelectionCorruptException() }.map { RegistryEntry.decode(it) }.toSet()
        }

        private fun persistedSelection(): Triple<String, String?, String?>? {
            val all = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            if (!all.containsKey(SELECTED_ID)) return null
            val id = all[SELECTED_ID] as? String ?: throw SelectionCorruptException()
            val validShape = id.matches(Regex("primary:[A-Za-z0-9._-]+")) ||
                id.matches(Regex("volume:[A-Za-z0-9._-]+")) ||
                id.matches(Regex("volume-path:[0-9a-f]{64}"))
            if (!validShape || id.length > 160) throw SelectionCorruptException()
            val label = all[SELECTED_LABEL]?.let { it as? String ?: throw SelectionCorruptException() }
            val descriptor = all[SELECTED_DESCRIPTOR]?.let { it as? String ?: throw SelectionCorruptException() }
            registryEntries()
            return Triple(id, label?.take(160), descriptor?.take(160))
        }

        private fun stableId(candidate: VolumeCandidate, kind: Kind): String {
            if (kind == Kind.PRIMARY) return "primary:${candidate.uuid ?: "emulated"}"
            candidate.uuid?.takeIf { it.isNotBlank() }?.let { return "volume:$it" }
            return "volume-path:${sha256Bytes(candidate.externalFilesDir.absolutePath.toByteArray())}"
        }

        private fun mountIdentity(option: StorageOption): String? = option.mountIdentity
        private fun availableBytes(root: File): Long = runCatching { StatFs((generateSequence(root) { it.parentFile }.firstOrNull { isDirectory(it) } ?: root).path).availableBytes }.getOrDefault(0L)
    }

    /**
     * Minimal launch-recognition readers. The managed-install admission
     * format, its target/build-info/loose-file validation and the record
     * path are owned by the updater gate (ForeverClientUpdateService); this
     * file only re-proves the two launched anchors for this root: the
     * .build.info bytes bound to the admission, and the executable content
     * (size plus MD5 content key plus ARM64 PE, never size-only trust).
     */



    /** The admitted executable path is pinned to WowB-ARM64.exe by the strict target parse. */
    private fun verifyManagedExecutableBytes(root: File, expectedBytes: Long, expectedContentKey: String): Boolean {
        return try {
            val base = root.toPath().toAbsolutePath().normalize()
            val path = base.resolve(ForeverFunctionalPoc.EXECUTABLE_NAME).normalize()
            if (!path.startsWith(base)) return false
            if (hasManagedSymlink(path)) return false
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.size(path) != expectedBytes) return false
            val md5 = md5ManagedBytes(path, expectedBytes) ?: return false
            if (!md5.equals(expectedContentKey, ignoreCase = true)) return false
            if (Files.size(path) != expectedBytes) return false
            isPeArm64(path.toFile())
        } catch (_: Exception) {
            false
        }
    }

    private fun md5ManagedBytes(path: Path, expectedSize: Long): String? {
        return try {
            if (expectedSize < 1) return null
            val digest = MessageDigest.getInstance("MD5")
            var consumed = 0L
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    consumed = Math.addExact(consumed, count.toLong())
                    if (consumed > expectedSize) return null
                    buffer.flip()
                    val block = ByteArray(count)
                    buffer.get(block)
                    digest.update(block)
                }
            }
            if (consumed != expectedSize) return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasManagedSymlink(path: Path): Boolean {
        return try {
            val absolute = path.toAbsolutePath().normalize()
            var current = absolute.root ?: return true
            if (Files.isSymbolicLink(current)) return true
            for (part in absolute) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) return true
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    /** Bounded NOFOLLOW read of an anchor file whose exact size the admission binds. */
    private fun readBoundedManagedFile(file: File, exactSize: Long): ByteArray? {
        return try {
            if (exactSize < 1) return null
            val path = file.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(path) != exactSize) return null
            val output = ByteArrayOutputStream(exactSize.toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(8 * 1024)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > exactSize) return null
                    buffer.flip()
                    val block = ByteArray(count)
                    buffer.get(block)
                    output.write(block)
                }
            }
            val bytes = output.toByteArray()
            if (bytes.size.toLong() != exactSize || Files.size(path) != exactSize) return null
            bytes
        } catch (_: Exception) {
            null
        }
    }

    internal fun replaceDriveA(drives: String, target: String): String {
        val entries = driveEntries(drives)
        require(entries.count { it.first.equals("A", ignoreCase = true) } <= 1) { "duplicate A: drive" }
        val result = StringBuilder("A:").append(target)
        entries.filterNot { it.first.equals("A", ignoreCase = true) }.forEach { result.append(it.first).append(':').append(it.second) }
        return result.toString()
    }

    private fun driveEntries(drives: String): List<Pair<String, String>> = Container.drivesIterator(drives).asSequence().map { it[0] to it[1] }.toList()

    private fun markerBytes(identity: StorageIdentity): ByteArray = "$MARKER_VERSION\n${identity.id}\n".toByteArray(Charsets.UTF_8)
    private fun isRegular(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun isDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Checks only path segments below the trusted Android API base; never walks client data. */
    private fun safeSegments(base: File, child: File): Boolean {
        val bp = base.absoluteFile.toPath().normalize()
        val cp = child.absoluteFile.toPath().normalize()
        if (!cp.startsWith(bp) || !isDirectory(base)) return false
        val relative = bp.relativize(cp)
        var current = bp
        for (part in relative) {
            current = current.resolve(part)
            // One NOFOLLOW lookup proves existence and type. Only an absent
            // component permits a not-yet-created sandbox; other errors fail closed.
            val attrs = try {
                Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) {
                break
            } catch (_: Exception) {
                return false
            }
            if (!attrs.isDirectory) return false
        }
        return true
    }

    private fun lexicalWithin(child: File, parent: File): Boolean = child.absoluteFile.toPath().normalize().startsWith(parent.absoluteFile.toPath().normalize())
    private fun readBounded(file: File): ByteArray? {
        return runCatching {
            Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(MAX_MARKER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_MARKER_BYTES) return null
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    private fun fileIdentity(file: File, primary: Boolean): FileIdentity? = runCatching {
        val attrs = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) return null
        val key = attrs.fileKey()?.toString()
        val stable = key ?: if (primary) "primary:${pathFingerprint(file)}" else return null
        if (attrs.isRegularFile) {
            FileIdentity("$stable:size=${attrs.size()}:modified=${attrs.lastModifiedTime().toMillis()}")
        } else FileIdentity(stable)
    }.getOrNull()

    private fun pathFingerprint(file: File): String = sha256Bytes(file.canonicalFile.path.toByteArray())
    private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isPeArm64(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.readUnsignedByte() != 'M'.code || raf.readUnsignedByte() != 'Z'.code) return false
            raf.seek(0x3c)
            val offset = Integer.reverseBytes(raf.readInt()).toLong()
            if (offset < 0 || offset > file.length() - 24) return false
            raf.seek(offset)
            if (raf.readInt() != 0x50450000) return false
            (raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)) == 0xAA64
        }
    }.getOrDefault(false)

    private fun mountIdentityFor(path: File): String? = runCatching {
        val bytes = File("/proc/self/mountinfo").inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_MOUNTINFO_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_MOUNTINFO_BYTES - total))
                if (n < 0) break
                output.write(buffer, 0, n)
                total += n
            }
            if (total == MAX_MOUNTINFO_BYTES && input.read() != -1) return@runCatching null
            output.toByteArray()
        }.toString(Charsets.UTF_8)
        val target = path.absoluteFile.toPath().normalize().toString()
        bytes.lineSequence().mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size < 6) return@mapNotNull null
            val mountPoint = fields[4].replace("\\040", " ").replace("\\011", "\t")
            val prefix = mountPoint.trimEnd('/')
            if (target == prefix || target.startsWith("$prefix/")) fields[0].toIntOrNull()?.let { it to prefix } else null
        }.maxByOrNull { it.second.length }?.let { "${it.first}:${it.second}" }
    }.getOrNull()

    private fun defaultLegacyRootReadOnly(context: Context): File {
        val external = DownloadService.baseExternalAppDirPath
        val parent = if (external.isNotEmpty()) File(external, "CustomGames") else
            File(DownloadService.baseDataDirPath, "CustomGames")
        return File(parent, ForeverFunctionalPoc.GAME_FOLDER_NAME)
    }
}
