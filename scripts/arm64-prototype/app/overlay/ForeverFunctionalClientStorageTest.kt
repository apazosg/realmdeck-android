package app.gamenative

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.GameMetadata
import app.gamenative.utils.GameMetadataManager
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ForeverFunctionalClientStorageTest {
    @Test
    fun capacityLabelsUseCorrectBinaryUnits() {
        assertEquals("8.0 GiB", ForeverFunctionalClientStorage.formatAvailableBytes(8L * 1024 * 1024 * 1024))
        assertEquals("512.0 MiB", ForeverFunctionalClientStorage.formatAvailableBytes(512L * 1024 * 1024))
        assertEquals("No disponible", ForeverFunctionalClientStorage.formatAvailableBytes(0))
    }

    @Test
    fun permissionFailuresExplainAndroidLimitsWithoutLeakingExceptionContents() {
        val text = ForeverFunctionalClientStorage.selectionFailureMessage(SecurityException("synthetic-private-path"))
        assertTrue(text.contains("Android no permite"))
        assertFalse(text.contains("synthetic-private-path"))
        assertTrue(ForeverFunctionalClientStorage.selectionFailureMessage(
            ForeverFunctionalClientStorage.StorageException("storage_selection_not_saved")).contains("No se ha podido confirmar"))
    }

    @Test
    fun transientUsbIsEnumeratedWithoutContextDirectoryOrSnapshotWrites() {
        val base = Files.createTempDirectory("forever-usb-metadata").toFile()
        val primary = File(base, "primary/files").apply { mkdirs() }
        val usb = File(base, "usb").apply { mkdirs() }
        val contextVolumes = listOf(ForeverFunctionalClientStorage.VolumeCandidate(
            primary, null, true, false, Environment.MEDIA_MOUNTED, "Interno"))
        val metadata = ForeverFunctionalClientStorage.NativeVolumeMetadata(
            usb, "USB-META", false, true, Environment.MEDIA_MOUNTED, "USB", "mount:usb-meta")
        val candidates = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName, contextVolumes, listOf(metadata))
        assertEquals(2, candidates.size)
        val storage = ForeverFunctionalClientStorage.testService(context,
            volumes = { candidates }, legacyRoot = { File(base, "primary/CustomGames/WoW Forever") })
        val option = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
        assertTrue(option.available)
        assertFalse(File(usb, "Android").exists())
        storage.select(option.identity)
        assertTrue(File(usb, "Android/data/${context.packageName}/files/CustomGames/WoW Forever/.forever-client-storage").isFile)
        assertEquals(option.identity, storage.snapshot().selected?.identity)
    }

    @Test
    fun nativeMetadataDeduplicatesVolumesAndNeverGuessesAnUnavailablePath() {
        val base = Files.createTempDirectory("forever-usb-dedup").toFile()
        val files = File(base, "files").apply { mkdirs() }
        val primary = ForeverFunctionalClientStorage.VolumeCandidate(files, null, true, false, Environment.MEDIA_MOUNTED, "Interno")
        val metadata = ForeverFunctionalClientStorage.NativeVolumeMetadata(base, null, true, false, Environment.MEDIA_MOUNTED, "Interno")
        val missingPath = metadata.copy(directory = null, uuid = "USB-NO-PATH", primary = false, removable = true)
        val merged = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName, listOf(primary), listOf(metadata, missingPath))
        assertEquals(listOf(primary), merged)
    }

    @Test
    fun matchingContextUsbRetainsNativeAnchorForItsMissingAppDirectory() {
        val base = Files.createTempDirectory("forever-usb-enrichment").toFile()
        val appFiles = File(base, "Android/data/${context.packageName}/files")
        val candidate = ForeverFunctionalClientStorage.VolumeCandidate(appFiles, "USB-MERGE", false, true,
            Environment.MEDIA_MOUNTED, "USB")
        val metadata = ForeverFunctionalClientStorage.NativeVolumeMetadata(base, "USB-MERGE", false, true,
            Environment.MEDIA_MOUNTED, "USB", "mount:merged")
        val merged = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName, listOf(candidate), listOf(metadata))
        assertEquals(1, merged.size)
        assertEquals(base, merged.single().nativeVolumeRoot)
        assertEquals("mount:merged", merged.single().mountIdentity)
        assertFalse(appFiles.exists())
        val enrichedByPath = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName,
            listOf(candidate.copy(uuid = null, isRemovable = false)), listOf(metadata)).single()
        assertEquals("USB-MERGE", enrichedByPath.uuid)
        assertTrue(enrichedByPath.isRemovable)
    }

    @Test
    fun readonlyNativeUsbIsVisibleButCannotBeSelected() {
        val base = Files.createTempDirectory("forever-usb-readonly").toFile()
        val primary = File(base, "primary/files").apply { mkdirs() }
        val usb = File(base, "usb").apply { mkdirs() }
        val candidates = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName,
            listOf(ForeverFunctionalClientStorage.VolumeCandidate(primary, null, true, false, Environment.MEDIA_MOUNTED, "Interno")),
            listOf(ForeverFunctionalClientStorage.NativeVolumeMetadata(usb, "USB-READONLY", false, true, Environment.MEDIA_MOUNTED_READ_ONLY, "USB", "mount:ro")))
        val storage = ForeverFunctionalClientStorage.testService(context,
            volumes = { candidates }, legacyRoot = { File(base, "primary/CustomGames/WoW Forever") })
        val option = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
        assertTrue(option.mounted)
        assertFalse(option.available)
        assertFalse(File(usb, "Android").exists())
    }

    @Test
    fun symlinkInNativeUsbNamespaceIsNotFollowed() {
        val base = Files.createTempDirectory("forever-usb-namespace-link").toFile()
        val primary = File(base, "primary/files").apply { mkdirs() }
        val usb = File(base, "usb").apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(usb, "Android").toPath(), outside.toPath())
        val candidates = ForeverFunctionalClientStorage.mergeVolumeCandidates(context.packageName,
            listOf(ForeverFunctionalClientStorage.VolumeCandidate(primary, null, true, false, Environment.MEDIA_MOUNTED, "Interno")),
            listOf(ForeverFunctionalClientStorage.NativeVolumeMetadata(usb, "USB-LINK", false, true, Environment.MEDIA_MOUNTED, "USB", "mount:link")))
        val storage = ForeverFunctionalClientStorage.testService(context,
            volumes = { candidates }, legacyRoot = { File(base, "primary/CustomGames/WoW Forever") })
        assertFalse(storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.available)
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test
    fun mountedEmptyUsbIsSelectableWithoutAnExistingCustomGamesDirectory() {
        val base = Files.createTempDirectory("forever-storage-empty-usb").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val storage = serviceWithCandidate(external, legacy, "usb-empty", true, Environment.MEDIA_MOUNTED)
        assertFalse(File(external, "CustomGames").exists())
        val option = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
        assertTrue(option.available)
        assertFalse(File(external, "CustomGames").exists())
        storage.select(option.identity)
        val selected = requireNotNull(storage.snapshot().selected)
        assertEquals(option.identity, selected.identity)
        assertEquals(ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED, selected.client.state)
    }

    @Test
    fun fileAtClientRootIsUnavailableAndOtherVolumesNeedMountProof() {
        val base = Files.createTempDirectory("forever-storage-root-file").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { parentFile!!.mkdirs(); writeText("not a directory") }
        val option = requireNotNull(service(external, legacy).snapshot().selected)
        assertFalse(option.available)
        assertFalse(option.copy(identity = ForeverFunctionalClientStorage.StorageIdentity("volume-path:test", ForeverFunctionalClientStorage.Kind.OTHER),
            trusted = true, mountIdentity = null).available)
    }

    @Test
    fun selectionLeaseExcludesIoAndStartedGuestCannotBeRolledBack() {
        val guard = ForeverFunctionalClientStorage.ProcessGuard
        val selection = requireNotNull(guard.acquireSelection())
        assertNull(guard.acquireIo())
        selection.close()
        val io = requireNotNull(guard.acquireIo())
        val guest = requireNotNull(guard.reserveGuest(io))
        assertTrue(guard.markGuestStarted(guest))
        io.close()
        assertFalse(guard.rollbackGuest(guest))
        assertNull(guard.acquireSelection())
        assertTrue(guard.releaseGuest(guest))
        val next = requireNotNull(guard.acquireSelection())
        next.close()
    }
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clearStorageState() {
        context.getSharedPreferences("forever_functional_client_storage", 0).edit().clear().commit()
        ForeverFunctionalClientStorage.ProcessGuard.resetForTests()
    }

    @Test
    fun defaultSelectionUsesTheLegacyRootAndDoesNotDuplicateCustomGames() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val service = service(external, legacy)

        val snapshot = service.snapshot()
        assertEquals(legacy.canonicalFile, snapshot.selected?.root)
        assertEquals("primary:emulated", snapshot.selected?.identity?.id)
        assertEquals(1, snapshot.options.size)
        assertFalse(File(external, "CustomGames").exists())
        assertTrue(base.walkTopDown().none { it.name.startsWith(".forever-probe-") })
    }

    @Test
    fun selectingAnEmptyDestinationCommitsItAndReportsClientNotInstalled() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val service = service(external, legacy)

        service.select(service.snapshot().selected!!.identity)
        val selected = service.snapshot().selected!!
        assertEquals(ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED, selected.client.state)
        assertTrue(File(selected.root, ".forever-client-storage").isFile)
    }

    @Test
    fun pastedCascSourceHasItsOwnUnverifiedStorageStateWithoutChangingOlderStates() {
        val base = Files.createTempDirectory("forever-pasted-state").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        val storage = serviceWithCandidate(external, legacy, "usb-pasted-state", true, Environment.MEDIA_MOUNTED)
        val identity = storage.snapshot().options.single {
            it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE
        }.identity
        val root = File(external, "CustomGames/WoW Forever")
        File(root, "Data").mkdirs()
        storage.select(identity)
        assertEquals(
            ForeverFunctionalClientStorage.ClientInspection.State.INCOMPLETE,
            storage.snapshot().selected?.client?.state,
        )

        File(root, "Data/data").mkdirs()
        assertEquals(
            ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE,
            storage.snapshot().selected?.client?.state,
        )

        File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).writeText("synthetic executable")
        File(root, "WTF").mkdirs()
        File(root, ForeverFunctionalClientStorage.ORIGINAL_WTF).writeText("synthetic config")
        assertEquals(
            ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED,
            storage.snapshot().selected?.client?.state,
        )
    }

    @Test
    fun preparePastedClientFolderCreatesAnEmptyRootAndReadmeBesideIt() {
        val base = Files.createTempDirectory("forever-pasted-prepare").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        val storage = serviceWithCandidate(external, legacy, "usb-pasted-prepare", true, Environment.MEDIA_MOUNTED)
        val removable = storage.snapshot().options.single {
            it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE
        }
        val existingInstructions = File(external, "CustomGames/LEEME-INSTALACION.txt").apply {
            parentFile!!.mkdirs()
            writeText("no reemplazar")
        }
        // A selected removable destination whose client root does not yet exist.
        context.getSharedPreferences("forever_functional_client_storage", 0).edit()
            .putString("selected_id", removable.identity.id)
            .putString("selected_label", removable.label)
            .putString("selected_descriptor", removable.descriptor)
            .commit()

        val result = storage.preparePastedClientFolder()
        val root = File(result.clientRootPath)
        val readme = File(result.readmePath)
        assertTrue(result.created)
        assertTrue(result.readmeWritten)
        assertTrue(root.isDirectory)
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals("no reemplazar", existingInstructions.readText())
        assertEquals("LEEME-INSTALACION-2.txt", readme.name)
        assertEquals(root.parentFile?.canonicalFile, readme.parentFile?.canonicalFile)
        assertFalse(readme.toPath().startsWith(root.toPath()))
        assertTrue(result.pcPath.endsWith("Android/data/${context.packageName}/files/CustomGames/WoW Forever"))
        val instructions = readme.readText()
        assertTrue(instructions.contains(result.pcPath))
        assertTrue(instructions.contains("Data/"))
        assertTrue(instructions.contains(".build.info"))
        assertTrue(instructions.contains(".forever-client-storage"))
        assertTrue(instructions.contains(".forever-client-update/"))
        assertTrue(instructions.contains(".forever-update-staging/"))
        assertTrue(instructions.contains("Revisar cliente pegado"))
        assertTrue(instructions.contains("No se sobrescribe"))
        // The review is metadata/index only: no verified-source or fixed
        // byte-count promises may leak into the instructions.
        assertFalse(instructions.contains("70450"))
        assertFalse(instructions.contains("70009"))
        assertFalse(instructions.contains("verificad"))
        assertFalse(instructions.contains("casi nada"))
        assertFalse(instructions.contains("GiB"))
        assertTrue(instructions.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    }

    @Test
    fun preparingPastedFolderWhileIoIsBusyFailsWithoutTouchingDisk() {
        val base = Files.createTempDirectory("forever-pasted-busy").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        val storage = serviceWithCandidate(external, legacy, "usb-pasted-busy", true, Environment.MEDIA_MOUNTED)
        val removable = storage.snapshot().options.single {
            it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE
        }
        context.getSharedPreferences("forever_functional_client_storage", 0).edit()
            .putString("selected_id", removable.identity.id)
            .putString("selected_label", removable.label)
            .putString("selected_descriptor", removable.descriptor)
            .commit()
        val busy = storage.acquireIoLease() ?: throw AssertionError("expected a free IO lease")
        try {
            assertStorageFailure("storage_busy") {
                storage.preparePastedClientFolder()
            }
        } finally {
            busy.close()
        }
        assertFalse(File(external, "CustomGames").exists())
    }

    @Test
    fun preparingPastedFolderOnPrimaryStorageFailsClearly() {
        val base = Files.createTempDirectory("forever-pasted-primary").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val storage = service(external, File(base, "CustomGames/WoW Forever"))
        assertStorageFailure("storage_paste_requires_removable") {
            storage.preparePastedClientFolder()
        }
    }

    @Test
    fun anUnmountedSelectedUuidStaysSelectedAndNeverFallsBack() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val removable = serviceWithCandidate(external, legacy, "usb-1", true, Environment.MEDIA_MOUNTED)
        val identity = removable.snapshot().options.first { it.identity.id == "volume:usb-1" }.identity
        val oldRoot = removable.snapshot().options.first { it.identity.id == identity.id }.root!!
        removable.select(identity)
        val disconnected = ForeverFunctionalClientStorage.testService(
            context,
            volumes = { emptyList() },
            legacyRoot = { legacy },
        )
        val selected = disconnected.snapshot().selected!!
        assertEquals(identity.id, selected.identity.id)
        assertFalse(selected.available)
        assertEquals("No conectado", selected.route)
        assertTrue(disconnected.isAllowedPriorRoot(oldRoot))
    }

    @Test
    fun sameUuidWithChangedRootFailsRevalidationInsteadOfUsingTheNewRootSilently() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val first = File(base, "first/files").apply { mkdirs() }
        val second = File(base, "second/files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val original = serviceWithCandidate(first, legacy, "usb-2", true, Environment.MEDIA_MOUNTED)
        val identity = original.snapshot().options.first { it.identity.id == "volume:usb-2" }.identity
        original.select(identity)
        val originalRoot = requireNotNull(original.snapshot().selected?.root).canonicalFile
        val changed = serviceWithCandidate(second, legacy, "usb-2", true, Environment.MEDIA_MOUNTED)
        val current = changed.snapshot().selected!!
        var failed = false
        try {
            changed.revalidate(
                ForeverFunctionalClientStorage.ResolvedTarget(identity, originalRoot, currentRevision(changed)),
            )
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            failed = failure.code == "storage_root_changed"
        }
        assertTrue(failed)
        assertTrue(current.root!!.canonicalFile != originalRoot)
    }

    @Test
    fun emptyUpdateDestinationWithOwnerMarkerResolvesWithoutExecutableOrProbes() {
        val base = Files.createTempDirectory("forever-update-empty").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val storage = service(external, legacy)
        storage.select(storage.snapshot().selected!!.identity)

        val target = storage.resolveForUpdate()
        assertEquals(legacy.canonicalFile, target.root)
        assertTrue(target.legacy)
        // The update admission path requires no installed client payload.
        assertFalse(File(target.root, ForeverFunctionalPoc.EXECUTABLE_NAME).exists())
        assertFalse(File(target.root, ForeverFunctionalClientStorage.DATA_DIRECTORY).exists())
        assertFalse(File(target.root, ForeverFunctionalClientStorage.ORIGINAL_WTF).exists())
        assertTrue(File(target.root, ".forever-client-storage").isFile)

        val entries = treeEntries(target.root)
        storage.revalidateForUpdate(target)
        assertEquals(entries, treeEntries(target.root))
        assertNoProbes(base)
    }

    @Test
    fun updateRevalidationUsesASingleFreshSnapshotWithoutCaching() {
        val base = Files.createTempDirectory("forever-update-single-snapshot").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val counting = CountingVolumes(listOf(primaryCandidate(external)))
        val storage = ForeverFunctionalClientStorage.testService(
            context,
            volumes = counting,
            legacyRoot = { legacy },
        )
        storage.select(storage.snapshot().selected!!.identity)

        val before = counting.enumerations
        val target = storage.resolveForUpdate()
        assertEquals(before + 1, counting.enumerations)
        storage.revalidateForUpdate(target)
        assertEquals(before + 2, counting.enumerations)
        // A second revalidation consults the provider again instead of reusing storage.
        storage.revalidateForUpdate(target)
        assertEquals(before + 3, counting.enumerations)
        assertNoProbes(base)
    }

    @Test
    fun updateRevalidationRejectsReselectionRevisionBumpAndDisconnect() {
        val base = Files.createTempDirectory("forever-update-guards").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbBase = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val counting = CountingVolumes(
            listOf(primaryCandidate(primaryBase), usbCandidate(usbBase, "usb-guard", "mount:guard")),
        )
        val storage = ForeverFunctionalClientStorage.testService(
            context,
            volumes = counting,
            legacyRoot = { legacy },
        )
        val removable = storage.snapshot().options
            .single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(removable)

        val beforeSwitch = storage.resolveForUpdate()
        storage.select(storage.snapshot().options
            .single { it.identity.kind == ForeverFunctionalClientStorage.Kind.PRIMARY }.identity)
        assertStorageFailure("storage_selection_changed") { storage.revalidateForUpdate(beforeSwitch) }

        val beforeBump = storage.resolveForUpdate()
        storage.select(beforeBump.identity)
        assertStorageFailure("storage_selection_changed") { storage.revalidateForUpdate(beforeBump) }

        storage.select(removable)
        val beforeDisconnect = storage.resolveForUpdate()
        counting.current = emptyList()
        assertStorageFailure("storage_mount_unproven") { storage.revalidateForUpdate(beforeDisconnect) }
        val selected = storage.snapshot().selected!!
        assertEquals(removable, selected.identity)
        assertFalse(selected.available)
        assertNoProbes(base)
    }

    @Test
    fun updateRevalidationRejectsPathMountAndIdentityReplacement() {
        val base = Files.createTempDirectory("forever-update-replacement").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val firstBase = File(base, "first/files").apply { mkdirs() }
        val secondBase = File(base, "second/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-replace"
        var mount = "mount:replace-1"
        val counting = CountingVolumes(
            listOf(primaryCandidate(primaryBase), usbCandidate(firstBase, uuid, mount)),
        )
        val storage = ForeverFunctionalClientStorage.testService(
            context,
            volumes = counting,
            legacyRoot = { legacy },
        )
        val identity = storage.snapshot().options.single { it.identity.id == "volume:$uuid" }.identity
        storage.select(identity)

        // The same UUID on another path must not be mistaken for the admitted root.
        val beforeMove = storage.resolveForUpdate()
        counting.current = listOf(primaryCandidate(primaryBase), usbCandidate(secondBase, uuid, mount))
        assertStorageFailure("storage_root_changed") { storage.revalidateForUpdate(beforeMove) }

        // The same UUID and path behind another mount must not be reused.
        storage.select(identity)
        val beforeRemount = storage.resolveForUpdate()
        mount = "mount:replace-2"
        counting.current = listOf(primaryCandidate(primaryBase), usbCandidate(secondBase, uuid, mount))
        assertStorageFailure("storage_mount_changed") { storage.revalidateForUpdate(beforeRemount) }

        // A recreated root keeps the path but loses the admitted identity.
        storage.select(identity)
        val beforeRootReplace = storage.resolveForUpdate()
        val admittedRoot = beforeRootReplace.root
        assertTrue(admittedRoot.deleteRecursively())
        assertTrue(admittedRoot.mkdirs())
        assertStorageFailure("storage_root_replaced") { storage.revalidateForUpdate(beforeRootReplace) }

        // A recreated base under the same root invalidates the admitted base identity.
        storage.select(identity)
        val beforeBaseReplace = storage.resolveForUpdate()
        val staged = File(base, "staged-root")
        assertTrue(beforeBaseReplace.root.renameTo(staged))
        assertTrue(secondBase.deleteRecursively())
        secondBase.mkdirs()
        File(secondBase, "CustomGames").mkdirs()
        assertTrue(staged.renameTo(beforeBaseReplace.root))
        assertStorageFailure("storage_base_replaced") { storage.revalidateForUpdate(beforeBaseReplace) }
        assertNoProbes(base)
    }

    @Test
    fun updateResolveRejectsSymlinkRootAndForeignMarkerWithoutSideEffects() {
        val base = Files.createTempDirectory("forever-update-marker").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val storage = service(external, legacy)
        storage.select(storage.snapshot().selected!!.identity)
        val target = storage.resolveForUpdate()
        val marker = File(target.root, ".forever-client-storage")
        assertTrue(marker.isFile)

        File(marker.path).writeText("foreign\n", Charsets.UTF_8)
        assertStorageFailure("storage_marker_invalid") { storage.resolveForUpdate() }
        assertStorageFailure("storage_marker_invalid") { storage.revalidateForUpdate(target) }
        assertArrayEquals("foreign\n".toByteArray(Charsets.UTF_8), marker.readBytes())
        assertNoProbes(base)

        val linkBase = Files.createTempDirectory("forever-update-link").toFile()
        val linkExternal = File(linkBase, "files").apply { mkdirs() }
        val linkLegacy = File(linkBase, "CustomGames/WoW Forever")
        val linkStorage = service(linkExternal, linkLegacy)
        linkStorage.select(linkStorage.snapshot().selected!!.identity)
        val linkTarget = linkStorage.resolveForUpdate()
        val outside = File(linkBase, "outside").apply { mkdirs() }
        File(outside, "keep.bin").writeBytes(byteArrayOf(9, 8, 7))
        assertTrue(linkTarget.root.deleteRecursively())
        Files.createSymbolicLink(linkTarget.root.toPath(), outside.toPath())
        assertStorageFailure("storage_unavailable") { linkStorage.resolveForUpdate() }
        assertStorageFailure("storage_unavailable") { linkStorage.revalidateForUpdate(linkTarget) }
        assertTrue(Files.isSymbolicLink(linkTarget.root.toPath()))
        assertArrayEquals(byteArrayOf(9, 8, 7), File(outside, "keep.bin").readBytes())
        assertNoProbes(linkBase)
    }

    @Test
    fun replacingMountUnderTheSameUuidAndPathRejectsFrozenPreparation() {
        val base = Files.createTempDirectory("forever-storage-remount").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val root = File(external, "CustomGames/WoW Forever").apply { mkdirs() }
        File(root, "WowB-ARM64.exe").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "Data").mkdirs()
        File(root, "WTF").mkdirs()
        File(root, "WTF/BetaSuspendedTest.wtf").writeText("fixture")
        var mount = "mount:1"
        val primary = File(base, "primary/files").apply { mkdirs() }
        val storage = ForeverFunctionalClientStorage.testService(
            context,
            volumes = { listOf(
                ForeverFunctionalClientStorage.VolumeCandidate(primary, null, true, false, Environment.MEDIA_MOUNTED, "Interno"),
                ForeverFunctionalClientStorage.VolumeCandidate(external, "usb-remount", false, true, Environment.MEDIA_MOUNTED, "USB", mount)) },
            legacyRoot = { File(base, "primary/CustomGames/WoW Forever") },
            executableVerifier = ForeverFunctionalClientStorage.ExecutableVerifier { _, _ -> true },
        )
        storage.select(storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity)
        val frozen = storage.resolveForPrepare()
        mount = "mount:2"
        try {
            storage.revalidate(frozen)
            throw AssertionError("replacement mount accepted")
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            assertEquals("storage_mount_changed", failure.code)
        }
    }

    @Test
    fun wrongTypePreferenceFailsClosedInsteadOfFallingBackToPrimary() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        context.getSharedPreferences("forever_functional_client_storage", 0).edit()
            .putInt("selected_id", 4).commit()
        val service = service(external, legacy)
        var failed = false
        try {
            service.snapshot()
        } catch (_: ForeverFunctionalClientStorage.SelectionCorruptException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun wrongTypeRegistryFailsClosedEvenWithoutASelectedId() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        context.getSharedPreferences("forever_functional_client_storage", 0).edit()
            .putString("validated_registry", "not-a-set").commit()
        var failed = false
        try {
            service(external, legacy).snapshot()
        } catch (_: ForeverFunctionalClientStorage.SelectionCorruptException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun cancelledAsyncSelectionCannotCommitAndProbeIsCleanedUp() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val service = service(external, legacy)
        val id = service.snapshot().selected!!.identity
        var failed = false
        try {
            service.select(id) { false }
        } catch (_: ForeverFunctionalClientStorage.SelectionCancelledException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(context.getSharedPreferences("forever_functional_client_storage", 0).contains("selected_id"))
        assertTrue(base.walkTopDown().none { it.name.startsWith(".forever-probe-") })
    }

    @Test
    fun selectionIsBlockedByProcessIoAndGuestLeases() {
        ForeverFunctionalClientStorage.ProcessGuard.resetForTests()
        val io = ForeverFunctionalClientStorage.ProcessGuard.acquireIo()!!
        assertNull(ForeverFunctionalClientStorage.ProcessGuard.acquireSelection())
        val guest = ForeverFunctionalClientStorage.ProcessGuard.reserveGuest(io)!!
        io.close()
        assertNull(ForeverFunctionalClientStorage.ProcessGuard.acquireSelection())
        ForeverFunctionalClientStorage.ProcessGuard.releaseGuest(guest)
        val selection = ForeverFunctionalClientStorage.ProcessGuard.acquireSelection()!!
        selection.close()
        ForeverFunctionalClientStorage.ProcessGuard.resetForTests()
    }

    @Test
    fun symlinkAndForeignMarkerAreRejected() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        File(legacy, ".forever-client-storage").writeText("foreign\n")
        val service = service(external, legacy)
        var markerRejected = false
        try {
            service.select(service.snapshot().selected!!.identity)
        } catch (_: ForeverFunctionalClientStorage.StorageException) {
            markerRejected = true
        }
        assertTrue(markerRejected)

        val link = File(legacy, "link")
        runCatching { Files.createSymbolicLink(link.toPath(), File(base, "outside").toPath()) }
        assertTrue(Files.isSymbolicLink(link.toPath()) || !link.exists())
    }

    @Test
    fun dataAndOriginalWtfLinksAreRejectedWithoutWalkingData() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        Files.createSymbolicLink(File(legacy, "Data").toPath(), File(base, "outside-data").toPath())
        val service = service(external, legacy)
        var rejected = false
        try {
            service.select(service.snapshot().selected!!.identity)
        } catch (_: ForeverFunctionalClientStorage.StorageException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun nestedLinksAreRejectedDuringPreparation() {
        assertNestedTreeRejected("nested-file-link") { root, base ->
            val outside = File(base, "outside-file").apply { writeText("outside") }
            Files.createSymbolicLink(File(root, "Data/nested-file-link").toPath(), outside.toPath())
        }
        assertNestedTreeRejected("nested-directory-link") { root, base ->
            val outside = File(base, "outside-directory").apply { mkdirs() }
            Files.createSymbolicLink(File(root, "Data/nested-directory-link").toPath(), outside.toPath())
        }
        assertNestedTreeRejected("dangling-link") { root, base ->
            Files.createSymbolicLink(
                File(root, "WTF/dangling-link").toPath(),
                File(base, "does-not-exist").toPath(),
            )
        }
    }

    @Test
    fun nestedSpecialNodeIsRejectedDuringPreparation() {
        val base = Files.createTempDirectory("forever-storage-special").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, "special")
        val dataPath = File(root, "Data").toPath()
        val fifoPath = dataPath.resolve("special-fifo")
        val mkfifo = File("/usr/bin/mkfifo")
        assertTrue(Files.isDirectory(dataPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue("/usr/bin/mkfifo is required for this Linux special-file fixture",
            mkfifo.isFile && mkfifo.canExecute())
        assertFalse(Files.exists(fifoPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        try {
            val process = ProcessBuilder(mkfifo.absolutePath, fifoPath.toString())
                .redirectErrorStream(true)
                .start()
            val exitCode = try {
                process.waitFor()
            } finally {
                process.destroy()
            }
            assertEquals("mkfifo failed", 0, exitCode)
            assertTrue(Files.exists(fifoPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.isRegularFile(fifoPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertStorageFailure("storage_tree_invalid") {
                tinyFixtureService(external, root).resolveForPrepare()
            }
        } finally {
            Files.deleteIfExists(fifoPath)
        }
        assertFalse(Files.exists(fifoPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun nestedLinkAddedAfterResolveIsRejectedByRevalidation() {
        val base = Files.createTempDirectory("forever-storage-revalidation-tree").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, "revalidation")
        val service = tinyFixtureService(external, root)
        val target = service.resolveForPrepare()
        Files.createSymbolicLink(
            File(root, "Data/late-link").toPath(),
            File(base, "outside-late").apply { writeText("outside") }.toPath(),
        )
        assertStorageFailure("storage_tree_invalid") { service.revalidate(target) }
    }

    @Test
    fun intermediateRevalidationSkipsTreeTraversalButKeepsFrozenGuards() {
        val base = Files.createTempDirectory("forever-storage-prepared-gate").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, "prepared")
        val service = tinyFixtureService(external, root)
        val target = service.resolveForPrepare()
        // A late symlink deep in Data: the full gate still walks the tree and
        // rejects it, while the same-operation intermediate gate passes.
        val outside = File(base, "outside").apply { writeText("outside") }
        val link = File(root, "Data/late-link").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        try {
            assertStorageFailure("storage_tree_invalid") { service.revalidate(target) }
            service.revalidatePrepared(target)
        } finally {
            Files.deleteIfExists(link)
        }
        service.revalidatePrepared(target)
        service.revalidate(target)
        // A replaced executable is still rejected by the intermediate gate.
        File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).appendBytes("replaced".toByteArray())
        assertStorageFailure("client_executable_replaced") { service.revalidatePrepared(target) }
        assertStorageFailure("client_executable_replaced") { service.revalidate(target) }
    }

    @Test
    fun derivedConfigEqualWritesAreSkippedWithoutTouchingBytes() {
        val gameFolder = Files.createTempDirectory("forever-derived-reuse").toFile()
        File(gameFolder, "WTF").mkdirs()
        val config = FunctionalServerProfileConfig(
            profileId = FunctionalServerProfile.CLASSIC_FOREVER.id,
            portalHost = "test.invalid",
            memoryPatchEnabled = false,
            patchContractId = "test-contract",
            compatibleBuild = 70009,
        )
        val originalText = "SET portal \"test.invalid\"\n"
        val expected = ForeverFunctionalServerProfiles.applyPocOverrides(
            ForeverFunctionalServerProfiles.deriveConfig(originalText, config),
            null,
        )
        val fileName = FunctionalServerProfile.CLASSIC_FOREVER.configFileName
        val (first, reusedFirst) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, expected)
        assertFalse(reusedFirst)
        assertTrue(first.isFile)
        val written = first.readBytes()
        val (second, reusedSecond) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, expected)
        assertTrue(reusedSecond)
        assertEquals(first, second)
        assertArrayEquals(written, second.readBytes())
        // Changed content is still written.
        val altered = expected + "SET Sound_MasterVolume \"0.04\"\n"
        val (third, reusedThird) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, altered)
        assertFalse(reusedThird)
        assertArrayEquals(altered.toByteArray(Charsets.UTF_8), third.readBytes())
        // Invalid UTF-8 that decodes (with replacement) to the expected text
        // must not compare equal: bytes rule, never decoded text.
        val replacement = "�"
        val (fourth, reusedFourth) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, replacement)
        assertFalse(reusedFourth)
        assertArrayEquals(replacement.toByteArray(Charsets.UTF_8), fourth.readBytes())
        // A lone lead byte decodes to the same replacement char but is not
        // the same content: it must be repaired, not reused.
        File(gameFolder, "WTF/$fileName").writeBytes(byteArrayOf(0xC3.toByte()))
        val (fifth, reusedFifth) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, replacement)
        assertFalse(reusedFifth)
        assertArrayEquals(replacement.toByteArray(Charsets.UTF_8), fifth.readBytes())
        // A hostile oversized file is only probed up to the expected size,
        // never read fully into memory.
        File(gameFolder, "WTF/$fileName").writeBytes(ByteArray(1024 * 1024) { 'x'.code.toByte() })
        val (sixth, reusedSixth) = ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, expected)
        assertFalse(reusedSixth)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), sixth.readBytes())
        // A symlinked target is refused, never followed.
        assertTrue(third.delete())
        val outside = File(gameFolder, "outside-target.wtf").apply { writeText("foreign") }
        Files.createSymbolicLink(third.toPath(), outside.toPath())
        try {
            var refused = false
            try {
                ForeverFunctionalPoc.writeDerivedConfigIfUnchanged(gameFolder, fileName, expected)
            } catch (_: IllegalArgumentException) {
                refused = true
            }
            assertTrue(refused)
            assertEquals("foreign", outside.readText())
        } finally {
            Files.deleteIfExists(third.toPath())
        }
    }

    @Test
    fun deeplyNestedRegularTreeFailsAtThePreparationBound() {
        val base = Files.createTempDirectory("forever-storage-depth").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, "depth")
        var current = File(root, "Data")
        repeat(64) {
            current = File(current, "d$it").apply { mkdirs() }
        }
        assertStorageFailure("storage_tree_too_deep") {
            tinyFixtureService(external, root).resolveForPrepare()
        }
    }

    @Test
    fun primaryToRemovableToPrimaryUsesPersistentSelectionAndRealContainerHandoff() {
        val base = Files.createTempDirectory("forever-storage-e2e").toFile()
        val primaryFiles = File(base, "primary/files").apply { mkdirs() }
        val removableFiles = File(base, "removable/files").apply { mkdirs() }
        val rootA = File(base, "primary/CustomGames/WoW Forever")
        val rootB = File(removableFiles, "CustomGames/WoW Forever")
        writeTinyClient(rootA, "A")
        writeTinyClient(rootB, "B")
        val bytesA = snapshotClientBytes(rootA)
        val bytesB = snapshotClientBytes(rootB)
        val volumes = {
            listOf(
                ForeverFunctionalClientStorage.VolumeCandidate(
                    primaryFiles, null, true, false, Environment.MEDIA_MOUNTED, "Interno",
                ),
                ForeverFunctionalClientStorage.VolumeCandidate(
                    removableFiles, "usb-e2e", false, true, Environment.MEDIA_MOUNTED,
                    "USB", "mount:e2e",
                ),
            )
        }
        val service = e2eService(volumes, rootA)
        val imageFs = ImageFs.find(context)
        val containerDir = File(imageFs.rootDir, "home/${ImageFs.USER}-${ForeverFunctionalPoc.APP_ID}")
        containerDir.deleteRecursively()
        containerDir.mkdirs()
        try {
            Container(ForeverFunctionalPoc.APP_ID).apply {
                setRootDir(containerDir)
                setDrives("D:/preservedE:/other")
                saveData()
            }

            val optionA = service.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.PRIMARY }
            val optionB = service.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
            service.select(optionA.identity)
            val targetA = service.resolveForPrepare()
            service.prepareContainer(targetA)
            assertEquals("A:${rootA.canonicalPath}D:/preservedE:/other", ContainerUtils.getContainer(context, ForeverFunctionalPoc.APP_ID).drives)

            service.select(optionB.identity)
            assertStorageFailure("storage_selection_changed") { service.revalidate(targetA) }
            val recreatedOnB = e2eService(volumes, rootA)
            assertEquals(optionB.identity, recreatedOnB.snapshot().selected!!.identity)
            val targetB = recreatedOnB.resolveForPrepare()
            // The previously mapped A: is a registered prior target, not a reason
            // to reject B or silently restore the primary destination.
            recreatedOnB.prepareContainer(targetB)
            assertEquals("A:${rootB.canonicalPath}D:/preservedE:/other", ContainerUtils.getContainer(context, ForeverFunctionalPoc.APP_ID).drives)
            val profileB = ForeverFunctionalServerProfiles.resolve(
                context, ForeverFunctionalServerProfiles.defaultSettings(),
            )
            val derivedB = ForeverFunctionalServerProfiles.writeDerivedConfig(
                targetB.root, File(targetB.root, ForeverFunctionalClientStorage.ORIGINAL_WTF).readText(), profileB,
            )
            assertEquals(File(rootB, "WTF").canonicalFile, derivedB.parentFile!!.canonicalFile)

            recreatedOnB.select(optionA.identity)
            assertStorageFailure("storage_selection_changed") { recreatedOnB.revalidate(targetB) }
            val targetAAgain = recreatedOnB.resolveForPrepare()
            recreatedOnB.prepareContainer(targetAAgain)
            val reloaded = ContainerUtils.getContainer(context, ForeverFunctionalPoc.APP_ID)
            assertEquals("A:${rootA.canonicalPath}D:/preservedE:/other", reloaded.drives)

            val originalWtf = File(rootA, ForeverFunctionalClientStorage.ORIGINAL_WTF).readBytes()
            val profile = ForeverFunctionalServerProfiles.resolve(
                context,
                ForeverFunctionalServerProfiles.defaultSettings(),
            )
            val derived = ForeverFunctionalServerProfiles.writeDerivedConfig(
                rootA,
                originalWtf.toString(Charsets.UTF_8),
                profile,
            )
            assertEquals(File(rootA, "WTF").canonicalFile, derived.parentFile!!.canonicalFile)
            assertArrayEquals(originalWtf, File(rootA, ForeverFunctionalClientStorage.ORIGINAL_WTF).readBytes())
            assertClientBytesUnchanged(rootA, bytesA)
            assertClientBytesUnchanged(rootB, bytesB)
        } finally {
            containerDir.deleteRecursively()
        }
    }

    @Test
    fun emptyThenDisconnectedRemovableSelectionStaysSelectedWithoutFallback() {
        val base = Files.createTempDirectory("forever-storage-no-fallback").toFile()
        val primaryFiles = File(base, "primary/files").apply { mkdirs() }
        val removableFiles = File(base, "removable/files").apply { mkdirs() }
        val rootA = File(base, "primary/CustomGames/WoW Forever")
        writeTinyClient(rootA, "A")
        val aBefore = snapshotClientBytes(rootA)
        val volumes = {
            listOf(
                ForeverFunctionalClientStorage.VolumeCandidate(
                    primaryFiles, null, true, false, Environment.MEDIA_MOUNTED, "Interno",
                ),
                ForeverFunctionalClientStorage.VolumeCandidate(
                    removableFiles, "usb-empty-e2e", false, true, Environment.MEDIA_MOUNTED,
                    "USB", "mount:empty-e2e",
                ),
            )
        }
        val service = e2eService(volumes, rootA)
        val removable = service.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
        service.select(removable.identity)
        assertStorageFailure("client_not_installed") { service.resolveForPrepare() }

        val disconnected = ForeverFunctionalClientStorage.testService(
            context,
            volumes = { listOf(volumes().first()) },
            legacyRoot = { rootA },
            executableVerifier = ForeverFunctionalClientStorage.ExecutableVerifier { _, _ -> true },
        )
        val selected = disconnected.snapshot().selected!!
        assertEquals(removable.identity, selected.identity)
        assertFalse(selected.available)
        assertNull(selected.root)
        assertStorageFailure("storage_mount_unproven") { disconnected.resolveForPrepare() }
        assertClientBytesUnchanged(rootA, aBefore)
        assertFalse(File(rootA, "WTF/BetaFunctionalPoc-original.wtf").exists())
    }

    @Test
    fun driveReplacementPreservesDirectContainerSerializationAndRejectsDuplicateA() {
        assertEquals("A:/newB:/bC:/cD:/d", ForeverFunctionalClientStorage.replaceDriveA("B:/bC:/cD:/d", "/new"))
        assertEquals("A:/newB:/bC:/cD:/d", ForeverFunctionalClientStorage.replaceDriveA("A:/oldB:/bC:/cD:/d", "/new"))
        var rejected = false
        try {
            ForeverFunctionalClientStorage.replaceDriveA("A:/oneA:/two", "/new")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun explicitInternalVerifierCanProveAPositiveFixtureWithoutReplacingProductionPin() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        File(legacy, "WowB-ARM64.exe").writeBytes(byteArrayOf(1, 2, 3))
        File(legacy, "WTF").mkdirs()
        File(legacy, "WTF/BetaSuspendedTest.wtf").writeText("fixture")
        File(legacy, "Data").mkdirs()
        val service = ForeverFunctionalClientStorage.testService(
            context,
            volumes = { listOf(ForeverFunctionalClientStorage.VolumeCandidate(external, null, true, false, Environment.MEDIA_MOUNTED, "Interno")) },
            legacyRoot = { legacy },
            executableVerifier = ForeverFunctionalClientStorage.ExecutableVerifier { _, _ -> true },
        )
        assertEquals(legacy.canonicalFile, service.resolveForPrepare().root)
    }

    @Test
    fun cancellationAfterCommitReportsCommittedStateAndCannotRollbackSelection() {
        val base = Files.createTempDirectory("forever-storage").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val service = service(external, legacy)
        val request = service.beginSelection(service.snapshot().selected!!.identity)
        request.commit()
        assertEquals(ForeverFunctionalClientStorage.SelectionRequest.State.COMMITTED, request.state)
        assertFalse(request.cancel())
        assertTrue(context.getSharedPreferences("forever_functional_client_storage", 0).contains("selected_id"))
        request.close()
    }

    @Test
    fun invalidExecutableMetadataDoesNotPassAnInjectedVerifierWithoutProductionPin() {
        val file = Files.createTempFile("wowb", ".exe").toFile()
        file.outputStream().use { output ->
            output.write(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()))
        }
        java.io.RandomAccessFile(file, "rw").use { it.setLength(ForeverFunctionalClientStorage.EXECUTABLE_SIZE) }
        assertFalse(ForeverFunctionalClientStorage.PinnedExecutableVerifier.verify(file, true))
    }

    // Managed-build admission for launch recognition. These fixtures carry a
    // synthetic admitted build (never the legacy 70009 pin): the updater's
    // commit-time proof stands in for the on-device 70450 admission, and the
    // launch gate re-proves it without any CASC scan or loose-file sweep.

    private fun fakeManagedPe(size: Int = 128): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3c] = 0x40
        bytes[0x40] = 'P'.code.toByte()
        bytes[0x41] = 'E'.code.toByte()
        bytes[0x44] = 0x64
        bytes[0x45] = 0xAA.toByte()
        return bytes
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun managedTargetJson(exeSize: Long, exeMd5: String): String = JSONObject()
        .put("schema", "wowforever.client-target")
        .put("schema_version", 1)
        .put("product", "wow_classic_beta")
        .put("region", "us")
        .put("platform", "Windows")
        .put("architecture", "arm64")
        .put("locale", "enUS")
        .put("version", "1.60.1.70450")
        .put("build_id", 70450L)
        .put("launcher_version_code", 1)
        .put("launcher_version_name", "managed-test")
        .put("artifact_subdir", "client-updater-packaged-catalog/70450")
        .put("build_config", JSONObject().put("hash", "aa".repeat(16)).put("bytes", 100).put("sha256", "cc".repeat(32)))
        .put("cdn_config", JSONObject().put("hash", "bb".repeat(16)).put("bytes", 100).put("sha256", "dd".repeat(32)))
        .put("catalog", JSONObject().put("bytes", 1000).put("sha256", "ee".repeat(32)).put("gzip_bytes", 500).put("gzip_sha256", "ff".repeat(32)))
        .put("executable", JSONObject().put("path", "WowB-ARM64.exe").put("decoded_bytes", exeSize).put("content_key", exeMd5).put("expected_machine", 43620))
        .put("server_version_source", "not-confirmed")
        .put("native_validation", JSONObject().put("status", "pending").put("evidence_sha256", JSONObject.NULL))
        .toString()

    private fun managedBuildInfo(): ByteArray = (
        "Branch!STRING:0|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0|Tags!STRING:0\n" +
            "us|${"aa".repeat(16)}|${"bb".repeat(16)}|1.60.1.70450|wow_classic_beta|Windows arm64 enUS\n"
        ).toByteArray(Charsets.UTF_8)

    private fun managedLooseList(exeSize: Long, exeMd5: String): List<ForeverClientUpdateService.ManagedAdmissionLooseFile> = listOf(
        ForeverClientUpdateService.ManagedAdmissionLooseFile("WowB-ARM64.exe", exeMd5, exeSize),
        ForeverClientUpdateService.ManagedAdmissionLooseFile("Utils/notes.dat", "ab".repeat(16), 10),
    )

    private fun writeManagedAdmission(
        storageId: String,
        storageIdentity: String,
        rootPath: String,
        targetJson: String,
        buildInfo: ByteArray,
        loose: List<ForeverClientUpdateService.ManagedAdmissionLooseFile>,
    ): File {
        // Envelope encoding is the updater's own writer; the fixture only
        // places the resulting bytes under the derived record name.
        val envelope = ForeverClientUpdateService.encodeManagedInstallAdmissionRecord(
            targetJson.toByteArray(Charsets.UTF_8),
            "ee".repeat(32),
            storageId,
            storageIdentity,
            rootPath,
            buildInfo,
            loose,
        )
        val directory = File(context.noBackupFilesDir.canonicalFile, "forever-managed-install-admissions").apply { mkdirs() }
        val file = File(directory, "managed-install-admission-" + sha256Hex(storageId.toByteArray(Charsets.UTF_8)) + ".json")
        file.writeBytes(envelope)
        return file
    }

    private data class ManagedLaunchFixture(
        val service: ForeverFunctionalClientStorage.Service,
        val root: File,
        val record: File,
        val exe: ByteArray,
        val buildInfo: ByteArray,
    )

    private fun managedLaunchFixture(label: String, withWtf: Boolean): ManagedLaunchFixture {
        val base = Files.createTempDirectory("forever-managed-$label").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val exe = fakeManagedPe()
        File(legacy, "WowB-ARM64.exe").writeBytes(exe)
        File(legacy, "Data").mkdirs()
        if (withWtf) {
            File(legacy, "WTF").mkdirs()
            File(legacy, "WTF/BetaSuspendedTest.wtf").writeText("SET portal \"test.invalid\"\n")
        }
        val buildInfo = managedBuildInfo()
        File(legacy, ".build.info").writeBytes(buildInfo)
        val service = service(external, legacy)
        service.select(service.snapshot().selected!!.identity)
        val update = service.resolveForUpdate()
        val storageId = service.operationStorageId(update)
        val record = writeManagedAdmission(
            storageId, update.identity.id, ForeverClientUpdateService.stableRootPath(update.root),
            managedTargetJson(exe.size.toLong(), md5Hex(exe)),
            buildInfo, loose = managedLooseList(exe.size.toLong(), md5Hex(exe)),
        )
        return ManagedLaunchFixture(service, legacy, record, exe, buildInfo)
    }

    @Test
    fun managedAdmissionGovernsPrepareAndRevalidationWhenLegacyPinDiffers() {
        val fixture = managedLaunchFixture("success", withWtf = true)
        try {
            val target = fixture.service.resolveForPrepare()
            assertEquals(fixture.root.canonicalFile, target.root)
            fixture.service.revalidate(target)
        } finally {
            fixture.record.delete()
        }
    }

    @Test
    fun managedAdmissionRejectsTamperedRecordReplacedExecutableAndArbitraryBuildInfo() {
        // Tampered envelope: any byte change breaks the payload checksum.
        run {
            val fixture = managedLaunchFixture("tamper", withWtf = true)
            try {
                val bytes = fixture.record.readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
                fixture.record.writeBytes(bytes)
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                fixture.record.delete()
            }
        }
        // Executable replaced after admission: frozen revalidation reports
        // the replacement, and a fresh resolve re-proves the content.
        run {
            val fixture = managedLaunchFixture("replaced", withWtf = true)
            try {
                val target = fixture.service.resolveForPrepare()
                File(fixture.root, "WowB-ARM64.exe").writeBytes(fakeManagedPe(160))
                assertStorageFailure("client_executable_replaced") { fixture.service.revalidate(target) }
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                fixture.record.delete()
            }
        }
        // Arbitrary .build.info bytes never match the admitted fingerprint.
        run {
            val fixture = managedLaunchFixture("buildinfo", withWtf = true)
            try {
                val other = ("Branch!STRING:0|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0|Tags!STRING:0\n" +
                    "us|${"cc".repeat(16)}|${"bb".repeat(16)}|1.60.1.70450|wow_classic_beta|Windows arm64 enUS\n")
                    .toByteArray(Charsets.UTF_8)
                File(fixture.root, ".build.info").writeBytes(other)
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                fixture.record.delete()
            }
        }
        // A record whose bytes match but never belonged to the admitted
        // target cannot be produced by the updater's own writer: encoding
        // enforces the semantic build bind at commit, so the launch gate
        // only ever meets well-formed admissions (covered above) or none.
    }

    @Test
    fun managedAdmissionForAnotherDestinationNeverGovernsThisRoot() {
        val fixture = managedLaunchFixture("foreign", withWtf = true)
        try {
            fixture.record.delete()
            val rebound = writeManagedAdmission(
                "storage-" + "00".repeat(32),
                "volume:foreign",
                ForeverClientUpdateService.stableRootPath(fixture.root),
                managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
                fixture.buildInfo,
                loose = managedLooseList(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            )
            try {
                // A record bound to another destination is not even readable
                // here, and nothing links it: unavailable, never governed.
                assertStorageFailure("client_admission_unavailable") { fixture.service.resolveForPrepare() }
            } finally {
                rebound.delete()
            }
        } finally {
            fixture.record.delete()
        }
    }

    @Test
    fun missingAdmissionKeepsRejectionDistinctFromCorruptExecutable() {
        val base = Files.createTempDirectory("forever-managed-noadmission").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        File(legacy, "WowB-ARM64.exe").writeBytes(fakeManagedPe())
        File(legacy, "Data").mkdirs()
        File(legacy, "WTF").mkdirs()
        File(legacy, "WTF/BetaSuspendedTest.wtf").writeText("SET portal \"test.invalid\"\n")
        val service = service(external, legacy)
        service.select(service.snapshot().selected!!.identity)
        // Present, well-formed ARM64 executable, but no admission binds it:
        // size alone never governs, and the missing admission is reported as
        // unavailable rather than mistaken for a corrupt executable.
        assertStorageFailure("client_admission_unavailable") { service.resolveForPrepare() }
    }

    @Test
    fun missingOriginalConfigStillReportsIncompleteAtStorageLayer() {
        val fixture = managedLaunchFixture("nowtf", withWtf = false)
        try {
            assertStorageFailure("client_incomplete") { fixture.service.resolveForPrepare() }
        } finally {
            fixture.record.delete()
        }
    }

    @Test
    fun deletedAdmissionFailsRevalidationClosed() {
        val fixture = managedLaunchFixture("gone", withWtf = true)
        try {
            val target = fixture.service.resolveForPrepare()
            fixture.record.delete()
            assertStorageFailure("client_executable_invalid") { fixture.service.revalidate(target) }
        } finally {
            fixture.record.delete()
        }
    }

    // Stable identity across filesystem key rotation, plus the bounded legacy
    // migration. Rotation is simulated honestly on the real test filesystem:
    // directories are deleted and recreated with byte-identical content at
    // the same paths, which yields genuinely different fileKeys, exactly like
    // a FAT/FUSE inode-cache rotation without any replacement.

    private data class UsbManagedFixture(
        val service: ForeverFunctionalClientStorage.Service,
        val base: File,
        val external: File,
        val root: File,
        val exe: ByteArray,
        val buildInfo: ByteArray,
    )

    private fun managedUsbFixture(
        label: String,
        withWtf: Boolean,
        gate: (() -> String?)? = { null },
    ): UsbManagedFixture {
        val base = Files.createTempDirectory("forever-managed-usb-$label").toFile()
        val external = File(base, "usb/files").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        val service = serviceWithCandidate(external, legacy, "usb-$label", true, Environment.MEDIA_MOUNTED, gate)
        val identity = service.snapshot().options.single {
            it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE && it.available
        }.identity
        service.select(identity)
        val root = File(external, "CustomGames/WoW Forever")
        val exe = fakeManagedPe()
        File(root, "WowB-ARM64.exe").writeBytes(exe)
        File(root, "Data").mkdirs()
        if (withWtf) {
            File(root, "WTF").mkdirs()
            File(root, "WTF/BetaSuspendedTest.wtf").writeText("SET portal \"test.invalid\"\n")
        }
        val buildInfo = managedBuildInfo()
        File(root, ".build.info").writeBytes(buildInfo)
        return UsbManagedFixture(service, base, external, root, exe, buildInfo)
    }

    private fun managedUsbLoose(
        root: File,
        exe: ByteArray,
        extra: Map<String, ByteArray>,
    ): List<ForeverClientUpdateService.ManagedAdmissionLooseFile> =
        listOf(ForeverClientUpdateService.ManagedAdmissionLooseFile("WowB-ARM64.exe", md5Hex(exe), exe.size.toLong())) +
            extra.map { (path, bytes) ->
                File(root, path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
                ForeverClientUpdateService.ManagedAdmissionLooseFile(path, md5Hex(bytes), bytes.size.toLong())
            }

    private fun currentFileKey(file: File): String =
        Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .fileKey().toString()

    private fun snapshotTree(root: File): Map<String, ByteArray?> {
        val result = LinkedHashMap<String, ByteArray?>()
        root.walkTopDown().forEach { file ->
            if (file == root) return@forEach
            val relative = root.toPath().relativize(file.toPath()).toString()
            result[relative] = if (file.isFile) file.readBytes() else null
        }
        return result
    }

    private fun restoreTree(root: File, snapshot: Map<String, ByteArray?>) {
        root.deleteRecursively()
        root.mkdirs()
        snapshot.forEach { (relative, bytes) ->
            val target = File(root, relative)
            if (bytes == null) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
    }

    private fun admissionAnchor(): File = context.noBackupFilesDir.canonicalFile

    private fun admissionFileFor(storageId: String): File = File(
        File(admissionAnchor(), "forever-managed-install-admissions"),
        "managed-install-admission-" + sha256Hex(storageId.toByteArray(Charsets.UTF_8)) + ".json",
    )

    private fun legacyV1Envelope(
        storageId: String,
        rootIdentity: String,
        targetJson: String,
        buildInfo: ByteArray,
        loose: List<ForeverClientUpdateService.ManagedAdmissionLooseFile>,
    ): ByteArray {
        val payload = JSONObject()
            .put("schema", 1)
            .put("storage_id", storageId)
            .put("root_identity", rootIdentity)
            .put("target_json", targetJson)
            .put("catalog_sha256", "ee".repeat(32))
            .put("build_info_bytes", buildInfo.size)
            .put("build_info_sha256", sha256Hex(buildInfo))
            .put("loose_files", org.json.JSONArray().also { array ->
                loose.forEach { file ->
                    array.put(JSONObject()
                        .put("path", file.path)
                        .put("content_key", file.contentKey)
                        .put("decoded_bytes", file.decodedBytes))
                }
            })
            .toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("payload", payload)
            .put("sha256", sha256Hex(payloadBytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    @Test
    fun stableStorageIdSurvivesRootAndBaseKeyRotationAcrossRuns() {
        val fixture = managedUsbFixture("stable", withWtf = true)
        val notes = ByteArray(10) { it.toByte() }
        val loose = managedUsbLoose(fixture.root, fixture.exe, mapOf("Utils/notes.dat" to notes))
        val update = fixture.service.resolveForUpdate()
        val stableId = fixture.service.operationStorageId(update)
        val rootKeyBefore = currentFileKey(fixture.root)
        val baseKeyBefore = currentFileKey(fixture.external)
        val record = writeManagedAdmission(
            stableId, update.identity.id, ForeverClientUpdateService.stableRootPath(update.root),
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose = loose,
        )
        try {
            val prepared = fixture.service.resolveForPrepare()
            assertEquals(fixture.root.canonicalFile, prepared.root)
            // Rotate both the client root and its app-namespace base: same
            // canonical paths, genuinely different filesystem keys.
            restoreTree(fixture.root, snapshotTree(fixture.root))
            restoreTree(fixture.external, snapshotTree(fixture.external))
            val rootKeyAfter = currentFileKey(fixture.root)
            val baseKeyAfter = currentFileKey(fixture.external)
            assertNotEquals("root fileKey must rotate", rootKeyBefore, rootKeyAfter)
            assertNotEquals("base fileKey must rotate", baseKeyBefore, baseKeyAfter)
            // A new run derives the same stable ID and still resolves: no
            // re-download, no new admission, no full scan.
            val rerun = serviceWithCandidate(
                fixture.external, File(fixture.base, "primary/CustomGames/WoW Forever"),
                "usb-stable", true, Environment.MEDIA_MOUNTED, { null },
            )
            // A prior container mapping remains an authorized location even
            // before resolving/remembering its new transient filesystem key.
            assertTrue(rerun.isAllowedPriorRoot(fixture.root))
            val rerunUpdate = rerun.resolveForUpdate()
            assertEquals(stableId, rerun.operationStorageId(rerunUpdate))
            val rerunTarget = rerun.resolveForPrepare()
            assertEquals(fixture.root.canonicalFile, rerunTarget.root)
            rerun.revalidate(rerunTarget)
        } finally {
            record.delete()
        }
    }

    @Test
    fun legacyFileKeyBoundAdmissionMigratesAfterRotationAndPreservesLegacyRecord() {
        val fixture = managedUsbFixture("migrate", withWtf = true)
        val notes = ByteArray(10) { (it * 7).toByte() }
        val loose = managedUsbLoose(fixture.root, fixture.exe, mapOf("Utils/notes.dat" to notes))
        val update = fixture.service.resolveForUpdate()
        val stableId = fixture.service.operationStorageId(update)
        val rootKeyBefore = currentFileKey(fixture.root)
        val baseKeyBefore = currentFileKey(fixture.external)
        // The legacy record binds the previous run's transient identities: a
        // storage id that no longer derives plus the registered root fileKey.
        val legacyStorageId = "storage-" + "aa".repeat(32)
        assertTrue(stableId != legacyStorageId)
        val legacyBytes = legacyV1Envelope(
            legacyStorageId, rootKeyBefore,
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose,
        )
        val legacyFile = admissionFileFor(legacyStorageId).apply { parentFile?.mkdirs(); writeBytes(legacyBytes) }
        val journalDir = File(admissionAnchor(), "forever-client-update-journal").apply { mkdirs() }
        val sentinel = File(journalDir, "migration-test-sentinel").apply { writeText("pending") }
        try {
            restoreTree(fixture.root, snapshotTree(fixture.root))
            restoreTree(fixture.external, snapshotTree(fixture.external))
            assertNotEquals("root fileKey must rotate", rootKeyBefore, currentFileKey(fixture.root))
            assertNotEquals("base fileKey must rotate", baseKeyBefore, currentFileKey(fixture.external))
            // The Data tree stays empty: migration proves only the admitted
            // loose files and .build.info, never a CASC scan.
            assertTrue(File(fixture.root, "Data").listFiles()!!.isEmpty())
            val rerun = serviceWithCandidate(
                fixture.external, File(fixture.base, "primary/CustomGames/WoW Forever"),
                "usb-migrate", true, Environment.MEDIA_MOUNTED, { null },
            )
            val target = rerun.resolveForPrepare()
            assertEquals(fixture.root.canonicalFile, target.root)
            rerun.revalidate(target)
            // The versioned record now exists under the stable id while the
            // legacy record and any unrelated pending state are untouched.
            val migrated = admissionFileFor(stableId)
            assertTrue(migrated.isFile)
            val reread = ForeverClientUpdateService.readManagedInstallAdmission(
                admissionAnchor(), stableId, target.identity.id,
                ForeverClientUpdateService.stableRootPath(target.root),
            )
            assertNotNull(reread)
            assertArrayEquals(legacyBytes, legacyFile.readBytes())
            assertEquals("pending", sentinel.readText())
            // The migrated record stands alone: removing the legacy file
            // keeps the destination launchable.
            legacyFile.delete()
            val standalone = serviceWithCandidate(
                fixture.external, File(fixture.base, "primary/CustomGames/WoW Forever"),
                "usb-migrate", true, Environment.MEDIA_MOUNTED, { null },
            )
            standalone.revalidate(standalone.resolveForPrepare())
        } finally {
            legacyFile.delete()
            admissionFileFor(stableId).delete()
            sentinel.delete()
            if (journalDir.listFiles()?.isEmpty() == true) journalDir.delete()
        }
    }

    @Test
    fun legacyMigrationRejectsForeignAmbiguousAlteredAndCorruptRecords() {
        // Foreign link: a legacy record for another destination never links.
        run {
            val fixture = managedUsbFixture("reject-foreign", withWtf = true)
            val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
            val foreignBytes = legacyV1Envelope(
                "storage-" + "bb".repeat(32), "foreign-root-identity",
                managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
                fixture.buildInfo, loose,
            )
            val foreignFile = admissionFileFor("storage-" + "bb".repeat(32))
                .apply { parentFile?.mkdirs(); writeBytes(foreignBytes) }
            try {
                assertStorageFailure("client_admission_unavailable") { fixture.service.resolveForPrepare() }
            } finally {
                foreignFile.delete()
            }
        }
        // Ambiguous links: two legacy records claim the same registered key.
        run {
            val fixture = managedUsbFixture("reject-ambiguous", withWtf = true)
            val rootKey = currentFileKey(fixture.root)
            val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
            val targetJson = managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe))
            val first = admissionFileFor("storage-" + "cc".repeat(32))
                .apply { parentFile?.mkdirs(); writeBytes(legacyV1Envelope("storage-" + "cc".repeat(32), rootKey, targetJson, fixture.buildInfo, loose)) }
            val second = admissionFileFor("storage-" + "dd".repeat(32))
                .apply { parentFile?.mkdirs(); writeBytes(legacyV1Envelope("storage-" + "dd".repeat(32), rootKey, targetJson, fixture.buildInfo, loose)) }
            try {
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                first.delete()
                second.delete()
            }
        }
        // Altered executable after a linked admission: the content proof fails.
        run {
            val fixture = managedUsbFixture("reject-exe", withWtf = true)
            val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
            val legacyFile = admissionFileFor("storage-" + "ee".repeat(32)).apply {
                parentFile?.mkdirs()
                writeBytes(legacyV1Envelope(
                    "storage-" + "ee".repeat(32), currentFileKey(fixture.root),
                    managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
                    fixture.buildInfo, loose,
                ))
            }
            try {
                File(fixture.root, "WowB-ARM64.exe").writeBytes(fakeManagedPe(160))
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                legacyFile.delete()
            }
        }
        // Altered .build.info after a linked admission: the build bind fails.
        run {
            val fixture = managedUsbFixture("reject-buildinfo", withWtf = true)
            val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
            val legacyFile = admissionFileFor("storage-" + "ef".repeat(32)).apply {
                parentFile?.mkdirs()
                writeBytes(legacyV1Envelope(
                    "storage-" + "ef".repeat(32), currentFileKey(fixture.root),
                    managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
                    fixture.buildInfo, loose,
                ))
            }
            try {
                val altered = String(managedBuildInfo(), Charsets.UTF_8)
                    .replace("aa".repeat(16), "cc".repeat(16))
                    .toByteArray(Charsets.UTF_8)
                File(fixture.root, ".build.info").writeBytes(altered)
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                legacyFile.delete()
            }
        }
        // Present-but-corrupt versioned record: corruption, not absence.
        run {
            val fixture = managedUsbFixture("reject-corrupt", withWtf = true)
            val update = fixture.service.resolveForUpdate()
            val stableId = fixture.service.operationStorageId(update)
            val corrupt = admissionFileFor(stableId)
                .apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { it.toByte() }) }
            try {
                assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            } finally {
                corrupt.delete()
            }
        }
        // Unregistered destination: no selection-registry link, no migration.
        run {
            val fixture = managedUsbFixture("reject-unregistered", withWtf = true)
            val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
            val legacyFile = admissionFileFor("storage-" + "fa".repeat(32)).apply {
                parentFile?.mkdirs()
                writeBytes(legacyV1Envelope(
                    "storage-" + "fa".repeat(32), currentFileKey(fixture.root),
                    managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
                    fixture.buildInfo, loose,
                ))
            }
            try {
                val prefs = context.getSharedPreferences("forever_functional_client_storage", 0)
                val selectedId = requireNotNull(prefs.getString("selected_id", null))
                prefs.edit().clear()
                    .putString("selected_id", selectedId)
                    .putString("selected_label", "USB de prueba")
                    .putString("selected_descriptor", "USB")
                    .commit()
                assertStorageFailure("client_admission_unavailable") { fixture.service.resolveForPrepare() }
            } finally {
                legacyFile.delete()
            }
        }
    }

    @Test
    fun midOperationRootReplacementStillRejectedAfterStableMigration() {
        val fixture = managedUsbFixture("frozen", withWtf = true)
        val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
        val update = fixture.service.resolveForUpdate()
        val record = writeManagedAdmission(
            fixture.service.operationStorageId(update), update.identity.id,
            ForeverClientUpdateService.stableRootPath(update.root),
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose = loose,
        )
        try {
            val target = fixture.service.resolveForPrepare()
            val frozenKey = requireNotNull(target.rootIdentity).key
            // Same paths, same bytes, but a replaced directory: the frozen
            // per-operation identity still rejects mid-operation swaps even
            // though the stable destination id is unchanged.
            restoreTree(fixture.root, snapshotTree(fixture.root))
            assertNotEquals("root fileKey must rotate", frozenKey, currentFileKey(fixture.root))
            assertStorageFailure("storage_root_replaced") { fixture.service.revalidate(target) }
        } finally {
            record.delete()
        }
    }

    @Test
    fun realPendingUpdateRecordBlocksMigrationBeforeAnyWrite() {
        val fixture = managedUsbFixture("pending", withWtf = true)
        val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
        val update = fixture.service.resolveForUpdate()
        val stableId = fixture.service.operationStorageId(update)
        val legacyStorageId = "storage-" + "ab".repeat(32)
        assertTrue(stableId != legacyStorageId)
        val legacyBytes = legacyV1Envelope(
            legacyStorageId, currentFileKey(fixture.root),
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose,
        )
        val legacyFile = admissionFileFor(legacyStorageId).apply { parentFile?.mkdirs(); writeBytes(legacyBytes) }
        // A real durable UPDATING record under the legacy storage id: the
        // exact pending old-journal situation. The quiescent observer lambda
        // keeps the verdict deterministic without host /proc dependence; the
        // record JSON, lease write/read and reason mapping are all real.
        val leaseDir = File(admissionAnchor(), "forever-client-operation")
        val operations = ForeverClientOperationLease(leaseDir) { RuntimeObservation.Quiescent }
        val held = operations.acquireUpdate(legacyStorageId)
        try {
            assertNotNull(operations.blockingReason())
            held.close()
            assertEquals("update_recovery_required", operations.blockingReason())
            fixture.service.migrationGateBlockForTests = { operations.blockingReason() }
            assertStorageFailure("client_update_pending") { fixture.service.resolveForPrepare() }
            // Blocked before any proof or write: no versioned record minted,
            // legacy record and pending journal both preserved for recovery.
            assertFalse(admissionFileFor(stableId).exists())
            assertArrayEquals(legacyBytes, legacyFile.readBytes())
        } finally {
            runCatching { held.close() }
            runCatching { operations.recoverUpdate(legacyStorageId) { false } }
            legacyFile.delete()
            admissionFileFor(stableId).delete()
            runCatching { leaseDir.deleteRecursively() }
        }
    }

    @Test
    fun pendingJournalWithoutOperationRecordBlocksMigration() {
        val fixture = managedUsbFixture("bare-journal", withWtf = true)
        val stableId = fixture.service.operationStorageId(fixture.service.resolveForUpdate())
        val journal = File(admissionAnchor(), "forever-client-update-journal/.forever-client-update.json")
        val bytes = "pending journal must be recovered, not parsed or rebased here".toByteArray()
        try {
            journal.parentFile?.mkdirs()
            journal.writeBytes(bytes)
            assertStorageFailure("client_update_pending") { fixture.service.resolveForPrepare() }
            assertFalse(admissionFileFor(stableId).exists())
            assertArrayEquals(bytes, journal.readBytes())
        } finally {
            journal.delete()
        }
    }

    @Test
    fun contentSwapDuringProofLeavesNoVersionedRecord() {
        val fixture = managedUsbFixture("swap-proof", withWtf = true)
        val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
        val update = fixture.service.resolveForUpdate()
        val stableId = fixture.service.operationStorageId(update)
        val legacyStorageId = "storage-" + "cd".repeat(32)
        val legacyBytes = legacyV1Envelope(
            legacyStorageId, currentFileKey(fixture.root),
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose,
        )
        val legacyFile = admissionFileFor(legacyStorageId).apply { parentFile?.mkdirs(); writeBytes(legacyBytes) }
        // Deterministic mid-migration swap: the executable is replaced after
        // the content proof. The frozen boundary must refuse publication, so
        // no admission is ever minted for the unverified destination.
        fixture.service.migrationAfterProofProbeForTests = {
            File(fixture.root, "WowB-ARM64.exe").writeBytes(fakeManagedPe(160))
        }
        try {
            assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            assertFalse(admissionFileFor(stableId).exists())
            assertArrayEquals(legacyBytes, legacyFile.readBytes())
        } finally {
            legacyFile.delete()
            admissionFileFor(stableId).delete()
        }
    }

    @Test
    fun rotationAfterPublishRollsBackOnlyOurRecord() {
        val fixture = managedUsbFixture("swap-publish", withWtf = true)
        val loose = managedUsbLoose(fixture.root, fixture.exe, emptyMap())
        val update = fixture.service.resolveForUpdate()
        val stableId = fixture.service.operationStorageId(update)
        val legacyStorageId = "storage-" + "dc".repeat(32)
        val legacyBytes = legacyV1Envelope(
            legacyStorageId, currentFileKey(fixture.root),
            managedTargetJson(fixture.exe.size.toLong(), md5Hex(fixture.exe)),
            fixture.buildInfo, loose,
        )
        val legacyFile = admissionFileFor(legacyStorageId).apply { parentFile?.mkdirs(); writeBytes(legacyBytes) }
        // Identical bytes but a replaced directory after publication: the
        // post-publish frozen boundary must refuse and roll back only the
        // record this call created, never a concurrent winner or foreign file.
        fixture.service.migrationAfterPublishProbeForTests = {
            restoreTree(fixture.root, snapshotTree(fixture.root))
        }
        try {
            assertStorageFailure("client_executable_invalid") { fixture.service.resolveForPrepare() }
            assertFalse(admissionFileFor(stableId).exists())
            assertArrayEquals(legacyBytes, legacyFile.readBytes())
        } finally {
            legacyFile.delete()
            admissionFileFor(stableId).delete()
        }
    }

    @Test
    fun launchResolutionProvisionsContractConfigOnceWhenOnlyItIsMissing() {
        val fixture = managedLaunchFixture("provision", withWtf = false)
        try {
            var provisions = 0
            val target = ForeverFunctionalPoc.resolveClientForLaunch(context, fixture.service) { root ->
                provisions++
                File(root, "WTF").mkdirs()
                File(root, "WTF/BetaSuspendedTest.wtf").writeText("SET portal \"test.invalid\"\n")
            }
            assertEquals(1, provisions)
            assertEquals(fixture.root.canonicalFile, target.root)
            // A present original is never rewritten.
            ForeverFunctionalPoc.resolveClientForLaunch(context, fixture.service) {
                throw AssertionError("original config rewritten")
            }
        } finally {
            fixture.record.delete()
        }
    }

    @Test
    fun launchResolutionKeepsPreciseBlockForUngovernedClients() {
        val base = Files.createTempDirectory("forever-managed-ungoverned").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        File(legacy, "WowB-ARM64.exe").writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 1, 2, 3))
        File(legacy, "Data").mkdirs()
        val service = service(external, legacy)
        service.select(service.snapshot().selected!!.identity)
        var provisions = 0
        try {
            ForeverFunctionalPoc.resolveClientForLaunch(context, service) { root ->
                provisions++
                File(root, "WTF").mkdirs()
                File(root, "WTF/BetaSuspendedTest.wtf").writeText("SET portal \"test.invalid\"\n")
            }
            throw AssertionError("ungoverned client accepted")
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            assertEquals("client_admission_unavailable", failure.code)
        }
        // The executable is never proved, so nothing is ever written.
        assertEquals(0, provisions)
        assertFalse(File(legacy, "WTF").exists())
    }

    @Test
    fun launchResolutionNeverProvisionsWhenTheExecutableIsMissing() {
        val base = Files.createTempDirectory("forever-managed-noexe").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        File(legacy, "Data").mkdirs()
        val service = service(external, legacy)
        service.select(service.snapshot().selected!!.identity)
        var provisions = 0
        try {
            ForeverFunctionalPoc.resolveClientForLaunch(context, service) {
                provisions++
            }
            throw AssertionError("missing executable prepared")
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            assertEquals("client_incomplete", failure.code)
        }
        assertEquals(0, provisions)
        assertFalse(File(legacy, "WTF").exists())
    }

    @Test
    fun launchResolutionDoesNotProvisionForEmptyDestinations() {
        val base = Files.createTempDirectory("forever-managed-empty").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val legacy = File(base, "CustomGames/WoW Forever")
        val service = service(external, legacy)
        service.select(service.snapshot().selected!!.identity)
        var provisions = 0
        try {
            ForeverFunctionalPoc.resolveClientForLaunch(context, service) {
                provisions++
            }
            throw AssertionError("empty destination prepared")
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            assertEquals("client_not_installed", failure.code)
        }
        assertEquals(0, provisions)
    }

    private fun assertNestedTreeRejected(label: String, mutation: (File, File) -> Unit) {
        val base = Files.createTempDirectory("forever-storage-$label").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, label)
        mutation(root, base)
        assertStorageFailure("storage_tree_invalid") {
            tinyFixtureService(external, root).resolveForPrepare()
        }
    }

    private fun tinyFixtureService(
        external: File,
        root: File,
    ): ForeverFunctionalClientStorage.Service = ForeverFunctionalClientStorage.testService(
        context,
        volumes = {
            listOf(ForeverFunctionalClientStorage.VolumeCandidate(
                external, null, true, false, Environment.MEDIA_MOUNTED, "Interno",
            ))
        },
        legacyRoot = { root },
        executableVerifier = ForeverFunctionalClientStorage.ExecutableVerifier { _, _ -> true },
    )

    private fun e2eService(
        volumes: () -> List<ForeverFunctionalClientStorage.VolumeCandidate>,
        legacyRoot: File,
    ): ForeverFunctionalClientStorage.Service = ForeverFunctionalClientStorage.testService(
        context,
        volumes = { volumes() },
        legacyRoot = { legacyRoot },
        executableVerifier = ForeverFunctionalClientStorage.ExecutableVerifier { _, _ -> true },
    )

    private fun writeTinyClient(root: File, token: String) {
        root.mkdirs()
        File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).writeBytes("exe-$token".toByteArray())
        File(root, ForeverFunctionalClientStorage.DATA_DIRECTORY).mkdirs()
        File(root, "Data/content-$token.bin").writeBytes("data-$token".toByteArray())
        File(root, "WTF").mkdirs()
        File(root, ForeverFunctionalClientStorage.ORIGINAL_WTF).writeBytes("original-$token\n".toByteArray())
    }

    private fun snapshotClientBytes(root: File): List<ByteArray> = listOf(
        File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).readBytes(),
        File(root, "Data").listFiles()!!.single { it.isFile && it.name.startsWith("content-") }.readBytes(),
        File(root, ForeverFunctionalClientStorage.ORIGINAL_WTF).readBytes(),
    )

    private fun assertClientBytesUnchanged(root: File, expected: List<ByteArray>) {
        assertArrayEquals(expected[0], File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).readBytes())
        val dataFile = File(root, "Data").listFiles()!!.single { it.isFile && it.name.startsWith("content-") }
        assertArrayEquals(expected[1], dataFile.readBytes())
        assertArrayEquals(expected[2], File(root, ForeverFunctionalClientStorage.ORIGINAL_WTF).readBytes())
    }

    private fun assertStorageFailure(expectedCode: String, action: () -> Unit) {
        var actualCode: String? = null
        try {
            action()
        } catch (failure: ForeverFunctionalClientStorage.StorageException) {
            actualCode = failure.code
        }
        assertEquals(expectedCode, actualCode)
    }

    private fun service(external: File, legacy: File): ForeverFunctionalClientStorage.Service {
        legacy.parentFile?.mkdirs()
        // Idle durable gate: these fixtures never consult host /proc; the
        // real pending-record gate is covered by its own dedicated test.
        return serviceWithCandidate(external, legacy, null, false, Environment.MEDIA_MOUNTED, { null })
    }

    private fun serviceWithCandidate(
        external: File,
        legacy: File,
        uuid: String?,
        removable: Boolean,
        state: String,
        gate: (() -> String?)? = null,
    ): ForeverFunctionalClientStorage.Service {
        legacy.parentFile?.mkdirs()
        return ForeverFunctionalClientStorage.testService(
            context,
            volumes = {
                val primaryBase = if (removable) {
                    File(requireNotNull(external.parentFile?.parentFile), "files").apply { mkdirs() }
                } else external
                mutableListOf<ForeverFunctionalClientStorage.VolumeCandidate>().apply {
                    add(ForeverFunctionalClientStorage.VolumeCandidate(
                        externalFilesDir = primaryBase,
                        uuid = null,
                        isPrimary = true,
                        isRemovable = false,
                        state = Environment.MEDIA_MOUNTED,
                        description = "Interno",
                    ))
                    if (removable) add(ForeverFunctionalClientStorage.VolumeCandidate(
                        externalFilesDir = external,
                        uuid = uuid,
                        isPrimary = false,
                        isRemovable = true,
                        state = state,
                        description = "USB de prueba",
                        mountIdentity = if (removable) "mount:${uuid ?: "path"}" else null,
                    ))
                }
            },
            legacyRoot = { legacy },
        ).also { it.migrationGateBlockForTests = gate }
    }

    private fun primaryCandidate(files: File): ForeverFunctionalClientStorage.VolumeCandidate =
        ForeverFunctionalClientStorage.VolumeCandidate(
            files, null, true, false, Environment.MEDIA_MOUNTED, "Interno",
        )

    private fun usbCandidate(
        files: File,
        uuid: String,
        mount: String,
        state: String = Environment.MEDIA_MOUNTED,
    ): ForeverFunctionalClientStorage.VolumeCandidate =
        ForeverFunctionalClientStorage.VolumeCandidate(
            files, uuid, false, true, state, "USB de prueba", mount,
        )

    private class CountingNative(
        var current: List<ForeverFunctionalClientStorage.NativeVolumeMetadata>,
        var fail: Boolean = false,
        var cancel: Boolean = false,
    ) : (Context) -> List<ForeverFunctionalClientStorage.NativeVolumeMetadata> {
        var queries = 0
        override fun invoke(context: Context): List<ForeverFunctionalClientStorage.NativeVolumeMetadata> {
            queries++
            if (cancel) throw CancellationException("synthetic native query cancel")
            if (fail) throw IllegalStateException("synthetic native query failure")
            return current
        }
    }

    private fun nativeUsbCandidate(
        volumeDir: File,
        uuid: String?,
        mount: String?,
        state: String = Environment.MEDIA_MOUNTED,
    ): ForeverFunctionalClientStorage.VolumeCandidate {
        val appFiles = File(volumeDir, "Android/data/${context.packageName}/files")
        return ForeverFunctionalClientStorage.VolumeCandidate(
            appFiles, uuid, false, true, state, "USB de prueba", mount, volumeDir,
        )
    }

    private fun nativeMetadata(
        volumeDir: File?,
        uuid: String?,
        primary: Boolean = false,
        removable: Boolean = true,
        state: String? = Environment.MEDIA_MOUNTED,
        mount: String? = "mount:native",
    ): ForeverFunctionalClientStorage.NativeVolumeMetadata =
        ForeverFunctionalClientStorage.NativeVolumeMetadata(
            volumeDir, uuid, primary, removable, state, "USB", mount,
        )

    private fun directedStorage(
        full: CountingVolumes,
        native: CountingNative,
        legacy: File,
    ): ForeverFunctionalClientStorage.Service = ForeverFunctionalClientStorage.testService(
        context,
        volumes = full,
        legacyRoot = { legacy },
        nativeVolumes = native,
    )

    private fun validDirectedNative(
        volumeDir: File,
        uuid: String,
        mount: String,
    ): List<ForeverFunctionalClientStorage.NativeVolumeMetadata> =
        listOf(nativeMetadata(volumeDir, uuid, mount = mount))

    @Test
    @Config(sdk = [34])
    fun directedUpdateAdmissionBindsProofAndRevalidatesWithoutFullSnapshots() {
        val base = Files.createTempDirectory("forever-directed-fresh").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-directed"
        val mount = "mount:directed-1"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount)))
        val native = CountingNative(validDirectedNative(usbVol, uuid, mount))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        assertEquals("volume:$uuid", identity.id)
        storage.select(identity)

        // A cancelled admission query propagates instead of mapping to a storage code.
        // Baselines are captured after this attempt so the valid resolve below is isolated.
        native.cancel = true
        try {
            storage.resolveForUpdate()
            throw AssertionError("cancelled admission accepted")
        } catch (_: CancellationException) {
        } finally {
            native.cancel = false
        }

        // Admission costs exactly one full snapshot plus one extra fresh metadata query.
        val fullAtAdmit = full.enumerations
        val queriesAtAdmit = native.queries
        val target = storage.resolveForUpdate()
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 1, native.queries)
        assertEquals(uuid, target.admittedUuid)
        assertTrue((target.admittedVolumeDirectory?.absolutePath?.endsWith("usbvol") == true))
        assertTrue((target.admittedAppFilesDirectory?.absolutePath?.contains("Android/data/") == true))
        assertEquals(target.root.absoluteFile.toPath().normalize().toString(),
            target.admittedLexicalRoot?.absoluteFile?.toPath()?.normalize().toString())

        // Each directed object costs one fresh query and zero full snapshots.
        val tree = treeEntries(target.root)
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 2, native.queries)
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 3, native.queries)
        assertEquals(tree, treeEntries(target.root))
        assertNoProbes(base)
    }

    @Test
    @Config(sdk = [34])
    fun directedRevalidationDiagnosticsAggregatePhasesAndPreserveFailures() {
        val base = Files.createTempDirectory("forever-directed-timings").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-timing"
        val mount = "mount:timing"
        val storage = directedStorage(
            CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount))),
            CountingNative(validDirectedNative(usbVol, uuid, mount)), legacy,
        )
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)
        val target = storage.resolveForUpdate()
        val before = ForeverFunctionalClientStorage.revalidationDiagnosticSnapshot()

        storage.revalidateForUpdate(target)
        val afterSuccess = ForeverFunctionalClientStorage.revalidationDiagnosticSnapshot()
        assertEquals(before.calls + 1, afterSuccess.calls)
        assertEquals(before.directed + 1, afterSuccess.directed)
        assertTrue(afterSuccess.totalNanos > before.totalNanos)
        assertTrue(afterSuccess.selectionRegistryNanos > before.selectionRegistryNanos)
        assertTrue(afterSuccess.freshMetadataNanos > before.freshMetadataNanos)
        assertTrue(afterSuccess.namespaceNanos > before.namespaceNanos)
        assertTrue(afterSuccess.candidateInspectionNanos > before.candidateInspectionNanos)
        assertTrue(afterSuccess.resolveNanos > before.resolveNanos)
        assertTrue(afterSuccess.pathCanonicalNanos > before.pathCanonicalNanos)
        assertTrue(afterSuccess.markerNanos > before.markerNanos)
        assertTrue(afterSuccess.identityMountNanos > before.identityMountNanos)
        assertTrue(afterSuccess.finalCompareNanos > before.finalCompareNanos)

        val originalNative = CountingNative(validDirectedNative(usbVol, uuid, mount))
        val failingStorage = directedStorage(
            CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount))),
            originalNative, legacy,
        )
        val failingId = failingStorage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        failingStorage.select(failingId)
        val failingTarget = failingStorage.resolveForUpdate()
        val beforeFailures = ForeverFunctionalClientStorage.revalidationDiagnosticSnapshot()
        originalNative.current = emptyList()
        assertStorageFailure("storage_mount_unproven") { failingStorage.revalidateForUpdate(failingTarget) }
        originalNative.current = validDirectedNative(usbVol, uuid, mount)
        originalNative.cancel = true
        try {
            failingStorage.revalidateForUpdate(failingTarget)
            throw AssertionError("cancelled revalidation accepted")
        } catch (_: CancellationException) {
        }
        val afterFailures = ForeverFunctionalClientStorage.revalidationDiagnosticSnapshot()
        assertEquals(beforeFailures.calls + 2, afterFailures.calls)
        assertEquals(beforeFailures.directed + 2, afterFailures.directed)
        assertEquals(beforeFailures.failures + 1, afterFailures.failures)
        assertEquals(beforeFailures.cancellations + 1, afterFailures.cancellations)
        assertEquals(beforeFailures.fallback, afterFailures.fallback)
        assertNoProbes(base)
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdateRejectsUuidPathStateDuplicatesAndQueryFailureWithoutFallback() {
        val base = Files.createTempDirectory("forever-directed-identity").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val otherVol = File(base, "othervol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-identity"
        val mount = "mount:identity-1"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount)))
        val native = CountingNative(validDirectedNative(usbVol, uuid, mount))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)
        val target = storage.resolveForUpdate()

        fun checkRejectWithoutFallback(expectedCode: String, mutate: () -> Unit) {
            val fullBefore = full.enumerations
            val queriesBefore = native.queries
            val treeBefore = treeEntries(target.root)
            mutate()
            assertStorageFailure(expectedCode) { storage.revalidateForUpdate(target) }
            assertEquals(fullBefore, full.enumerations)
            assertEquals(treeBefore, treeEntries(target.root))
            assertNoProbes(base)
            native.fail = false
            native.current = validDirectedNative(usbVol, uuid, mount)
            storage.revalidateForUpdate(target)
            assertEquals(fullBefore, full.enumerations)
            assertTrue(native.queries > queriesBefore)
        }

        checkRejectWithoutFallback("storage_mount_unproven") { native.current = emptyList() }
        checkRejectWithoutFallback("storage_mount_unproven") { native.current = validDirectedNative(usbVol, "usb-other", mount) }
        checkRejectWithoutFallback("storage_mount_unproven") { native.current = listOf(nativeMetadata(null, uuid, mount = mount)) }
        checkRejectWithoutFallback("storage_unavailable") {
            native.current = validDirectedNative(usbVol, uuid, mount) + validDirectedNative(otherVol, uuid, mount)
        }
        checkRejectWithoutFallback("storage_root_changed") { native.current = validDirectedNative(otherVol, uuid, mount) }
        checkRejectWithoutFallback("storage_unavailable") { native.fail = true }
        // A cancelled query is never mapped to a storage code: it propagates.
        run {
            val fullBefore = full.enumerations
            val queriesBefore = native.queries
            val treeBefore = treeEntries(target.root)
            native.cancel = true
            try {
                storage.revalidateForUpdate(target)
                throw AssertionError("cancelled query accepted")
            } catch (_: CancellationException) {
            } finally {
                native.cancel = false
            }
            assertEquals(fullBefore, full.enumerations)
            assertEquals(queriesBefore + 1, native.queries)
            assertEquals(treeBefore, treeEntries(target.root))
            assertNoProbes(base)
            storage.revalidateForUpdate(target)
            assertEquals(fullBefore, full.enumerations)
        }
        checkRejectWithoutFallback("storage_unavailable") { native.current = listOf(nativeMetadata(usbVol, uuid, state = null, mount = mount)) }
        checkRejectWithoutFallback("storage_unavailable") {
            native.current = listOf(nativeMetadata(usbVol, uuid, state = Environment.MEDIA_UNMOUNTED, mount = mount))
        }
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdateRejectsRemountAndReadOnlyWhileFuseMountLooksUnchanged() {
        val base = Files.createTempDirectory("forever-directed-mount").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-mount"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, "mount:mount-1")))
        val native = CountingNative(validDirectedNative(usbVol, uuid, "mount:mount-1"))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)
        val target = storage.resolveForUpdate()

        // Same path behind another mount is rejected even though the FUSE mount point is unchanged.
        var fullBefore = full.enumerations
        var treeBefore = treeEntries(target.root)
        native.current = validDirectedNative(usbVol, uuid, "mount:mount-2")
        assertStorageFailure("storage_mount_changed") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertEquals(treeBefore, treeEntries(target.root))
        assertNoProbes(base)
        native.current = validDirectedNative(usbVol, uuid, "mount:mount-1")
        storage.revalidateForUpdate(target)
        assertEquals(fullBefore, full.enumerations)

        // Read-only media is rejected even though the underlying FUSE mount identity is identical.
        fullBefore = full.enumerations
        treeBefore = treeEntries(target.root)
        native.current = listOf(nativeMetadata(usbVol, uuid, state = Environment.MEDIA_MOUNTED_READ_ONLY, mount = "mount:mount-1"))
        assertStorageFailure("storage_unavailable") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertEquals(treeBefore, treeEntries(target.root))
        assertNoProbes(base)
        native.current = validDirectedNative(usbVol, uuid, "mount:mount-1")
        storage.revalidateForUpdate(target)
        assertEquals(fullBefore, full.enumerations)
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdateRejectsPhysicalReplacementMarkerSymlinkAndReselection() {
        val base = Files.createTempDirectory("forever-directed-physical").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-physical"
        val mount = "mount:physical-1"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount)))
        val native = CountingNative(validDirectedNative(usbVol, uuid, mount))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)
        var target = storage.resolveForUpdate()

        // A reselection bumps the revision, so the frozen target no longer applies.
        // (select() itself takes a full snapshot; the directed revalidate below takes none.)
        storage.select(identity)
        var fullBefore = full.enumerations
        var queriesBefore = native.queries
        assertStorageFailure("storage_selection_changed") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertEquals(queriesBefore, native.queries)
        target = storage.resolveForUpdate()

        // A recreated root keeps the path but loses the admitted identity.
        fullBefore = full.enumerations
        assertTrue(target.root.deleteRecursively())
        assertTrue(target.root.mkdirs())
        assertStorageFailure("storage_root_replaced") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertNoProbes(base)
        // Recreate only this fixture root through select so the next variant starts
        // from an owner marker again; the rest of the USB base is untouched.
        assertTrue(target.root.deleteRecursively())
        storage.select(identity)
        target = storage.resolveForUpdate()

        // A recreated base under the same root invalidates the admitted base identity.
        fullBefore = full.enumerations
        val staged = File(base, "staged-root")
        assertTrue(target.root.renameTo(staged))
        val appFiles = File(usbVol, "Android/data/${context.packageName}/files")
        assertTrue(appFiles.deleteRecursively())
        appFiles.mkdirs()
        File(appFiles, "CustomGames").mkdirs()
        assertTrue(staged.renameTo(target.root))
        assertStorageFailure("storage_base_replaced") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertNoProbes(base)
        storage.select(identity)
        target = storage.resolveForUpdate()

        // A foreign marker is preserved and still rejected with the existing code.
        // The base variant above renamed this root, so first prove the owner
        // marker survived that move well-formed before injecting the foreign one.
        fullBefore = full.enumerations
        val marker = File(target.root, ".forever-client-storage")
        assertTrue(marker.isFile)
        val originalMarker = marker.readBytes()
        assertArrayEquals("forever-client-storage-v1\n${identity.id}\n".toByteArray(Charsets.UTF_8), originalMarker)
        marker.writeText("foreign\n", Charsets.UTF_8)
        assertStorageFailure("storage_marker_invalid") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertEquals("foreign\n", marker.readText(Charsets.UTF_8))
        assertNoProbes(base)
        // Restore the fixture marker explicitly: select must never repair an unknown marker.
        marker.writeBytes(originalMarker)
        val refreshed = target
        storage.select(identity)
        target = storage.resolveForUpdate()
        assertEquals(refreshed.root, target.root)

        // A symlinked root is untrusted and rejected without touching the target.
        fullBefore = full.enumerations
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "keep.bin").writeBytes(byteArrayOf(9, 8, 7))
        assertTrue(target.root.deleteRecursively())
        Files.createSymbolicLink(target.root.toPath(), outside.toPath())
        try {
            assertStorageFailure("storage_unavailable") { storage.revalidateForUpdate(target) }
        } finally {
            Files.deleteIfExists(target.root.toPath())
            assertTrue(target.root.mkdirs())
        }
        assertEquals(fullBefore, full.enumerations)
        assertArrayEquals(byteArrayOf(9, 8, 7), File(outside, "keep.bin").readBytes())
        assertNoProbes(base)
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdateAllowsMissingRootButRejectsAbsentVolumeAndPartialProof() {
        val base = Files.createTempDirectory("forever-directed-absent").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-absent"
        val mount = "mount:absent-1"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, mount)))
        val native = CountingNative(validDirectedNative(usbVol, uuid, mount))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)

        // An absent root is legal when the volume, base, and mount still prove out.
        assertTrue(storage.snapshot().selected!!.root!!.deleteRecursively())
        val fullAtAdmit = full.enumerations
        val queriesAtAdmit = native.queries
        val target = storage.resolveForUpdate()
        assertNull(target.rootIdentity)
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 1, native.queries)
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 2, native.queries)

        // An absent volume is different from an absent root: admission proof cannot be shown.
        val fullBefore = full.enumerations
        native.current = emptyList()
        assertStorageFailure("storage_mount_unproven") { storage.revalidateForUpdate(target) }
        assertEquals(fullBefore, full.enumerations)
        assertNoProbes(base)
        native.current = validDirectedNative(usbVol, uuid, mount)
        storage.revalidateForUpdate(target)
        assertEquals(fullBefore, full.enumerations)

        // A partial proof is invalid and never falls back to a full snapshot.
        val partials = listOf(
            target.copy(admittedUuid = null),
            target.copy(admittedVolumeDirectory = null),
            target.copy(admittedAppFilesDirectory = null),
            target.copy(admittedLexicalRoot = null),
        )
        for (partial in partials) {
            val fullEach = full.enumerations
            val queriesEach = native.queries
            assertStorageFailure("storage_unavailable") { storage.revalidateForUpdate(partial) }
            assertEquals(fullEach, full.enumerations)
            assertEquals(queriesEach, native.queries)
        }
        assertNoProbes(base)
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdateWithNullMountsUsesFreshHostMountWithoutFullSnapshots() {
        // Both the full candidate and the fresh metadata carry mountIdentity=null, so the
        // admitted and revalidated mounts come from the real host /proc mount parser on
        // the existing sandbox base (no mock reader, JNI, hidden API, or reflection).
        // Limitation: this exercises the prod null-mount + compare path, not a real
        // hot-unplug or a real mount-table change on device.
        val base = Files.createTempDirectory("forever-directed-hostmount").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val uuid = "usb-hostmount"
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, uuid, null)))
        val native = CountingNative(listOf(nativeMetadata(usbVol, uuid, mount = null)))
        val storage = directedStorage(full, native, legacy)
        val identity = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }.identity
        storage.select(identity)

        // Admit with the root absent: the mount must be proven from the base, never the root.
        assertTrue(storage.snapshot().selected!!.root!!.deleteRecursively())
        val fullAtAdmit = full.enumerations
        val queriesAtAdmit = native.queries
        val target = storage.resolveForUpdate()
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 1, native.queries)
        assertTrue(target.mountIdentity != null)

        // Directed revalidation reuses the fresh host mount with zero full snapshots.
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(queriesAtAdmit + 2, native.queries)

        // A forged mount against the same path is rejected without falling back.
        val forged = target.copy(mountIdentity = "definitely-not-this-mount")
        val fullBefore = full.enumerations
        val queriesBefore = native.queries
        val treeBefore = treeEntries(target.root)
        assertStorageFailure("storage_mount_changed") { storage.revalidateForUpdate(forged) }
        assertEquals(fullBefore, full.enumerations)
        assertEquals(queriesBefore + 1, native.queries)
        assertEquals(treeBefore, treeEntries(target.root))
        assertNoProbes(base)
        storage.revalidateForUpdate(target)
        assertEquals(fullBefore, full.enumerations)
    }

    @Test
    @Config(sdk = [34])
    fun directedUpdatePathIdentityKeepsFullRouteWithoutNativeQuery() {
        // A native USB namespace without UUID admits as volume-path:... and is statically
        // ineligible for the directed route. The native provider is armed to fail, so any
        // consultation would explode: cold resolve + revalidations use full snapshots only.
        val base = Files.createTempDirectory("forever-directed-pathid").toFile()
        val primaryBase = File(base, "primary/files").apply { mkdirs() }
        val usbVol = File(base, "usbvol").apply { mkdirs() }
        val legacy = File(base, "primary/CustomGames/WoW Forever")
        legacy.parentFile?.mkdirs()
        val full = CountingVolumes(listOf(primaryCandidate(primaryBase), nativeUsbCandidate(usbVol, null, "mount:path-1")))
        val native = CountingNative(emptyList(), fail = true)
        val storage = directedStorage(full, native, legacy)
        val option = storage.snapshot().options.single { it.identity.kind == ForeverFunctionalClientStorage.Kind.REMOVABLE }
        assertTrue(option.identity.id.startsWith("volume-path:"))
        storage.select(option.identity)

        val fullAtAdmit = full.enumerations
        val target = storage.resolveForUpdate()
        assertEquals(fullAtAdmit + 1, full.enumerations)
        assertEquals(0, native.queries)
        assertTrue(!target.hasDirectedProof() && !target.hasPartialProof())

        val tree = treeEntries(target.root)
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 2, full.enumerations)
        assertEquals(0, native.queries)
        storage.revalidateForUpdate(target)
        assertEquals(fullAtAdmit + 3, full.enumerations)
        assertEquals(0, native.queries)
        assertEquals(tree, treeEntries(target.root))
        assertNoProbes(base)
    }

    @Test
    fun containerRegistrationReusesAnEquivalentMappingWithoutSideEffects() {
        val base = Files.createTempDirectory("forever-container-registration").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        service.resolveContainerRegistration(
            root,
            lookup = { File(root, "sub/..").path },
            register = { throw AssertionError("registration must not run for an equivalent mapping") },
        )
    }

    @Test
    fun containerRegistrationBindsOnlyTheProvedRoot() {
        val base = Files.createTempDirectory("forever-container-register").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        var registered: String? = null
        service.resolveContainerRegistration(
            root,
            lookup = { registered },
            register = {
                registered = root.canonicalPath
                ForeverFunctionalPoc.APP_ID
            },
        )
        assertEquals(root.canonicalPath, registered)
    }

    @Test
    fun containerRegistrationForAForeignIdentityFailsClosed() {
        val base = Files.createTempDirectory("forever-container-foreign").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        assertStorageFailure("container_registration_mismatch") {
            service.resolveContainerRegistration(
                root,
                lookup = { null },
                register = { "CUSTOM_GAME_1" },
            )
        }
    }

    @Test
    fun containerRegistrationWithoutARoundTripFailsClosed() {
        val base = Files.createTempDirectory("forever-container-roundtrip").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        assertStorageFailure("container_registration_mismatch") {
            service.resolveContainerRegistration(
                root,
                lookup = { null },
                register = { ForeverFunctionalPoc.APP_ID },
            )
        }
    }

    @Test
    fun knownUsbRootBindsTheFixedIdentityThroughTheStockMetadataPin() {
        // Deterministic premise, verified against the scanner algorithm
        // (CustomGameScanner/CustomGameCache: stored pin wins, otherwise
        // abs(absolutePath.hashCode())): hashing the real USB root cannot
        // yield the fixed functional identity, so the stock `.gamenative`
        // pin is required and never arbitrary.
        val usbRoot = "/storage/6AD6-EF0E/Android/data/com.wowforever.arm64probe/files/CustomGames/WoW Forever"
        assertEquals(422876682, abs(usbRoot.hashCode()).let { if (it == 0) 1 else it })
        assertEquals(1368588668, ForeverFunctionalPoc.APP_ID.removePrefix("CUSTOM_GAME_").toInt())

        // The stock pin mechanism binds any root to the fixed identity with
        // plain file IO: no scanner statics, no container, no network.
        val base = Files.createTempDirectory("forever-usb-pin").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        assertNull(GameMetadataManager.getAppId(root))
        GameMetadataManager.write(root, GameMetadata(1368588668))
        assertEquals(1368588668, GameMetadataManager.getAppId(root))
        assertTrue(File(root, ".gamenative").isFile)

        // The registration guard consumes the pinned mapping: no
        // registration side effects, no creation for a foreign root.
        val service = tinyFixtureService(external, root)
        var registered = false
        service.resolveContainerRegistration(
            root,
            lookup = { if (GameMetadataManager.getAppId(root) == 1368588668) root.canonicalPath else null },
            register = {
                registered = true
                ForeverFunctionalPoc.APP_ID
            },
        )
        assertFalse(registered)
    }

    @Test
    fun staleTargetFailsBeforeAnyRegistrationMutation() {
        val base = Files.createTempDirectory("forever-container-stale").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever")
        writeTinyClient(root, "stale")
        val service = tinyFixtureService(external, root)
        val target = service.resolveForPrepare()
        // The USB swap window between resolve and ensure: the frozen
        // executable identity no longer matches, so the terminal proof must
        // fail before a single registration lookup or mutation.
        File(root, ForeverFunctionalPoc.EXECUTABLE_NAME).appendBytes("replaced".toByteArray())
        var lookups = 0
        var registrations = 0
        assertStorageFailure("client_executable_replaced") {
            service.ensureContainer(
                target,
                lookup = { lookups++; null },
                register = { registrations++; ForeverFunctionalPoc.APP_ID },
            )
        }
        assertEquals(0, lookups)
        assertEquals(0, registrations)
    }

    @Test
    fun foreignOwnerLookupFailsBeforeAnyRegistrationCall() {
        val base = Files.createTempDirectory("forever-container-foreign-owner").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val foreign = File(base, "elsewhere").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        var registrations = 0
        assertStorageFailure("container_registration_mismatch") {
            service.resolveContainerRegistration(
                root,
                lookup = { foreign.path },
                register = { registrations++; ForeverFunctionalPoc.APP_ID },
            )
        }
        assertEquals(0, registrations)
    }

    @Test
    fun stockPinLifecycleForTheFixedIdentity() {
        val base = Files.createTempDirectory("forever-pin-lifecycle").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        val numericId = ForeverFunctionalPoc.APP_ID.removePrefix("CUSTOM_GAME_").toInt()
        // Absent: stock write through the actual production helper.
        assertTrue(service.pinFunctionalIdentity(root))
        assertEquals(numericId, GameMetadataManager.getAppId(root))
        val written = File(root, ".gamenative").readBytes()
        // Same identity: noop, bytes untouched.
        assertFalse(service.pinFunctionalIdentity(root))
        assertArrayEquals(written, File(root, ".gamenative").readBytes())
        // Foreign identity: fail closed, bytes untouched.
        File(root, ".gamenative").writeText("{\"appId\":422876682}")
        assertStorageFailure("container_registration_mismatch") { service.pinFunctionalIdentity(root) }
        assertEquals("{\"appId\":422876682}", File(root, ".gamenative").readText())
        // Malformed: fail closed, bytes untouched.
        File(root, ".gamenative").writeText("not-json{{{")
        assertStorageFailure("container_registration_mismatch") { service.pinFunctionalIdentity(root) }
        assertEquals("not-json{{{", File(root, ".gamenative").readText())
    }

    @Test
    fun pinRejectsSymlinkAndNonRegularMetadata() {
        val base = Files.createTempDirectory("forever-pin-special").toFile()
        val external = File(base, "files").apply { mkdirs() }
        val root = File(base, "CustomGames/WoW Forever").apply { mkdirs() }
        val service = tinyFixtureService(external, root)
        val outside = File(base, "outside-pin").apply { writeText("{\"appId\":1368588668}") }
        val before = outside.readBytes()
        Files.createSymbolicLink(File(root, ".gamenative").toPath(), outside.toPath())
        try {
            assertStorageFailure("container_registration_mismatch") { service.pinFunctionalIdentity(root) }
            assertArrayEquals(before, outside.readBytes())
        } finally {
            Files.deleteIfExists(File(root, ".gamenative").toPath())
        }
        File(root, ".gamenative").mkdir()
        try {
            assertStorageFailure("container_registration_mismatch") { service.pinFunctionalIdentity(root) }
        } finally {
            File(root, ".gamenative").delete()
        }
    }

    private class CountingVolumes(
        var current: List<ForeverFunctionalClientStorage.VolumeCandidate>,
    ) : (Context) -> List<ForeverFunctionalClientStorage.VolumeCandidate> {
        var enumerations = 0
        override fun invoke(context: Context): List<ForeverFunctionalClientStorage.VolumeCandidate> {
            enumerations++
            return current
        }
    }

    private fun treeEntries(root: File): Set<String> =
        if (!root.exists()) emptySet() else root.walkTopDown().map { it.relativeTo(root).path }.toSet()

    private fun assertNoProbes(base: File) {
        assertTrue(base.walkTopDown().none { it.name.startsWith(".forever-probe-") })
    }

    private fun currentRevision(service: ForeverFunctionalClientStorage.Service): Long = service.snapshot().revision
}
