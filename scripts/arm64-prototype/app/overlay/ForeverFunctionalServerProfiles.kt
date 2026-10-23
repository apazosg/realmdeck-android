package app.gamenative

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The connection choice for the unmanaged functional PoC.
 *
 * This is deliberately independent from the Forever patch contract.  A future
 * endpoint can therefore be added without making the endpoint name decide
 * whether a memory patch is permitted.
 */
enum class FunctionalServerProfile(
    val id: String,
    val displayName: String,
    val configFileName: String,
) {
    ORIGINAL("original", "Original", "BetaFunctionalPoc-original.wtf"),
    CLASSIC_FOREVER("classic_forever", "Classic Forever", "BetaFunctionalPoc-classic_forever.wtf"),
    ;

    companion object {
        fun fromId(value: String?): FunctionalServerProfile =
            values().firstOrNull { it.id == value } ?: ORIGINAL
    }
}

data class FunctionalServerProfileSettings(
    val profile: FunctionalServerProfile = FunctionalServerProfile.ORIGINAL,
    val portalOverride: String? = null,
    val memoryPatchEnabled: Boolean = false,
    val codeCaptureEnabled: Boolean = false,
)

data class FunctionalServerProfileConfig(
    val profileId: String,
    val portalHost: String?,
    val memoryPatchEnabled: Boolean,
    val patchContractId: String,
    val compatibleBuild: Int,
    val codeCaptureEnabled: Boolean = false,
) {
    val profile: FunctionalServerProfile
        get() = FunctionalServerProfile.fromId(profileId)

    val configFileName: String
        get() = profile.configFileName
}

object ForeverFunctionalServerProfiles {
    private const val PREFS = "functional_poc_server_profiles"
    private const val PROFILE_KEY = "profile_id"
    private const val PORTAL_OVERRIDE_KEY = "classic_portal_override"
    private const val MEMORY_PATCH_KEY = "memory_patch_enabled"
    private const val CODE_CAPTURE_KEY = "code_capture_enabled"
    private const val FOREVER_PATCH_CONTRACT = "forever-contract-v1"
    private const val COMPATIBLE_BUILD = 70009

    /**
     * Build-time effective profile for this product: Classic Forever. Injected
     * by the source recipes as BuildConfig.FOREVER_SERVER_PROFILE and
     * validated here; a stored ORIGINAL preference from an older install can
     * never override it. The enum keeps both values for internal/test
     * compatibility, but there is no runtime user selection.
     */
    val EFFECTIVE_PROFILE: FunctionalServerProfile =
        FunctionalServerProfile.fromId(BuildConfig.FOREVER_SERVER_PROFILE)
            .also {
                check(it == FunctionalServerProfile.CLASSIC_FOREVER) {
                    "build must fix the server profile to classic_forever"
                }
            }

    /** The persisted default is intentionally unpatched and credential-free. */
    fun defaultSettings(): FunctionalServerProfileSettings = FunctionalServerProfileSettings(
        profile = EFFECTIVE_PROFILE,
    )

    fun load(context: Context): FunctionalServerProfileSettings {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The build-time profile wins over any stored preference: an old
        // ORIGINAL value is coerced, never honored.
        val rawOverride = preferences.getString(PORTAL_OVERRIDE_KEY, null)
        val portalOverride = validateHostnameOverride(rawOverride)
        return FunctionalServerProfileSettings(
            profile = EFFECTIVE_PROFILE,
            portalOverride = portalOverride,
            memoryPatchEnabled = preferences.getBoolean(MEMORY_PATCH_KEY, false),
            codeCaptureEnabled = preferences.getBoolean(CODE_CAPTURE_KEY, false),
        )
    }

    fun save(context: Context, settings: FunctionalServerProfileSettings) {
        val override = validateHostnameOverride(settings.portalOverride)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PROFILE_KEY, EFFECTIVE_PROFILE.id)
            .putBoolean(MEMORY_PATCH_KEY, settings.memoryPatchEnabled)
            .putBoolean(CODE_CAPTURE_KEY, settings.codeCaptureEnabled)
            .apply {
                if (override == null) remove(PORTAL_OVERRIDE_KEY) else putString(PORTAL_OVERRIDE_KEY, override)
            }
            .apply()
    }

    /** Resolves the endpoint from the versioned contract, not from user input. */
    fun resolve(context: Context, settings: FunctionalServerProfileSettings): FunctionalServerProfileConfig {
        if (settings.profile == FunctionalServerProfile.ORIGINAL) {
            return FunctionalServerProfileConfig(
                profileId = settings.profile.id,
                portalHost = null,
                memoryPatchEnabled = false,
                codeCaptureEnabled = false,
                patchContractId = "none",
                compatibleBuild = COMPATIBLE_BUILD,
            )
        }
        val contractPortal = ForeverContract.read(context)
            .getJSONObject("server")
            .getString("portal")
        require(validateHostnameOverride(contractPortal) == contractPortal) {
            "versioned Forever contract has an invalid portal hostname"
        }
        val portal = validateHostnameOverride(settings.portalOverride) ?: contractPortal
        return FunctionalServerProfileConfig(
            profileId = settings.profile.id,
            portalHost = portal,
            memoryPatchEnabled = settings.memoryPatchEnabled,
            codeCaptureEnabled = settings.codeCaptureEnabled,
            patchContractId = FOREVER_PATCH_CONTRACT,
            compatibleBuild = COMPATIBLE_BUILD,
        )
    }

    /**
     * Blank means no override.  Everything else must be a hostname only: no
     * URL syntax, path, whitespace, quotes, shell metacharacters, or control
     * characters are accepted.
     */
    fun validateHostnameOverride(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        require(value == value.trim()) { "portal override must not contain surrounding whitespace" }
        require(value.length <= 253 && value.none { it.isWhitespace() || it.isISOControl() }) {
            "portal override must be a hostname without whitespace"
        }
        require(value.matches(HOSTNAME)) { "portal override must be a hostname only" }
        return value
    }

    /** Pure config transformation used by both the Android path and JVM tests. */
    fun deriveConfig(originalText: String, config: FunctionalServerProfileConfig): String {
        if (config.profile == FunctionalServerProfile.ORIGINAL) return originalText
        val portal = requireNotNull(config.portalHost)
        val lines = originalText.split('\n').toMutableList()
        var found = false
        val result = lines.mapNotNull { line ->
            if (!PORTAL_LINE.matches(line.removeSuffix("\r"))) return@mapNotNull line
            if (found) return@mapNotNull null
            found = true
            "SET portal \"$portal\""
        }.toMutableList()
        if (!found) result += "SET portal \"$portal\""
        return result.joinToString("\n")
    }

    /**
     * One-time graphics defaults for a newly created derived file: visible
     * quality level 1 (graphicsQuality=0 is the game's visible level 1),
     * shadows off, and a 30 FPS cap. These apply only on first creation or
     * on an explicit user reset; later preparations preserve whatever the
     * game saved instead of re-imposing them.
     */
    val GRAPHICS_PRESET_DEFAULTS: Map<String, String> = linkedMapOf(
        "maxFPS" to "30",
        "maxFPSBk" to "30",
        "useMaxFPS" to "1",
        "useMaxFPSBk" to "1",
        "useTargetFPS" to "0",
        // graphicsQuality=0 is the game's visible quality level 1.
        "graphicsQuality" to "0",
        "graphicsShadowQuality" to "0",
        "graphicsViewDistance" to "0",
        "graphicsEnvironmentDetail" to "0",
        "graphicsGroundClutter" to "0",
        "graphicsTextureResolution" to "0",
        "graphicsParticleDensity" to "1",
        "graphicsSpellDensity" to "1",
        "graphicsLiquidDetail" to "0",
        "graphicsSunshafts" to "0",
        "graphicsProjectedTextures" to "0",
        "graphicsSSAO" to "0",
        "graphicsDepthEffects" to "0",
        "graphicsComputeEffects" to "0",
        "graphicsOutlineMode" to "0",
        "graphicsPBRLiquidDetail" to "0",
        "graphicsBloomUserMult" to "0",
    )

    /**
     * Launcher-owned keys that are re-imposed on every preparation: the
     * legacy resource/audio baseline, the driver/input policy, and (once a
     * landscape viewport has been measured) the frozen display plan.
     */
    private fun alwaysManagedKeys(displayPlan: ForeverFunctionalDisplayFit.Plan?): Map<String, String> {
        val always = linkedMapOf(
            // Legacy functional-PoC controls remain separate from the new UX
            // preset. They preserve the tested resource/audio baseline; this is
            // not a new resource policy or global warning acknowledgement.
            "enableBGDL" to "0",
            "accessibilityScreenNarrationEnabled" to "0",
            "Sound_MasterVolume" to "0.03",
            "showPhotosensitivityWarning" to "0",
            // Launcher-owned driver/input controls. Gamepad and the D3D11
            // baseline stay imposed; per-launch graphics taste lives in
            // GRAPHICS_PRESET_DEFAULTS and is preserved once saved in-game.
            "gxApi" to "D3D11",
            "vsync" to "0",
            "hwDetect" to "0",
            "GamePadEnable" to "1",
            "InputDeviceInterfaceStyle" to "1",
        )
        // These are written only after the launcher has measured the final
        // landscape viewport.  gxWindow is intentionally not owned: its
        // fullscreen/window policy is client-version specific and must not be
        // guessed from a portrait preparation pass.
        if (displayPlan != null) {
            always["gxResolution"] = displayPlan.size
            always["gxWindowedResolution"] = displayPlan.size
            always["gxFullscreenResolution"] = displayPlan.size
            always["gxMaximize"] = "1"
        }
        return always
    }

    private fun mergeConfigText(
        text: String,
        displayPlan: ForeverFunctionalDisplayFit.Plan?,
        imposeGraphics: Boolean,
        onlyGraphics: Boolean,
    ): String {
        val imposed = linkedMapOf<String, String>().apply {
            if (!onlyGraphics) putAll(alwaysManagedKeys(displayPlan))
            if (onlyGraphics || imposeGraphics) putAll(GRAPHICS_PRESET_DEFAULTS)
        }
        val preserved = if (!onlyGraphics && !imposeGraphics) {
            GRAPHICS_PRESET_DEFAULTS.keys.map(String::lowercase).toSet()
        } else {
            emptySet()
        }
        val imposedKeys = imposed.keys.map(String::lowercase).toSet()
        val seen = mutableSetOf<String>()
        val sourceLines = text.trimEnd('\n').let { if (it.isEmpty()) emptyList() else it.split('\n') }
        val result = sourceLines.mapNotNull { line ->
            // split('\n') retains CR from Windows CRLF files. Java Regex '.'
            // excludes CR, so match a logical line but preserve unowned bytes.
            val key = SET_KEY_LINE.matchEntire(line.removeSuffix("\r"))?.groupValues?.get(1)
            if (key == null || (key.lowercase() !in imposedKeys && key.lowercase() !in preserved)) {
                line
            } else if (!seen.add(key.lowercase())) {
                null
            } else if (key.lowercase() in preserved) {
                // The game owns this value now: keep the saved line byte-exact
                // (including any CRLF) and drop later duplicates.
                line
            } else {
                // Canonical spelling/value makes repeated preparation stable.
                "SET ${imposed.keys.first { it.equals(key, ignoreCase = true) }} \"${imposed.entries.first { it.key.equals(key, ignoreCase = true) }.value}\""
            }
        }.toMutableList()
        imposed.forEach { (key, value) ->
            if (key.lowercase() !in seen) result += "SET $key \"$value\""
        }
        if (!onlyGraphics && !imposeGraphics) {
            GRAPHICS_PRESET_DEFAULTS.forEach { (key, value) ->
                if (key.lowercase() !in seen) result += "SET $key \"$value\""
            }
        }
        return result.joinToString("\n").trimEnd('\n') + "\n"
    }

    /**
     * Applies the launcher-owned preset for the first creation of a private
     * derived file.
     *
     * The global client config and BetaSuspendedTest.wtf are never passed here for writing.  A
     * managed key is replaced once, regardless of spelling or duplicate entries;
     * unrelated lines retain their original order and bytes.
     *
     * Later preparations must use [mergeDerivedPreservingGraphics] so graphics
     * choices saved inside the game survive; an explicit user reset goes
     * through [resetGraphicsPreset].
     */
    fun applyPocOverrides(
        text: String,
        displayPlan: ForeverFunctionalDisplayFit.Plan? = null,
    ): String = mergeConfigText(text, displayPlan, imposeGraphics = true, onlyGraphics = false)

    /**
     * Merges an existing derived file: launcher-owned keys (portal via
     * [deriveConfig], plus the legacy/driver/input/display keys) are
     * re-imposed, while graphics-preset keys already present keep the values
     * the game saved. Missing graphics keys receive the one-time defaults.
     * Unrelated lines retain their original order and bytes.
     */
    fun mergeDerivedPreservingGraphics(
        text: String,
        displayPlan: ForeverFunctionalDisplayFit.Plan? = null,
    ): String = mergeConfigText(text, displayPlan, imposeGraphics = false, onlyGraphics = false)

    /**
     * Restores only the graphics preset to its initial values, for the
     * explicit user reset. Resolution, portal, input, legacy and foreign
     * lines are preserved byte-exact; missing graphics keys are appended.
     */
    fun resetGraphicsPreset(text: String): String =
        mergeConfigText(text, null, imposeGraphics = true, onlyGraphics = true)

    fun writeDerivedConfig(
        gameFolder: File,
        originalText: String,
        config: FunctionalServerProfileConfig,
        displayPlan: ForeverFunctionalDisplayFit.Plan? = null,
    ): File {
        val directory = File(gameFolder, "WTF")
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "WTF directory is missing" }
        val target = File(directory, config.configFileName)
        require(!Files.isSymbolicLink(target.toPath())) { "derived PoC config is a symlink" }
        target.writeText(
            applyPocOverrides(deriveConfig(originalText, config), displayPlan),
            Charsets.UTF_8,
        )
        return target
    }

    private val HOSTNAME = Regex(
        "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
    )
    private val PORTAL_LINE = Regex("^\\s*SET\\s+portal\\s+.*$", RegexOption.IGNORE_CASE)
    private val SET_KEY_LINE = Regex("^\\s*SET\\s+([A-Za-z][A-Za-z0-9_]*)\\s+.*$", RegexOption.IGNORE_CASE)
}
