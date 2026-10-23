package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalDisplayFitTest {
    private val wide = ForeverFunctionalDisplayFit.Viewport(2460, 1080)

    @Test
    fun recreatedActivityCannotBypassThePreviousProcessIoLease() {
        val oldCoordinator = ForeverFunctionalDisplayFit.launchCoordinatorForProcess()
        val token = requireNotNull(oldCoordinator.begin())
        val lease = requireNotNull(oldCoordinator.acquireIo(token))
        oldCoordinator.invalidate(token)
        val recreatedCoordinator = ForeverFunctionalDisplayFit.launchCoordinatorForProcess()
        assertTrue(oldCoordinator === recreatedCoordinator)
        try {
            assertTrue(recreatedCoordinator.hasPendingIo())
            assertTrue(recreatedCoordinator.begin() == null)
        } finally {
            lease.close()
        }
        val replacement = requireNotNull(recreatedCoordinator.begin())
        recreatedCoordinator.invalidate(replacement)
    }

    @Test
    fun approvedModesPreserveUsefulAspectAndCapAtPhysicalViewport() {
        assertEquals("1640x720", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            wide,
        ).size)
        assertEquals("1230x540", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.PERFORMANCE,
            wide,
        ).size)
        assertEquals("2050x900", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.QUALITY,
            wide,
        ).size)
        assertEquals("2460x1080", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.NATIVE,
            wide,
        ).size)
        assertEquals("1280x720", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            ForeverFunctionalDisplayFit.Viewport(1280, 720),
        ).size)
    }

    @Test
    fun cutoutInsetsAreAppliedOnceAndSmallNativeValuesAreEvenAndClamped() {
        val insetViewport = ForeverFunctionalDisplayFit.Viewport(
            width = 2460,
            height = 1200,
            insets = ForeverFunctionalDisplayFit.Insets(top = 60, bottom = 60),
        )
        assertEquals("1640x720", ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            insetViewport,
        ).size)

        val small = ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.NATIVE,
            ForeverFunctionalDisplayFit.Viewport(801, 501),
        )
        assertTrue(small.width % 2 == 0)
        assertTrue(small.height % 2 == 0)
        assertTrue(small.width <= 801)
        assertTrue(small.height <= 501)
    }

    @Test
    fun cutoutOverlapUsesAbsoluteViewCoordinatesWithoutDoubleCounting() {
        val edgeToEdge = ForeverFunctionalDisplayFit.calculateOverlappingInsets(
            windowWidth = 2460,
            windowHeight = 1080,
            viewLeft = 0,
            viewTop = 0,
            viewWidth = 2460,
            viewHeight = 1080,
            physicalCutout = ForeverFunctionalDisplayFit.Insets(left = 80, right = 40),
        )
        assertEquals(80, edgeToEdge?.left)
        assertEquals(40, edgeToEdge?.right)

        val alreadyFitted = ForeverFunctionalDisplayFit.calculateOverlappingInsets(
            windowWidth = 2460,
            windowHeight = 1080,
            viewLeft = 80,
            viewTop = 0,
            viewWidth = 2340,
            viewHeight = 1080,
            physicalCutout = ForeverFunctionalDisplayFit.Insets(left = 80, right = 40),
        )
        assertEquals(0, alreadyFitted?.left)
        assertEquals(0, alreadyFitted?.right)
        assertEquals(null, ForeverFunctionalDisplayFit.calculateOverlappingInsets(
            windowWidth = 2460,
            windowHeight = 1080,
            viewLeft = 0,
            viewTop = 0,
            viewWidth = 2460,
            viewHeight = 1080,
            physicalCutout = null,
        ))
    }

    @Test
    fun portraitOrZeroViewportCannotProduceAPlan() {
        try {
            ForeverFunctionalDisplayFit.calculate(
                ForeverFunctionalDisplayFit.Mode.AUTO,
                ForeverFunctionalDisplayFit.Viewport(1080, 2460),
            )
            throw AssertionError("portrait viewport was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            ForeverFunctionalDisplayFit.Viewport(0, 0)
            throw AssertionError("zero viewport was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun gateNeedsTwoStableFramesAndCannotLaunchTwice() {
        val gate = ForeverFunctionalDisplayFit.ForeverFunctionalDisplayFitLaunchGate()
        assertTrue(gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, wide, 0L) is ForeverFunctionalDisplayFit.Observation.Waiting)
        val ready = gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, wide, 16L)
        assertTrue(ready is ForeverFunctionalDisplayFit.Observation.Ready)
        assertTrue(gate.isFinalized)
        assertTrue(gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, wide, 32L) is ForeverFunctionalDisplayFit.Observation.AlreadyFinalized)
        gate.cancel()
        assertFalse(gate.isFinalized)
        assertTrue(gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, wide, 48L) is ForeverFunctionalDisplayFit.Observation.Waiting)
    }

    @Test
    fun invalidViewportTimesOutAndPreferenceFallsBackToAuto() {
        val gate = ForeverFunctionalDisplayFit.ForeverFunctionalDisplayFitLaunchGate()
        val portrait = ForeverFunctionalDisplayFit.Viewport(1080, 2460)
        assertTrue(gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, portrait, 0L) is ForeverFunctionalDisplayFit.Observation.Invalid)
        assertTrue(gate.observe(ForeverFunctionalDisplayFit.Mode.AUTO, portrait, 5_000L) is ForeverFunctionalDisplayFit.Observation.TimedOut)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("functional_poc_display_fit", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("display_mode", "not-a-mode").commit()
            assertEquals(ForeverFunctionalDisplayFit.Mode.AUTO, ForeverFunctionalDisplayFit.loadMode(context))
            ForeverFunctionalDisplayFit.saveMode(context, ForeverFunctionalDisplayFit.Mode.QUALITY)
            assertEquals(ForeverFunctionalDisplayFit.Mode.QUALITY, ForeverFunctionalDisplayFit.loadMode(context))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun derivedDisplayKeysMatchOnePlanAndOriginalInputIsUntouched() {
        val original = "SET gxResolution \"old\"\r\n" +
            "SET GXRESOLUTION \"duplicate\"\r\n" +
            "SET GXWINDOWEDRESOLUTION \"old-window\"\r\n" +
            "SET gxwindowedresolution \"duplicate-window\"\r\n" +
            "SET gxFullscreenResolution \"old-fullscreen\"\r\n" +
            "SET GXFULLSCREENRESOLUTION \"duplicate-fullscreen\"\r\n" +
            "SET gxMaximize \"0\"\r\n" +
            "SET GXMAXIMIZE \"0\"\r\n" +
            "SET foreignSetting \"keep\"\r\n"
        val plan = ForeverFunctionalDisplayFit.calculate(ForeverFunctionalDisplayFit.Mode.AUTO, wide)
        val config = FunctionalServerProfileConfig("classic_forever", "portal.example", false, "forever-contract-v1", 70009)
        val merged = ForeverFunctionalServerProfiles.applyPocOverrides(
            ForeverFunctionalServerProfiles.deriveConfig(original, config),
            plan,
        )

        assertEquals(1, Regex("(?im)^SET gxResolution ").findAll(merged).count())
        assertEquals(1, Regex("(?im)^SET gxWindowedResolution ").findAll(merged).count())
        assertEquals(1, Regex("(?im)^SET gxFullscreenResolution ").findAll(merged).count())
        assertTrue(merged.contains("SET gxResolution \"1640x720\""))
        assertTrue(merged.contains("SET gxWindowedResolution \"1640x720\""))
        assertTrue(merged.contains("SET gxFullscreenResolution \"1640x720\""))
        assertTrue(merged.contains("SET gxMaximize \"1\""))
        assertTrue(merged.contains("SET foreignSetting \"keep\""))
        assertEquals(merged, ForeverFunctionalServerProfiles.applyPocOverrides(merged, plan))
        assertEquals("SET gxResolution \"old\"\r\n" +
            "SET GXRESOLUTION \"duplicate\"\r\n" +
            "SET GXWINDOWEDRESOLUTION \"old-window\"\r\n" +
            "SET gxwindowedresolution \"duplicate-window\"\r\n" +
            "SET gxFullscreenResolution \"old-fullscreen\"\r\n" +
            "SET GXFULLSCREENRESOLUTION \"duplicate-fullscreen\"\r\n" +
            "SET gxMaximize \"0\"\r\n" +
            "SET GXMAXIMIZE \"0\"\r\n" +
            "SET foreignSetting \"keep\"\r\n", original)
    }

    @Test
    fun noDisplayPlanDoesNotInventOrClearDisplayKeys() {
        val original = "SET gxResolution \"foreign\"\nSET unrelated \"keep\"\n"
        val merged = ForeverFunctionalServerProfiles.applyPocOverrides(original)
        assertTrue(merged.contains("SET gxResolution \"foreign\""))
        assertTrue(merged.contains("SET unrelated \"keep\""))
        assertFalse(merged.contains("SET gxWindowedResolution"))
        assertFalse(merged.contains("SET gxFullscreenResolution"))
        assertFalse(merged.contains("SET gxMaximize"))
    }

    @Test
    fun modeLabelsDescribeResolutionAndPersistedNamesStayCompatible() {
        // The APK selector is resolution-only: labels carry the target short
        // edge instead of quality words, while persisted names are unchanged.
        assertEquals("AUTO", ForeverFunctionalDisplayFit.Mode.AUTO.persistedName)
        assertEquals("PERFORMANCE", ForeverFunctionalDisplayFit.Mode.PERFORMANCE.persistedName)
        assertEquals("QUALITY", ForeverFunctionalDisplayFit.Mode.QUALITY.persistedName)
        assertEquals("NATIVE", ForeverFunctionalDisplayFit.Mode.NATIVE.persistedName)
        assertEquals(ForeverFunctionalDisplayFit.Mode.QUALITY, ForeverFunctionalDisplayFit.Mode.fromPersisted("quality"))
        assertEquals(ForeverFunctionalDisplayFit.Mode.AUTO, ForeverFunctionalDisplayFit.Mode.fromPersisted("not-a-mode"))
        for (mode in listOf(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            ForeverFunctionalDisplayFit.Mode.PERFORMANCE,
            ForeverFunctionalDisplayFit.Mode.QUALITY,
        )) {
            assertTrue(mode.label.contains(requireNotNull(mode.targetShortEdge).toString()))
        }
        assertFalse(ForeverFunctionalDisplayFit.Mode.PERFORMANCE.label.contains("Rendimiento"))
        assertFalse(ForeverFunctionalDisplayFit.Mode.QUALITY.label.contains("Calidad"))
    }

    @Test
    fun canceledIoLeaseCannotClobberAReplacementLaunch() {
        val coordinator = ForeverFunctionalDisplayFit.ForeverFunctionalDisplayFitLaunchCoordinator()
        val oldToken = requireNotNull(coordinator.begin())
        val lease = requireNotNull(coordinator.acquireIo(oldToken))
        coordinator.invalidate(oldToken)
        assertEquals(null, coordinator.begin())
        assertFalse(coordinator.canCommit(oldToken, lifecycleResumed = true))
        lease.close()

        val newToken = requireNotNull(coordinator.begin())
        assertFalse(coordinator.complete(oldToken))
        assertTrue(coordinator.canCommit(newToken, lifecycleResumed = true))
        assertTrue(coordinator.complete(newToken))
    }
}
