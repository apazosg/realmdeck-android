package app.gamenative

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Veredicto de la revisión de un cliente pegado por el usuario en la raíz del cliente. */
enum class PastedClientVerdict {
    NONE,
    NOT_CASC,
    WRONG_PRODUCT,
    NOT_ARM64,
    TARGET_BUILD,
    PRIOR_BUILD,
    UNKNOWN_BUILD_AS_SOURCE,
}

data class PastedClientReport(
    val verdict: PastedClientVerdict,
    val present: Boolean,
    val hasCascData: Boolean,
    val version: String?,
    val buildId: Long,
    val product: String?,
    val platformTag: String?,
    val isArm64: Boolean,
    val matchesTargetBuild: Boolean,
    val matchesPriorBuild: Boolean,
    val unexpectedEntries: List<String>,
)

/** Operation-scoped, blocking service used by the client-management UI. Call every method on a worker thread. */
class ForeverClientUpdateService private constructor(
    context: Context,
    packagedTargetOverride: ForeverClientTargetBuild?,
    publishedBuildFetcher: (() -> Boolean) -> ForeverClientUpdateCheck.PublishedBuild,
    resolverFactory: (File) -> ForeverClientCatalogResolver,
) : AutoCloseable {
    data class Inspection(
        val installedVersion: String?,
        val targetVersion: String,
        val storageLabel: String,
        val availableBytes: Long,
        val detail: String,
        val mutationReady: Boolean,
        val blockingReason: String?,
        val targetOrigin: String = "APK",
        val serverVersionConfirmed: Boolean = false,
        val clientPresent: Boolean = false,
        val pastedReport: PastedClientReport? = null,
    )

    /** Explicit, per-action consent to resolve official metadata for one published identity. */
    data class MetadataConsent(
        val fingerprint: ForeverClientUpdateCheck.Fingerprint,
        val maximumNetworkBytes: Long = MAX_METADATA_NETWORK_BYTES,
        val serverVersionConfirmed: Boolean,
    )

    internal data class ActiveCatalogAdmission(
        val target: ForeverClientTargetBuild,
        val sourceFingerprint: ForeverClientUpdateCheck.Fingerprint,
        val officialVersionsSha256: String,
        val catalogFile: File,
        val catalogSha256: String,
        val catalogBytes: Long,
        val buildConfigFile: File,
        val cdnConfigFile: File,
    )

    constructor(context: Context) : this(
        context,
        null,
        { cancelled -> ForeverClientUpdateCheck.published(cancelled) },
        { root -> ForeverClientCatalogResolver(root) },
    )

    internal constructor(
        context: Context,
        packagedTargetBuild: ForeverClientTargetBuild,
        publishedBuildFetcher: (() -> Boolean) -> ForeverClientUpdateCheck.PublishedBuild,
        resolverFactory: (File) -> ForeverClientCatalogResolver,
        testSeam: Unit,
    ) : this(context, packagedTargetBuild, publishedBuildFetcher, resolverFactory) {
        require(testSeam == Unit)
    }

    private val appContext = context.applicationContext ?: context
    init { ForeverWifiDownloads.initialize(appContext) }
    // Android may expose an OS-owned alias above the private sandbox. Resolve
    // only this Context-provided anchor, never caller-selected child paths.
    private val privateAnchor = appContext.noBackupFilesDir.canonicalFile
    private val packagedTargetBuild = packagedTargetOverride ?: ForeverClientTargetBuild.load(appContext)
    private val storage = ForeverFunctionalClientStorage.service(appContext)
    private val runtimeGate = ForeverClientRuntimeGate.forContext(appContext)
    private var ownedCatalog: ForeverClientUpdateCatalog? = null
    private var activeAdmissionLoaded = false
    private var cachedActiveAdmission: ActiveCatalogAdmission? = null
    private var publishedBuildFetcher = publishedBuildFetcher
    private var resolverFactory = resolverFactory
    private var closed = false

    private val targetBuild: ForeverClientTargetBuild
        get() = activeAdmission()?.target ?: packagedTargetBuild

    /** Reads local catalog/installation metadata only. It never contacts the CDN or writes the client. */
    fun inspect(): Inspection {
        checkOpen()
        val catalog = catalog()
        val summary = catalog.summary()
        var selected: ForeverFunctionalClientStorage.StorageOption? = null
        var target: ForeverFunctionalClientStorage.UpdateTarget? = null
        var proof: InstalledProof? = null
        var pasted: PastedClientReport? = null
        var storageProblem: String? = null
        try {
            selected = storage.snapshot().selected
            target = storage.resolveForUpdate()
            if (selected?.identity != target.identity) throw IllegalStateException("storage_selection_changed")
            val inspection = inspectInstalled(target.root, catalog, { false }, target)
            proof = inspection?.proof
            pasted = inspection?.report
            storage.revalidateForUpdate(target)
        } catch (failure: Exception) {
            storageProblem = safeStorageProblem(failure)
            target = null
            proof = null
            pasted = null
        }

        val runtimeProblem = runCatching { runtimeGate.blockingReason() }.getOrElse { "operation_state_unreadable" }
        val qualificationReady = target?.let(::hasCurrentQualificationProof) == true
        val mutationReady = runtimeProblem == null && qualificationReady && storageProblem == null
        val present = target?.root?.let(::hasClientArtifacts) == true
        val detail = when {
            proof != null -> "Destino seleccionado ${summary.version} (${targetOrigin()}); instalación ${proof.version} y archivos sueltos verificados. La integridad completa de CASC y el inicio nativo aún no están verificados."
            present -> "Destino seleccionado ${summary.version} (${targetOrigin()}); instalación presente, versión sin verificar. No se afirma que los datos CASC sean íntegros ni que el cliente sea jugable."
            storageProblem != null -> "No se pudo inspeccionar de forma segura el destino seleccionado."
            else -> "Destino seleccionado ${summary.version} (${targetOrigin()}). No se ha detectado una instalación verificable; su catálogo completo está disponible."
        }
        val reasons = buildList {
            if (runtimeProblem != null) add(runtimeBlockingMessage(runtimeProblem))
            if (storageProblem != null) add(storageProblem)
            if (!qualificationReady) add("Falta una comprobación reciente de almacenamiento para este volumen.")
        }
        return Inspection(
            installedVersion = proof?.version,
            targetVersion = summary.version,
            storageLabel = selected?.label ?: "No disponible",
            availableBytes = selected?.availableBytes ?: 0L,
            detail = detail,
            mutationReady = mutationReady,
            blockingReason = reasons.takeIf { it.isNotEmpty() }?.joinToString(" "),
            targetOrigin = targetOrigin(),
            serverVersionConfirmed = targetBuild.serverVersionSource == "user-confirmed",
            clientPresent = present,
            pastedReport = pasted,
        )
    }

    /** Revisión de solo lectura del cliente pegado. No escribe nada en el cliente. */
    fun inspectPastedClient(): PastedClientReport {
        checkOpen()
        val snapshot = storage.snapshot()
        val selected = snapshot.selected
        val selectedRoot = selected?.root
        if (selected != null && selectedRoot != null && isSafeDirectory(selectedRoot) &&
            hasForeignStorageMarker(selectedRoot, selected.identity.id)
        ) {
            val report = inspectInstalled(
                selectedRoot,
                catalog(),
                { false },
                storageIdentity = selected.identity.id,
            )?.report ?: emptyPastedReport()
            val current = storage.snapshot()
            if (current.revision != snapshot.revision || current.selected?.identity != selected.identity ||
                current.selected?.root != selectedRoot
            ) throw IllegalStateException("storage_selection_changed")
            return report.copy(unexpectedEntries = (report.unexpectedEntries + STORAGE_MARKER_PATH).distinct().sorted())
        }

        val target = storage.resolveForUpdate()
        storage.revalidateForUpdate(target)
        val report = inspectInstalled(target.root, catalog(), { false }, target)?.report ?: emptyPastedReport()
        storage.revalidateForUpdate(target)
        return report
    }

    /** Blocking official-metadata request; no client or payload files are written. */
    fun checkVersions(): ForeverClientUpdateCheck.CheckResult {
        checkOpen()
        // This is an explicitly remote operation. Fail before an expensive local
        // catalog import/inspection when no permitted network can perform it.
        ForeverWifiDownloads.requireAvailable()
        val catalog = catalog()
        val installed = try {
            val target = storage.resolveForUpdate()
            val proof = inspectInstalled(target.root, catalog, { false }, target)?.proof
            storage.revalidateForUpdate(target)
            proof?.let(::versionFingerprint)
        } catch (_: Exception) {
            null
        }
        val architecture = ForeverClientUpdateCheck.ArchitectureEvidence(
            product = targetBuild.product,
            version = targetBuild.version,
            buildConfigHash = targetBuild.buildConfig.hash,
            architecture = "arm64",
        )
        return ForeverClientUpdateCheck.check(
            installed,
            architectureEvidence = architecture,
            admittedFingerprint = targetBuild.fingerprint,
        )
    }

    /**
     * Explicitly resolves the currently published build's official metadata only. This never
     * downloads game payloads or mutates the selected client. The storage IO lease is held
     * across the pending-resume guard and any resolve/validation/publication (same lease
     * model as [withStorageIo]), so no new execute can start between a negative guard and
     * publication and orphan staged work; the lease is released before the final [inspect].
     * Call from a worker thread.
     */
    fun preparePublishedVersion(
        consent: MetadataConsent,
        cancelled: () -> Boolean,
        progress: (String, Long, Long) -> Unit,
    ): Inspection {
        checkOpen()
        require(consent.maximumNetworkBytes == MAX_METADATA_NETWORK_BYTES) {
            "the metadata transport enforces a fixed 1 GiB cumulative network budget; other consent limits cannot be honored"
        }
        checkCancelled(cancelled)

        val published = publishedBuildFetcher(cancelled)
        checkCancelled(cancelled)
        require(sameFingerprint(consent.fingerprint, published.fingerprint)) {
            "the published build changed since the displayed version check; check versions again before preparing metadata"
        }
        require(published.metadataSha256.matches(HEX64)) { "official versions metadata SHA-256 is malformed" }

        // An existing receipt must be valid before any newer result can replace it.
        val frozen = activeAdmission()
        val pinToFrozen: Boolean
        val lease = storage.acquireIoLease()
            ?: throw ForeverFunctionalClientStorage.StorageException("storage_busy")
        try {
            val target = storage.resolveForUpdate()
            storage.revalidateForUpdate(target)
            val destination = recoveryDestination(target)
            val catalog = catalog()
            if (frozen != null) {
                require(catalog.verifiedSha256 == frozen.catalogSha256 &&
                    catalog.summary().version == frozen.target.version
                ) { "frozen catalog identity changed before the pending-resume guard" }
            }
            // The guard runs even with no active admission: existing or unknown
            // pending work anywhere must block publication of a new pointer.
            // No-admission plus fully idle state keeps the fresh flow unchanged.
            when (updater(catalog, includeConfigAssets = false).resumeGuardState(destination)) {
                ForeverClientUpdater.ResumeGuardState.OWN_PENDING -> {
                    if (frozen != null && isSameFrozenBuildForResume(frozen.sourceFingerprint, published.fingerprint)) {
                        // CDN-only rotation of the same frozen build while its
                        // download is still staged: keep the frozen admission
                        // byte-for-byte and plan against the original catalog
                        // and cost consent. The newly published metadata is not
                        // resolved or published here.
                        pinToFrozen = true
                    } else {
                        throw IllegalStateException(FROZEN_RESUME_REQUIRED_MESSAGE)
                    }
                }
                ForeverClientUpdater.ResumeGuardState.BLOCKED ->
                    throw IllegalStateException(FROZEN_RESUME_REQUIRED_MESSAGE)
                ForeverClientUpdater.ResumeGuardState.IDLE ->
                    pinToFrozen = false
            }
            if (!pinToFrozen) {
                val root = dynamicCatalogRoot()
                ensurePrivateDirectory(root)
                val free = ForeverClientUpdateCatalog.availableBytes(root.toPath())
                require(free >= MIN_METADATA_WORKSPACE_BYTES) {
                    "insufficient private storage for metadata preparation: require at least 2 GiB free (1.5 GiB hard minimum)"
                }
                checkCancelled(cancelled)

                val result = resolverFactory(root).resolve(
                    published = published.fingerprint,
                    serverVersionConfirmed = consent.serverVersionConfirmed,
                    cancelled = cancelled,
                    officialVersionsSha256 = published.metadataSha256,
                    progress = progress,
                )
                checkCancelled(cancelled)
                val admission = validateResolverResult(result, root, published, cancelled)
                // Revalidate the selected mount/root immediately before
                // publishing the new pointer: a storage change between the
                // guard and this write must fail closed, never orphan work.
                storage.revalidateForUpdate(target)
                checkCancelled(cancelled)
                val receiptBytes = encodeActiveCatalogReceipt(admission)
                publishActiveCatalogReceipt(root, receiptBytes)
                // Re-read from the durable receipt, so current and next service instances use the same path.
                activeAdmissionLoaded = false
                val durableAdmission = loadActiveCatalogReceipt(root)
                    ?: throw IllegalStateException("resolved catalog receipt disappeared after publication")
                cachedActiveAdmission = durableAdmission
                activeAdmissionLoaded = true
                ownedCatalog?.close()
                ownedCatalog = null
            }
        } finally {
            lease.close()
        }
        return inspect()
    }

    /** Read-only planning; private scratch may be used by the engine, the selected client is not changed. */
    fun plan(
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ): ForeverClientUpdatePlan = withStorageIo(cancelled) { target ->
        val catalog = catalog()
        updater(catalog).plan(destination(target, catalog, cancelled), cancelled, progress)
    }

    /** Requires runtime quiescence, usable storage and explicit plan consent. Native evidence is informational. */
    fun execute(
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
        cancelled: () -> Boolean,
        progress: (ForeverClientUpdateProgress) -> Unit,
    ): ForeverClientUpdateResult = withStorageIo(cancelled) { target ->
        val runtimeProblem = runtimeGate.blockingReason()
        if (runtimeProblem != null) throw IllegalStateException(runtimeBlockingMessage(runtimeProblem))
        if (!hasCurrentQualificationProof(target)) {
            throw IllegalStateException("A recent storage qualification for the selected volume is required before mutation.")
        }
        val catalog = catalog()
        val engine = updater(catalog)
        if (target.hasDirectedProof()) {
            // Physical FUSE/USB gate: prove the cold-replay reader binds real
            // path-to-FD identity in this exact app namespace before it may be
            // used. PASS only wires the factory for this invocation; any
            // failure or cancellation propagates with no engine run, no
            // fallback to another mode, and no persisted proof.
            val admitted = requireNotNull(target.admittedAppFilesDirectory) {
                "directed USB proof is missing its app namespace"
            }
            storage.revalidateForUpdate(target)
            ForeverCascReplayPreflight.run(admitted, privateAnchor, cancelled)
            storage.revalidateForUpdate(target)
            engine.replayReaderFactory = { readerCancelled -> ForeverCascReplayReaderAndroid(readerCancelled) }
        }
        val updateDestination = destination(target, catalog, cancelled)
        val result = engine.execute(updateDestination, plan, consent, cancelled, progress)
        storage.revalidateForUpdate(target)
        val installed = inspectInstalled(target.root, catalog, cancelled, target)?.proof
            ?: throw IllegalStateException("committed client did not pass independent target-build verification")
        storage.revalidateForUpdate(target)
        saveManagedInstallAdmission(target, catalog, installed)
        result
    }

    /** Explicit journal recovery; this never clears unknown/update state or writes install data by inference. */
    fun recover(progress: (ForeverClientUpdateProgress) -> Unit): ForeverClientUpdater.RecoveryStatus {
        checkOpen()
        val initialReason = runtimeGate.blockingReason()
        if (initialReason == "guest_start_reserved" || initialReason == "guest_completion_unconfirmed") {
            // This is the existing explicit guest-recovery path. It owns its own IO lease,
            // so it must run before this service acquires the process storage lease.
            ForeverFunctionalPoc.recoverPreviousGuestOperation(appContext)
        }
        return withStorageIo({ false }) { target ->
            val catalog = catalog()
            val destination = recoveryDestination(target)
            val status = updater(catalog, includeConfigAssets = false).recover(destination, progress)
            // A transaction journal can recover cleanly while the frozen operation
            // record still says UPDATING; the gate only clears through this
            // explicit completion step, and only when the record was frozen.
            if (!status.pendingTransaction && initialReason == "update_recovery_required") {
                runtimeGate.operations.recoverUpdate(
                    storage.operationStorageId(target),
                ) { updater(catalog, includeConfigAssets = false).hasPendingTransaction(destination) }
            }
            status
        }
    }

    /** Opt-in, bounded filesystem/runtime/CDN qualification. Probe scratch is outside the client root. */
    fun qualify(cancelled: () -> Boolean): String = withStorageIo(cancelled) { target ->
        val key = qualificationStorageKey(target)
        try {
            val catalog = catalog()
            val storageParent = qualificationParent(target)
            val result = ForeverClientUpdaterQualification.run(appContext, storageParent, catalog, cancelled)
            storage.revalidateForUpdate(target)
            val saved = saveQualification(key, result)
            qualificationReport(result, saved)
        } catch (failure: Exception) {
            saveQualification(
                key,
                ForeverClientUpdaterQualification.Result(false, false, false, false, false, "qualification_failed"),
            )
            throw failure
        }
    }

    private fun qualificationReport(
        result: ForeverClientUpdaterQualification.Result,
        saved: Boolean,
    ): String = buildString {
        append("Almacenamiento privado: ").append(if (result.privateStorageOk) "correcto" else "no verificado")
        append("; volumen del cliente: ").append(if (result.clientVolumeOk) "correcto" else "no verificado")
        append("; entorno: ").append(if (result.runtimeIdle) "inactivo" else "no confirmado")
        append("; transferencia de prueba: ").append(if (result.transferOk) "correcta" else "no verificada")
        if (!saved) append("; el informe privado no se pudo guardar de forma segura")
        if (result.detail.isNotBlank()) append(". ").append(result.detail.take(MAX_QUALIFICATION_DETAIL_CHARS))
    }.take(MAX_REPORT_CHARS)

    /** Closes only this service's own catalog connection; it does not cancel other operations. */
    override fun close() {
        if (closed) return
        closed = true
        ownedCatalog?.close()
        ownedCatalog = null
    }

    private fun <T> withStorageIo(
        cancelled: () -> Boolean,
        action: (ForeverFunctionalClientStorage.UpdateTarget) -> T,
    ): T {
        checkOpen()
        checkCancelled(cancelled)
        val lease = storage.acquireIoLease() ?: throw ForeverFunctionalClientStorage.StorageException("storage_busy")
        try {
            val target = storage.resolveForUpdate()
            storage.revalidateForUpdate(target)
            checkCancelled(cancelled)
            return action(target)
        } finally {
            lease.close()
        }
    }

    private fun activeAdmission(): ActiveCatalogAdmission? {
        if (activeAdmissionLoaded) return cachedActiveAdmission
        val root = dynamicCatalogRoot()
        val receipt = File(root, ACTIVE_RECEIPT_NAME)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            activeAdmissionLoaded = true
            return null
        }
        ensurePrivateDirectory(root)
        if (!Files.exists(receipt.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            activeAdmissionLoaded = true
            return null
        }
        val bytes = readSmallRegularFile(receipt, MAX_ACTIVE_RECEIPT_BYTES)
            ?: throw IllegalStateException("active dynamic catalog receipt is unreadable or outside its bound; APK fallback is blocked")
        val admission = parseActiveCatalogReceipt(bytes, root)
        cachedActiveAdmission = admission
        activeAdmissionLoaded = true
        return admission
    }

    private fun loadActiveCatalogReceipt(root: File): ActiveCatalogAdmission? {
        val receipt = File(root, ACTIVE_RECEIPT_NAME)
        if (!Files.exists(receipt.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readSmallRegularFile(receipt, MAX_ACTIVE_RECEIPT_BYTES)
            ?: throw IllegalStateException("active dynamic catalog receipt is unreadable or outside its bound; APK fallback is blocked")
        return parseActiveCatalogReceipt(bytes, root)
    }

    private fun targetOrigin(): String = if (activeAdmission() == null) {
        "catálogo genérico incluido en el APK"
    } else {
        "catálogo dinámico derivado de metadatos oficiales (validación nativa pendiente)"
    }

    private fun dynamicCatalogRoot(): File = File(privateAnchor, DYNAMIC_CATALOG_DIRECTORY)

    private fun validateResolverResult(
        result: ForeverClientCatalogResolver.Result,
        root: File,
        published: ForeverClientUpdateCheck.PublishedBuild,
        cancelled: () -> Boolean,
    ): ActiveCatalogAdmission {
        val target = result.target
        require(sameFingerprint(target.fingerprint, published.fingerprint)) {
            "resolver target fingerprint does not match the freshly published official build"
        }
        require(target.serverVersionSource in setOf("user-confirmed", "not-confirmed") &&
            target.nativeValidation.status == ForeverClientTargetBuild.NativeValidation.Status.PENDING &&
            !target.nativeActivationValidated
        ) { "dynamic resolver result has unexpected compatibility or native evidence" }
        require(target.architecture == "arm64" && target.executable.expectedMachine == EXPECTED_ARM64_MACHINE) {
            "dynamic resolver result is not admitted for the ARM64 PE machine"
        }
        require(result.catalogBytes == target.catalog.bytes && result.catalogSha256.equals(target.catalog.sha256, true)) {
            "resolver catalog size or digest does not match its generated target snapshot"
        }

        val key = dynamicCacheKey(published.fingerprint)
        val expectedCatalog = "catalogs/$key/client-catalog.sqlite3"
        val expectedBuild = "metadata-cache/$key/config/${target.buildConfig.hash.take(2)}/${target.buildConfig.hash}.config"
        val expectedCdn = "metadata-cache/$key/config/${target.cdnConfig.hash.take(2)}/${target.cdnConfig.hash}.config"
        require(relativeResolverPath(root, result.catalogFile) == expectedCatalog &&
            relativeResolverPath(root, result.buildConfigFile) == expectedBuild &&
            relativeResolverPath(root, result.cdnConfigFile) == expectedCdn
        ) { "resolver returned an artifact outside its expected private per-build cache paths" }
        verifyReceiptFile(result.catalogFile, result.catalogBytes, result.catalogSha256, null, cancelled)
        verifyActiveConfig(result.buildConfigFile, target.buildConfig.bytes, target.buildConfig.sha256, target.buildConfig.hash, cancelled)
        verifyActiveConfig(result.cdnConfigFile, target.cdnConfig.bytes, target.cdnConfig.sha256, target.cdnConfig.hash, cancelled)

        ForeverClientUpdateCatalog(result.catalogFile, result.catalogSha256, result.catalogBytes, target).use { catalog ->
            require(catalog.verifiedSha256.equals(result.catalogSha256, true)) {
                "resolver catalog did not retain its read-only verified SHA-256"
            }
            val summary = catalog.summary()
            require(summary.version == target.version && summary.buildConfigHash.equals(target.buildConfig.hash, true) &&
                summary.cdnConfigHash.equals(target.cdnConfig.hash, true)
            ) { "read-only catalog identity does not match the resolver target" }
            require(catalog.looseFiles().any { it.path.equals(target.executable.path, true) }) {
                "read-only catalog does not contain the target ARM64 executable"
            }
        }
        return ActiveCatalogAdmission(
            target = target,
            sourceFingerprint = published.fingerprint,
            officialVersionsSha256 = published.metadataSha256.lowercase(Locale.ROOT),
            catalogFile = result.catalogFile,
            catalogSha256 = result.catalogSha256.lowercase(Locale.ROOT),
            catalogBytes = result.catalogBytes,
            buildConfigFile = result.buildConfigFile,
            cdnConfigFile = result.cdnConfigFile,
        )
    }

    private fun verifyActiveConfig(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        expectedMd5: String,
        cancelled: () -> Boolean,
    ) {
        verifyReceiptFile(file, expectedBytes, expectedSha256, expectedMd5, cancelled)
    }

    private fun relativeResolverPath(root: File, file: File): String {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val filePath = file.toPath().toAbsolutePath().normalize()
        require(filePath.startsWith(rootPath)) { "resolver artifact escaped its app-private root" }
        rejectSymlinkPath(filePath)
        return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
    }

    private fun catalog(): ForeverClientUpdateCatalog {
        checkOpen()
        ownedCatalog?.let { return it }
        activeAdmission()?.let { active ->
            return ForeverClientUpdateCatalog(
                active.catalogFile,
                active.catalogSha256,
                active.catalogBytes,
                active.target,
            ).also { catalog ->
                require(catalog.verifiedSha256 == active.catalogSha256) {
                    "active catalog read-only verification did not retain its receipt SHA-256"
                }
                ownedCatalog = catalog
            }
        }
        val noBackup = privateAnchor
        val directory = File(noBackup, CATALOG_DIRECTORY)
        ensurePrivateDirectory(directory)
        val database = File(directory, "catalog-${targetBuild.catalog.sha256}.sqlite3")
        if (!Files.exists(database.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val input = appContext.assets.open(CATALOG_ASSET)
            try {
                ForeverClientUpdateCatalog.importGzip(
                    input,
                    database,
                    targetBuild.catalog.bytes,
                    targetBuild.catalog.sha256,
                    { false },
                    expectedCompressedSize = targetBuild.catalog.gzipBytes,
                    compressedSha256 = targetBuild.catalog.gzipSha256,
                )
            } finally {
                input.close()
            }
        }
        // Existing files are validated in place. A corrupt file is deliberately preserved;
        // it is never silently deleted or replaced by a fresh asset import.
        return ForeverClientUpdateCatalog(
            database,
            targetBuild.catalog.sha256,
            targetBuild.catalog.bytes,
            targetBuild,
        ).also { ownedCatalog = it }
    }

    private fun updater(
        catalog: ForeverClientUpdateCatalog,
        includeConfigAssets: Boolean = true,
    ): ForeverClientUpdater {
        val noBackup = privateAnchor
        val configs = if (includeConfigAssets) configFiles(noBackup) else emptyMap()
        val work = File(noBackup, WORK_DIRECTORY)
        val journal = File(noBackup, JOURNAL_DIRECTORY)
        ensurePrivateDirectory(work)
        ensurePrivateDirectory(journal)
        return ForeverClientUpdater(catalog, configs, work, journal, runtimeGate.operations)
    }

    private fun configFiles(noBackup: File): Map<String, File> {
        activeAdmission()?.let { active ->
            verifyActiveConfig(active.buildConfigFile, active.target.buildConfig.bytes, active.target.buildConfig.sha256, active.target.buildConfig.hash, { false })
            verifyActiveConfig(active.cdnConfigFile, active.target.cdnConfig.bytes, active.target.cdnConfig.sha256, active.target.cdnConfig.hash, { false })
            return mapOf(
                active.target.buildConfig.hash to active.buildConfigFile,
                active.target.cdnConfig.hash to active.cdnConfigFile,
            )
        }
        val directory = File(noBackup, CONFIG_DIRECTORY)
        ensurePrivateDirectory(directory)
        val build = extractPinnedAsset(
            { appContext.assets.open(BUILD_CONFIG_ASSET) },
            File(directory, "build-config-${targetBuild.buildConfig.hash}.txt"),
            targetBuild.buildConfig.bytes,
            targetBuild.buildConfig.sha256,
            targetBuild.buildConfig.hash,
            { false },
        )
        val cdn = extractPinnedAsset(
            { appContext.assets.open(CDN_CONFIG_ASSET) },
            File(directory, "cdn-config-${targetBuild.cdnConfig.hash}.txt"),
            targetBuild.cdnConfig.bytes,
            targetBuild.cdnConfig.sha256,
            targetBuild.cdnConfig.hash,
            { false },
        )
        return mapOf(
            targetBuild.buildConfig.hash to build,
            targetBuild.cdnConfig.hash to cdn,
        )
    }

    private fun destination(
        target: ForeverFunctionalClientStorage.UpdateTarget,
        catalog: ForeverClientUpdateCatalog,
        cancelled: () -> Boolean,
    ): ForeverClientUpdateDestination {
        storage.revalidateForUpdate(target)
        ensureCatalogDoesNotTouchWtf(catalog)
        val inspection = inspectInstalled(target.root, catalog, cancelled, target)
        val report = inspection?.report
        if (report != null && report.present && report.verdict !in setOf(
                PastedClientVerdict.TARGET_BUILD,
                PastedClientVerdict.PRIOR_BUILD,
                PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE,
            )
        ) {
            throw IllegalStateException(pastedClientRejection(report))
        }
        val ownership = inspection?.proof?.verifiedOwnedFiles?.toMutableMap() ?: mutableMapOf()
        val sourceOrigin = inspection?.sourceOrigin ?: ForeverClientSourceOrigin.APPROVED_CLIENT
        val marker = readStorageMarker(target)
        if (marker != null) ownership[STORAGE_MARKER_PATH] = marker
        preflightClientTargets(
            target.root,
            catalog,
            ownership,
            allowUnownedBuildInfo = sourceOrigin == ForeverClientSourceOrigin.PASTED_SOURCE,
        )
        storage.revalidateForUpdate(target)
        return ForeverClientUpdateDestination(
            root = target.root,
            storageId = storage.operationStorageId(target),
            revalidate = { storage.revalidateForUpdate(target) },
            verifiedOwnedFiles = ownership.toMap(),
            storageMarkerIdentity = target.identity.id,
            sourceOrigin = sourceOrigin,
        )
    }

    private fun recoveryDestination(
        target: ForeverFunctionalClientStorage.UpdateTarget,
    ): ForeverClientUpdateDestination {
        storage.revalidateForUpdate(target)
        val marker = readStorageMarker(target)
        val ownership = marker?.let { mapOf(STORAGE_MARKER_PATH to it) }.orEmpty()
        return ForeverClientUpdateDestination(
            root = target.root,
            storageId = storage.operationStorageId(target),
            revalidate = { storage.revalidateForUpdate(target) },
            verifiedOwnedFiles = ownership,
            storageMarkerIdentity = target.identity.id,
        )
    }

    private data class InstalledProof(
        val version: String,
        val buildConfigHash: String,
        val cdnConfigHash: String,
        val buildId: Long,
        val verifiedOwnedFiles: Map<String, FileFingerprint>,
    )

    private data class ClientInspection(
        val report: PastedClientReport,
        val proof: InstalledProof?,
        val sourceOrigin: ForeverClientSourceOrigin,
    )

    internal data class BuildInfoFingerprint(
        val branch: String,
        val buildConfigHash: String,
        val cdnConfigHash: String,
        val version: String,
        val product: String,
        val tags: String? = null,
        val platformTag: String? = null,
        val architecture: String? = null,
        val locale: String? = null,
    ) {
        val buildId: Long get() = version.substringAfterLast('.').toLongOrNull() ?: 0L
        val isArm64: Boolean get() = architecture?.equals("arm64", ignoreCase = true) == true
    }

    private data class ManifestFile(
        val path: String,
        val md5: String,
        val size: Long,
    )

    internal data class ManagedAdmissionLooseFile(val path: String, val contentKey: String, val decodedBytes: Long)

    internal data class ManagedInstallAdmissionRecord(
        val storageId: String,
        val storageIdentity: String,
        val rootPath: String,
        val targetBuild: ForeverClientTargetBuild,
        val catalogSha256: String,
        val buildInfoBytes: Long,
        val buildInfoSha256: String,
        val looseFiles: List<ManagedAdmissionLooseFile>,
    )

    private fun inspectInstalled(
        root: File,
        catalog: ForeverClientUpdateCatalog,
        cancelled: () -> Boolean,
        storageTarget: ForeverFunctionalClientStorage.UpdateTarget? = null,
        storageIdentity: String? = storageTarget?.identity?.id,
    ): ClientInspection? {
        return try {
            checkCancelled(cancelled)
            if (!isSafeDirectory(root)) return null
            val unexpected = unexpectedClientRootEntries(root, storageIdentity)
            val present = hasPastedClientContent(root)
            if (!present) return null

            val hasCascData = hasReadableLocalCascIndex(File(root, "Data/data"))
            if (!hasCascData) {
                return ClientInspection(
                    pastedClientReport(PastedClientVerdict.NOT_CASC, true, false, null, unexpected),
                    null,
                    ForeverClientSourceOrigin.PASTED_SOURCE,
                )
            }

            val buildInfoPath = File(root, BUILD_INFO_PATH)
            val buildInfoExists = Files.exists(buildInfoPath.toPath(), LinkOption.NOFOLLOW_LINKS)
            val buildInfo = if (buildInfoExists) readSmallRegularFile(buildInfoPath, MAX_BUILD_INFO_BYTES) else null
            val parsed = buildInfo?.let(::parseBuildInfo)
            when (classifyPastedClientMetadata(true, buildInfoExists, parsed)) {
                PastedClientVerdict.NOT_CASC -> return ClientInspection(
                    pastedClientReport(PastedClientVerdict.NOT_CASC, true, true, parsed, unexpected),
                    null,
                    ForeverClientSourceOrigin.PASTED_SOURCE,
                )
                PastedClientVerdict.WRONG_PRODUCT -> return ClientInspection(
                    pastedClientReport(PastedClientVerdict.WRONG_PRODUCT, true, true, parsed, unexpected),
                    null,
                    ForeverClientSourceOrigin.PASTED_SOURCE,
                )
                PastedClientVerdict.NOT_ARM64 -> return ClientInspection(
                    pastedClientReport(PastedClientVerdict.NOT_ARM64, true, true, parsed, unexpected),
                    null,
                    ForeverClientSourceOrigin.PASTED_SOURCE,
                )
                else -> Unit
            }

            val verified = if (buildInfo != null && parsed != null) {
                inspectVerifiedBuild(root, catalog, cancelled, storageTarget)
            } else {
                null
            }
            val matchesTarget = parsed?.let(::matchesTargetBuild) == true
            val matchesPrior = parsed?.let { isPriorBuild(it) && it.isArm64 && it.locale == "enUS" } == true
            val verifiedUnexpected = unexpected.filterNot { it.equals(PRIOR_EXECUTABLE_PATH, ignoreCase = true) }
            val targetVerified = verified != null && verified.buildId == targetBuild.buildId && matchesTarget
            val priorVerified = verified != null && verified.buildId == PRIOR_BUILD_ID && matchesPrior
            val knownBuildVerdict = classifyPastedClientMetadata(
                hasCascData = true,
                buildInfoPresent = buildInfoExists,
                info = parsed,
                targetBuildVerified = targetVerified,
                priorBuildVerified = priorVerified,
            )
            if (targetVerified && knownBuildVerdict == PastedClientVerdict.TARGET_BUILD) {
                return ClientInspection(
                    pastedClientReport(PastedClientVerdict.TARGET_BUILD, true, true, parsed, verifiedUnexpected),
                    requireNotNull(verified),
                    ForeverClientSourceOrigin.APPROVED_CLIENT,
                )
            }
            if (priorVerified && knownBuildVerdict == PastedClientVerdict.PRIOR_BUILD) {
                return ClientInspection(
                    pastedClientReport(PastedClientVerdict.PRIOR_BUILD, true, true, parsed, verifiedUnexpected),
                    requireNotNull(verified),
                    ForeverClientSourceOrigin.APPROVED_CLIENT,
                )
            }
            if (verified != null) {
                return ClientInspection(
                    pastedClientReport(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE, true, true, parsed, verifiedUnexpected),
                    verified,
                    ForeverClientSourceOrigin.APPROVED_CLIENT,
                )
            }

            if (unexpected.isNotEmpty()) {
                return ClientInspection(
                    pastedClientReport(PastedClientVerdict.NOT_CASC, true, true, parsed, unexpected),
                    null,
                    ForeverClientSourceOrigin.PASTED_SOURCE,
                )
            }
            ClientInspection(
                pastedClientReport(PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE, true, true, parsed, unexpected),
                null,
                ForeverClientSourceOrigin.PASTED_SOURCE,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            val present = runCatching { isSafeDirectory(root) && hasPastedClientContent(root) }.getOrDefault(false)
            if (!present) return null
            ClientInspection(
                pastedClientReport(PastedClientVerdict.NOT_CASC, true, false, null, emptyList()),
                null,
                ForeverClientSourceOrigin.PASTED_SOURCE,
            )
        }
    }

    private fun inspectVerifiedBuild(
        root: File,
        catalog: ForeverClientUpdateCatalog,
        cancelled: () -> Boolean,
        storageTarget: ForeverFunctionalClientStorage.UpdateTarget? = null,
    ): InstalledProof? {
        return try {
            checkCancelled(cancelled)
            if (!isSafeDirectory(root)) return null
            val buildInfoFile = File(root, BUILD_INFO_PATH)
            val buildInfo = readSmallRegularFile(buildInfoFile, MAX_BUILD_INFO_BYTES) ?: return null
            val parsed = parseBuildInfo(buildInfo) ?: return null
            val targetCatalog = catalog.summary()
            val targetBuildInfo = buildInfoBytes(catalog)
            if (buildInfo.contentEquals(targetBuildInfo) && isCascDataDirectory(root)) {
                val records = catalog.looseFiles()
                val executable = records.singleOrNull { it.path.equals(targetBuild.executable.path, ignoreCase = true) }
                    ?: return null
                if (executable.objectRecord.decodedSize != targetBuild.executable.decodedBytes ||
                    !executable.objectRecord.contentKey.equals(targetBuild.executable.contentKey, true)
                ) return null
                val verified = verifyCatalogLooseFiles(root, records.map {
                    ManifestFile(it.path, it.objectRecord.contentKey, it.objectRecord.decodedSize)
                }, cancelled) ?: return null
                val exe = safeClientFile(root, targetBuild.executable.path) ?: return null
                if (!isArm64Pe(exe, targetBuild.executable.expectedMachine)) return null
                val targetProof = verifiedBuildInfoFingerprint(
                    buildInfo,
                    targetCatalog.version,
                    targetCatalog.buildConfigHash,
                    targetCatalog.cdnConfigHash,
                    manifestVerified = true,
                    exactBytes = targetBuildInfo,
                ) ?: return null
                val owned = verified.toMutableMap()
                owned[BUILD_INFO_PATH] = targetProof
                return InstalledProof(
                    targetCatalog.version,
                    targetCatalog.buildConfigHash,
                    targetCatalog.cdnConfigHash,
                    targetBuild.buildId,
                    owned,
                )
            }

            if (isPriorBuild(parsed) && isCascDataDirectory(root)) {
                val executable = safeClientFile(root, PRIOR_EXECUTABLE_PATH) ?: return null
                checkCancelled(cancelled)
                if (!ForeverFunctionalClientStorage.PinnedExecutableVerifier.verify(executable, true)) return null
                checkCancelled(cancelled)
                val priorCatalog = ForeverClientCatalog.load(appContext)
                val priorFiles = priorManifest(priorCatalog) ?: return null
                val verifiedOld = verifyCatalogLooseFiles(root, priorFiles, cancelled) ?: return null
                val targetPaths = targetCatalogLoosePaths(catalog)
                val overlap = verifiedOld.filterKeys { it in targetPaths }.toMutableMap()
                val priorBuild = verifiedBuildInfoFingerprint(
                    buildInfo,
                    PRIOR_VERSION,
                    PRIOR_BUILD_CONFIG_HASH,
                    PRIOR_CDN_CONFIG_HASH,
                    manifestVerified = true,
                ) ?: return null
                overlap[BUILD_INFO_PATH] = priorBuild
                return InstalledProof(
                    PRIOR_VERSION,
                    PRIOR_BUILD_CONFIG_HASH,
                    PRIOR_CDN_CONFIG_HASH,
                    PRIOR_BUILD_ID,
                    overlap,
                )
            }

            if (storageTarget != null) {
                inspectManagedPrior(root, buildInfo, parsed, catalog, cancelled, storageTarget)?.let { return it }
            }
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    private fun inspectManagedPrior(
        root: File,
        buildInfo: ByteArray,
        parsed: BuildInfoFingerprint,
        catalog: ForeverClientUpdateCatalog,
        cancelled: () -> Boolean,
        storageTarget: ForeverFunctionalClientStorage.UpdateTarget,
    ): InstalledProof? {
        if (!isCascDataDirectory(root)) return null
        val storageId = storage.operationStorageId(storageTarget)
        val record = readManagedAdmission(storageId, storageTarget.identity.id, stableRootPath(storageTarget.root)) ?: return null
        val prior = record.targetBuild
        if (sameTargetFingerprint(prior.fingerprint, targetBuild.fingerprint) ||
            parsed.branch != prior.region || parsed.product != prior.product || parsed.version != prior.version ||
            !parsed.buildConfigHash.equals(prior.buildConfig.hash, true) ||
            !parsed.cdnConfigHash.equals(prior.cdnConfig.hash, true) ||
            buildInfo.size.toLong() != record.buildInfoBytes || !sha256(buildInfo).equals(record.buildInfoSha256, true)
        ) return null
        val verifiedOld = verifyCatalogLooseFiles(
            root,
            record.looseFiles.map { ManifestFile(it.path, it.contentKey, it.decodedBytes) },
            cancelled,
        ) ?: return null
        val executableRecord = record.looseFiles.singleOrNull { it.path.equals(prior.executable.path, true) } ?: return null
        if (executableRecord.decodedBytes != prior.executable.decodedBytes ||
            !executableRecord.contentKey.equals(prior.executable.contentKey, true)
        ) return null
        val executable = safeClientFile(root, prior.executable.path) ?: return null
        if (!isArm64Pe(executable, prior.executable.expectedMachine)) return null
        val buildProof = verifiedBuildInfoFingerprint(
            buildInfo,
            prior.version,
            prior.buildConfig.hash,
            prior.cdnConfig.hash,
            manifestVerified = true,
        ) ?: return null
        val overlap = verifiedOld.filterKeys { path -> catalog.looseFiles().any { it.path == path } }.toMutableMap()
        overlap[BUILD_INFO_PATH] = buildProof
        return InstalledProof(
            prior.version,
            prior.buildConfig.hash,
            prior.cdnConfig.hash,
            prior.buildId,
            overlap,
        )
    }

    private fun sameTargetFingerprint(
        left: ForeverClientUpdateCheck.Fingerprint,
        right: ForeverClientUpdateCheck.Fingerprint,
    ): Boolean = left.product == right.product && left.version == right.version && left.buildId == right.buildId &&
        left.buildConfigHash.equals(right.buildConfigHash, true) && left.cdnConfigHash.equals(right.cdnConfigHash, true)

    private fun readManagedAdmission(
        storageId: String,
        storageIdentity: String,
        rootPath: String,
    ): ManagedInstallAdmissionRecord? =
        readManagedInstallAdmission(privateAnchor, storageId, storageIdentity, rootPath)

    private fun saveManagedInstallAdmission(
        storageTarget: ForeverFunctionalClientStorage.UpdateTarget,
        catalog: ForeverClientUpdateCatalog,
        proof: InstalledProof,
    ) {
        val storageId = storage.operationStorageId(storageTarget)
        val storageIdentity = storageTarget.identity.id
        val rootPath = stableRootPath(storageTarget.root)
        val buildInfo = readSmallRegularFile(File(storageTarget.root, BUILD_INFO_PATH), MAX_BUILD_INFO_BYTES)
            ?: throw IllegalStateException("committed client build info is not a bounded regular file")
        val buildInfoProof = proof.verifiedOwnedFiles[BUILD_INFO_PATH]
            ?: throw IllegalStateException("committed client build info has no independent verification fingerprint")
        require(buildInfoProof == FileFingerprint(buildInfo.size.toLong(), sha256(buildInfo)) &&
            buildInfo.contentEquals(buildInfoBytes(catalog))
        ) { "committed client build info changed after target verification" }
        val loose = catalog.looseFiles().map {
            ManagedAdmissionLooseFile(it.path, it.objectRecord.contentKey, it.objectRecord.decodedSize)
        }
        val recordBytes = encodeManagedInstallAdmissionRecord(
            targetBuild.originalJsonCopy(),
            targetBuild.catalog.sha256,
            storageId,
            storageIdentity,
            rootPath,
            buildInfo,
            loose,
        )
        require(proof.version == targetBuild.version && proof.buildId == targetBuild.buildId) {
            "verified installation proof does not match the admitted target"
        }
        val directory = File(privateAnchor, ADMISSION_DIRECTORY)
        ensurePrivateDirectory(directory)
        val file = File(directory, "$ADMISSION_FILE_PREFIX${sha256(storageId.toByteArray(Charsets.UTF_8))}.json")
        publishManagedAdmission(file, recordBytes, storageId, storageIdentity, rootPath)
    }

    private fun publishManagedAdmission(
        file: File,
        bytes: ByteArray,
        storageId: String,
        storageIdentity: String,
        rootPath: String,
    ) {
        require(bytes.size in 1..MAX_ADMISSION_RECORD_BYTES) { "managed install admission record is outside its bound" }
        val path = file.toPath().toAbsolutePath().normalize()
        val parent = path.parent ?: throw IllegalArgumentException("managed install admission has no private parent")
        ensurePrivateDirectory(parent.toFile())
        rejectSymlinkPath(path)
        val existing = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val oldBytes = readSmallRegularFile(path.toFile(), MAX_ADMISSION_RECORD_BYTES)
                ?: throw IllegalStateException("existing managed install admission is not a bounded regular file")
            parseManagedInstallAdmissionRecord(oldBytes, storageId, storageIdentity, rootPath)
                ?: throw IllegalStateException("existing managed install admission is invalid; preserving it")
            true
        } else {
            false
        }
        val temporary = Files.createTempFile(parent, ".managed-admission-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { channel ->
                writeFully(channel, ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            rejectSymlinkPath(path)
            if (existing) {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "existing managed install admission changed before replacement"
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                try {
                    ForeverClientUpdateCatalog.publishVerifiedPrivateFile(path, temporary)
                } catch (failure: FileAlreadyExistsException) {
                    throw IllegalStateException("managed install admission appeared during publication", failure)
                }
                Files.deleteIfExists(temporary)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun priorManifest(catalog: JSONObject): List<ManifestFile>? {
        val loose = catalog.optJSONArray("loose_files") ?: return null
        if (loose.length() != PRIOR_LOOSE_COUNT) return null
        val result = ArrayList<ManifestFile>(loose.length())
        val seen = HashSet<String>()
        repeat(loose.length()) { index ->
            val item = loose.optJSONObject(index) ?: return null
            val path = item.optString("relative_path", "")
            val contentKey = item.optString("content_key", "").lowercase(Locale.ROOT)
            val md5 = item.optString("decoded_md5", contentKey).lowercase(Locale.ROOT)
            val size = item.optLong("decoded_size", -1L)
            if (!isSafeManifestPath(path) || isWtfPath(path) ||
                !contentKey.matches(HEX32) || md5 != contentKey || size !in 0..MAX_DECODED_FILE_BYTES ||
                !seen.add(path.lowercase(Locale.ROOT))
            ) return null
            result += ManifestFile(path, md5, size)
        }
        return result
    }

    private fun targetCatalogLoosePaths(catalog: ForeverClientUpdateCatalog): Set<String> =
        catalog.looseFiles().map { it.path }.toSet()

    private fun verifyCatalogLooseFiles(
        root: File,
        records: List<ManifestFile>,
        cancelled: () -> Boolean,
    ): Map<String, FileFingerprint>? {
        if (records.isEmpty() || records.size > MAX_LOOSE_FILES) return null
        val result = LinkedHashMap<String, FileFingerprint>(records.size)
        val seen = HashSet<String>()
        for (record in records) {
            checkCancelled(cancelled)
            if (!isSafeManifestPath(record.path) || isWtfPath(record.path) ||
                !record.md5.matches(HEX32) || record.size !in 0..MAX_DECODED_FILE_BYTES ||
                !seen.add(record.path.lowercase(Locale.ROOT))
            ) return null
            val file = safeClientFile(root, record.path) ?: return null
            val fingerprint = fingerprintManifestFile(file, record.size, record.md5, cancelled) ?: return null
            result[record.path] = fingerprint
        }
        return result
    }

    private fun preflightClientTargets(
        root: File,
        catalog: ForeverClientUpdateCatalog,
        ownership: Map<String, FileFingerprint>,
        allowUnownedBuildInfo: Boolean = false,
    ) {
        ensureCatalogDoesNotTouchWtf(catalog)
        val rootPath = root.toPath().toAbsolutePath().normalize()
        for (relative in listOf(BUILD_INFO_PATH) + catalog.looseFiles().map { it.path }) {
            require(isSafeManifestPath(relative) && !isWtfPath(relative)) {
                "updater target path is unsafe"
            }
            val file = File(root, relative)
            val path = file.toPath().toAbsolutePath().normalize()
            require(path.startsWith(rootPath)) { "updater target escaped the selected client root" }
            rejectSymlinkPath(path)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "existing client target is not a regular file" }
            val max = if (relative == BUILD_INFO_PATH) MAX_BUILD_INFO_BYTES.toLong() else MAX_DECODED_FILE_BYTES
            require(Files.size(path) <= max) { "existing client target exceeds its bounded verification size" }
            // A file is replaceable only after a complete prior-build proof. Exact target
            // matches are independently checked by the engine's admitted content key.
            if (relative == BUILD_INFO_PATH && relative !in ownership && !allowUnownedBuildInfo) {
                throw IllegalStateException("existing .build.info has no independently verified build ownership")
            }
        }
        for (hash in listOf(targetBuild.buildConfig.hash, targetBuild.cdnConfig.hash)) {
            val relative = "Data/config/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
            val path = File(root, relative).toPath().toAbsolutePath().normalize()
            require(path.startsWith(rootPath)) { "TACT config path escaped the selected client root" }
            rejectSymlinkPath(path)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_CONFIG_BYTES) {
                "existing hash-addressed config exceeds its bounded verification size"
            }
        }
    }

    private fun readStorageMarker(target: ForeverFunctionalClientStorage.UpdateTarget): FileFingerprint? {
        val path = File(target.root, STORAGE_MARKER_PATH).toPath()
        rejectSymlinkPath(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = readSmallRegularFile(path.toFile(), MAX_STORAGE_MARKER_BYTES) ?:
            throw IllegalStateException("selected storage marker is not a bounded regular file")
        val expected = "$STORAGE_MARKER_VERSION\n${target.identity.id}\n".toByteArray(Charsets.UTF_8)
        if (!bytes.contentEquals(expected)) throw IllegalStateException("selected storage marker identity changed")
        return FileFingerprint(bytes.size.toLong(), sha256(bytes))
    }

    private fun versionFingerprint(proof: InstalledProof): ForeverClientUpdateCheck.Fingerprint =
        ForeverClientUpdateCheck.Fingerprint(
            targetBuild.product,
            proof.version,
            proof.buildConfigHash,
            proof.cdnConfigHash,
            proof.buildId,
        )

    private fun buildInfoBytes(catalog: ForeverClientUpdateCatalog): ByteArray {
        val summary = catalog.summary()
        val metadata = JSONObject(catalog.metadata("metadata_objects"))
        val installKey = metadata.getJSONObject("install").getString("encoding_key").lowercase(Locale.ROOT)
        require(installKey.matches(HEX32) && catalog.objectByKey(installKey).ekey == installKey) {
            "target catalog install metadata is not bound to its object manifest"
        }
        return ForeverCasc.buildInfo(
            JSONObject()
                .put("branch", targetBuild.region)
                .put("build_key", summary.buildConfigHash)
                .put("cdn_key", summary.cdnConfigHash)
                .put("install_key", installKey)
                .put("cdn_path", "tpr/wow")
                .put("cdn_hosts", JSONArray().put("level3.blizzard.com").put("us.cdn.blizzard.com"))
                .put("version", summary.version)
                .put("product", targetBuild.product)
                .put("platform", targetBuild.platform)
                .put("architecture", targetBuild.architecture)
                .put("locale", targetBuild.locale)
                .put("region", targetBuild.region),
        )
    }

    private fun isPriorBuild(info: BuildInfoFingerprint): Boolean =
        info.branch == "us" && info.product == PRODUCT && info.version == PRIOR_VERSION &&
            info.buildConfigHash.equals(PRIOR_BUILD_CONFIG_HASH, true) &&
            info.cdnConfigHash.equals(PRIOR_CDN_CONFIG_HASH, true)

    private fun matchesTargetBuild(info: BuildInfoFingerprint): Boolean =
        info.branch == targetBuild.region && info.product == targetBuild.product &&
            info.version == targetBuild.version && info.buildId == targetBuild.buildId &&
            info.isArm64 && info.locale == targetBuild.locale &&
            info.buildConfigHash.equals(targetBuild.buildConfig.hash, true) &&
            info.cdnConfigHash.equals(targetBuild.cdnConfig.hash, true)

    private fun pastedClientReport(
        verdict: PastedClientVerdict,
        present: Boolean,
        hasCascData: Boolean,
        info: BuildInfoFingerprint?,
        unexpectedEntries: List<String>,
    ): PastedClientReport {
        val targetMatch = info?.let(::matchesTargetBuild) == true
        val priorMatch = info?.let { isPriorBuild(it) && it.isArm64 && it.locale == "enUS" } == true
        return PastedClientReport(
            verdict = verdict,
            present = present,
            hasCascData = hasCascData,
            version = info?.version,
            buildId = info?.buildId ?: 0L,
            product = info?.product,
            platformTag = info?.platformTag,
            isArm64 = info?.isArm64 == true,
            matchesTargetBuild = targetMatch,
            matchesPriorBuild = priorMatch,
            unexpectedEntries = unexpectedEntries,
        )
    }

    private fun emptyPastedReport(): PastedClientReport = pastedClientReport(
        PastedClientVerdict.NONE,
        present = false,
        hasCascData = false,
        info = null,
        unexpectedEntries = emptyList(),
    )

    private fun hasPastedClientContent(root: File): Boolean = Files.newDirectoryStream(root.toPath()).use { children ->
        children.any { path -> path.fileName.toString() !in APP_ROOT_ENTRIES }
    }

    private fun unexpectedClientRootEntries(root: File, storageIdentity: String?): List<String> {
        val expectedEntries = EXPECTED_PASTED_ROOT_ENTRIES.toMutableSet()
        if (hasForeignStorageMarker(root, storageIdentity)) expectedEntries.remove(STORAGE_MARKER_PATH)
        val unexpected = Files.newDirectoryStream(root.toPath()).use { children ->
            children.map { it.fileName.toString() }
                .filterNot { it in expectedEntries }
                .map { it.map { ch -> if (ch.code < 0x20 || ch.code == 0x7f) '?' else ch }.joinToString("").take(160) }
                .sorted()
        }
        if (unexpected.size <= MAX_REPORTED_UNEXPECTED_ENTRIES) return unexpected
        return unexpected.take(MAX_REPORTED_UNEXPECTED_ENTRIES) + "… (${unexpected.size - MAX_REPORTED_UNEXPECTED_ENTRIES} more)"
    }

    private fun hasForeignStorageMarker(root: File, storageIdentity: String?): Boolean {
        val marker = File(root, STORAGE_MARKER_PATH)
        val path = marker.toPath().toAbsolutePath().normalize()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (storageIdentity == null) return true
        val bytes = runCatching { readSmallRegularFile(marker, MAX_STORAGE_MARKER_BYTES) }.getOrNull() ?: return true
        return !storageMarkerMatches(bytes, storageIdentity)
    }

    private fun pastedClientRejection(report: PastedClientReport): String = when (report.verdict) {
        PastedClientVerdict.NOT_CASC -> when {
            report.unexpectedEntries.isNotEmpty() ->
                "pasted client contains unexpected root entries: ${report.unexpectedEntries.joinToString(", ")}; " +
                    "keep only Data/ and .build.info unless the build is fully verified"
            report.hasCascData -> "pasted client .build.info is missing a valid bounded build record"
            else -> "pasted client has no readable local CASC Data/data index; copy Data/ and its local indexes"
        }
        PastedClientVerdict.WRONG_PRODUCT -> "pasted client must be wow_classic_beta, US, enUS"
        PastedClientVerdict.NOT_ARM64 -> "pasted client is not tagged for ARM64; this app cannot use x86_64 clients"
        else -> "pasted client is not admissible as a CASC source"
    }

    private fun hasReadableLocalCascIndex(dataFiles: File): Boolean {
        return try {
        val directory = dataFiles.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false
        val indexPattern = Regex("^[0-9a-fA-F]{10}\\.idx$")
        val segmentPattern = Regex("^data\\.(\\d{3,})$")
        val latest = arrayOfNulls<Pair<Long, File>>(16)
        var indexCount = 0
        var hasSegment = false
        Files.newDirectoryStream(directory).use { children ->
            for (path in children) {
                if (Files.isSymbolicLink(path)) return false
                val name = path.fileName.toString()
                if (name.endsWith(".idx", ignoreCase = true) && !indexPattern.matches(name)) return false
                if (indexPattern.matches(name)) {
                    if (++indexCount > MAX_LOCAL_INDEX_GENERATIONS) return false
                    val bucket = name.substring(0, 2).toIntOrNull(16) ?: return false
                    if (bucket !in 0..15 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
                    val generation = name.substring(2, 10).toLongOrNull(16) ?: return false
                    val previous = latest[bucket]
                    if (previous == null || generation > previous.first) latest[bucket] = generation to path.toFile()
                }
                val segment = segmentPattern.matchEntire(name) ?: continue
                val archiveId = segment.groupValues[1].toIntOrNull() ?: return false
                if (archiveId !in 0..1023 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.size(path) !in 1..MAX_CASC_ARCHIVE_BYTES
                ) return false
                hasSegment = true
            }
        }
        if (!hasSegment || latest.any { it == null }) return false
        for (bucket in 0..15) {
            val file = latest[bucket]!!.second
            val size = Files.size(file.toPath())
            if (size !in MIN_LOCAL_INDEX_BYTES..MAX_LOCAL_INDEX_BYTES || size % LOCAL_INDEX_ALIGNMENT != 0L) return false
            val header = ByteBuffer.allocate(0x28).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                while (header.hasRemaining()) {
                    val count = channel.read(header)
                    if (count <= 0) return false
                }
                if (channel.size() != size) return false
            }
            val bytes = header.array()
            val blockSize = header.getInt(0x20).toLong() and 0xffffffffL
            val sortedEnd = 0x28L + blockSize
            val alignedEnd = (sortedEnd + LOCAL_INDEX_ALIGNMENT - 1L) and -LOCAL_INDEX_ALIGNMENT
            if (header.getInt(0) != 16 || (header.getShort(8).toInt() and 0xffff) != 7 ||
                (bytes[10].toInt() and 0xff) != bucket ||
                (bytes[12].toInt() and 0xff) != 4 || (bytes[13].toInt() and 0xff) != 5 ||
                (bytes[14].toInt() and 0xff) != 9 || (bytes[15].toInt() and 0xff) != 30 ||
                header.getInt(16) != 0 || header.getInt(20) != 64 ||
                blockSize % 18L != 0L || blockSize / 18L > MAX_LOCAL_INDEX_RECORDS || alignedEnd > size
            ) return false
        }
        true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureCatalogDoesNotTouchWtf(catalog: ForeverClientUpdateCatalog) {
        require(catalog.looseFiles().none { isWtfPath(it.path) }) {
            "target catalog contains protected WTF paths; refusing to read or copy user configuration"
        }
    }

    private fun isCascDataDirectory(root: File): Boolean {
        val data = File(root, "Data")
        val dataFiles = File(data, "data")
        rejectSymlinkPath(data.toPath())
        rejectSymlinkPath(dataFiles.toPath())
        return Files.isDirectory(data.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isDirectory(dataFiles.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun hasClientArtifacts(root: File): Boolean = try {
        listOf(File(root, targetBuild.executable.path), File(root, BUILD_INFO_PATH), File(root, "Data"))
            .any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    } catch (_: Exception) {
        false
    }

    private fun safeClientFile(root: File, relative: String): File? {
        if (!isSafeManifestPath(relative) || isWtfPath(relative)) return null
        val base = root.toPath().toAbsolutePath().normalize()
        val path = base.resolve(relative).normalize()
        if (!path.startsWith(base)) return null
        rejectSymlinkPath(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return path.toFile()
    }

    private fun isArm64Pe(file: File, expectedMachine: Int): Boolean {
        return try {
            val path = file.toPath()
            rejectSymlinkPath(path)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() < 0x40) return false
                val dos = ByteBuffer.allocate(0x40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                readFully(channel, dos, 0L)
                if (dos.get(0) != 'M'.code.toByte() || dos.get(1) != 'Z'.code.toByte()) return false
                val peOffset = dos.getInt(0x3c).toLong() and 0xffff_ffffL
                if (peOffset < 0x40L || peOffset > channel.size() - 26L) return false
                val header = ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                readFully(channel, header, peOffset)
                header.getInt(0) == PE_SIGNATURE &&
                    (header.getShort(4).toInt() and 0xffff) == expectedMachine &&
                    (header.getShort(24).toInt() and 0xffff) == PE32_PLUS_MAGIC
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fingerprintManifestFile(
        file: File,
        expectedSize: Long,
        expectedMd5: String,
        cancelled: () -> Boolean,
    ): FileFingerprint? {
        if (!expectedMd5.matches(HEX32) || expectedSize !in 0..MAX_DECODED_FILE_BYTES) return null
        val path = file.toPath()
        rejectSymlinkPath(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.size() != expectedSize) return null
        val md5 = MessageDigest.getInstance("MD5")
        val sha = MessageDigest.getInstance("SHA-256")
        var consumed = 0L
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled(cancelled)
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                consumed = Math.addExact(consumed, count.toLong())
                if (consumed > expectedSize) return null
                buffer.flip()
                val bytes = ByteArray(count)
                buffer.get(bytes)
                md5.update(bytes)
                sha.update(bytes)
            }
        }
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (consumed != expectedSize || after.size() != expectedSize || before.fileKey() != after.fileKey() ||
            before.lastModifiedTime() != after.lastModifiedTime()
        ) return null
        val actualMd5 = md5.digest().hex()
        if (!actualMd5.equals(expectedMd5, true)) return null
        return FileFingerprint(expectedSize, sha.digest().hex())
    }

    private fun qualificationParent(target: ForeverFunctionalClientStorage.UpdateTarget): File {
        val selected = storage.snapshot().selected ?: throw IllegalStateException("storage_unselected")
        if (selected.identity != target.identity || selected.legacy != target.legacy) {
            throw IllegalStateException("storage_selection_changed")
        }
        val trustedBase = selected.trustedBase?.canonicalFile ?: throw IllegalStateException("storage_base_unavailable")
        val gameParent = target.root.canonicalFile.parentFile ?: throw IllegalStateException("storage_base_unavailable")
        if (gameParent.name != "CustomGames") throw IllegalStateException("storage_external_files_parent_unproven")
        val rootBase = gameParent.parentFile?.canonicalFile ?: throw IllegalStateException("storage_base_unavailable")
        val expectedBase = if (target.legacy) trustedBase else trustedBase
        if (rootBase != expectedBase) throw IllegalStateException("storage_external_files_parent_unproven")
        val candidates = appContext.getExternalFilesDirs(null).filterNotNull()
        val parent = candidates.firstOrNull { candidate ->
            val path = candidate.toPath().toAbsolutePath().normalize()
            !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { candidate.canonicalFile == if (target.legacy) rootBase.resolve("files").canonicalFile else rootBase }
                    .getOrDefault(false)
        } ?: throw IllegalStateException("storage_external_files_parent_unproven")
        return parent.canonicalFile
    }

    private data class QualificationReceipt(
        val storageKey: String,
        val privateOk: Boolean,
        val clientOk: Boolean,
        val runtimeKnown: Boolean,
        val runtimeIdle: Boolean,
        val transferOk: Boolean,
        val timestampMillis: Long,
        val detail: String,
    ) {
        val passed: Boolean get() = privateOk && clientOk && runtimeKnown && runtimeIdle && transferOk
    }

    private fun qualificationStorageKey(target: ForeverFunctionalClientStorage.UpdateTarget): String {
        val material = listOf(
            storage.operationStorageId(target),
            target.identity.kind.name,
            target.mountIdentity ?: "legacy-mount-unavailable",
        ).joinToString("\u0000")
        return sha256(material.toByteArray(Charsets.UTF_8))
    }

    private fun hasCurrentQualificationProof(target: ForeverFunctionalClientStorage.UpdateTarget): Boolean {
        val receipt = readQualificationReceipt() ?: return false
        val age = System.currentTimeMillis() - receipt.timestampMillis
        return receipt.storageKey == qualificationStorageKey(target) && receipt.passed && age in 0..MAX_QUALIFICATION_AGE_MILLIS
    }

    private fun saveQualification(
        storageKey: String,
        result: ForeverClientUpdaterQualification.Result,
    ): Boolean {
        return try {
            val file = File(privateAnchor, QUALIFICATION_FILE)
            val parent = file.parentFile ?: return false
            ensurePrivateDirectory(parent)
            val previous = readQualificationReceipt()
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && previous == null) return false
            val detail = result.detail.filter { it.isLetterOrDigit() || it in "_;-" }.take(MAX_QUALIFICATION_DETAIL_CHARS)
            val receipt = QualificationReceipt(
                storageKey,
                result.privateStorageOk,
                result.clientVolumeOk,
                result.runtimeKnown,
                result.runtimeIdle,
                result.transferOk,
                System.currentTimeMillis(),
                detail,
            )
            writeQualificationReceipt(file, receipt, replaceExisting = previous != null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readQualificationReceipt(): QualificationReceipt? {
        return try {
            val file = File(privateAnchor, QUALIFICATION_FILE)
            val bytes = readSmallRegularFile(file, MAX_QUALIFICATION_RECORD_BYTES) ?: return null
            val envelope = JSONObject(String(bytes, Charsets.UTF_8))
            if (envelope.keys().asSequence().toSet() != setOf("payload", "sha256")) return null
            val payloadText = envelope.getString("payload")
            val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
            if (envelope.getString("sha256") != sha256(payloadBytes)) return null
            val payload = JSONObject(payloadText)
            if (payload.keys().asSequence().toSet() != QUALIFICATION_FIELDS ||
                payload.getInt("schema") != QUALIFICATION_SCHEMA
            ) return null
            val key = payload.getString("storageKey")
            val detail = payload.getString("detail")
            if (!key.matches(HEX64) || detail.length > MAX_QUALIFICATION_DETAIL_CHARS ||
                !detail.matches(Regex("[A-Za-z0-9_;-]*"))
            ) return null
            QualificationReceipt(
                key,
                payload.getBoolean("privateOk"),
                payload.getBoolean("clientOk"),
                payload.getBoolean("runtimeKnown"),
                payload.getBoolean("runtimeIdle"),
                payload.getBoolean("transferOk"),
                payload.getLong("timestampMillis").takeIf { it > 0L } ?: return null,
                detail,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeQualificationReceipt(file: File, receipt: QualificationReceipt, replaceExisting: Boolean) {
        val payload = JSONObject()
            .put("schema", QUALIFICATION_SCHEMA)
            .put("storageKey", receipt.storageKey)
            .put("privateOk", receipt.privateOk)
            .put("clientOk", receipt.clientOk)
            .put("runtimeKnown", receipt.runtimeKnown)
            .put("runtimeIdle", receipt.runtimeIdle)
            .put("transferOk", receipt.transferOk)
            .put("timestampMillis", receipt.timestampMillis)
            .put("detail", receipt.detail)
            .toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val bytes = JSONObject().put("payload", payload).put("sha256", sha256(payloadBytes))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_QUALIFICATION_RECORD_BYTES) { "qualification receipt exceeds its fixed bound" }
        rejectSymlinkPath(file.toPath())
        val parent = file.parentFile ?: throw IllegalArgumentException("qualification receipt has no private parent")
        val temporary = Files.createTempFile(parent.toPath(), ".qualification-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { channel ->
                writeFully(channel, ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            if (replaceExisting) {
                require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
                    "existing qualification receipt is not a regular private file"
                }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                try {
                    ForeverClientUpdateCatalog.publishVerifiedPrivateFile(file.toPath(), temporary)
                } catch (failure: FileAlreadyExistsException) {
                    throw IllegalStateException("qualification receipt appeared during publication", failure)
                }
                Files.deleteIfExists(temporary)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun safeStorageProblem(failure: Exception): String = when (failure) {
        is ForeverFunctionalClientStorage.StorageException -> when (failure.code) {
            "storage_busy" -> "El almacenamiento está ocupado por otra operación."
            "storage_unavailable", "storage_mount_unproven", "storage_base_unavailable" -> "El destino seleccionado no está disponible de forma segura."
            else -> "No se pudo confirmar la identidad del destino seleccionado."
        }
        else -> "No se pudo confirmar la identidad del destino seleccionado."
    }

    private fun runtimeBlockingMessage(reason: String): String = when {
        reason == "own_runtime_active" -> "El entorno del juego está activo; espera a que termine antes de actualizar."
        reason.startsWith("own_runtime_unknown") || reason == "operation_state_unreadable" ->
            "No se pudo confirmar el estado del entorno; la modificación queda bloqueada."
        reason.startsWith("guest_") -> "Hay una operación del entorno pendiente de recuperación."
        reason == "update_recovery_required" -> "Hay una actualización anterior pendiente de recuperación."
        reason == "operation_lock_held" -> "Otra operación del cliente está en curso."
        else -> "El estado de las operaciones del cliente no permite modificarlo."
    }

    private fun checkOpen() = check(!closed) { "client update service is closed" }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("client update operation cancelled")
    }

    companion object {
        const val CATALOG_ASSET = "forever/client-update/catalog.sqlite3.gzip"
        const val BUILD_CONFIG_ASSET = "forever/client-update/build-config.txt"
        const val CDN_CONFIG_ASSET = "forever/client-update/cdn-config.txt"


        private const val PRODUCT = "wow_classic_beta"
        private const val CATALOG_DIRECTORY = "forever-client-catalog"
        private const val DYNAMIC_CATALOG_DIRECTORY = "forever-dynamic-client-catalog"
        private const val ACTIVE_RECEIPT_NAME = "active-admission.json"
        private const val ACTIVE_RECEIPT_SCHEMA = 1
        private const val MAX_ACTIVE_RECEIPT_BYTES = 64 * 1024
        private const val MAX_CATALOG_RECEIPT_FILE_BYTES = 256L * 1024L * 1024L
        private const val MAX_METADATA_NETWORK_BYTES = 1L shl 30
        private const val MIN_METADATA_WORKSPACE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val EXPECTED_ARM64_MACHINE = 0xaa64
        private const val CONFIG_DIRECTORY = "forever-client-configs"
        private const val WORK_DIRECTORY = "forever-client-update-work"
        private const val JOURNAL_DIRECTORY = "forever-client-update-journal"
        private const val ADMISSION_DIRECTORY = "forever-managed-install-admissions"
        private const val ADMISSION_FILE_PREFIX = "managed-install-admission-"
        private const val ADMISSION_SCHEMA = 2
        private const val LEGACY_ADMISSION_SCHEMA = 1
        private const val MAX_ADMISSION_RECORD_BYTES = 64 * 1024
        private const val MAX_ADMISSION_ROOT_ID_CHARS = 1_024
        private const val MAX_ADMISSION_LOCATION_CHARS = 1_024
        private const val MAX_ADMISSION_ENUMERATION = 32
        private const val QUALIFICATION_FILE = "forever-client-update-qualification.json"
        private const val QUALIFICATION_SCHEMA = 1
        private const val MAX_QUALIFICATION_RECORD_BYTES = 4_096
        private const val MAX_QUALIFICATION_DETAIL_CHARS = 512
        private const val MAX_QUALIFICATION_AGE_MILLIS = 6L * 60L * 60L * 1_000L
        private const val MAX_REPORT_CHARS = 1_024
        private const val MAX_REPORTED_UNEXPECTED_ENTRIES = 128
        private const val MAX_BUILD_INFO_BYTES = 64 * 1024
        private const val MAX_LOCAL_INDEX_GENERATIONS = 256
        private const val MAX_LOCAL_INDEX_RECORDS = 1_000_000L
        private const val MIN_LOCAL_INDEX_BYTES = 0x1000L
        private const val LOCAL_INDEX_ALIGNMENT = 0x1000L
        private const val MAX_LOCAL_INDEX_BYTES = 20L * 1024L * 1024L
        private const val MAX_CASC_ARCHIVE_BYTES = 1L shl 30
        private const val MAX_STORAGE_MARKER_BYTES = 512
        private const val MAX_DECODED_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_CONFIG_BYTES = 4L * 1024L * 1024L
        private const val MAX_LOOSE_FILES = 4_096
        private const val PRIOR_LOOSE_COUNT = 35
        private const val PRIOR_VERSION = "1.60.1.70009"
        private const val PRIOR_BUILD_ID = 70_009L
        private const val PRIOR_BUILD_CONFIG_HASH = "05215079e3905ef5922ae0b03ffefb73"
        private const val PRIOR_CDN_CONFIG_HASH = "9b3c456dbb837d133a026d380c7c13e9"
        private const val PRIOR_EXECUTABLE_PATH = "WowB-ARM64.exe"
        private const val BUILD_INFO_PATH = ".build.info"
        private const val STORAGE_MARKER_PATH = ".forever-client-storage"
        private const val STORAGE_MARKER_VERSION = "forever-client-storage-v1"
        private val APP_ROOT_ENTRIES = setOf(STORAGE_MARKER_PATH, ".forever-update-staging", ".forever-client-update")
        private val EXPECTED_PASTED_ROOT_ENTRIES = APP_ROOT_ENTRIES + setOf("Data", BUILD_INFO_PATH)
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PE_SIGNATURE = 0x00004550
        private const val PE32_PLUS_MAGIC = 0x020b
        private val HEX32 = Regex("(?i)[0-9a-f]{32}")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val QUALIFICATION_FIELDS = setOf(
            "schema", "storageKey", "privateOk", "clientOk", "runtimeKnown", "runtimeIdle", "transferOk", "timestampMillis", "detail",
        )

        private val MANAGED_ADMISSION_FIELDS = setOf(
            "schema", "storage_id", "storage_identity", "root_path", "target_json", "catalog_sha256", "build_info_bytes",
            "build_info_sha256", "loose_files",
        )

        private val LEGACY_MANAGED_ADMISSION_FIELDS = setOf(
            "schema", "storage_id", "root_identity", "target_json", "catalog_sha256", "build_info_bytes",
            "build_info_sha256", "loose_files",
        )

        private val ACTIVE_RECEIPT_FIELDS = setOf(
            "schema", "target_json", "source_fingerprint", "official_versions_sha256", "catalog_path",
            "catalog_bytes", "catalog_sha256", "build_config_path", "build_config_bytes",
            "build_config_sha256", "cdn_config_path", "cdn_config_bytes", "cdn_config_sha256",
        )

        private val SOURCE_FINGERPRINT_FIELDS = setOf(
            "product", "version", "build_config_hash", "cdn_config_hash", "build_id",
        )

        internal fun encodeActiveCatalogReceipt(admission: ActiveCatalogAdmission): ByteArray {
            val targetBytes = admission.target.originalJsonCopy()
            val target = ForeverClientTargetBuild.parse(targetBytes)
            require(sameFingerprint(target.fingerprint, admission.sourceFingerprint) &&
                admission.target.nativeValidation.status == ForeverClientTargetBuild.NativeValidation.Status.PENDING &&
                admission.target.serverVersionSource in setOf("user-confirmed", "not-confirmed")
            ) { "active receipt target is not a resolver-derived, server-confirmed pending build" }
            require(admission.officialVersionsSha256.matches(HEX64) &&
                admission.catalogSha256.equals(target.catalog.sha256, true) && admission.catalogBytes == target.catalog.bytes
            ) { "active receipt evidence does not match its target snapshot" }
            val key = dynamicCacheKey(admission.sourceFingerprint)
            val catalogPath = "catalogs/$key/client-catalog.sqlite3"
            val buildPath = "metadata-cache/$key/config/${target.buildConfig.hash.take(2)}/${target.buildConfig.hash}.config"
            val cdnPath = "metadata-cache/$key/config/${target.cdnConfig.hash.take(2)}/${target.cdnConfig.hash}.config"
            val payload = JSONObject()
                .put("schema", ACTIVE_RECEIPT_SCHEMA)
                .put("target_json", String(targetBytes, Charsets.UTF_8))
                .put("source_fingerprint", fingerprintJson(admission.sourceFingerprint))
                .put("official_versions_sha256", admission.officialVersionsSha256.lowercase(Locale.ROOT))
                .put("catalog_path", catalogPath)
                .put("catalog_bytes", admission.catalogBytes)
                .put("catalog_sha256", admission.catalogSha256.lowercase(Locale.ROOT))
                .put("build_config_path", buildPath)
                .put("build_config_bytes", target.buildConfig.bytes)
                .put("build_config_sha256", target.buildConfig.sha256)
                .put("cdn_config_path", cdnPath)
                .put("cdn_config_bytes", target.cdnConfig.bytes)
                .put("cdn_config_sha256", target.cdnConfig.sha256)
                .toString()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val bytes = JSONObject()
                .put("payload", payload)
                .put("sha256", sha256(payloadBytes))
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_ACTIVE_RECEIPT_BYTES) { "active catalog receipt exceeds its fixed bound" }
            return bytes
        }

        internal fun parseActiveCatalogReceipt(bytes: ByteArray, root: File): ActiveCatalogAdmission {
            require(bytes.isNotEmpty() && bytes.size <= MAX_ACTIVE_RECEIPT_BYTES && bytes.none { it == 0.toByte() }) {
                "active dynamic catalog receipt is empty or outside its bound"
            }
            ForeverClientTargetBuild.validateJson(bytes)
            val envelope = JSONObject(String(bytes, Charsets.UTF_8))
            require(envelope.keys().asSequence().toSet() == setOf("payload", "sha256")) {
                "active dynamic catalog receipt envelope is malformed"
            }
            val payloadText = envelope.get("payload") as? String
                ?: throw IllegalArgumentException("active dynamic catalog receipt payload is malformed")
            val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
            ForeverClientTargetBuild.validateJson(payloadBytes)
            val checksum = envelope.get("sha256") as? String
            require(checksum != null && checksum.matches(HEX64) && checksum.equals(sha256(payloadBytes), true)) {
                "active dynamic catalog receipt checksum is invalid"
            }
            val payload = JSONObject(payloadText)
            require(payload.keys().asSequence().toSet() == ACTIVE_RECEIPT_FIELDS &&
                payload.requiredLong("schema") == ACTIVE_RECEIPT_SCHEMA.toLong()
            ) { "active dynamic catalog receipt fields or schema are invalid" }
            val targetBytes = payload.requiredString("target_json").toByteArray(Charsets.UTF_8)
            val target = ForeverClientTargetBuild.parse(targetBytes)
            require(target.serverVersionSource in setOf("user-confirmed", "not-confirmed") &&
                target.nativeValidation.status == ForeverClientTargetBuild.NativeValidation.Status.PENDING &&
                !target.nativeActivationValidated && target.architecture == "arm64" &&
                target.executable.expectedMachine == EXPECTED_ARM64_MACHINE
            ) { "active dynamic catalog target contains unsupported compatibility or native evidence" }
            val source = payload.get("source_fingerprint") as? JSONObject
                ?: throw IllegalArgumentException("active dynamic catalog source fingerprint is malformed")
            require(source.keys().asSequence().toSet() == SOURCE_FINGERPRINT_FIELDS) {
                "active dynamic catalog source fingerprint fields are invalid"
            }
            val fingerprint = ForeverClientUpdateCheck.Fingerprint(
                source.requiredString("product"),
                source.requiredString("version"),
                source.requiredString("build_config_hash"),
                source.requiredString("cdn_config_hash"),
                source.requiredLong("build_id"),
            )
            require(sameFingerprint(target.fingerprint, fingerprint)) {
                "active dynamic catalog target and source fingerprint differ"
            }
            val versionsSha = payload.requiredString("official_versions_sha256")
            require(versionsSha.matches(HEX64)) { "active dynamic catalog versions-response digest is malformed" }
            val key = dynamicCacheKey(fingerprint)
            val catalogPath = "catalogs/$key/client-catalog.sqlite3"
            val buildPath = "metadata-cache/$key/config/${target.buildConfig.hash.take(2)}/${target.buildConfig.hash}.config"
            val cdnPath = "metadata-cache/$key/config/${target.cdnConfig.hash.take(2)}/${target.cdnConfig.hash}.config"
            val catalogBytes = payload.requiredLong("catalog_bytes")
            val catalogSha = payload.requiredString("catalog_sha256")
            val buildBytes = payload.requiredLong("build_config_bytes")
            val buildSha = payload.requiredString("build_config_sha256")
            val cdnBytes = payload.requiredLong("cdn_config_bytes")
            val cdnSha = payload.requiredString("cdn_config_sha256")
            require(payload.requiredString("catalog_path") == catalogPath &&
                payload.requiredString("build_config_path") == buildPath &&
                payload.requiredString("cdn_config_path") == cdnPath
            ) { "active dynamic catalog receipt points outside its expected per-build cache paths" }
            require(catalogBytes == target.catalog.bytes && catalogSha.matches(HEX64) &&
                catalogSha.equals(target.catalog.sha256, true) &&
                buildBytes == target.buildConfig.bytes && buildSha.equals(target.buildConfig.sha256, true) &&
                cdnBytes == target.cdnConfig.bytes && cdnSha.equals(target.cdnConfig.sha256, true)
            ) { "active dynamic catalog receipt file proofs do not match its target snapshot" }
            val rootPath = root.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(rootPath)
            require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) { "active dynamic catalog root is unavailable" }
            val catalogFile = resolveReceiptPath(rootPath, catalogPath)
            val buildFile = resolveReceiptPath(rootPath, buildPath)
            val cdnFile = resolveReceiptPath(rootPath, cdnPath)
            verifyReceiptFile(catalogFile, catalogBytes, catalogSha, null, { false })
            verifyReceiptFile(buildFile, buildBytes, buildSha, target.buildConfig.hash, { false })
            verifyReceiptFile(cdnFile, cdnBytes, cdnSha, target.cdnConfig.hash, { false })
            return ActiveCatalogAdmission(
                target, fingerprint, versionsSha.lowercase(Locale.ROOT), catalogFile, catalogSha.lowercase(Locale.ROOT),
                catalogBytes, buildFile, cdnFile,
            )
        }

        internal fun publishActiveCatalogReceipt(root: File, bytes: ByteArray) {
            require(bytes.size in 1..MAX_ACTIVE_RECEIPT_BYTES) { "active catalog receipt exceeds its fixed bound" }
            val rootPath = root.toPath().toAbsolutePath().normalize()
            ensurePrivateDirectory(rootPath.toFile())
            val receipt = rootPath.resolve(ACTIVE_RECEIPT_NAME)
            rejectSymlinkPath(receipt)
            if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
                val previous = readBoundedReceipt(receipt.toFile(), MAX_ACTIVE_RECEIPT_BYTES)
                parseActiveCatalogReceipt(previous, root)
            }
            val temporary = Files.createTempFile(rootPath, ".active-admission-", ".tmp")
            try {
                FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    writeFully(channel, ByteBuffer.wrap(bytes))
                    channel.force(true)
                }
                rejectSymlinkPath(receipt)
                if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
                        "existing active dynamic catalog receipt changed before replacement"
                    }
                    Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    try {
                        ForeverClientUpdateCatalog.publishVerifiedPrivateFile(receipt, temporary)
                    } catch (failure: FileAlreadyExistsException) {
                        throw IllegalStateException("active dynamic catalog receipt appeared during publication", failure)
                    }
                    Files.deleteIfExists(temporary)
                }
                FileChannel.open(rootPath, StandardOpenOption.READ).use { it.force(true) }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        internal fun encodeManagedInstallAdmissionRecord(
            targetJson: ByteArray,
            catalogSha256: String,
            storageId: String,
            storageIdentity: String,
            rootPath: String,
            buildInfo: ByteArray,
            looseFiles: List<ManagedAdmissionLooseFile>,
        ): ByteArray {
            val admittedTarget = ForeverClientTargetBuild.parse(targetJson)
            require(admittedTarget.catalog.sha256.equals(catalogSha256, true)) { "managed install catalog does not match its APK target" }
            require(storageId.matches(Regex("storage-[0-9a-f]{64}"))) { "managed install storage identity is invalid" }
            requireValidAdmissionLocation(storageIdentity, rootPath)
            require(verifiedBuildInfoFingerprint(
                buildInfo,
                admittedTarget.version,
                admittedTarget.buildConfig.hash,
                admittedTarget.cdnConfig.hash,
                manifestVerified = true,
            ) != null) { "managed install build info does not match its APK target" }
            val validatedLoose = validateManagedAdmissionLooseFiles(looseFiles, admittedTarget)
            val payload = JSONObject()
                .put("schema", ADMISSION_SCHEMA)
                .put("storage_id", storageId)
                .put("storage_identity", storageIdentity)
                .put("root_path", rootPath)
                .put("target_json", String(targetJson, Charsets.UTF_8))
                .put("catalog_sha256", admittedTarget.catalog.sha256)
                .put("build_info_bytes", buildInfo.size)
                .put("build_info_sha256", sha256(buildInfo))
                .put("loose_files", JSONArray().also { array ->
                    validatedLoose.forEach { file ->
                        array.put(JSONObject()
                            .put("path", file.path)
                            .put("content_key", file.contentKey)
                            .put("decoded_bytes", file.decodedBytes))
                    }
                })
                .toString()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val envelope = JSONObject()
                .put("payload", payload)
                .put("sha256", sha256(payloadBytes))
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(envelope.size <= MAX_ADMISSION_RECORD_BYTES) { "managed install admission record exceeds its fixed bound" }
            return envelope
        }

        internal fun parseManagedInstallAdmissionRecord(
            bytes: ByteArray,
            expectedStorageId: String,
            expectedStorageIdentity: String,
            expectedRootPath: String,
        ): ManagedInstallAdmissionRecord? {
            return try {
                // Parsing is intentionally strict even though production only calls this for
                // the app-private receipt; callers cannot promote a client-side marker to trust.
                if (bytes.isEmpty() || bytes.size > MAX_ADMISSION_RECORD_BYTES || bytes.any { it == 0.toByte() }) return null
                ForeverClientTargetBuild.validateJson(bytes)
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                val envelope = JSONObject(text)
                if (envelope.keys().asSequence().toSet() != setOf("payload", "sha256")) return null
                val payloadText = envelope.get("payload") as? String ?: return null
                val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
                ForeverClientTargetBuild.validateJson(payloadBytes)
                val checksum = envelope.get("sha256") as? String ?: return null
                if (!checksum.matches(HEX64) || !checksum.equals(sha256(payloadBytes), true)) return null
                val payload = JSONObject(payloadText)
                if (payload.keys().asSequence().toSet() != MANAGED_ADMISSION_FIELDS || payload.requiredLong("schema") != ADMISSION_SCHEMA.toLong()) return null
                val storageId = payload.requiredString("storage_id")
                val storageIdentity = payload.requiredString("storage_identity")
                val rootPath = payload.requiredString("root_path")
                if (storageId != expectedStorageId || storageIdentity != expectedStorageIdentity || rootPath != expectedRootPath ||
                    !storageId.matches(Regex("storage-[0-9a-f]{64}"))
                ) return null
                requireValidAdmissionLocation(storageIdentity, rootPath)
                val targetJson = payload.requiredString("target_json").toByteArray(Charsets.UTF_8)
                val admittedTarget = ForeverClientTargetBuild.parse(targetJson)
                val catalogSha = payload.requiredString("catalog_sha256")
                if (!catalogSha.matches(HEX64) || !catalogSha.equals(admittedTarget.catalog.sha256, true)) return null
                val buildInfoBytes = payload.requiredLong("build_info_bytes")
                val buildInfoSha = payload.requiredString("build_info_sha256")
                if (buildInfoBytes !in 1..MAX_BUILD_INFO_BYTES.toLong() || !buildInfoSha.matches(HEX64)) return null
                val looseArray = payload.get("loose_files") as? JSONArray ?: return null
                if (looseArray.length() !in 1..MAX_LOOSE_FILES) return null
                val looseFiles = ArrayList<ManagedAdmissionLooseFile>(looseArray.length())
                repeat(looseArray.length()) { index ->
                    val item = looseArray.get(index) as? JSONObject ?: return null
                    if (item.keys().asSequence().toSet() != setOf("path", "content_key", "decoded_bytes")) return null
                    looseFiles += ManagedAdmissionLooseFile(
                        item.requiredString("path"),
                        item.requiredString("content_key"),
                        item.requiredLong("decoded_bytes"),
                    )
                }
                val validatedLoose = validateManagedAdmissionLooseFiles(looseFiles, admittedTarget)
                ManagedInstallAdmissionRecord(
                    storageId,
                    storageIdentity,
                    rootPath,
                    admittedTarget,
                    catalogSha.lowercase(Locale.ROOT),
                    buildInfoBytes,
                    buildInfoSha.lowercase(Locale.ROOT),
                    validatedLoose,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Schema-1 admission bound to transient filesystem identities
         * (`storage_id` derived from the base fileKey, `root_identity` holding
         * the root fileKey). Only the bounded launch-time migration may read
         * this shape, and only when it is linked to the private selection
         * registry for the same destination. New records are always schema 2.
         */
        internal data class LegacyManagedInstallAdmission(
            val storageId: String,
            val rootIdentity: String,
            val targetBuild: ForeverClientTargetBuild,
            val catalogSha256: String,
            val buildInfoBytes: Long,
            val buildInfoSha256: String,
            val looseFiles: List<ManagedAdmissionLooseFile>,
        )

        internal fun parseLegacyManagedInstallAdmissionRecord(bytes: ByteArray): LegacyManagedInstallAdmission? {
            return try {
                if (bytes.isEmpty() || bytes.size > MAX_ADMISSION_RECORD_BYTES || bytes.any { it == 0.toByte() }) return null
                ForeverClientTargetBuild.validateJson(bytes)
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                val envelope = JSONObject(text)
                if (envelope.keys().asSequence().toSet() != setOf("payload", "sha256")) return null
                val payloadText = envelope.get("payload") as? String ?: return null
                val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
                ForeverClientTargetBuild.validateJson(payloadBytes)
                val checksum = envelope.get("sha256") as? String ?: return null
                if (!checksum.matches(HEX64) || !checksum.equals(sha256(payloadBytes), true)) return null
                val payload = JSONObject(payloadText)
                if (payload.keys().asSequence().toSet() != LEGACY_MANAGED_ADMISSION_FIELDS ||
                    payload.requiredLong("schema") != LEGACY_ADMISSION_SCHEMA.toLong()
                ) return null
                val storageId = payload.requiredString("storage_id")
                val rootIdentity = payload.requiredString("root_identity")
                if (!storageId.matches(Regex("storage-[0-9a-f]{64}")) ||
                    rootIdentity.length !in 1..MAX_ADMISSION_ROOT_ID_CHARS || rootIdentity.any { it.code < 0x20 || it.code == 0x7f }
                ) return null
                val targetJson = payload.requiredString("target_json").toByteArray(Charsets.UTF_8)
                val admittedTarget = ForeverClientTargetBuild.parse(targetJson)
                val catalogSha = payload.requiredString("catalog_sha256")
                if (!catalogSha.matches(HEX64) || !catalogSha.equals(admittedTarget.catalog.sha256, true)) return null
                val buildInfoBytes = payload.requiredLong("build_info_bytes")
                val buildInfoSha = payload.requiredString("build_info_sha256")
                if (buildInfoBytes !in 1..MAX_BUILD_INFO_BYTES.toLong() || !buildInfoSha.matches(HEX64)) return null
                val looseArray = payload.get("loose_files") as? JSONArray ?: return null
                if (looseArray.length() !in 1..MAX_LOOSE_FILES) return null
                val looseFiles = ArrayList<ManagedAdmissionLooseFile>(looseArray.length())
                repeat(looseArray.length()) { index ->
                    val item = looseArray.get(index) as? JSONObject ?: return null
                    if (item.keys().asSequence().toSet() != setOf("path", "content_key", "decoded_bytes")) return null
                    looseFiles += ManagedAdmissionLooseFile(
                        item.requiredString("path"),
                        item.requiredString("content_key"),
                        item.requiredLong("decoded_bytes"),
                    )
                }
                val validatedLoose = validateManagedAdmissionLooseFiles(looseFiles, admittedTarget)
                LegacyManagedInstallAdmission(
                    storageId,
                    rootIdentity,
                    admittedTarget,
                    catalogSha.lowercase(Locale.ROOT),
                    buildInfoBytes,
                    buildInfoSha.lowercase(Locale.ROOT),
                    validatedLoose,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun requireValidAdmissionLocation(storageIdentity: String, rootPath: String) {
            val identityOk = storageIdentity.length in 1..160 && (
                storageIdentity.matches(Regex("primary:[A-Za-z0-9._-]+")) ||
                    storageIdentity.matches(Regex("volume:[A-Za-z0-9._-]+")) ||
                    storageIdentity.matches(Regex("volume-path:[0-9a-f]{64}"))
                )
            require(identityOk) { "managed install storage location identity is invalid" }
            require(rootPath.length in 1..MAX_ADMISSION_LOCATION_CHARS &&
                rootPath.startsWith('/') && rootPath.none { it.code < 0x20 || it.code == 0x7f } &&
                File(rootPath).toPath().normalize().toString() == rootPath
            ) { "managed install root location is invalid" }
        }

        /** Canonical stable root path shared by the storage ID and the admission location. */
        internal fun stableRootPath(root: File): String =
            root.absoluteFile.toPath().normalize().toString()

        private fun validateManagedAdmissionLooseFiles(
            files: List<ManagedAdmissionLooseFile>,
            target: ForeverClientTargetBuild,
        ): List<ManagedAdmissionLooseFile> {
            require(files.size in 1..MAX_LOOSE_FILES) { "managed install loose-file count is outside its bound" }
            val seen = HashSet<String>()
            files.forEach { file ->
                require(isSafeManifestPath(file.path) && !isWtfPath(file.path) && file.contentKey.matches(HEX32) &&
                    file.decodedBytes in 0..MAX_DECODED_FILE_BYTES && seen.add(file.path.lowercase(Locale.ROOT))
                ) { "managed install loose-file identity is invalid" }
            }
            val executable = files.singleOrNull { it.path.equals(target.executable.path, true) }
                ?: throw IllegalArgumentException("managed install proof is missing its admitted executable")
            require(executable.decodedBytes == target.executable.decodedBytes &&
                executable.contentKey.equals(target.executable.contentKey, true)
            ) { "managed install executable does not match its APK target" }
            return files.toList()
        }

        private fun JSONObject.requiredString(name: String): String =
            get(name) as? String ?: throw IllegalArgumentException("managed install record field is not a string")

        private fun fingerprintJson(fingerprint: ForeverClientUpdateCheck.Fingerprint): JSONObject = JSONObject()
            .put("product", fingerprint.product)
            .put("version", fingerprint.version)
            .put("build_config_hash", fingerprint.buildConfigHash.lowercase(Locale.ROOT))
            .put("cdn_config_hash", fingerprint.cdnConfigHash.lowercase(Locale.ROOT))
            .put("build_id", requireNotNull(fingerprint.buildId))

        private fun dynamicCacheKey(fingerprint: ForeverClientUpdateCheck.Fingerprint): String =
            "${requireNotNull(fingerprint.buildId)}-${fingerprint.version}-" +
                "${fingerprint.buildConfigHash.lowercase(Locale.ROOT)}-${fingerprint.cdnConfigHash.lowercase(Locale.ROOT)}"

        private fun sameFingerprint(
            left: ForeverClientUpdateCheck.Fingerprint,
            right: ForeverClientUpdateCheck.Fingerprint,
        ): Boolean = left.product == right.product && left.version == right.version && left.buildId == right.buildId &&
            left.buildConfigHash.equals(right.buildConfigHash, true) && left.cdnConfigHash.equals(right.cdnConfigHash, true)

        /**
         * Truthful planning-gate text when a staged download's original context
         * is still required. It must never claim a recovery failed: recovery is
         * not attempted on this path. Kept free of the recovery-failure wording
         * so the UI mapper cannot conflate the two.
         */
        internal const val FROZEN_RESUME_REQUIRED_MESSAGE =
            "An unfinished client download is still staged under its original catalog and destination. " +
                "Review its original download context before planning a different published build; " +
                "nothing was changed, retargeted or reset."

        /**
         * Same frozen build ignoring CDN/catalog rotation: product, visible
         * version, build ID and build-config hash must match exactly. A visible
         * version match alone never implies equivalence.
         */
        internal fun isSameFrozenBuildForResume(
            frozen: ForeverClientUpdateCheck.Fingerprint,
            published: ForeverClientUpdateCheck.Fingerprint,
        ): Boolean = frozen.product == published.product && frozen.version == published.version &&
            frozen.buildId == published.buildId &&
            frozen.buildConfigHash.equals(published.buildConfigHash, true)

        private fun resolveReceiptPath(root: java.nio.file.Path, relative: String): File {
            require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative &&
                relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
            ) { "active dynamic catalog receipt path is not relative and normalized" }
            val path = root.resolve(relative).normalize()
            require(path.startsWith(root)) { "active dynamic catalog receipt path escaped its private root" }
            rejectSymlinkPath(path)
            return path.toFile()
        }

        private fun verifyReceiptFile(
            file: File,
            expectedBytes: Long,
            expectedSha256: String,
            expectedMd5: String?,
            cancelled: () -> Boolean,
        ) {
            require(expectedBytes in 1..MAX_CATALOG_RECEIPT_FILE_BYTES && expectedSha256.matches(HEX64) &&
                (expectedMd5 == null || expectedMd5.matches(HEX32))
            ) { "active catalog file proof is malformed" }
            val path = file.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == expectedBytes) {
                "active catalog file is not a regular file of its proven size"
            }
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val sha = MessageDigest.getInstance("SHA-256")
            val md5 = expectedMd5?.let { MessageDigest.getInstance("MD5") }
            var consumed = 0L
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    if (cancelled()) throw CancellationException("active catalog verification cancelled")
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    consumed = Math.addExact(consumed, count.toLong())
                    require(consumed <= expectedBytes) { "active catalog file exceeded its proven size" }
                    buffer.flip()
                    val block = ByteArray(count)
                    buffer.get(block)
                    sha.update(block)
                    md5?.update(block)
                }
            }
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(consumed == expectedBytes && after.size() == expectedBytes && before.fileKey() == after.fileKey() &&
                before.lastModifiedTime() == after.lastModifiedTime() && sha.digest().hex().equals(expectedSha256, true) &&
                (expectedMd5 == null || md5!!.digest().hex().equals(expectedMd5, true))
            ) { "active catalog file does not match its private receipt proof" }
        }

        private fun readBoundedReceipt(file: File, maxBytes: Int): ByteArray {
            val path = file.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "existing active catalog receipt is not a regular file" }
            val size = Files.size(path)
            require(size in 1..maxBytes.toLong()) { "existing active catalog receipt is outside its bound" }
            val output = ByteArrayOutputStream(size.toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= maxBytes) { "active catalog receipt exceeds its fixed bound" }
                    buffer.flip()
                    val block = ByteArray(count)
                    buffer.get(block)
                    output.write(block)
                }
            }
            require(output.size().toLong() == size && Files.size(path) == size) { "active catalog receipt changed while being read" }
            return output.toByteArray()
        }

        private fun JSONObject.requiredLong(name: String): Long {
            val number = get(name) as? Number ?: throw IllegalArgumentException("managed install record field is not an integer")
            val lexicalInteger = number.toString()
            require(lexicalInteger.matches(Regex("-?(?:0|[1-9][0-9]*)"))) { "managed install record field is not an integer" }
            return lexicalInteger.toLongOrNull()
                ?: throw IllegalArgumentException("managed install record field is outside the integer range")
        }

        internal fun parseBuildInfo(bytes: ByteArray): BuildInfoFingerprint? {
            if (bytes.isEmpty() || bytes.size > MAX_BUILD_INFO_BYTES || bytes.last() != '\n'.code.toByte() ||
                bytes.any { it == 0.toByte() }
            ) return null
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                return null
            }
            val lines = text.dropLast(1).split('\n').map { line ->
                if (line.endsWith('\r')) line.dropLast(1) else line
            }
            if (lines.size != 2 || lines.any { '\r' in it || it.length > MAX_BUILD_INFO_BYTES }) return null
            val headerTokens = lines[0].split('|')
            val values = lines[1].split('|')
            if (headerTokens.isEmpty() || headerTokens.size > 64 || values.size != headerTokens.size ||
                values.any { it.length > MAX_BUILD_INFO_BYTES }
            ) return null
            val names = ArrayList<String>(headerTokens.size)
            for (token in headerTokens) {
                val marker = token.indexOf('!')
                if (marker <= 0 || token.indexOf('!', marker + 1) >= 0) return null
                val name = token.substring(0, marker).trim().lowercase(Locale.ROOT)
                val type = token.substring(marker + 1)
                if (!name.matches(Regex("[a-z][a-z ]{0,40}")) || !type.matches(Regex("(?i)[a-z]+:[0-9]+"))) return null
                names += name
            }
            if (names.toSet().size != names.size) return null
            val indexes = names.withIndex().associate { it.value to it.index }
            val required = listOf("branch", "build key", "cdn key", "version", "product")
            if (!required.all { it in indexes }) return null
            fun field(name: String): String = values[indexes.getValue(name)].trim()
            fun optional(name: String): String? = indexes[name]?.let { values[it].trim().takeIf(String::isNotEmpty) }
            val build = field("build key")
            val cdn = field("cdn key")
            val version = field("version")
            val product = field("product")
            val branch = field("branch")
            if (!build.matches(HEX32) || !cdn.matches(HEX32) ||
                !version.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) ||
                !product.matches(Regex("[a-z0-9_]{1,64}")) || !branch.matches(Regex("[a-z0-9_-]{1,32}"))
            ) return null
            val tags = optional("tags")
            val tokens = tags?.split(Regex("\\s+"))?.filter(String::isNotEmpty).orEmpty()
            val architecture = optional("architecture")?.takeIf { it.equals("arm64", true) || it.equals("x86_64", true) }
                ?: tokens.firstOrNull { it.equals("arm64", true) || it.equals("x86_64", true) }
            val platform = optional("platform") ?: architecture?.let { arch ->
                val archIndex = tokens.indexOfFirst { it.equals(arch, true) }
                tokens.getOrNull(archIndex - 1)
            }
            val locale = optional("locale")?.takeIf { it.matches(Regex("[a-z]{2}[A-Z]{2}")) }
                ?: Regex("(?<![A-Za-z])[a-z]{2}[A-Z]{2}(?![A-Za-z])").find(tags.orEmpty())?.value
            val platformTag = if (platform != null && architecture != null) "$platform $architecture" else null
            return BuildInfoFingerprint(
                branch,
                build.lowercase(Locale.ROOT),
                cdn.lowercase(Locale.ROOT),
                version,
                product,
                tags,
                platformTag,
                architecture,
                locale,
            )
        }

        internal fun classifyPastedClientMetadata(
            hasCascData: Boolean,
            buildInfoPresent: Boolean,
            info: BuildInfoFingerprint?,
            targetBuildVerified: Boolean = false,
            priorBuildVerified: Boolean = false,
        ): PastedClientVerdict {
            if (!hasCascData) return PastedClientVerdict.NOT_CASC
            if (!buildInfoPresent) return PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE
            val parsed = info ?: return PastedClientVerdict.NOT_CASC
            if (parsed.product != PRODUCT || parsed.branch != "us" || parsed.locale != "enUS") {
                return PastedClientVerdict.WRONG_PRODUCT
            }
            if (!parsed.isArm64) return PastedClientVerdict.NOT_ARM64
            if (targetBuildVerified) return PastedClientVerdict.TARGET_BUILD
            if (priorBuildVerified) return PastedClientVerdict.PRIOR_BUILD
            return PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE
        }

        /**
         * Management-CTA gate: an installedVersion==null client may leave the
         * detect loop only toward the existing qualify/plan flow. Ownership is
         * still enforced downstream by destination()/plan()/execute(); this
         * only decides the button route, never file ownership.
         */
        internal fun isPastedSourceCompletable(report: PastedClientReport?): Boolean =
            report?.present == true && report.verdict in setOf(
                PastedClientVerdict.TARGET_BUILD,
                PastedClientVerdict.PRIOR_BUILD,
                PastedClientVerdict.UNKNOWN_BUILD_AS_SOURCE,
            )

        internal fun storageMarkerMatches(bytes: ByteArray, storageIdentity: String): Boolean {
            if (bytes.size !in 1..MAX_STORAGE_MARKER_BYTES) return false
            return bytes.contentEquals("$STORAGE_MARKER_VERSION\n$storageIdentity\n".toByteArray(Charsets.UTF_8))
        }

        /** Produces an ownership fingerprint only after an independent build and file-manifest proof. */
        internal fun verifiedBuildInfoFingerprint(
            bytes: ByteArray,
            expectedVersion: String,
            expectedBuildConfigHash: String,
            expectedCdnConfigHash: String,
            manifestVerified: Boolean,
            exactBytes: ByteArray? = null,
        ): FileFingerprint? {
            if (!manifestVerified || (exactBytes != null && !bytes.contentEquals(exactBytes))) return null
            val parsed = parseBuildInfo(bytes) ?: return null
            if (parsed.branch != "us" || parsed.product != PRODUCT || parsed.version != expectedVersion ||
                !parsed.buildConfigHash.equals(expectedBuildConfigHash, true) ||
                !parsed.cdnConfigHash.equals(expectedCdnConfigHash, true)
            ) return null
            return FileFingerprint(bytes.size.toLong(), sha256(bytes))
        }

        /** Extracts one bounded private asset using atomic create-new publication; invalid existing files are preserved. */
        internal fun extractPinnedAsset(
            input: () -> InputStream,
            destination: File,
            expectedSize: Long,
            expectedSha256: String,
            expectedMd5: String,
            cancelled: () -> Boolean,
        ): File {
            require(expectedSize in 1..4L * 1024L * 1024L && expectedSha256.matches(HEX64) && expectedMd5.matches(HEX32)) {
                "pinned private asset fingerprint is malformed"
            }
            val parent = destination.toPath().toAbsolutePath().normalize().parent
                ?: throw IllegalArgumentException("private asset has no parent directory")
            ensurePrivateDirectory(parent.toFile())
            val target = destination.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(target)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                validatePinnedFile(target.toFile(), expectedSize, expectedSha256, expectedMd5)
                return target.toFile()
            }
            val temporary = Files.createTempFile(parent, ".${target.fileName.toString().take(32)}-", ".asset-part")
            var published = false
            try {
                val sha = MessageDigest.getInstance("SHA-256")
                val md5 = MessageDigest.getInstance("MD5")
                var total = 0L
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    input().use { source ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            if (cancelled()) throw CancellationException("private asset extraction cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total = Math.addExact(total, count.toLong())
                            require(total <= expectedSize) { "private asset exceeds its pinned size bound" }
                            sha.update(buffer, 0, count)
                            md5.update(buffer, 0, count)
                            val bytes = ByteBuffer.wrap(buffer, 0, count)
                            while (bytes.hasRemaining()) {
                                if (channel.write(bytes) <= 0) throw IllegalStateException("private asset write made no progress")
                            }
                        }
                    }
                    channel.force(true)
                }
                require(total == expectedSize && sha.digest().hex().equals(expectedSha256, true) &&
                    md5.digest().hex().equals(expectedMd5, true)
                ) { "private asset size or digest does not match its pinned fingerprint" }
                if (cancelled()) throw CancellationException("private asset extraction cancelled")
                rejectSymlinkPath(target)
                try {
                    ForeverClientUpdateCatalog.publishVerifiedPrivateFile(target, temporary)
                } catch (failure: FileAlreadyExistsException) {
                    validatePinnedFile(target.toFile(), expectedSize, expectedSha256, expectedMd5)
                }
                published = true
                return target.toFile()
            } finally {
                if (published || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(temporary)
            }
        }

        private fun validatePinnedFile(file: File, size: Long, sha256: String, md5: String) {
            val path = file.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == size) {
                "existing private asset is not a regular file of its pinned size; preserving it"
            }
            val (actualSha, actualMd5) = digestFile(path)
            require(actualSha.equals(sha256, true) && actualMd5.equals(md5, true)) {
                "existing private asset does not match its pinned fingerprints; preserving it"
            }
        }

        private fun digestFile(path: java.nio.file.Path): Pair<String, String> {
            val sha = MessageDigest.getInstance("SHA-256")
            val md5 = MessageDigest.getInstance("MD5")
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    buffer.flip()
                    val bytes = ByteArray(count)
                    buffer.get(bytes)
                    sha.update(bytes)
                    md5.update(bytes)
                }
            }
            return sha.digest().hex() to md5.digest().hex()
        }

        /**
         * Bounded read-only admission for launch recognition. Reads the
         * managed-install admission committed for one storage destination and
         * re-parses it with the same strict validation as the updater path;
         * returns null when absent or invalid. Private-sandbox and
         * ensurePrivateDirectory semantics match the updater's own read, and
         * the instance reader below delegates here so both stay identical.
         * Only this app-private no-backup path is read. The checksum detects
         * corruption; provenance comes from the app sandbox and the record is
         * re-proved against the root.
         */
        internal fun readManagedInstallAdmission(
            anchor: File,
            storageId: String,
            storageIdentity: String,
            rootPath: String,
        ): ManagedInstallAdmissionRecord? {
            val directory = File(anchor, ADMISSION_DIRECTORY)
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            ensurePrivateDirectory(directory)
            val file = File(directory, "$ADMISSION_FILE_PREFIX${sha256(storageId.toByteArray(Charsets.UTF_8))}.json")
            val bytes = readSmallRegularFile(file, MAX_ADMISSION_RECORD_BYTES) ?: return null
            return parseManagedInstallAdmissionRecord(bytes, storageId, storageIdentity, rootPath)
        }

        /** Read-only journal/backup probe; no catalog, directory creation or recovery. */
        internal fun hasPendingInstallTransaction(anchor: File, root: File, storageId: String): Boolean =
            ForeverClientUpdateTransaction(
                root, File(anchor, JOURNAL_DIRECTORY), storageId,
                identityStillMatches = { false }, mutationAllowed = { false },
            ).hasPendingTransaction()

        /** Record path for one stable storage ID; the file itself may be absent. */
        internal fun managedInstallAdmissionFile(anchor: File, storageId: String): File {
            require(storageId.matches(Regex("storage-[0-9a-f]{64}"))) { "managed install storage identity is invalid" }
            return File(File(anchor, ADMISSION_DIRECTORY), "$ADMISSION_FILE_PREFIX${sha256(storageId.toByteArray(Charsets.UTF_8))}.json")
        }

        /**
         * Bounded enumeration of stored admission files for legacy migration.
         * Returns prefix-matching entries in name order without reading them;
         * anything beyond the bound fails closed at the caller. The directory
         * itself is never created here.
         */
        internal fun listManagedInstallAdmissionFiles(anchor: File): List<File> {
            val directory = File(anchor, ADMISSION_DIRECTORY)
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
            ensurePrivateDirectory(directory)
            val entries = directory.listFiles { file -> file.name.startsWith(ADMISSION_FILE_PREFIX) }
                ?.sortedBy { it.name } ?: throw IllegalStateException("managed install admission directory is unreadable")
            if (entries.size > MAX_ADMISSION_ENUMERATION) {
                throw IllegalStateException("managed install admission directory exceeds its enumeration bound")
            }
            return entries
        }

        /** Bounded NOFOLLOW read of one admission file; null only for a missing regular file of valid size. */
        internal fun readManagedInstallAdmissionBytes(file: File): ByteArray? =
            readSmallRegularFile(file, MAX_ADMISSION_RECORD_BYTES)

        /**
         * Create-new-only publication for legacy migration. An already present
         * record (even a concurrent migration result) fails closed instead of
         * being overwritten; the next launch then uses the direct record path.
         * The legacy record file is never touched here.
         */
        internal fun publishMigratedInstallAdmission(anchor: File, storageId: String, bytes: ByteArray): File {
            require(bytes.size in 1..MAX_ADMISSION_RECORD_BYTES) { "managed install admission record is outside its bound" }
            val directory = File(anchor, ADMISSION_DIRECTORY)
            ensurePrivateDirectory(directory)
            val file = managedInstallAdmissionFile(anchor, storageId)
            val path = file.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("managed install admission appeared during migration; preserving it")
            }
            val temporary = Files.createTempFile(path.parent, ".managed-admission-", ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    writeFully(channel, ByteBuffer.wrap(bytes))
                    channel.force(true)
                }
                rejectSymlinkPath(path)
                try {
                    ForeverClientUpdateCatalog.publishVerifiedPrivateFile(path, temporary)
                } catch (failure: FileAlreadyExistsException) {
                    throw IllegalStateException("managed install admission appeared during migration; preserving it", failure)
                }
                Files.deleteIfExists(temporary)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return file
        }

        private fun readSmallRegularFile(file: File, maxBytes: Int): ByteArray? {
            val path = file.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            val size = Files.size(path)
            if (size !in 1..maxBytes.toLong()) return null
            val output = ByteArrayOutputStream(size.toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(minOf(COPY_BUFFER_BYTES, maxBytes))
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > maxBytes) return null
                    buffer.flip()
                    val bytes = ByteArray(count)
                    buffer.get(bytes)
                    output.write(bytes)
                }
            }
            if (output.size().toLong() != size || Files.size(path) != size) return null
            return output.toByteArray()
        }

        private fun ensurePrivateDirectory(directory: File) {
            val path = directory.toPath().toAbsolutePath().normalize()
            val root = path.root ?: throw IllegalArgumentException("private directory path is not absolute")
            var current = root
            if (Files.isSymbolicLink(current)) throw IllegalStateException("private directory path contains a symlink")
            for (part in path) {
                current = current.resolve(part)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw IllegalStateException("private directory component is not a regular directory (component=${current.fileName ?: "root"}, link=${Files.isSymbolicLink(current)}, directory=${Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)})")
                    }
                } else {
                    try {
                        Files.createDirectory(current)
                    } catch (_: FileAlreadyExistsException) {
                        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                            throw IllegalStateException("private directory creation raced with an unsafe path")
                        }
                    }
                }
            }
        }

        private fun rejectSymlinkPath(path: java.nio.file.Path) {
            val absolute = path.toAbsolutePath().normalize()
            var current = absolute.root ?: throw IllegalArgumentException("path is not absolute")
            if (Files.isSymbolicLink(current)) throw IllegalStateException("path contains a symbolic link")
            for (part in absolute) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) throw IllegalStateException("path contains a symbolic link")
            }
        }

        private fun isSafeDirectory(directory: File): Boolean = try {
            val path = directory.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(path)
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }

        private fun isSafeManifestPath(value: String): Boolean =
            value.isNotEmpty() && value.length <= 4_096 && '\u0000' !in value && '\\' !in value &&
                !value.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(value) &&
                value.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { ch -> ch.code < 0x20 || ch.code == 0x7f } }

        private fun isWtfPath(value: String): Boolean = value.substringBefore('/').equals("WTF", ignoreCase = true)

        private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, position + buffer.position())
                if (count <= 0) throw IllegalStateException("private file ended before its declared bound")
            }
            buffer.flip()
        }

        private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IllegalStateException("private receipt write made no progress")
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
