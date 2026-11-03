package app.gamenative

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ForeverAddonsTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("forever-addons-").toFile()
        val store get() = ForeverAddons.Store(root)
        fun toc(name: String) = File(root, "Interface/AddOns/$name/$name.toc").also {
            it.parentFile.mkdirs(); it.writeText("## Title: $name\n## Version: 1\n")
        }
        fun zip(vararg folders: String): File = File.createTempFile("addon-", ".zip", root).also { file ->
            ZipOutputStream(file.outputStream()).use { zip -> folders.forEach { name ->
                zip.putNextEntry(ZipEntry("$name/$name.toc"))
                zip.write("## Title: $name\n## Version: 1\n".toByteArray()); zip.closeEntry()
            } }
        }
        fun metadata(id: Long = 1, deps: List<Long> = emptyList()) =
            ForeverAddons.PackageMetadata(id, id * 10, "Package$id", "1", deps)
        override fun close() { root.deleteRecursively() }
    }
    private fun code(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected $expected, got $failure", failure is ForeverAddons.AddonException)
        assertEquals(expected, (failure as ForeverAddons.AddonException).code)
    }

    @Test fun inventoryIsOfflineReadOnlyAndDoesNotExposeBlizzardOrInventMetadata() {
        Fixture().use { f ->
            assertTrue(f.store.list().isEmpty())
            assertFalse(File(f.root, "Interface").exists())
            f.toc("Blizzard_UI")
            f.toc("Manual")
            File(f.root, "Interface/AddOns/Unknown").mkdirs()
            val list = f.store.list()
            assertEquals(listOf("Manual", "Unknown"), list.map { it.title })
            assertFalse(list.single { it.title == "Unknown" }.manageable)
            code("unknown-folder") { f.store.uninstall("folder:Unknown") }
        }
    }

    @Test fun multifolderPackageStaysOneUnitAcrossDisableRestartReenableAndRemoval() {
        Fixture().use { f ->
            f.store.install(f.zip("One", "Two"), f.metadata())
            assertEquals(listOf("One", "Two"), f.store.list().single().folders)
            f.store.setEnabled("project:1", false)
            assertFalse(f.store.list().single().enabled)
            assertFalse(File(f.root, "Interface/AddOns/One").exists())
            assertTrue(File(f.root, "Interface/.forever-addons/disabled/Two/Two.toc").isFile)
            f.store.setEnabled("project:1", true)
            assertTrue(f.store.list().single().enabled)
            f.store.setEnabled("project:1", false)
            f.store.uninstall("project:1")
            assertTrue(f.store.list().isEmpty())
        }
    }

    @Test fun manualUninstallAndDisablePreservePreferencesAndOtherAddons() {
        Fixture().use { f ->
            f.toc("Manual"); f.toc("Other"); f.toc("Blizzard_UI")
            val saved = File(f.root, "WTF/Account/User/SavedVariables/Manual.lua")
            saved.parentFile.mkdirs(); saved.writeText("preserve")
            f.store.setEnabled("folder:Manual", false)
            f.store.uninstall("folder:Manual")
            assertEquals("preserve", saved.readText())
            assertTrue(File(f.root, "Interface/AddOns/Other/Other.toc").isFile)
            assertTrue(File(f.root, "Interface/AddOns/Blizzard_UI/Blizzard_UI.toc").isFile)
        }
    }

    @Test fun dependenciesNeedProvenEnabledIdentityAndCaseInsensitiveConflictsNeverOverwrite() {
        Fixture().use { f ->
            val dependent = f.zip("Dependent")
            code("dependency-missing") { f.store.install(dependent, f.metadata(2, listOf(1))) }
            f.store.install(f.zip("First"), f.metadata())
            f.store.setEnabled("project:1", false)
            code("dependency-missing") { f.store.install(dependent, f.metadata(2, listOf(1))) }
            code("conflict") { f.store.install(f.zip("first"), f.metadata(3)) }
            f.store.setEnabled("project:1", true)
            f.store.install(dependent, f.metadata(2, listOf(1)))
            assertEquals(2, f.store.list().size)
        }
    }

    @Test fun strayFilesOutsideAddonFoldersDoNotDisableInventoryOrManagement() {
        Fixture().use { f ->
            f.toc("Manual")
            File(f.root, "Interface/AddOns/notes.txt").writeText("ignore me")
            assertEquals(listOf("Manual"), f.store.list().map { it.title })
            f.store.setEnabled("folder:Manual", false)
            File(f.root, "Interface/.forever-addons/disabled/notes.txt").apply { parentFile.mkdirs(); writeText("ignore me") }
            assertFalse(f.store.list().single { it.title == "Manual" }.enabled)
            f.store.setEnabled("folder:Manual", true)
            assertTrue(f.store.list().single { it.title == "Manual" }.enabled)
        }
    }

    @Test fun traversalSymlinkAndBlizzardArchivesAreRejectedBeforePublication() {
        Fixture().use { f ->
            for (name in listOf("../Escape", "Blizzard_UI")) {
                assertTrue(runCatching { f.store.install(f.zip(name), f.metadata()) }.isFailure)
                assertFalse(File(f.root, "Interface/AddOns").exists())
            }
            val zip = f.zip("Link")
            val bytes = zip.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val offset = (0..bytes.size - 46).first { b.getInt(it) == 0x02014b50 }
            b.putShort(offset + 4, 0x0314.toShort())
            b.putInt(offset + 38, 0xa1ff shl 16)
            zip.writeBytes(bytes)
            code("archive") { f.store.install(zip, f.metadata()) }
            assertFalse(File(f.root, "Interface/AddOns").exists())
        }
    }

    @Test fun interruptedMultifolderPublicationBlocksInventoryUntilExplicitRecoveryCompletes() {
        Fixture().use { f ->
            var injected = false
            val store = ForeverAddons.Store(f.root) {
                if (!injected && File(f.root, "Interface/AddOns/One").exists()) {
                    injected = true
                    error("synthetic storage failure after first publish")
                }
            }
            code("io") { store.install(f.zip("One", "Two"), f.metadata()) }
            assertTrue(f.store.hasPendingTransaction())
            code("recovery-required") { f.store.list() }
            f.store.recover()
            assertEquals(listOf("One", "Two"), f.store.list().single().folders)
            assertFalse(f.store.hasPendingTransaction())
        }
    }

    @Test fun interruptedStatePublicationReplaysJournalInsteadOfRejectingItsTemporaryState() {
        Fixture().use { f ->
            val store = ForeverAddons.Store(f.root) {
                if (File(f.root, "Interface/.forever-addons/state.json.tmp").exists())
                    error("synthetic crash before state rename")
            }
            code("io") { store.install(f.zip("One", "Two"), f.metadata()) }
            assertTrue(File(f.root, "Interface/.forever-addons/state.json.tmp").isFile)
            assertTrue(f.store.hasPendingTransaction())
            f.store.recover()
            assertEquals(2, f.store.list().single().folders.size)
            assertFalse(f.store.hasPendingTransaction())
        }
    }
}
