package app.gamenative

import android.content.Intent
import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.OsConstants
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], application = android.app.Application::class)
class ForeverNativeSupervisorPolicyTest {
    @org.junit.Before fun ownKernelIdentity() {
        org.robolectric.shadows.ShadowProcess.setPid(4242)
        org.robolectric.shadows.ShadowProcess.setUid(10330)
    }
    @Test fun onlyAllowlistedScenariosAndScenarioIntentAreAccepted() {
        assertEquals(ForeverNativeSupervisorPolicy.Scenario.NORMAL, ForeverNativeSupervisorPolicy.scenario("normal"))
        assertEquals(ForeverNativeSupervisorPolicy.Scenario.CANCEL_FORK, ForeverNativeSupervisorPolicy.scenario("cancel_fork"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeSupervisorPolicy.scenario("/tmp/arbitrary") }
        val intent = Intent().putExtra("scenario", "normal")
        assertEquals(ForeverNativeSupervisorPolicy.Scenario.NORMAL, ForeverNativeSupervisorPolicy.scenarioFromIntent(intent))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeSupervisorPolicy.scenarioFromIntent(Intent().putExtra("path", "/tmp")) }
    }

    @Test fun isolationIsExactDebugApplicationAndProcessBeforeHilt() {
        val exact = "${ForeverNativeSupervisorPolicy.PACKAGE}:run_supervisor"
        assertTrue(ForeverNativeSupervisorPolicy.isIsolatedProcess(true, ForeverNativeSupervisorPolicy.PACKAGE, exact))
        assertFalse(ForeverNativeSupervisorPolicy.isIsolatedProcess(false, ForeverNativeSupervisorPolicy.PACKAGE, exact))
        assertFalse(ForeverNativeSupervisorPolicy.isIsolatedProcess(true, ForeverNativeSupervisorPolicy.PACKAGE, ForeverNativeSupervisorPolicy.PACKAGE))
        assertFalse(ForeverNativeSupervisorPolicy.isIsolatedProcess(true, "other", "other:run_supervisor"))
    }

    @Test fun strictJsonRejectsDuplicateUnknownUnicodeAndTypedLongViolations() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeStrictJson.objectOf("{\"schema\":1,\"schema\":1}") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeStrictJson.objectOf("{\"schéma\":1}") }
        val run = "NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; val nonce = "b".repeat(32)
        val event = validEvent(run, nonce)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(event.put("extra", true).toString(), run, nonce) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(event.put("startTicks", 1.5).toString(), run, nonce) }
    }

    @Test fun eventRequiresAllFourUidFieldsAndCanonicalRoles() {
        val run = "NS-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; val nonce = "b".repeat(32)
        val event = validEvent(run, nonce)
        assertEquals(listOf(AndroidProcess.myUid(), AndroidProcess.myUid(), AndroidProcess.myUid(), AndroidProcess.myUid()), ForeverNativeEvent.parse(event.toString(), run, nonce).uids)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(event.put("uid3", AndroidProcess.myUid() + 1).toString(), run, nonce) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ForeverNativeEvent.parse(event.put("role", "fixture").toString(), run, nonce) }
    }

    @Test fun artifactPinsAndLinkerInvocationAreCanonical() {
        assertEquals("native-run-supervisor-v2", ForeverNativeSupervisorPolicy.MANIFEST_VARIANT)
        assertEquals("READY", ForeverNativeSupervisorPolicy.APPROVED_NATIVE_PINS_STATUS)
        assertEquals("a246a11bc142fecbe156c40ac8913baba02a172288aff8b1f2787c21eb1134b6",
            ForeverNativeSupervisorPolicy.APPROVED_MANIFEST_SHA256)
        assertEquals(30_344L, ForeverNativeSupervisorPolicy.APPROVED_SUPERVISOR_SIZE)
        assertEquals(12_784L, ForeverNativeSupervisorPolicy.APPROVED_FIXTURE_SIZE)
        assertEquals("fd8727d3d4b3b76e00cbf17c74bcee75684d7945aa8736f644ecbbaf55f7385c",
            ForeverNativeSupervisorPolicy.APPROVED_SUPERVISOR_SHA256)
        assertEquals("7f1b6d256939c9a7310475c3d3bc7dbdfeb22ad46e3de4270413db05a663e67f",
            ForeverNativeSupervisorPolicy.APPROVED_FIXTURE_SHA256)
        assertTrue(ForeverNativeSupervisorPolicy.SUPERVISOR_NAME.endsWith(".so"))
        assertFalse(ForeverNativeSupervisorPolicy.FIXTURE_NAME.contains('/'))
    }

    @Test fun nativePermissionsAllowInstallerOwnerWriteButNotAppGroupOrOtherWrite() {
        assertTrue(ForeverNativeSupervisorPolicy.noGroupOrOtherWrite(493)) // 0755
        assertFalse(ForeverNativeSupervisorPolicy.noGroupOrOtherWrite(509)) // 0775
        assertFalse(ForeverNativeSupervisorPolicy.noGroupOrOtherWrite(495)) // 0757
    }

    @Test fun nativePermissionCheckRejectsWritableAndAcceptsDeniedOrEacces() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ForeverNativeSupervisorPolicy.requireNotWritableForTesting { true }
        }
        ForeverNativeSupervisorPolicy.requireNotWritableForTesting { false }
        ForeverNativeSupervisorPolicy.requireNotWritableForTesting {
            throw ErrnoException("access", OsConstants.EACCES)
        }
    }

    @Test fun nativePermissionCheckPropagatesNonEaccesErrnosFailClosed() {
        listOf(OsConstants.ENOENT, OsConstants.EPERM, OsConstants.EIO).forEach { errno ->
            val error = org.junit.Assert.assertThrows(ErrnoException::class.java) {
                ForeverNativeSupervisorPolicy.requireNotWritableForTesting {
                    throw ErrnoException("access", errno)
                }
            }
            assertEquals(errno, error.errno)
        }
    }

    private fun validEvent(run: String, nonce: String): JSONObject = JSONObject()
        .put("schema", 1).put("runId", run).put("nonce", nonce).put("seq", 1).put("phase", 0).put("event", "READY")
        .put("pid", 123).put("startTicks", 456L).put("ppid", AndroidProcess.myPid())
        .put("uid0", AndroidProcess.myUid()).put("uid1", AndroidProcess.myUid()).put("uid2", AndroidProcess.myUid()).put("uid3", AndroidProcess.myUid())
        .put("role", "supervisor").put("status", "READY").put("exitCode", -1).put("childCount", 0).put("echild", false)
        .put("setResult", 0).put("getResult", 0).put("getValue", 1)
}
