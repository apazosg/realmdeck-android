package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalServerProfilesTest {
    @Test
    fun originalConfigIsUnchangedAndClassicChangesOnlyPortal() {
        val original = "SET portal \"original.example\"\nSET unknownCVar \"kept\"\n"
        val originalConfig = FunctionalServerProfileConfig("original", null, false, "none", 70009)
        val classicConfig = FunctionalServerProfileConfig("classic_forever", "override.example", false, "forever-contract-v1", 70009)

        assertEquals(original, ForeverFunctionalServerProfiles.deriveConfig(original, originalConfig))
        assertEquals(
            "SET portal \"override.example\"\nSET unknownCVar \"kept\"\n",
            ForeverFunctionalServerProfiles.deriveConfig(original, classicConfig),
        )
        assertEquals(original, "SET portal \"original.example\"\nSET unknownCVar \"kept\"\n")
    }

    @Test
    fun hostnameOverrideRejectsUrlPathWhitespaceAndQuotes() {
        assertEquals("host.example", ForeverFunctionalServerProfiles.validateHostnameOverride("host.example"))
        assertEquals(null, ForeverFunctionalServerProfiles.validateHostnameOverride(""))
        listOf("https://host.example", "host.example/path", "host example", "host.example\";x").forEach { value ->
            try {
                ForeverFunctionalServerProfiles.validateHostnameOverride(value)
                throw AssertionError("accepted invalid hostname: $value")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun defaultsAreClassicAndPatchOff() {
        // The server profile is fixed at build time (Classic Forever for this
        // product); there is no runtime user selection and diagnostics stay
        // opt-in off.
        assertEquals(FunctionalServerProfile.CLASSIC_FOREVER, ForeverFunctionalServerProfiles.EFFECTIVE_PROFILE)
        val settings = ForeverFunctionalServerProfiles.defaultSettings()
        assertEquals(FunctionalServerProfile.CLASSIC_FOREVER, settings.profile)
        assertFalse(settings.memoryPatchEnabled)
        assertFalse(settings.codeCaptureEnabled)
        assertEquals(null, settings.portalOverride)
    }

    @Test
    fun storedOriginalProfileCannotOverrideBuildTimeProfile() {
        // A stale ORIGINAL preference from an older install is coerced to the
        // build-time profile on both load and save.
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            context.getSharedPreferences("functional_poc_server_profiles", Context.MODE_PRIVATE).edit()
                .putString("profile_id", "original")
                .putBoolean("memory_patch_enabled", true)
                .putBoolean("code_capture_enabled", true)
                .apply()
            val loaded = ForeverFunctionalServerProfiles.load(context)
            assertEquals(FunctionalServerProfile.CLASSIC_FOREVER, loaded.profile)
            ForeverFunctionalServerProfiles.save(
                context,
                FunctionalServerProfileSettings(profile = FunctionalServerProfile.ORIGINAL),
            )
            val reloaded = ForeverFunctionalServerProfiles.load(context)
            assertEquals(FunctionalServerProfile.CLASSIC_FOREVER, reloaded.profile)
        } finally {
            ForeverFunctionalServerProfiles.save(context, ForeverFunctionalServerProfiles.defaultSettings())
        }
    }

    @Test
    fun settingsPersistWithoutCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selected = FunctionalServerProfileSettings(
            profile = FunctionalServerProfile.CLASSIC_FOREVER,
            portalOverride = "future.example",
            memoryPatchEnabled = true,
            codeCaptureEnabled = true,
        )
        try {
            ForeverFunctionalServerProfiles.save(context, selected)
            val loaded = ForeverFunctionalServerProfiles.load(context)
            assertEquals(selected, loaded)
        } finally {
            ForeverFunctionalServerProfiles.save(context, ForeverFunctionalServerProfiles.defaultSettings())
        }
    }

    @Test
    fun disabledPatchDoesNotCallLaunchHook() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("functional-poc", ".jsonl", context.cacheDir)
        val prepared = ForeverFunctionalPoc.Prepared(
            Container("functional-poc-test"),
            context.cacheDir,
            file,
            "original",
            false,
            File(context.cacheDir, "WowB-ARM64.exe"),
        )
        var calls = 0
        ForeverFunctionalPoc.patchLaunchHook = { _, _ ->
            calls += 1
            {}
        }
        try {
            ForeverFunctionalPoc.beforeLaunch(context, prepared)
            assertEquals(0, calls)
            assertTrue(file.readText().isEmpty())
        } finally {
            ForeverFunctionalPoc.patchLaunchHook = null
            file.delete()
        }
    }

    @Test
    fun launcherPresetIsIdempotentCaseInsensitiveAndPreservesForeignLines() {
        val original = """
            SET gxResolution "2460x1080"
            SET MAXFPS "12"
            SET maxFPSBk "8"
            SET graphicsQuality "4"
            SET ENABLEBGDL "7"
            SET sound_mastervolume "0.8"
            SET SHOWPHOTOSENSITIVITYWARNING "1"
            SET accessibilityScreenNarrationEnabled "1"
            SET accessibilityscreennarrationenabled "1"
            SET GAMEPADENABLE "0"
            SET gamepadenable "0"
            SET unrelatedSetting "keep-me"
        """.trimIndent() + "\n"
        val config = FunctionalServerProfileConfig("classic_forever", "portal.example", false, "forever-contract-v1", 70009)

        val first = ForeverFunctionalServerProfiles.applyPocOverrides(
            ForeverFunctionalServerProfiles.deriveConfig(original, config),
        )
        val second = ForeverFunctionalServerProfiles.applyPocOverrides(first)

        assertEquals(first, second)
        assertTrue(first.contains("SET gxResolution \"2460x1080\""))
        assertTrue(first.contains("SET unrelatedSetting \"keep-me\""))
        assertEquals(1, Regex("""(?im)^SET maxFPS "30"$""").findAll(first).count())
        assertEquals(1, Regex("""(?im)^SET GamePadEnable "1"$""").findAll(first).count())
        assertTrue(first.contains("SET graphicsQuality \"0\""))
        assertTrue(first.contains("SET InputDeviceInterfaceStyle \"1\""))
        assertEquals(1, Regex("""(?im)^SET enableBGDL \"0\"$""").findAll(first).count())
        assertEquals(1, Regex("""(?im)^SET Sound_MasterVolume \"0.03\"$""").findAll(first).count())
        assertEquals(1, Regex("""(?im)^SET showPhotosensitivityWarning \"0\"$""").findAll(first).count())
        assertEquals(1, Regex("""(?im)^SET accessibilityScreenNarrationEnabled \"0\"$""").findAll(first).count())
    }

    @Test
    fun windowsLineEndingsDoNotRetainDuplicateManagedKeysOrPortals() {
        val original = "SET PORTAL \"old.example\"\r\n" +
            "SET portal \"duplicate.example\"\r\n" +
            "SET MAXFPS \"12\"\r\nSET maxfps \"8\"\r\n" +
            "SET foreignSetting \"keep\"\r\n"
        val config = FunctionalServerProfileConfig("classic_forever", "portal.example", false, "forever-contract-v1", 70009)
        val merged = ForeverFunctionalServerProfiles.applyPocOverrides(
            ForeverFunctionalServerProfiles.deriveConfig(original, config),
        )
        assertEquals(1, Regex("(?im)^SET portal ").findAll(merged).count())
        assertEquals(1, Regex("(?im)^SET maxFPS ").findAll(merged).count())
        assertTrue(merged.contains("SET portal \"portal.example\""))
        assertTrue(merged.contains("SET maxFPS \"30\""))
        assertTrue(merged.contains("SET foreignSetting \"keep\"\r\n"))
        assertEquals(merged, ForeverFunctionalServerProfiles.applyPocOverrides(merged))
    }

    @Test
    fun inGameGraphicsChoicesSurviveLaterPreparationAndResolution() {
        val config = FunctionalServerProfileConfig("classic_forever", "portal.example", false, "forever-contract-v1", 70009)
        val initial = ForeverFunctionalServerProfiles.applyPocOverrides(
            ForeverFunctionalServerProfiles.deriveConfig("SET portal \"old.example\"\n", config),
        )
        assertTrue(initial.contains("SET graphicsQuality \"0\""))
        assertTrue(initial.contains("SET maxFPS \"30\""))
        // Simulate choices saved inside the game.
        val edited = initial
            .replace("SET graphicsQuality \"0\"", "SET graphicsQuality \"4\"")
            .replace("SET maxFPS \"30\"", "SET maxFPS \"60\"")
            .replaceFirst("SET graphicsShadowQuality \"0\"", "SET graphicsShadowQuality \"2\"")
        val preserved = ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(
            ForeverFunctionalServerProfiles.deriveConfig(edited, config),
        )
        assertTrue(preserved.contains("SET graphicsQuality \"4\""))
        assertTrue(preserved.contains("SET maxFPS \"60\""))
        assertTrue(preserved.contains("SET graphicsShadowQuality \"2\""))
        // Launcher-owned input and portal policy are still imposed.
        assertTrue(preserved.contains("SET GamePadEnable \"1\""))
        assertTrue(preserved.contains("SET portal \"portal.example\""))
        assertEquals(preserved, ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(preserved))
        // A resolution change updates display keys without resetting graphics.
        val plan = ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            ForeverFunctionalDisplayFit.Viewport(2460, 1080),
        )
        val replanned = ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(
            ForeverFunctionalServerProfiles.deriveConfig(edited, config),
            plan,
        )
        assertTrue(replanned.contains("SET gxResolution \"${plan.size}\""))
        assertTrue(replanned.contains("SET graphicsQuality \"4\""))
        assertTrue(replanned.contains("SET maxFPS \"60\""))
        assertEquals(replanned, ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(replanned, plan))
    }

    @Test
    fun graphicsResetRestoresOnlyThePreset() {
        val config = FunctionalServerProfileConfig("classic_forever", "portal.example", false, "forever-contract-v1", 70009)
        val plan = ForeverFunctionalDisplayFit.calculate(
            ForeverFunctionalDisplayFit.Mode.AUTO,
            ForeverFunctionalDisplayFit.Viewport(2460, 1080),
        )
        val edited = ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(
            ForeverFunctionalServerProfiles.deriveConfig("SET portal \"old.example\"\n", config),
            plan,
        )
            .replace("SET graphicsQuality \"0\"", "SET graphicsQuality \"4\"")
            .replace("SET maxFPS \"30\"", "SET maxFPS \"60\"")
            .replaceFirst("SET graphicsShadowQuality \"0\"", "SET graphicsShadowQuality \"2\"") +
            "SET foreignSetting \"keep\"\n" +
            "SET GamePadEnable \"0\"\n"
        val reset = ForeverFunctionalServerProfiles.resetGraphicsPreset(edited)
        assertTrue(reset.contains("SET graphicsQuality \"0\""))
        assertTrue(reset.contains("SET maxFPS \"30\""))
        assertTrue(reset.contains("SET graphicsShadowQuality \"0\""))
        assertEquals(1, Regex("(?im)^SET graphicsQuality ").findAll(reset).count())
        // Resolution, portal, input, legacy and foreign lines are untouched.
        assertTrue(reset.contains("SET gxResolution \"${plan.size}\""))
        assertTrue(reset.contains("SET portal \"portal.example\""))
        assertTrue(reset.contains("SET GamePadEnable \"0\""))
        assertTrue(reset.contains("SET foreignSetting \"keep\""))
        assertEquals(reset, ForeverFunctionalServerProfiles.resetGraphicsPreset(reset))
    }

    @Test
    fun preservedGraphicsKeepCrlfForeignBytes() {
        val original = "SET graphicsQuality \"4\"\r\n" +
            "SET GRAPHICSQUALITY \"5\"\r\n" +
            "SET maxFPS \"60\"\r\n" +
            "SET foreignSetting \"keep\"\r\n"
        val merged = ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(original)
        assertTrue(merged.contains("SET graphicsQuality \"4\"\r\n"))
        assertEquals(1, Regex("(?im)^SET graphicsQuality ").findAll(merged).count())
        assertEquals(1, Regex("(?im)^SET maxFPS ").findAll(merged).count())
        assertTrue(merged.contains("SET foreignSetting \"keep\"\r\n"))
        assertTrue(merged.contains("SET graphicsShadowQuality \"0\""))
        assertTrue(merged.contains("SET GamePadEnable \"1\""))
        assertEquals(merged, ForeverFunctionalServerProfiles.mergeDerivedPreservingGraphics(merged))
    }
}
