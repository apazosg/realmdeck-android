package app.gamenative

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PanvkWsiPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun debugProcessGateIsExactAndNormalProcessIsRejected() {
        assertTrue(PanvkWsiPolicy.isWsiProcess(true, "app.gamenative", "app.gamenative:panvk_wsi"))
        assertFalse(PanvkWsiPolicy.isWsiProcess(false, "app.gamenative", "app.gamenative:panvk_wsi"))
        assertFalse(PanvkWsiPolicy.isWsiProcess(true, "app.gamenative", "app.gamenative"))
        assertFalse(PanvkWsiPolicy.isWsiProcess(true, "other", "app.gamenative:panvk_wsi"))
    }

    @Test fun onlyBooleanTrueStartExtraIsACommand() {
        assertTrue(PanvkWsiPolicy.isStartIntent(Intent().putExtra("start", true)))
        assertFalse(PanvkWsiPolicy.isStartIntent(Intent().putExtra("start", false)))
        assertFalse(PanvkWsiPolicy.isStartIntent(Intent().putExtra("start", "true")))
        assertFalse(PanvkWsiPolicy.isStartIntent(Intent().putExtra("path", "/outside")))
    }

    @Test fun duplicateStartAndPreReadyCancelCannotSpawnTwice() {
        val gate = PanvkWsiStartGate()
        assertFalse(gate.request(false))
        assertFalse(gate.request(true))
        assertTrue(gate.wasRequested())
        assertTrue(gate.surfaceBecameReady())
        assertTrue(gate.isArmed())
        assertTrue(gate.cancel())
        assertFalse(gate.surfaceBecameReady())
        assertFalse(gate.request(true))
    }

    @Test fun sameActivityStartDeliveryWaitsForResumeBeforeOneWorker() {
        val gate = PanvkWsiStartGate()
        assertFalse(PanvkWsiPolicy.cancelOnPause(nativeStarted = false))
        assertFalse(PanvkWsiPolicy.mayStartNative(hostResumed = false))
        assertTrue(gate.request(surfaceReady = true))
        assertTrue(gate.isArmed())
        assertTrue(PanvkWsiPolicy.mayStartNative(hostResumed = true))
        assertFalse(gate.request(surfaceReady = true))
        assertTrue(PanvkWsiPolicy.cancelOnPause(nativeStarted = true))
        gate.cancel()
        assertTrue(gate.isCancelled())
        assertFalse(gate.surfaceBecameReady())
    }

    @Test fun processSessionClaimIsOneUseUntilFixtureReset() {
        PanvkWsiPolicy.resetProcessSessionForTests()
        assertTrue(PanvkWsiPolicy.claimProcessSession())
        assertFalse(PanvkWsiPolicy.claimProcessSession())
        PanvkWsiPolicy.resetProcessSessionForTests()
        assertTrue(PanvkWsiPolicy.claimProcessSession())
        PanvkWsiPolicy.resetProcessSessionForTests()
    }

    @Test fun frozenAssetPinIsBoundedAndExact() {
        assertEquals(31_142_504L, PanvkWsiStore.APPROVED_SIZE)
        assertEquals(64 * 1024, PanvkWsiStore.COPY_BUFFER_BYTES)
        assertEquals("5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9", PanvkWsiStore.APPROVED_SHA256)
        assertEquals(64, PanvkWsiStore.APPROVED_SHA256.length)
        assertFalse(PanvkWsiStore.verifyAssetBytes(ByteArray(0)))
    }

    @Test fun canonicalIcdOwnerAllowsOnlyTheTwoSamePackageRunAliases() {
        val store = PanvkWsiStore(FixtureContext(temporary.newFolder("aliases")))
        val requested = "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/PWSI-0123456789abcdef0123456789abcdef/libvulkan_panfrost.so"
        val actual = requested.replace("/data/data/", "/data/user/0/")
        assertTrue(store.icdOwnerMatches(actual, requested))
        assertFalse(store.icdOwnerMatches(actual.replace("/com.wowforever.arm64probe/", "/other/"), requested))
        assertFalse(store.icdOwnerMatches(actual.replace("0123456789abcdef", "ffffffffffffffff"), requested))
    }

    @Test fun knownHooksRejectUnknownErrors() {
        assertTrue(PanvkWsiPolicy.acceptedHook("surfaceCreated"))
        assertTrue(PanvkWsiPolicy.acceptedHook("nativeReturned"))
        assertFalse(PanvkWsiPolicy.acceptedHook("some_unbounded_error"))
        assertFalse(File("/outside").canonicalPath.startsWith("/data/data/app.gamenative/files/forever/controls/panvk-wsi/"))
    }

    @Test fun cancelPriorToReadyIsTerminalAndDoesNotInvokeNative() {
        val store = PanvkWsiStore(FixtureContext(temporary.newFolder("cancel")))
        val run = store.createRun(pid = 17)
        assertTrue(store.cancel(run, "cancel_before_ready"))
        assertEquals(PanvkWsiStatus.CANCELLED, store.readState(run)?.status)
    }

    @Test fun recreationGetsNewRunAndStaleFinishCannotResurrectOldRun() {
        val store = PanvkWsiStore(FixtureContext(temporary.newFolder("recreate")))
        val first = store.createRun(pid = 17, generation = 1)
        val second = store.createRun(pid = 17, generation = 2)
        val nativeResult = "{\"schema\":\"wowforever.android-panvk-wsi\",\"version\":1,\"status\":\"CANCELLED\"}"
        assertFalse(store.finishNative(first, nativeResult))
        assertEquals(PanvkWsiStatus.PREPARING, store.readState(first)?.status)
        assertEquals(second.runId, store.readState(second)?.runId)
    }

    @Test fun managedRootRejectsSymlinkEscape() {
        val files = temporary.newFolder("symlink")
        val outside = temporary.newFolder("outside")
        val forever = File(files, "forever")
        java.nio.file.Files.createSymbolicLink(forever.toPath(), outside.toPath())
        val store = PanvkWsiStore(FixtureContext(files))
        var rejected = false
        try { store.createRun() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun earlyNativeFailureRetainsNullableResultsAndMatchingBinding() {
        val store = PanvkWsiStore(FixtureContext(temporary.newFolder("native-failure")))
        val run = store.createRun(pid = 17)
        val native = org.json.JSONObject().put("schema", "wowforever.android-panvk-wsi").put("version", 1)
            .put("runId", run.runId).put("pid", run.pid).put("uid", run.uid).put("generation", run.generation)
            .put("scopeValid", false).put("icdPath", run.icdPath.absolutePath).put("icdBytes", 0)
            .put("loadLoaded", false).put("loadAttempted", false)
            .put("icdOwner", "").put("icdOwnerBefore", "").put("icdOwnerAfter", "")
            .put("icdSha256", "").put("icdAfterSha256", "")
            .put("surface", org.json.JSONObject().put("fromSurface", false).put("width", 0).put("height", 0).put("createResult", org.json.JSONObject.NULL))
            .put("frames", org.json.JSONObject().put("max", 3).put("count", 0))
            .put("apiPresentSuccess", false).put("cancelRequested", false)
            .put("cleanup", org.json.JSONObject().put("queueWaitIdleCalled", false).put("complete", true).put("unknownResourceRetained", false))
            .put("pfns", org.json.JSONArray())
            .put("journal", org.json.JSONObject().put("rowCapacity", 256).put("rows", 0).put("byteCapacity", 65536).put("bytes", 0))
            .put("androidPresentationObserved", false).put("wine", false).put("d3d", false).put("wow", false)
            .put("status", "UNKNOWN").put("nativeExit", 1)
        val validator = PanvkWsiStore::class.java.getDeclaredMethod("validateNativeBinding", org.json.JSONObject::class.java, PanvkWsiRun::class.java)
        validator.isAccessible = true
        validator.invoke(store, native, run)
        native.put("pid", run.pid + 1)
        var rejected = false
        try { validator.invoke(store, native, run) } catch (_: java.lang.reflect.InvocationTargetException) { rejected = true }
        assertTrue(rejected)
    }

    private class FixtureContext(private val directory: File) : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "app.gamenative.panvk.test"
    }
}
