package app.gamenative

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.winlator.core.AppUtils

/** Small, app-owned UX seam around the existing GameNative presentation/input APIs. */
object ForeverFunctionalLauncherUx {
    const val CLIENT_BUILD = "1.60.1.70009"

    data class ClientReadiness(
        val folderPresent: Boolean,
        val executablePresent: Boolean,
        val originalConfigPresent: Boolean,
        val dataPresent: Boolean = false,
        val storageAvailable: Boolean = true,
        val storageLabel: String = "Almacenamiento no seleccionado",
        val storageRoute: String = "No disponible",
        val storageState: ForeverFunctionalClientStorage.ClientInspection.State =
            ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED,
    ) {
        val ready: Boolean
            get() = folderPresent && executablePresent && originalConfigPresent && dataPresent &&
                storageState == ForeverFunctionalClientStorage.ClientInspection.State.READY

        // Presence permits requesting the strong local preparation, not launching
        // or claiming integrity. Pasted sources still require explicit admission.
        val canRequestPlay: Boolean
            get() = storageAvailable && folderPresent && executablePresent && dataPresent &&
                (storageState == ForeverFunctionalClientStorage.ClientInspection.State.READY ||
                    storageState == ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED)

        val label: String
            get() = when {
                !storageAvailable -> "Almacenamiento seleccionado no disponible"
                ready -> "Cliente preparado para comprobar"
                storageState == ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED ->
                    "Datos presentes; integridad no verificada"
                storageState == ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE ->
                    "Cliente pegado detectado; sin verificar"
                storageState == ForeverFunctionalClientStorage.ClientInspection.State.INCOMPLETE ->
                    "Cliente incompleto"
                !folderPresent -> "Cliente no instalado"
                !executablePresent -> "Falta WowB-ARM64.exe"
                else -> "Falta la configuración original"
            }
    }

    /**
     * Single home state, derived from the live download hub plus local
     * readiness. Priority: an active download dominates everything (even the
     * initial snapshot); then unknown/unavailable storage (fail closed, never
     * an actionable ready/missing on unchecked storage); then missing
     * client, incomplete/unverified, ready. READY stays a local-presence
     * statement, never an integrity proof.
     */
    sealed interface HomeState {
        data object Checking : HomeState
        data class Downloading(val applying: Boolean) : HomeState
        data object StorageUnavailable : HomeState
        data object Missing : HomeState
        data object Incomplete : HomeState
        data object Ready : HomeState
    }

    fun deriveHomeState(
        checking: Boolean,
        storageKnown: Boolean,
        readiness: ClientReadiness,
        downloadBusy: Boolean,
        downloadApplying: Boolean,
    ): HomeState = when {
        downloadBusy -> HomeState.Downloading(applying = downloadApplying)
        checking -> HomeState.Checking
        !storageKnown || !readiness.storageAvailable -> HomeState.StorageUnavailable
        readiness.storageState == ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED ||
            (!readiness.folderPresent && !readiness.executablePresent &&
                !readiness.originalConfigPresent && !readiness.dataPresent) ->
            HomeState.Missing
        readiness.storageState == ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE ->
            HomeState.Incomplete
        !readiness.ready -> HomeState.Incomplete
        else -> HomeState.Ready
    }

    fun inspectClient(context: android.content.Context): ClientReadiness {
        val selected = ForeverFunctionalClientStorage.service(context).snapshot().selected
        val client = selected?.client
        val folder = selected?.root
        return ClientReadiness(
            folderPresent = folder?.isDirectory == true,
            executablePresent = client?.executablePresent == true,
            originalConfigPresent = client?.originalConfigPresent == true,
            dataPresent = client?.dataPresent == true,
            storageAvailable = selected?.available == true,
            storageLabel = selected?.label ?: "Almacenamiento no seleccionado",
            storageRoute = selected?.route ?: "No disponible",
            storageState = client?.state ?: ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED,
        )
    }

    fun hideSystemUi(activity: Activity) {
        AppUtils.hideSystemUI(activity, true)
    }

    fun showSystemUi(activity: Activity) {
        AppUtils.hideSystemUI(activity, false)
    }

    fun isImeVisible(window: Window): Boolean =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * Hide only the drawn touchscreen controls; ControllerManager remains active.
     *
     * XServerScreen can show the profile again after its first layout.  Keep this
     * operation idempotent so a layout hook can enforce the app-owned policy
     * without rebuilding physical-controller mappings on every callback.
     */
    fun hideTouchscreenGamepad() {
        PluviaApp.inputControlsView?.let { controls ->
            val needsHide = controls.isShowTouchscreenControls ||
                controls.visibility != View.GONE ||
                controls.profile != null
            if (!needsHide) return@let
            controls.setShowTouchscreenControls(false)
            controls.visibility = View.GONE
            // This is the same external GameNative hide path: it stops drawing
            // the profile while retaining the gyro profile and IME callback.
            controls.hideProfileForOverlay()
            controls.invalidate()
            // Refresh only after a real visibility/profile transition.  This
            // preserves the physical WinHandler route without polling it.
            PluviaApp.xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()
        }
    }

    /**
     * Re-assert the hidden overlay after XServerScreen creates or lays out its
     * InputControlsView. Returns a cleanup callback for the owning composition.
     */
    fun installTouchscreenGamepadSuppression(activity: Activity): () -> Unit {
        val decor = activity.window.decorView
        val observer = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                hideTouchscreenGamepad()
            }
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            hideTouchscreenGamepad()
            true
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(observer)
        decor.viewTreeObserver.addOnPreDrawListener(preDraw)
        hideTouchscreenGamepad()
        return {
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(observer)
                decor.viewTreeObserver.removeOnPreDrawListener(preDraw)
            }
        }
    }

    /** Uses the existing XServer/InputControlsView IME bridge, not a fake text field. */
    fun requestRealKeyboard() {
        PluviaApp.inputControlsView?.triggerShowKeyboard()
    }

    fun hideRealKeyboard(view: View) {
        val manager = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        val token = PluviaApp.inputControlsView?.windowToken ?: view.windowToken ?: return
        manager.hideSoftInputFromWindow(token, 0)
    }
}

/** Tracks sticky Ctrl/Alt keys and emits balanced events for accessory-key taps. */
internal class ForeverFunctionalKeyboardKeys(
    private val dispatch: (keyCode: Int, action: Int) -> Unit,
) {
    private val pressedModifiers = linkedSetOf<Int>()

    fun tap(keyCode: Int) {
        dispatch(keyCode, android.view.KeyEvent.ACTION_DOWN)
        dispatch(keyCode, android.view.KeyEvent.ACTION_UP)
    }

    fun toggleModifier(keyCode: Int): Boolean {
        require(keyCode == android.view.KeyEvent.KEYCODE_CTRL_LEFT ||
            keyCode == android.view.KeyEvent.KEYCODE_ALT_LEFT)
        val action = if (pressedModifiers.add(keyCode)) {
            android.view.KeyEvent.ACTION_DOWN
        } else {
            pressedModifiers.remove(keyCode)
            android.view.KeyEvent.ACTION_UP
        }
        dispatch(keyCode, action)
        return action == android.view.KeyEvent.ACTION_DOWN
    }

    fun releaseModifiers() {
        pressedModifiers.toList().forEach { keyCode ->
            dispatch(keyCode, android.view.KeyEvent.ACTION_UP)
        }
        pressedModifiers.clear()
    }
}
