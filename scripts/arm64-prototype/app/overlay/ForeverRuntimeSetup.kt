package app.gamenative

import android.content.Context
import android.net.Uri
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File

object ForeverRuntimeSetup {
    const val PANVK_WINE_PROBE_OPT_IN_EXTRA = "foreverPanvkWineProbe"
    private const val ASSET_ROOT = "forever/runtime"
    private const val PROTON = "proton-11.0-90624-arm64ec"
    private const val DXVK = "2.4.1-wow-aarch64-test"

    data class Profiles(val wine: ContentProfile, val dxvk: ContentProfile)

    fun ensure(context: Context, progress: (String) -> Unit, cancelled: () -> Boolean = { false }): Profiles {
        val manager = ContentsManager(context)
        manager.syncContents()
        progress("runtime: resolving pinned Wine profile")
        val wine = importIfMissing(context, manager, "proton.wcp", PROTON, cancelled)
        progress("runtime: resolving pinned DXVK profile")
        val dxvk = importIfMissing(context, manager, "dxvk.wcp", DXVK, cancelled)
        checkCancelled(cancelled)
        val resolvedWine = resolveFullEntry(context, manager, wine, PROTON)
        val resolvedDxvk = resolveFullEntry(context, manager, dxvk, DXVK)
        check(com.winlator.core.WineInfo.fromIdentifier(context, manager, ContentsManager.getEntryName(resolvedWine)).isArm64EC()) {
            "pinned Wine profile is not ARM64EC"
        }
        return Profiles(resolvedWine, resolvedDxvk)
    }

    fun configureContainer(
        container: Container,
        profiles: Profiles,
        verifiedExe: File,
        runId: String,
        hostPackage: String = BuildConfig.APPLICATION_ID,
    ) {
        container.setExecutablePath(verifiedExe.name)
        container.setExecArgs("-config BetaSuspendedTest.wtf -d3d11")
        container.setWineVersion(ContentsManager.getEntryName(profiles.wine))
        container.setDXWrapper("dxvk-${profiles.dxvk.verName}")
        container.setDXWrapperConfig("version=${profiles.dxvk.verName},framerate=0,maxDeviceMemory=0,async=0,asyncCache=0")
        container.setEmulator("FEXCore")
        container.setContainerVariant(Container.BIONIC)
        container.setGraphicsDriver("wrapper")
        container.setGraphicsDriverVersion("System")
        container.setDisplayRenderer("vulkan")
        container.setAudioDriver("pulseaudio")
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL)
        // The run identity is part of the child launch contract; basename matching alone is unsafe.
        container.setEnvVars(com.winlator.core.envvars.EnvVars().apply {
            enforceManagedEnvironment(this, hostPackage, runId)
        }.toString())
        container.putExtra("arm64DiagnosticsPinnedDxvk", ContentsManager.getEntryName(profiles.dxvk))
        container.putExtra("foreverManaged", "true")
        container.putExtra("foreverRunId", runId)
        container.putExtra("foreverVerifiedExe", verifiedExe.absolutePath)
        container.putExtra("foreverScopedNoOpenGL", ForeverLaunchDebug.SCOPED_NO_OPENGL.toString())
        container.putExtra(PANVK_WINE_PROBE_OPT_IN_EXTRA, ForeverLaunchDebug.PANVK_WINE_PROBE.toString())
        container.saveData()
    }

    /** The small, explicit environment contract shared with the read-only preflight. */
    @JvmStatic
    fun enforceManagedEnvironment(env: com.winlator.core.envvars.EnvVars, hostPackage: String, runId: String) {
        declaredEnvironment(hostPackage, runId).forEach { (key, value) -> env.put(key, value) }
    }

    internal fun declaredEnvironment(hostPackage: String, runId: String): Map<String, String> {
        require(PACKAGE_IDENTIFIER.matches(hostPackage)) { "invalid Android host package: $hostPackage" }
        require(runId.isNotBlank() && runId.length <= 128 && runId.all { it.isLetterOrDigit() || it in "._-" }) {
            "invalid Forever run id"
        }
        return linkedMapOf(
            "WINEESYNC" to "0",
            "WOWFOREVER_RUN_ID" to runId,
            "WINEMU_HOST_PKG" to hostPackage,
            "HOST_PKG" to hostPackage,
            // Logging only: upstream DXVKHelper sets none. Keep Vulkan/feature settings unchanged.
            "DXVK_LOG_LEVEL" to "info",
            "DXVK_LOG_PATH" to "A:\\Logs",
            // Logging-only compiler resolution control: retain DLL search and
            // successful load messages instead of flooding the same bounded
            // snapshots with X11 traces. No file/relay/snoop/account channels.
            "WINEDEBUG" to "+timestamp,+pid,err+module,warn+module,trace+module,trace+loaddll",
        )
    }

    internal fun isAndroidPackageIdentifier(value: String): Boolean = PACKAGE_IDENTIFIER.matches(value)

    private fun importIfMissing(
        context: Context,
        manager: ContentsManager,
        name: String,
        version: String,
        cancelled: () -> Boolean,
    ): ContentProfile {
        val type = if (name.startsWith("proton", ignoreCase = true)) {
            ContentProfile.ContentType.CONTENT_TYPE_PROTON
        } else {
            ContentProfile.ContentType.CONTENT_TYPE_DXVK
        }
        manager.getProfiles(type).orEmpty().firstOrNull { it.verName == version }?.let {
            return verifyInstalled(context, it, version)
        }
        checkCancelled(cancelled)
        val staged = File(context.cacheDir, "forever-$name")
        context.assets.open("$ASSET_ROOT/$name").use { input -> staged.outputStream().use(input::copyTo) }
        var imported: ContentProfile? = null
        var failure: String? = null
        manager.extraContentFile(Uri.fromFile(staged), object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                failure = "$reason${e?.message?.let { ": $it" } ?: ""}"
            }

            override fun onSucceed(value: ContentProfile) {
                imported = value
            }
        })
        check(failure == null) { "runtime import failed: $failure" }
        val profile = checkNotNull(imported) { "runtime import returned no profile" }
        var finishFailure: String? = null
        manager.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                finishFailure = "$reason${e?.message?.let { ": $it" } ?: ""}"
            }

            override fun onSucceed(value: ContentProfile) = Unit
        })
        staged.delete()
        check(finishFailure == null) { "runtime install failed: $finishFailure" }
        checkCancelled(cancelled)
        manager.syncContents()
        val exact = manager.getProfiles(type).orEmpty().firstOrNull {
            it.verName == profile.verName && it.verCode == profile.verCode
        } ?: error("installed runtime profile is not resolvable: $version")
        return verifyInstalled(context, exact, version)
    }

    private fun resolveFullEntry(context: Context, manager: ContentsManager, profile: ContentProfile, version: String): ContentProfile {
        val entry = ContentsManager.getEntryName(profile)
        val exact = manager.getProfileByEntryName(entry)
            ?: error("full runtime entry disappeared: $entry")
        check(ContentsManager.getEntryName(exact) == entry) { "runtime profile lookup was not exact: $entry" }
        return verifyInstalled(context, exact, version)
    }

    private fun verifyInstalled(context: Context, profile: ContentProfile, version: String): ContentProfile {
        val entry = ContentsManager.getEntryName(profile)
        val wineProfile = version == PROTON
        val expectedType = if (wineProfile) ContentProfile.ContentType.CONTENT_TYPE_PROTON else ContentProfile.ContentType.CONTENT_TYPE_DXVK
        check(profile.verName == version && profile.verCode == 1 && profile.type == expectedType) { "unexpected runtime version: $entry" }
        // WCP `files` lists DLLs to copy into ImageFS, not all installed files.
        // ContentsManager applies that list only to non-Wine/Proton components.
        val copyFiles = checkNotNull(profile.fileList) { "runtime profile has no copy list: $entry" }
        check(wineProfile || copyFiles.isNotEmpty()) { "runtime profile has no installed file manifest: $entry" }
        val installDir = ContentsManager.getInstallDir(context, profile).canonicalFile
        check(installDir.isDirectory) { "runtime profile install directory is missing: ${installDir.absolutePath}" }
        copyFiles.forEach { entryFile ->
            installedFile(installDir, entryFile.source)
        }
        if (wineProfile) {
            val bin = installedPath(installDir, profile.wineBinPath)
            val lib = installedPath(installDir, profile.wineLibPath)
            check(bin.isDirectory && lib.isDirectory) { "installed Wine binary/library directory is missing: $entry" }
            for (binary in listOf("wine", "wineserver")) {
                val executable = installedFile(installDir, "${profile.wineBinPath}/$binary")
                check(executable.canExecute()) { "installed Wine binary is not executable: $binary" }
            }
            installedFile(installDir, profile.winePrefixPack)
            // The pinned Proton normalizes its architecture directories to lib/wine.
            installedFile(installDir, "${profile.wineLibPath}/wine/aarch64-unix/ntdll.so")
            installedFile(installDir, "${profile.wineLibPath}/wine/aarch64-windows/ntdll.dll")
        } else {
            check(copyFiles.map { it.source }.toSet().containsAll(listOf("system32/dxgi.dll", "system32/d3d11.dll"))) {
                "pinned DXVK copy list is missing required ARM64 DLLs: $entry"
            }
        }
        return profile
    }

    private fun installedPath(root: File, relative: String?): File {
        check(!relative.isNullOrBlank() && !File(relative).isAbsolute) { "invalid installed runtime path: $relative" }
        val file = File(root, relative).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file != root) { "installed runtime path escaped profile: $relative" }
        return file
    }

    private fun installedFile(root: File, relative: String?): File = installedPath(root, relative).also { file ->
        check(file.isFile && file.length() > 0) { "installed runtime file missing or empty: $relative" }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        check(!cancelled()) { "runtime setup cancelled" }
    }

    private val PACKAGE_IDENTIFIER = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
}
