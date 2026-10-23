package app.gamenative

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.ui.screen.xserver.XServerScreen
import com.winlator.xserver.XServer
import com.winlator.xserver.Window as XWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Assisted entry is enabled after the sender and privacy review; physical capture
// verification remains pending and is not claimed by this build.
private const val ASSISTED_ENTRY_ENABLED = true

/**
 * Brief Spanish wording for the advisory per-attempt patch state (US-004
 * AC-12). Fixed strings only: raw native errors, memory contents, addresses,
 * keys, and secrets never reach the UI. A terminal state distinguishes a
 * previously verified application from none; ending never claims the applied
 * key was removed, and a restore ERROR stays an error, never a green end.
 */
private fun patchAttemptLabel(status: ForeverFunctionalPocPatchSession.PatchStatus): String {
    if (status.captureOnly) {
        return when (status.phase) {
            ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED ->
                "Captura terminada (diagnóstico, sin aplicación)"
            ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED ->
                "Captura: fallo (diagnóstico, sin aplicación)"
            else -> "Captura diagnóstica (no aplica cambios)"
        }
    }
    return when (status.phase) {
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.DISABLED -> "Parche desactivado"
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING -> "Parche: pendiente…"
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING -> "Parche: buscando…"
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.VERIFIED -> "Parche aplicado (verificado)"
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED ->
            if (status.everApplied) "Parche: fallo (se había verificado aplicación)" else "Parche: fallo"
        ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED ->
            if (status.everApplied) {
                "Vigilancia terminada (se verificó aplicación)"
            } else {
                "Vigilancia terminada (sin aplicación verificada)"
            }
    }
}

/** Fixed, secret-free status wording; submission never claims authentication. */
private fun assistedEntryStatusMessage(result: ForeverFunctionalAutologinAssistedResult): String =
    when (result) {
        ForeverFunctionalAutologinAssistedResult.PREPARING -> "Preparando entrada asistida…"
        ForeverFunctionalAutologinAssistedResult.SENDING -> "Enviando datos de acceso…"
        ForeverFunctionalAutologinAssistedResult.SENT -> "Datos enviados; esto no confirma la autenticación."
        ForeverFunctionalAutologinAssistedResult.CANCELLED -> "Entrada cancelada; continúa manualmente."
        ForeverFunctionalAutologinAssistedResult.SESSION_UNAVAILABLE ->
            "Sesión no disponible; continúa manualmente."
        ForeverFunctionalAutologinAssistedResult.CREDENTIALS_UNAVAILABLE ->
            "Credenciales no disponibles; continúa manualmente."
        ForeverFunctionalAutologinAssistedResult.PRIVACY_UNAVAILABLE ->
            "Protección de privacidad no disponible; no se envió nada."
        ForeverFunctionalAutologinAssistedResult.CAPTURE_BLOCKED ->
            "Entrada asistida no disponible durante la captura."
        ForeverFunctionalAutologinAssistedResult.UNSUPPORTED_CREDENTIAL ->
            "Formato de credenciales no compatible; no se envió nada."
        ForeverFunctionalAutologinAssistedResult.INPUT_FAILED ->
            "No se pudo enviar; continúa manualmente."
    }

/** Dedicated explicit GUI entry point; it never starts the client on cold launch. */
class ForeverFunctionalPocActivity : ComponentActivity() {
    private var patchCancel: (() -> Unit)? = null
    private var wineGuiRunning = false
    private var displayMeasurementActive = false
    private var cancelPendingDisplayLaunch: (() -> Unit)? = null
    private var cancelAssistedEntry: (() -> Unit)? = null
    private val presentationHandler = Handler(Looper.getMainLooper())
    private var imeRequestPending = false
    private val clearImeRequest = Runnable {
        imeRequestPending = false
        syncGamePresentation()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        cancelAssistedEntry?.invoke()
        if (wineGuiRunning && PluviaApp.events.emit(app.gamenative.events.AndroidEvent.KeyEvent(event)) {
                results -> results.any { it }
            } == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelAssistedEntry?.invoke()
        return super.dispatchTouchEvent(event)
    }

    private fun syncGamePresentation(imeVisibleOverride: Boolean? = null) {
        // Insets dispatch is authoritative while it is in progress. Reading
        // rootWindowInsets again here can observe the previous IME state and
        // immediately undo an asynchronous keyboard request.
        val imeVisible = imeVisibleOverride ?: ForeverFunctionalLauncherUx.isImeVisible(window)
        if ((!wineGuiRunning && !displayMeasurementActive) || imeVisible || imeRequestPending) {
            ForeverFunctionalLauncherUx.showSystemUi(this)
        } else {
            ForeverFunctionalLauncherUx.hideSystemUi(this)
        }
    }

    private fun setWineGuiRunning(running: Boolean) {
        // Wine's terminal callback can arrive on gamenative-process-wait.
        runOnUiThread {
            wineGuiRunning = running
            if (!running) displayMeasurementActive = false
            requestedOrientation = if (running) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                imeRequestPending = false
                presentationHandler.removeCallbacks(clearImeRequest)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            syncGamePresentation()
        }
    }

    private fun requestGameKeyboard() {
        imeRequestPending = true
        presentationHandler.removeCallbacks(clearImeRequest)
        // GameNative requests the Android IME asynchronously. Keep bars visible
        // until insets report the real IME instead of hiding it immediately.
        ForeverFunctionalLauncherUx.showSystemUi(this)
        ForeverFunctionalLauncherUx.requestRealKeyboard()
        presentationHandler.postDelayed(clearImeRequest, 1_000L)
    }

    private fun retainPatchCancel(cancel: () -> Unit) {
        patchCancel = cancel
    }

    private fun setDisplayMeasurementActive(active: Boolean) {
        displayMeasurementActive = active
        syncGamePresentation()
    }

    private fun setPendingDisplayCancellation(callback: (() -> Unit)?) {
        cancelPendingDisplayLaunch = callback
    }

    private fun cancelPatch() {
        val cancel = patchCancel
        patchCancel = null
        cancel?.invoke()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!ForeverFunctionalPoc.protectActiveAssistedCredentialWindow(window)) {
            finish()
            return
        }
        recordRemoteDiagnosticsAction(this, "screen", "ok")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                imeRequestPending = false
            }
            syncGamePresentation(imeVisibleOverride = imeVisible)
            insets
        }
        ViewCompat.requestApplyInsets(window.decorView)
        com.winlator.inputcontrols.ControllerManager.getInstance().init(applicationContext)
        // Preserve the functional PoC's explicit quiet-audio baseline. UX does
        // not change the device audio policy; this is only the existing app
        // behavior, with bounded diagnostic logging.
        val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 1, 0)
            android.util.Log.i(
                "ForeverFunctionalPoc",
                "Media volume: ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}",
            )
        } catch (failure: RuntimeException) {
            android.util.Log.w("ForeverFunctionalPoc", "Media volume change unavailable: ${failure.javaClass.simpleName}")
        }
        setContent {
            ForeverLauncherTheme {
                ForeverFunctionalPocScreen(
                    retainPatchCancel = ::retainPatchCancel,
                    cancelPatch = ::cancelPatch,
                    onRunningChanged = { running ->
                        setWineGuiRunning(running)
                    },
                    onDisplayMeasurementActive = ::setDisplayMeasurementActive,
                    onPendingDisplayCancellationChanged = ::setPendingDisplayCancellation,
                    onAssistedEntryCancellationChanged = { cancelAssistedEntry = it },
                    onKeyboardRequested = ::requestGameKeyboard,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ForeverFunctionalPoc.protectActiveAssistedCredentialWindow(window)) {
            finish()
            return
        }
        syncGamePresentation()
    }

    override fun onPause() {
        cancelAssistedEntry?.invoke()
        if (!wineGuiRunning) cancelPendingDisplayLaunch?.invoke()
        super.onPause()
    }

    override fun onStop() {
        if (!wineGuiRunning) cancelPendingDisplayLaunch?.invoke()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncGamePresentation() else cancelAssistedEntry?.invoke()
    }

    override fun onDestroy() {
        wineGuiRunning = false
        cancelAssistedEntry?.invoke()
        cancelAssistedEntry = null
        displayMeasurementActive = false
        cancelPendingDisplayLaunch = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        imeRequestPending = false
        presentationHandler.removeCallbacksAndMessages(null)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, null)
        cancelPatch()
        ForeverFunctionalPoc.releaseActiveGuestAfterShutdown(this)
        if (PluviaApp.xEnvironment != null) PluviaApp.shutdownEnvironment()
        super.onDestroy()
    }
}

private fun foreverFunctionalDisplayWindowBounds(activity: Activity): Rect? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return activity.windowManager.currentWindowMetrics.bounds
    }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    activity.windowManager.defaultDisplay.getRealMetrics(metrics)
    if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
    // API 29 has no WindowMetrics. Real display metrics plus the view's
    // window-relative origin below is the explicit fallback contract.
    return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
}

private fun foreverFunctionalDisplayViewport(
    activity: Activity?,
    view: View,
): ForeverFunctionalDisplayFit.Viewport? {
    if (view.width <= 0 || view.height <= 0) return null
    val windowBounds = activity?.let { foreverFunctionalDisplayWindowBounds(it) } ?: return null
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val rootInsets = ViewCompat.getRootWindowInsets(view) ?: return null
    val cutout = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
    val overlap = ForeverFunctionalDisplayFit.calculateOverlappingInsets(
        windowWidth = windowBounds.width(),
        windowHeight = windowBounds.height(),
        viewLeft = location[0],
        viewTop = location[1],
        viewWidth = view.width,
        viewHeight = view.height,
        physicalCutout = ForeverFunctionalDisplayFit.Insets(
            left = cutout.left,
            top = cutout.top,
            right = cutout.right,
            bottom = cutout.bottom,
        ),
    ) ?: return null
    return runCatching {
        ForeverFunctionalDisplayFit.Viewport(
            width = view.width,
            height = view.height,
            insets = overlap,
            viewLeft = location[0],
            viewTop = location[1],
            windowWidth = windowBounds.width(),
            windowHeight = windowBounds.height(),
        )
    }.getOrNull()
}

private val ForeverBackground = Color(0xFF090F18)
private val ForeverSurface = Color(0xFF121D2A)
private val ForeverSurfaceRaised = Color(0xFF1A2938)
private val ForeverGold = Color(0xFFD5A34D)
private val ForeverBurgundy = Color(0xFF80343D)
private val ForeverBurgundyPressed = Color(0xFF9A4650)
private val ForeverInk = Color(0xFFF4E7CB)
private val ForeverMutedInk = Color(0xFFBCC4CA)

private val ForeverColorScheme = darkColorScheme(
    primary = ForeverGold,
    onPrimary = Color(0xFF231A0C),
    primaryContainer = Color(0xFF54252D),
    onPrimaryContainer = ForeverInk,
    secondary = ForeverGold,
    onSecondary = Color(0xFF231A0C),
    secondaryContainer = Color(0xFF4D3819),
    onSecondaryContainer = ForeverInk,
    background = ForeverBackground,
    onBackground = ForeverInk,
    surface = ForeverSurface,
    onSurface = ForeverInk,
    surfaceVariant = ForeverSurfaceRaised,
    onSurfaceVariant = ForeverMutedInk,
    outline = Color(0xFF7D6846),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val ForeverTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
private fun ForeverLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ForeverColorScheme,
        typography = ForeverTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

@Composable
private fun ForeverSectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(
            modifier = Modifier
                .padding(top = 7.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        )
    }
}

/** Original vector decoration: no Blizzard artwork, fonts or texture assets. */
@Composable
private fun ForeverRealmBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Quiet stars and distant mountain ridges: decoration never catches input.
        repeat(34) { index ->
            val x = ((index * 137 + 43) % 997) / 997f * w
            val y = ((index * 83 + 19) % 683) / 683f * h * 0.65f
            drawCircle(ForeverGold.copy(alpha = if (index % 4 == 0) 0.28f else 0.1f),
                radius = if (index % 4 == 0) 1.4.dp.toPx() else 0.7.dp.toPx(), center = Offset(x, y))
        }
        val mountains = Path().apply {
            moveTo(0f, h * 0.36f)
            lineTo(w * 0.15f, h * 0.2f)
            lineTo(w * 0.29f, h * 0.31f)
            lineTo(w * 0.51f, h * 0.15f)
            lineTo(w * 0.72f, h * 0.3f)
            lineTo(w * 0.88f, h * 0.22f)
            lineTo(w, h * 0.36f)
            lineTo(w, h * 0.54f)
            lineTo(0f, h * 0.54f)
            close()
        }
        drawPath(mountains, Color(0xFF233B50).copy(alpha = 0.27f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, ForeverBackground),
            startY = h * 0.23f, endY = h * 0.55f),
            topLeft = Offset(0f, h * 0.23f), size = Size(w, h * 0.32f))
        // Fine outer rails, deliberately behind safe-area content.
        val inset = 9.dp.toPx()
        drawLine(ForeverGold.copy(alpha = 0.2f), Offset(inset, h * 0.1f), Offset(inset, h * 0.87f))
        drawLine(ForeverGold.copy(alpha = 0.2f), Offset(w - inset, h * 0.1f), Offset(w - inset, h * 0.87f))
    }
}

@Composable
private fun ForeverCrest() {
    // The glow, rings and side rails stay on the canvas; only the inner
    // shield/sigil is the shared Emblem glyph, centered at the same height.
    Box(
        modifier = Modifier.fillMaxWidth().height(68.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = 24.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(ForeverGold.copy(alpha = 0.14f), Color.Transparent),
                center, radius * 1.8f), radius * 1.8f, center)
            drawCircle(ForeverGold.copy(alpha = 0.55f), radius, center, style = Stroke(1.dp.toPx()))
            drawCircle(ForeverGold.copy(alpha = 0.23f), radius + 5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            for (side in listOf(-1f, 1f)) {
                val start = center.x + side * (radius + 15.dp.toPx())
                val end = center.x + side * (size.width * 0.42f)
                drawLine(ForeverGold.copy(alpha = 0.5f), Offset(start, center.y), Offset(end, center.y), 1.dp.toPx())
                drawCircle(ForeverGold, 2.dp.toPx(), Offset(start, center.y))
            }
        }
        Icon(
            imageVector = ForeverFunctionalIcons.Emblem,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = ForeverGold,
        )
    }
}

@Composable
private fun ForeverSettingsAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ForeverAccessoryKey(
    label: String,
    accessibleLabel: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .widthIn(min = 48.dp)
        .heightIn(min = 48.dp)
        .semantics {
            contentDescription = accessibleLabel
            role = Role.Button
        }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun ForeverStonePanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
            shape = MaterialTheme.shapes.medium,
        ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        content = content,
    )
}

@Composable
private fun ForeverFunctionalPocScreen(
    retainPatchCancel: (() -> Unit) -> Unit,
    cancelPatch: () -> Unit,
    onRunningChanged: (Boolean) -> Unit,
    onDisplayMeasurementActive: (Boolean) -> Unit,
    onPendingDisplayCancellationChanged: (((() -> Unit)?) -> Unit),
    onAssistedEntryCancellationChanged: (((() -> Unit)?) -> Unit),
    onKeyboardRequested: () -> Unit,
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(ForeverFunctionalServerProfiles.load(context)) }
    val storageService = remember(context) { ForeverFunctionalClientStorage.service(context) }
    var storageSnapshot by remember { mutableStateOf<ForeverFunctionalClientStorage.Snapshot?>(null) }
    var showStorageSettings by remember { mutableStateOf(false) }
    var storagePendingId by remember { mutableStateOf<String?>(null) }
    var storageBusy by remember { mutableStateOf(false) }
    var pastedActionBusyTitle by remember { mutableStateOf<String?>(null) }
    var pastedActionErrorTitle by remember { mutableStateOf("No se pudo completar la acción") }
    var pastedActionError by remember { mutableStateOf<String?>(null) }
    var pastedFolderResult by remember { mutableStateOf<ForeverFunctionalClientStorage.PastedClientFolder?>(null) }
    var pastedClientReport by remember { mutableStateOf<PastedClientReport?>(null) }
    val storageGeneration = remember { AtomicLong(0L) }
    var storageJob by remember { mutableStateOf<Job?>(null) }
    var storageRequest by remember { mutableStateOf<ForeverFunctionalClientStorage.SelectionRequest?>(null) }
    var prepared by remember { mutableStateOf<ForeverFunctionalPoc.Prepared?>(null) }
    var clientChecked by remember { mutableStateOf(false) }
    // Advisory-only per-attempt patch state (US-004 AC-12): written only on
    // the main thread under the current attempt generation. The terminal
    // value survives prepared being cleared so the welcome surface still
    // shows it after returning from the game.
    var patchStatus by remember { mutableStateOf<ForeverFunctionalPocPatchSession.PatchStatus?>(null) }
    val patchAttemptGeneration = remember { AtomicLong(0L) }
    // Fail closed: until a snapshot/inspection succeeds, storage is unknown
    // and home offers no actionable ready/missing CTA.
    var storageKnown by remember { mutableStateOf(false) }
    var showGpuCompatibility by remember { mutableStateOf(false) }
    // Same-process download hub: home observes the live execution state and
    // structured detail, but never clears the terminal outcome (the manager
    // dialog owns consuming it) and never touches the updater service lease.
    val execution = remember { ForeverClientUpdateExecution }
    val downloadBusy by execution.busy.collectAsState()
    val downloadApplying by execution.applying.collectAsState()
    val downloadProgress by execution.progress.collectAsState()
    val downloadDetail by execution.detail.collectAsState()
    val downloadOutcome by execution.outcome.collectAsState()
    val homeRefreshGeneration = remember { AtomicLong(0L) }
    var displayMode by remember { mutableStateOf(ForeverFunctionalDisplayFit.loadMode(context)) }
    var displayPlan by remember { mutableStateOf<ForeverFunctionalDisplayFit.Plan?>(null) }
    var launchRequested by remember { mutableStateOf(false) }
    var launchToken by remember { mutableStateOf<ForeverFunctionalDisplayFit.ForeverFunctionalDisplayFitLaunchCoordinator.Token?>(null) }
    var pendingDisplayIo by remember { mutableStateOf(false) }
    var showDisplayModeOptions by remember { mutableStateOf(false) }
    val displayGate = remember { ForeverFunctionalDisplayFit.ForeverFunctionalDisplayFitLaunchGate() }
    val launchCoordinator = remember { ForeverFunctionalDisplayFit.launchCoordinatorForProcess() }
    var readiness by remember {
        mutableStateOf(ForeverFunctionalLauncherUx.ClientReadiness(false, false, false))
    }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClientManagement by remember { mutableStateOf(false) }
    var managerReturnToSettings by remember { mutableStateOf(true) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showAutologinSettings by remember { mutableStateOf(false) }
    var showClientDetails by remember { mutableStateOf(false) }
    var showGraphicsResetConfirm by remember { mutableStateOf(false) }
    var graphicsResetBusy by remember { mutableStateOf(false) }
    // Diagnóstico remoto: el intento se crea ANTES de las precondiciones y el
    // diálogo nunca provoca red hasta Enviar explícito.
    var remoteAttemptId by remember { mutableStateOf(ForeverRemoteDiagnosticsAndroid.newAttemptId()) }
    var showRemoteDiagnostics by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Comprobando almacenamiento…") }
    var error by remember { mutableStateOf<String?>(null) }
    var backAction by remember { mutableStateOf<() -> Unit>({}) }
    val controlsLocked = busy || prepared != null || running || launchRequested || pendingDisplayIo || storageBusy || graphicsResetBusy
    val sessionOrIoLocked = busy || running || launchRequested || pendingDisplayIo || storageBusy || graphicsResetBusy
    val homeState = ForeverFunctionalLauncherUx.deriveHomeState(
        checking = !clientChecked,
        storageKnown = storageKnown,
        readiness = readiness,
        downloadBusy = downloadBusy,
        downloadApplying = downloadApplying,
    )

    LaunchedEffect(storageService) {
        val storageGenerationAtStart = storageGeneration.get()
        val refreshAtStart = homeRefreshGeneration.get()
        try {
            val snapshot = withContext(Dispatchers.IO) { storageService.snapshot() }
            val inspection = withContext(Dispatchers.IO) { ForeverFunctionalLauncherUx.inspectClient(context) }
            if (storageGenerationAtStart == storageGeneration.get() &&
                refreshAtStart == homeRefreshGeneration.get()
            ) {
                storageSnapshot = snapshot
                readiness = inspection
                storageKnown = true
                clientChecked = true
                if (status == "Comprobando almacenamiento…") status = readiness.label
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (storageGenerationAtStart == storageGeneration.get() &&
                refreshAtStart == homeRefreshGeneration.get()
            ) {
                // Fail closed: storage stays unknown so home cannot offer
                // Descargar cliente on an unchecked destination.
                clientChecked = true
                status = "No se pudo comprobar el almacenamiento seleccionado"
                error = "No se pudo comprobar el almacenamiento seleccionado"
            }
        }
    }

    DisposableEffect(storageService) {
        onDispose {
            storageGeneration.incrementAndGet()
            storageRequest?.cancel()
            storageJob?.cancel()
        }
    }

    /**
     * Lightweight home refresh: the same bounded snapshot/inspection as the
     * initial load, never the updater-owned service lease and never a
     * concurrent detection run. The shared storage generation is captured
     * alongside the refresh generation so neither a newer storage result
     * nor a newer refresh is overwritten. A failed refresh marks storage
     * unknown instead of retaining a possibly stale Ready.
     */
    fun refreshReadiness() {
        // prepared is also the running guest's release handle. Home refreshes
        // must never clear it during preparation, launch or an active session.
        if (running || launchRequested || pendingDisplayIo || busy) return
        val refresh = homeRefreshGeneration.incrementAndGet()
        val storageAtStart = storageGeneration.get()
        scope.launch {
            try {
                val inspected = withContext(Dispatchers.IO) { ForeverFunctionalLauncherUx.inspectClient(context) }
                if (running || launchRequested || pendingDisplayIo || busy) return@launch
                if (refresh == homeRefreshGeneration.get() && storageAtStart == storageGeneration.get()) {
                    readiness = inspected
                    storageKnown = true
                    clientChecked = true
                    if (!inspected.ready) {
                        prepared = null
                        consent = false
                    }
                    if (status == "Comprobando almacenamiento…") status = inspected.label
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (running || launchRequested || pendingDisplayIo || busy) return@launch
                android.util.Log.w("ForeverFunctionalPoc", "home readiness refresh failed: ${failure.javaClass.simpleName}")
                if (refresh == homeRefreshGeneration.get() && storageAtStart == storageGeneration.get()) {
                    storageKnown = false
                    prepared = null
                    consent = false
                    clientChecked = true
                }
            }
        }
    }

    // Returning to the home surface re-reads the light readiness snapshot so
    // the single status panel reflects manager/storage/dialog outcomes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && clientChecked) refreshReadiness()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A foreground download invalidates any prepared session: launching
    // from a stale preparation while the client tree is being written is
    // never offered. The existing launch coordinator stays authoritative.
    LaunchedEffect(downloadBusy) {
        if (downloadBusy) {
            prepared = null
            consent = false
            runCatching { recordRemoteDiagnosticsAction(context, "resume", "pending") }
        }
    }

    // Terminal download outcomes only update home text/readiness. The outcome
    // itself is owned (and cleared) by the management dialog; a second
    // competing consumer clearing it here would lose the dialog's wording.
    LaunchedEffect(downloadOutcome) {
        when (val terminal = downloadOutcome) {
            is ForeverClientUpdateExecution.Outcome.Done -> {
                prepared = null
                consent = false
                status = "Cliente actualizado. Prepara WoW para iniciar."
                runCatching { recordRemoteDiagnosticsAction(context, "resume", "ok") }
                refreshReadiness()
            }
            is ForeverClientUpdateExecution.Outcome.Paused -> {
                prepared = null
                consent = false
                status = "Descarga pausada. Puedes continuar cuando quieras."
                runCatching { recordRemoteDiagnosticsAction(context, "pause", "ok") }
                refreshReadiness()
            }
            is ForeverClientUpdateExecution.Outcome.Failed -> {
                prepared = null
                consent = false
                status = "No se pudo completar la descarga"
                error = ForeverFunctionalUiText.userMessage(terminal.failure)
                runCatching { recordRemoteDiagnosticsAction(context, "error", "failed") }
                refreshReadiness()
            }
            null -> {}
        }
    }

    fun refreshStorage() {
        val generation = storageGeneration.incrementAndGet()
        storageJob?.cancel()
        storageJob = scope.launch {
            storageBusy = true
            try {
                val value = withContext(Dispatchers.IO) { storageService.snapshot() }
                if (generation == storageGeneration.get()) storageSnapshot = value
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
                if (generation == storageGeneration.get()) error = "No se pudo comprobar el almacenamiento"
            } finally {
                if (generation == storageGeneration.get()) storageBusy = false
            }
        }
    }

    fun commitStorage(identity: ForeverFunctionalClientStorage.StorageIdentity) {
        val generation = storageGeneration.incrementAndGet()
        storageRequest?.cancel()
        storageJob?.cancel()
        storageJob = scope.launch {
            storageBusy = true
            var request: ForeverFunctionalClientStorage.SelectionRequest? = null
            try {
                // Acquiring the in-memory lease must not cross a cancellable
                // withContext return boundary before its owner can close it.
                request = storageService.beginSelection(identity)
                storageRequest = request
                withContext(Dispatchers.IO) { request!!.commit() }
                if (!currentCoroutineContext().isActive || generation != storageGeneration.get()) return@launch
                storageSnapshot = withContext(Dispatchers.IO) { storageService.snapshot() }
                readiness = withContext(Dispatchers.IO) { ForeverFunctionalLauncherUx.inspectClient(context) }
                storageKnown = true
                prepared = null
                consent = false
                showStorageSettings = false
                showSettings = true
                status = "Ubicación guardada"
            } catch (_: CancellationException) {
                if (request?.state == ForeverFunctionalClientStorage.SelectionRequest.State.COMMITTED &&
                    generation == storageGeneration.get()
                ) {
                    storageSnapshot = withContext(NonCancellable + Dispatchers.IO) { storageService.snapshot() }
                    readiness = withContext(NonCancellable + Dispatchers.IO) {
                        ForeverFunctionalLauncherUx.inspectClient(context)
                    }
                    storageKnown = true
                    prepared = null
                    consent = false
                    showStorageSettings = false
                    showSettings = true
                    status = "Ubicación guardada"
                    runCatching { recordRemoteDiagnosticsAction(context, "profile-change", "ok") }
                } else {
                    request?.cancel()
                }
                throw CancellationException()
            } catch (failure: Throwable) {
                if (generation == storageGeneration.get()) {
                    val refreshed = withContext(Dispatchers.IO) { runCatching { storageService.snapshot() }.getOrNull() }
                    if (generation == storageGeneration.get()) {
                        refreshed?.let { storageSnapshot = it }
                        error = ForeverFunctionalClientStorage.selectionFailureMessage(failure)
                    }
                }
            } finally {
                request?.close()
                if (storageRequest === request) storageRequest = null
                if (generation == storageGeneration.get()) storageBusy = false
            }
        }
    }

    fun preparePastedClientFolder() {
        if (pastedActionBusyTitle != null) return
        showStorageSettings = false
        pastedFolderResult = null
        pastedClientReport = null
        pastedActionError = null
        pastedActionErrorTitle = "No se pudo preparar la carpeta"
        pastedActionBusyTitle = "Preparando la carpeta…"
        scope.launch {
            try {
                pastedFolderResult = withContext(Dispatchers.IO) {
                    storageService.preparePastedClientFolder()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                pastedActionError = ForeverFunctionalUiText.userMessage(failure)
            } finally {
                pastedActionBusyTitle = null
            }
        }
    }

    fun reviewPastedClient() {
        if (pastedActionBusyTitle != null) return
        showStorageSettings = false
        pastedFolderResult = null
        pastedClientReport = null
        pastedActionError = null
        pastedActionErrorTitle = "No se pudo revisar el cliente pegado"
        pastedActionBusyTitle = "Revisando el cliente pegado…"
        scope.launch {
            try {
                pastedClientReport = withContext(Dispatchers.IO) {
                    // Acquire only once this coroutine actually runs: a cancelled
                    // scope must never retain the process-wide IO lease.
                    val lease = storageService.acquireIoLease()
                        ?: throw ForeverFunctionalClientStorage.StorageException("storage_busy")
                    try {
                        val service = ForeverClientUpdateService(context)
                        try {
                            service.inspectPastedClient()
                        } finally {
                            service.close()
                        }
                    } finally {
                        lease.close()
                    }
                }
                storageSnapshot = withContext(Dispatchers.IO) { storageService.snapshot() }
                readiness = withContext(Dispatchers.IO) { ForeverFunctionalLauncherUx.inspectClient(context) }
                storageKnown = true
                clientChecked = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                pastedActionError = ForeverFunctionalUiText.userMessage(failure)
            } finally {
                pastedActionBusyTitle = null
            }
        }
    }

    fun returnToStorageAfterPastedAction() {
        pastedFolderResult = null
        pastedClientReport = null
        pastedActionError = null
        showStorageSettings = true
        refreshStorage()
    }

    /**
     * Auto-close of an idle, unlaunched preparation (US-004 AC-07/FR-017): a
     * completed prepare() with no guest handles is frozen data, not work.
     * Settings actions that take over (resolution entry, graphics-reset
     * confirm) discard it with its consent instead of asking the user to
     * manage internal state. Real work stays excluded by the callers'
     * enabled guards; a snapshot holding guest handles is never dropped here.
     */
    fun discardIdlePreparedSnapshot() {
        val snapshot = prepared
        if (snapshot != null && snapshot.durableGuestTicket == null && snapshot.guestReservation == null) {
            prepared = null
            consent = false
        }
    }

    fun updateSettings(updated: FunctionalServerProfileSettings) {
        // The build-time profile always wins: a stale stored ORIGINAL (or a
        // crafted copy) is coerced and persisted as Classic Forever.
        val coerced = updated.copy(profile = ForeverFunctionalServerProfiles.EFFECTIVE_PROFILE)
        settings = coerced
        ForeverFunctionalServerProfiles.save(context, coerced)
        prepared = null
        consent = false
        status = "Ajustes guardados"
        runCatching { recordRemoteDiagnosticsAction(context, "profile-change", "ok") }
    }

    /**
     * Explicit graphics-only reset with the game closed. Restores the initial
     * preset (visible quality 1, no shadows, 30 FPS cap) in the private
     * derived file; resolution, account, input, server, storage and client
     * data are untouched. Invalidates the prepared session so the next Play
     * prepares again from the reset file.
     */
    fun resetGraphicsToDefaults() {
        if (busy || running || launchRequested || pendingDisplayIo || storageBusy || downloadBusy || graphicsResetBusy) return
        graphicsResetBusy = true
        // Confirming the reset auto-closes an idle, unlaunched preparation
        // (US-004 FR-017); the next Play prepares again with fresh consent.
        discardIdlePreparedSnapshot()
        consent = false
        error = null
        status = "Restableciendo gráficos…"
        scope.launch(Dispatchers.IO) {
            try {
                // A matching stale guest reservation is recovered here with
                // the same destination, ownership and fresh quiescence checks
                // as explicit Play (US-004 AC-11). Active, unknown or updater
                // state throws and prevents the write below; no processes are
                // forced, no locks deleted, no storage reset.
                ForeverFunctionalPoc.recoverStaleGuestBeforePlay(context)
                val configuration = ForeverFunctionalServerProfiles.resolve(context, settings)
                ForeverFunctionalPoc.resetDerivedGraphics(context, configuration)
                withContext(Dispatchers.Main) {
                    prepared = null
                    consent = false
                    status = "Gráficos restablecidos: calidad 1, sin sombras y 30 FPS"
                }
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main) {
                    status = "No se pudieron restablecer los gráficos"
                    error = ForeverFunctionalUiText.userMessage(failure)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { graphicsResetBusy = false }
            }
        }
    }

    /**
     * Settles an attempt that never began monitoring (US-004 FR-019): a
     * failed begin reports FAILED, an abandoned pre-begin attempt reports a
     * clean end. Never overwrites a live VERIFIED or an already terminal
     * state delivered by the session; captureOnly is preserved. A live
     * session cancelled below still delivers its own terminal after.
     */
    fun settleUnstartedPatchStatus(failed: Boolean) {
        val current = patchStatus
        if (current != null &&
            (current.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING ||
                current.phase == ForeverFunctionalPocPatchSession.PatchAttemptPhase.SEARCHING)
        ) {
            // Preserve recorded history: never fabricate a clean
            // "no verified application" while everApplied is true.
            patchStatus = current.copy(
                phase = if (failed) {
                    ForeverFunctionalPocPatchSession.PatchAttemptPhase.FAILED
                } else {
                    ForeverFunctionalPocPatchSession.PatchAttemptPhase.ENDED
                },
            )
        }
    }

    /**
     * Single launch path shared by the home CTA and the manager "Jugar"
     * action: prepare, then return to the explicit consent screen. Consent is
     * never pre-checked; there is no shortcut around it.
     */
    fun prepareEnvironment(fromManager: Boolean) {
        if (busy || running || launchRequested || pendingDisplayIo || storageBusy || downloadBusy || graphicsResetBusy) return
        if (launchCoordinator.hasPendingIo()) {
            status = "El intento anterior aún está finalizando"
            return
        }
        // Do not replace the in-flight attempt on a duplicate tap.
        remoteAttemptId = runCatching { ForeverRemoteDiagnosticsAndroid.beginAttempt(context) }
            .getOrDefault(ForeverRemoteDiagnosticsAndroid.newAttemptId())
        val attemptForOutcome = remoteAttemptId
        // Un cliente incompleto queda como precondición, nunca como fallo Wine.
        runCatching { recordRemoteDiagnosticsAction(context, "launch", "pending") }
        busy = true
        error = null
        status = "Comprobando cliente y preparando entorno…"
        scope.launch(Dispatchers.IO) {
            try {
                // App-owned recovery of a stale guest reservation at explicit
                // Play (US-004 AC-09): only a matching, quiescent-selected
                // record is recovered; any other verdict stays blocked below.
                // Runs on this worker thread, never on the UI thread, and a
                // duplicate Play tap is excluded by the busy guard above.
                val recoveredGuest = ForeverFunctionalPoc.recoverStaleGuestBeforePlay(context)
                if (recoveredGuest) {
                    Handler(Looper.getMainLooper()).post {
                        status = "Reserva anterior recuperada. Preparando entorno…"
                    }
                }
                val configuration = ForeverFunctionalServerProfiles.resolve(context, settings)
                val value = ForeverFunctionalPoc.prepare(context, configuration) { message ->
                    Handler(Looper.getMainLooper()).post { status = message }
                }
                // Congela el log preparado por intento (correlación estricta).
                runCatching {
                    ForeverRemoteDiagnosticsStore.freezeIntentLog(
                        context.filesDir, attemptForOutcome, value.logFile.name)
                }
                val inspected = ForeverFunctionalLauncherUx.inspectClient(context)
                withContext(Dispatchers.Main) {
                    readiness = inspected
                    prepared = value
                    consent = false
                    if (fromManager) {
                        showClientManagement = false
                        showSettings = false
                    }
                    status = "Entorno listo"
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    status = "Preparación cancelada"
                }
                throw cancelled
            } catch (failure: Throwable) {
                val inspected = runCatching {
                    ForeverFunctionalLauncherUx.inspectClient(context)
                }.getOrNull()
                runCatching { recordRemoteDiagnosticsAction(context, "exception", "failed") }
                // Clase del error segura (nunca el mensaje con rutas).
                runCatching {
                    ForeverRemoteDiagnosticsAndroid.updateAttemptOutcome(
                        context, attemptForOutcome,
                        result = if (inspected?.ready == false) "precondition_failed" else "preparation_failed", stage = "prepare",
                        error = failure.javaClass.simpleName, ended = true)
                }
                withContext(Dispatchers.Main) {
                    prepared = null
                    consent = false
                    if (inspected != null) readiness = inspected
                    status = "No se pudo preparar el entorno"
                    error = ForeverFunctionalUiText.userMessage(failure)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { busy = false }
            }
        }
    }

    fun cancelDisplayLaunch() {
        runCatching { recordRemoteDiagnosticsAction(context, "cancel", "cancelled") }
        runCatching {
            ForeverRemoteDiagnosticsAndroid.updateAttemptOutcome(
                context, remoteAttemptId, result = "cancelled", stage = "display", ended = true)
        }
        launchToken?.let { launchCoordinator.invalidate(it) }
        launchToken = null
        launchRequested = false
        displayGate.cancel()
        displayPlan = null
        onDisplayMeasurementActive(false)
        (context as? android.app.Activity)?.let {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ForeverFunctionalLauncherUx.showSystemUi(it)
        }
        status = "Inicio cancelado; WoW no se ha lanzado"
    }

    DisposableEffect(Unit) {
        onPendingDisplayCancellationChanged {
            if (launchRequested || pendingDisplayIo) cancelDisplayLaunch()
        }
        onDispose {
            launchToken?.let { launchCoordinator.invalidate(it) }
            onPendingDisplayCancellationChanged(null)
        }
    }

    LaunchedEffect(launchRequested, displayMode, launchToken) {
        if (!launchRequested) return@LaunchedEffect
        val token = launchToken ?: return@LaunchedEffect
        onDisplayMeasurementActive(true)
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity?.let { ForeverFunctionalLauncherUx.hideSystemUi(it) }
        displayGate.cancel()
        while (launchRequested) {
            withFrameNanos { }
            if (!currentCoroutineContext().isActive) return@LaunchedEffect
            if (lifecycleOwner.lifecycle.currentState != Lifecycle.State.RESUMED ||
                !launchCoordinator.isCurrent(token)
            ) {
                cancelDisplayLaunch()
                return@LaunchedEffect
            }
            val observation = displayGate.observe(
                mode = displayMode,
                viewport = foreverFunctionalDisplayViewport(activity, rootView),
                nowMs = SystemClock.uptimeMillis(),
            )
            when (observation) {
                is ForeverFunctionalDisplayFit.Observation.Ready -> {
                    val value = prepared ?: run {
                        cancelDisplayLaunch()
                        return@LaunchedEffect
                    }
                    val lease = launchCoordinator.acquireIo(token) ?: return@LaunchedEffect
                    val storageLease = storageService.acquireIoLease() ?: run {
                        lease.close()
                        return@LaunchedEffect
                    }
                    pendingDisplayIo = true
                    var launchCancellation: (() -> Unit)? = null
                    var cancellationHandedOff = false
                    try {
                        // File/container writes happen after the real
                        // landscape viewport stabilises and before XServer is
                        // composed.  The plan is then frozen for this run.
                        val applied = withContext(Dispatchers.IO) {
                            ForeverFunctionalPoc.applyDisplayPlan(context, value, observation.plan, storageLease)
                        }
                        // Per-attempt advisory patch state (US-004 AC-12):
                        // generation-guarded and main-thread only, so a late
                        // callback from an older attempt can never overwrite
                        // the current one. Never authorizes writes.
                        val patchAttempt = patchAttemptGeneration.incrementAndGet()
                        patchStatus = when {
                            !applied.memoryPatchEnabled && !applied.codeCaptureEnabled ->
                                ForeverFunctionalPocPatchSession.PatchStatus(
                                    ForeverFunctionalPocPatchSession.PatchAttemptPhase.DISABLED,
                                )
                            applied.codeCaptureEnabled ->
                                ForeverFunctionalPocPatchSession.PatchStatus(
                                    ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING,
                                    captureOnly = true,
                                )
                            else ->
                                ForeverFunctionalPocPatchSession.PatchStatus(
                                    ForeverFunctionalPocPatchSession.PatchAttemptPhase.PENDING,
                                )
                        }
                        // Each launch attempt gets one state owner; downstream
                        // Prepared copies and Activity recreation retain it, but
                        // a failed earlier attempt cannot poison a later launch.
                        val withPatchStatus = applied.copy(
                            assistedEntryState = ForeverFunctionalAutologinAssistedEntryState(),
                            patchStatusListener = { snapshot ->
                                if (patchAttempt == patchAttemptGeneration.get()) {
                                    Handler(Looper.getMainLooper()).post {
                                        if (patchAttempt == patchAttemptGeneration.get()) patchStatus = snapshot
                                    }
                                }
                            },
                        )
                        if (!currentCoroutineContext().isActive ||
                            !launchCoordinator.canCommit(
                                token,
                                lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                            )
                        ) {
                            // Abandoned before the lease began: no searching
                            // state may linger for an attempt that never ran.
                            settleUnstartedPatchStatus(failed = false)
                            return@LaunchedEffect
                        }
                        // Capture ownership inside IO: cancellation at the dispatcher
                        // return boundary must still release this exact reservation.
                        withContext(Dispatchers.IO) {
                            launchCancellation = ForeverFunctionalPoc.beforeLaunch(context, withPatchStatus, storageLease)
                        }
                        val cancel = requireNotNull(launchCancellation)
                        if (!currentCoroutineContext().isActive ||
                            !launchCoordinator.canCommit(
                                token,
                                lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                            )
                        ) {
                            return@LaunchedEffect
                        }
                        if (!launchCoordinator.complete(token)) {
                            return@LaunchedEffect
                        }
                        if (!ForeverFunctionalPoc.markGuestStarted(context, withPatchStatus)) {
                            throw IllegalStateException("guest_reservation_lost")
                        }
                        prepared = withPatchStatus
                        displayPlan = observation.plan
                        retainPatchCancel(cancel)
                        cancellationHandedOff = true
                        launchRequested = false
                        running = true
                        onRunningChanged(true)
                        onDisplayMeasurementActive(false)
                        status = "WoW: ${observation.plan.size} · ${displayMode.label}"
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        if (!currentCoroutineContext().isActive ||
                            !launchCoordinator.canCommit(
                                token,
                                lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                            )
                        ) return@LaunchedEffect
                        value.let {
                            ForeverFunctionalPoc.record(
                                it.logFile,
                                "launch_blocked",
                                org.json.JSONObject()
                                    .put("message", failure.message ?: failure::class.java.simpleName)
                                    .put("displayMode", displayMode.persistedName)
                                    .put("readOnly", true)
                                    .put("bytesWritten", 0),
                            )
                        }
                        error = ForeverFunctionalUiText.userMessage(failure)
                        // A failed begin (no owned session started) reports
                        // FAILED, never a clean end; a live session cancelled
                        // below still delivers its own terminal after. Never
                        // resets an already live or terminal state.
                        settleUnstartedPatchStatus(failed = launchCancellation == null)
                        cancelDisplayLaunch()
                    } finally {
                        try {
                            if (!cancellationHandedOff) {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    launchCancellation?.invoke()
                                }
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                storageLease.close()
                                lease.close()
                                pendingDisplayIo = false
                            }
                        }
                    }
                    return@LaunchedEffect
                }
                ForeverFunctionalDisplayFit.Observation.TimedOut -> {
                    error = "No se pudo medir un viewport horizontal estable"
                    prepared?.let {
                        ForeverFunctionalPoc.record(
                            it.logFile,
                            "display_measurement_timeout",
                            org.json.JSONObject().put("timeoutMs", 5_000),
                        )
                    }
                    cancelDisplayLaunch()
                    return@LaunchedEffect
                }
                is ForeverFunctionalDisplayFit.Observation.Invalid -> {
                    status = "Esperando viewport horizontal…"
                }
                ForeverFunctionalDisplayFit.Observation.Waiting -> {
                    status = "Estabilizando presentación…"
                }
                ForeverFunctionalDisplayFit.Observation.AlreadyFinalized -> return@LaunchedEffect
            }
        }
    }

    if (running) {
        BackHandler { backAction(); cancelPatch() }
        DisposableEffect(Unit) {
            // XServerScreen may auto-show its touch profile after layout. Keep the
            // profile and physical-controller mapping alive, but never draw the
            // touchscreen gamepad in this launcher path. The layout/pre-draw
            // hooks are event-driven and cleaned up when the game leaves.
            val cleanup = (context as? android.app.Activity)?.let {
                ForeverFunctionalLauncherUx.installTouchscreenGamepadSuppression(it)
            } ?: {}
            onDispose { cleanup() }
        }
        val displayInsets = displayPlan?.viewport?.insets
        val density = androidx.compose.ui.platform.LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val activePrepared = prepared
        val assistedLaunch = activePrepared?.assistedEntryState
        val mappedGameWindow = remember(assistedLaunch) {
            AtomicReference<Pair<XServer, XWindow>?>(null)
        }
        var assistedStatus by remember(assistedLaunch) { mutableStateOf<String?>(null) }
        val assistedStatusHandler = remember { Handler(Looper.getMainLooper()) }
        val assistedSender = remember(assistedLaunch, mappedGameWindow) {
            val activity = context as? Activity
            if (!ASSISTED_ENTRY_ENABLED || assistedLaunch == null || activePrepared == null || activity == null) {
                null
            } else {
                val attemptSummary = activePrepared.patchSummary
                ForeverFunctionalAutologinAssistedEntry(
                    launch = assistedLaunch,
                    beginSensitiveSession = {
                        assistedLaunch.beginSensitiveCredentialSession(context.applicationContext, activity.window)
                    },
                    acquireCredentials = {
                        ForeverFunctionalAutologinStores.forApplication(context.applicationContext).acquire()
                    },
                    input = ForeverFunctionalAutologinXServerInput(
                        activity,
                        activePrepared,
                        mappedGameWindow,
                    ),
                    scheduler = ForeverFunctionalAutologinMainScheduler(),
                    report = { result ->
                        if (result == ForeverFunctionalAutologinAssistedResult.SENT) {
                            attemptSummary?.noteAssistedSubmission()
                        }
                        assistedStatusHandler.post {
                            assistedStatus = assistedEntryStatusMessage(result)
                        }
                    },
                )
            }
        }
        DisposableEffect(assistedSender) {
            val cancel: (() -> Unit)? = assistedSender?.let { sender -> { sender.cancel() } }
            onAssistedEntryCancellationChanged(cancel)
            onDispose {
                assistedSender?.cancel()
                onAssistedEntryCancellationChanged(null)
            }
        }
        val keyboardKeys = remember {
            ForeverFunctionalKeyboardKeys { keyCode, action ->
                val keyboard = PluviaApp.xServerView?.getxServer()?.keyboard
                if (keyboard != null) {
                    val now = SystemClock.uptimeMillis()
                    keyboard.onKeyEvent(android.view.KeyEvent(now, now, action, keyCode, 0))
                }
            }
        }
        var ctrlActive by remember { mutableStateOf(false) }
        var altActive by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (!imeVisible) {
                keyboardKeys.releaseModifiers()
                ctrlActive = false
                altActive = false
            }
        }
        DisposableEffect(keyboardKeys) {
            onDispose { keyboardKeys.releaseModifiers() }
        }
        val gameRootModifier = if (displayInsets == null) {
            Modifier.fillMaxSize()
        } else {
            with(density) {
                Modifier
                    .fillMaxSize()
                    .absolutePadding(
                        left = displayInsets.left.toDp(),
                        top = displayInsets.top.toDp(),
                        right = displayInsets.right.toDp(),
                        bottom = displayInsets.bottom.toDp(),
                    )
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = gameRootModifier) {
                XServerScreen(
                    appId = ForeverFunctionalPoc.APP_ID,
                    bootToContainer = false,
                    testGraphics = false,
                    diagnostics = false,
                    debugRun = false,
                    isOffline = false,
                    registerBackAction = { backAction = it },
                    navigateBack = {
                        assistedSender?.cancel()
                        val session = prepared
                        session?.let { ForeverFunctionalPoc.record(it.logFile, "gui_closed") }
                        // Sin inferir PID/WoW/render/login: resultado desconocido.
                        runCatching {
                            ForeverRemoteDiagnosticsAndroid.updateAttemptOutcome(
                                context, remoteAttemptId, result = "unknown", stage = "running", ended = true,
                                // No sobrescribir un exit code ya observado por finished().
                                onlyIfEmpty = true)
                        }
                        cancelPatch()
                        displayGate.cancel()
                        displayPlan = null
                        session?.let { ForeverFunctionalPoc.releaseGuestAfterShutdown(context, it) }
                        prepared = null
                        running = false
                        onRunningChanged(false)
                    },
                    onExit = { completion ->
                        assistedSender?.cancel()
                        val session = prepared
                        session?.let { ForeverFunctionalPoc.record(it.logFile, "wine_exit") }
                        // Terminación Wine observada como cierre de vista; el
                        // arranque de WoW y el render no quedan demostrados.
                        // No pisa un exit code ya observado por finished().
                        runCatching {
                            ForeverRemoteDiagnosticsAndroid.updateAttemptOutcome(
                                context, remoteAttemptId, result = "unknown", stage = "running", ended = true,
                                onlyIfEmpty = true)
                        }
                        cancelPatch()
                        displayGate.cancel()
                        displayPlan = null
                        completion?.invoke()
                        session?.let { ForeverFunctionalPoc.releaseGuestAfterShutdown(context, it) }
                        prepared = null
                        running = false
                        onRunningChanged(false)
                    },
                    onGameLaunchError = { message ->
                        assistedSender?.cancel()
                        runCatching { recordRemoteDiagnosticsAction(context, "error", "failed") }
                        // Etiqueta fija: el mensaje crudo puede traer rutas.
                        runCatching {
                            ForeverRemoteDiagnosticsAndroid.updateAttemptOutcome(
                                context, remoteAttemptId, result = "unknown", stage = "launch",
                                 error = "game_error", ended = true, onlyIfEmpty = true)
                        }
                        val session = prepared
                        session?.let {
                            ForeverFunctionalPoc.record(
                                it.logFile,
                                "launch_error",
                                org.json.JSONObject().put("message", message),
                            )
                        }
                        // The raw runtime message stays in the bounded log;
                        // the user only sees the human wording.
                        error = ForeverFunctionalUiText.userMessage(Exception(message))
                        status = "Error al iniciar WoW"
                        // Wine setup can fail without a normal guest-exit
                        // callback: cancel the owned patch session here, as
                        // onExit and navigateBack already do, so the lease
                        // and its native exclusion end exactly once instead
                        // of lingering until the watchdog.
                        cancelPatch()
                        displayGate.cancel()
                        displayPlan = null
                        if (session != null) {
                            // Single terminal release, off the UI thread: the
                            // async shutdown watch below proves quiescence
                            // before releasing this launch's reservation, and
                            // the next explicit Play recovers any retained
                            // stale record. A second synchronous recovery here
                            // would duplicate that work on this thread.
                            ForeverFunctionalPoc.releaseGuestAfterShutdown(context, session)
                        }
                        prepared = null
                        onRunningChanged.invoke(false)
                        running = false
                    },
                    onWindowMapped = { _, mappedWindow ->
                        if (mappedWindow.className?.contains(
                                ForeverFunctionalPoc.EXECUTABLE_NAME,
                                ignoreCase = true,
                            ) == true
                        ) {
                            PluviaApp.xServerView?.getxServer()?.let { server ->
                                mappedGameWindow.set(server to mappedWindow)
                            }
                        }
                        prepared?.let {
                            ForeverFunctionalPoc.record(it.logFile, "x_window_mapped")
                            val rendererView = PluviaApp.xServerView
                            val server = rendererView?.getxServer()
                            val androidView = rendererView as? android.view.View
                            val details = org.json.JSONObject()
                                .put("expectedDesktop", it.displayPlan?.size ?: "unmeasured")
                                .put("actualDesktopWidth", server?.screenInfo?.width?.toInt() ?: -1)
                                .put("actualDesktopHeight", server?.screenInfo?.height?.toInt() ?: -1)
                                .put("surfaceWidth", androidView?.width ?: -1)
                                .put("surfaceHeight", androidView?.height ?: -1)
                                .put("windowWidth", mappedWindow.width.toInt())
                                .put("windowHeight", mappedWindow.height.toInt())
                                .put("targetClassMatches", mappedWindow.className
                                    ?.contains(ForeverFunctionalPoc.EXECUTABLE_NAME, ignoreCase = true) == true)
                            ForeverFunctionalPoc.record(it.logFile, "display_runtime_observed", details)
                        }
                    },
                    onWindowUnmapped = { unmappedWindow ->
                        val current = mappedGameWindow.get()
                        if (current?.second === unmappedWindow) {
                            mappedGameWindow.compareAndSet(current, null)
                            assistedSender?.cancel()
                        }
                    },
                )
            }
            patchStatus?.let { snapshot ->
                // Advisory-only per-attempt patch state, visible before the
                // manual login. Noninteractive and outside the inset-padded
                // game viewport, so mapping, insets, input, and IME behavior
                // are unchanged. No login action or policy lives here. The
                // translucent backing is draw-only legibility over arbitrary
                // game pixels: no touch handling, no viewport change.
                Text(
                    patchAttemptLabel(snapshot),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (ASSISTED_ENTRY_ENABLED) assistedStatus?.let { message ->
                Text(
                    message,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .width(if (ASSISTED_ENTRY_ENABLED) 96.dp else 48.dp)
                    .height(48.dp),
                shape = if (ASSISTED_ENTRY_ENABLED) RoundedCornerShape(24.dp) else CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row {
                    if (ASSISTED_ENTRY_ENABLED) {
                        IconButton(onClick = { assistedSender?.request() }) {
                            Icon(
                                imageVector = ForeverFunctionalIcons.Account,
                                contentDescription = "Introducir cuenta. Requiere Usuario vacío y enfocado, sin diálogos. Borra manualmente cualquier nombre recordado. No detecta campos ni modales y no confirma el inicio de sesión.",
                            )
                        }
                    }
                    IconButton(onClick = onKeyboardRequested) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Abrir teclado",
                        )
                    }
                }
            }
            if (imeVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForeverAccessoryKey("Ocultar", "Ocultar teclado") {
                            ForeverFunctionalLauncherUx.hideRealKeyboard(rootView)
                        }
                        ForeverAccessoryKey("Esc", "Escape") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_ESCAPE) }
                        ForeverAccessoryKey("Tab", "Tab") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_TAB) }
                        ForeverAccessoryKey("Ctrl", "Control", selected = ctrlActive) {
                            ctrlActive = keyboardKeys.toggleModifier(android.view.KeyEvent.KEYCODE_CTRL_LEFT)
                        }
                        ForeverAccessoryKey("Alt", "Alt", selected = altActive) {
                            altActive = keyboardKeys.toggleModifier(android.view.KeyEvent.KEYCODE_ALT_LEFT)
                        }
                        ForeverAccessoryKey("←", "Flecha izquierda") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
                        ForeverAccessoryKey("↑", "Flecha arriba") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_DPAD_UP) }
                        ForeverAccessoryKey("↓", "Flecha abajo") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                        ForeverAccessoryKey("→", "Flecha derecha") { keyboardKeys.tap(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
                    }
                }
            }
        }
        return
    }

    if (launchRequested) {
        BackHandler { cancelDisplayLaunch() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
        .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF182A3B), ForeverBackground, Color(0xFF060B12)),
                ),
            ),
    ) {
        ForeverRealmBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            ForeverCrest()
            Text(
                "WoW Forever",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            ForeverStonePanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    ForeverSectionHeader("Cliente")
                    Text(
                        "Servidor: ${settings.profile.displayName}",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Single real-status block. A live download dominates
                    // partial-file labels; the hardcoded build is never shown
                    // as the installed version (the target build is authoritative
                    // only inside the manager).
                    when (val state = homeState) {
                        is ForeverFunctionalLauncherUx.HomeState.Checking -> {
                            Text(
                                "Comprobando…",
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        is ForeverFunctionalLauncherUx.HomeState.Downloading -> {
                            val detail = downloadDetail
                            Text(
                                if (state.applying) "Aplicando los archivos…" else "Descargando el cliente…",
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                ForeverFunctionalUiText.downloadStatusText(
                                    detail,
                                    downloadProgress.ifBlank { "Descargando…" },
                                ),
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val fraction = ForeverFunctionalUiText.downloadFraction(detail)
                            if (fraction != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .height(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(4.dp),
                                        ),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .height(8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.secondary,
                                                RoundedCornerShape(4.dp),
                                            ),
                                    )
                                }
                                Text(
                                    "${ForeverFunctionalUiText.downloadPercent(detail)} %",
                                    modifier = Modifier.padding(top = 6.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Pausing is only an explicit action, and never
                                // during the atomic apply/recover phases.
                                val canPauseNow = downloadBusy && !state.applying
                                OutlinedButton(
                                    enabled = canPauseNow,
                                    onClick = {
                                        runCatching { recordRemoteDiagnosticsAction(context, "pause", "ok") }
                                        context.startService(
                                            Intent(context, ForeverClientUpdateForegroundService::class.java)
                                                .setAction(ForeverClientUpdateForegroundService.ACTION_PAUSE),
                                        )
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) { Text(if (state.applying) "Aplicando…" else "Pausar") }
                                TextButton(
                                    onClick = {
                                        showSettings = false
                                        managerReturnToSettings = false
                                        showClientManagement = true
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(
                                        imageVector = ForeverFunctionalIcons.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Ver descarga")
                                }
                            }
                        }
                        is ForeverFunctionalLauncherUx.HomeState.StorageUnavailable -> {
                            Text(
                                if (storageKnown) readiness.label else "No se pudo comprobar el almacenamiento",
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        is ForeverFunctionalLauncherUx.HomeState.Missing -> {
                            Text(
                                "Cliente no instalado",
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        is ForeverFunctionalLauncherUx.HomeState.Incomplete -> {
                            Text(
                                readiness.label,
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        is ForeverFunctionalLauncherUx.HomeState.Ready -> {
                            Text(
                                readiness.label,
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    Text(
                        "Almacenamiento: ${readiness.storageLabel}",
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { showClientDetails = !showClientDetails },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (showClientDetails) "Ocultar detalles" else "Detalles") }
                    if (showClientDetails) {
                        Text(
                            "Ruta: ${readiness.storageRoute}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            // Idle status (stale 'Falta…', 'Descarga pausada…') is suppressed
            // while a download is active: the status block owns the truth.
            if (!downloadBusy && (busy || error != null || prepared != null || status != readiness.label)) {
                Text(
                    status,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // Terminal stays visible after prepared is cleared; a newer
            // attempt replaces it under its own generation guard. Plain
            // text only: no action, no login policy, no integrity claim.
            patchStatus?.let { snapshot ->
                Text(
                    patchAttemptLabel(snapshot),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (busy) {
                ForeverStonePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            strokeWidth = 3.dp,
                        )
                        Text(
                            "Preparando…",
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else when (val state = homeState) {
                is ForeverFunctionalLauncherUx.HomeState.Checking -> {
                    // No actionable readiness while the first inspection runs.
                }
                is ForeverFunctionalLauncherUx.HomeState.Downloading -> {
                    // Pause and the manager entry live in the status block.
                }
                is ForeverFunctionalLauncherUx.HomeState.StorageUnavailable -> {
                    Button(
                        onClick = {
                            showSettings = false
                            storagePendingId = storageSnapshot?.selected?.identity?.id
                            showStorageSettings = true
                            refreshStorage()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(top = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForeverBurgundy,
                            contentColor = ForeverInk,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ForeverGold.copy(alpha = 0.7f)),
                    ) { Text("Elegir almacenamiento") }
                }
                is ForeverFunctionalLauncherUx.HomeState.Missing -> {
                    Button(
                        onClick = {
                            showSettings = false
                            managerReturnToSettings = false
                            showClientManagement = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(top = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForeverBurgundy,
                            contentColor = ForeverInk,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ForeverGold.copy(alpha = 0.7f)),
                    ) {
                        Icon(
                            imageVector = ForeverFunctionalIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descargar cliente")
                    }
                }
                is ForeverFunctionalLauncherUx.HomeState.Incomplete -> {
                    if (prepared == null) {
                        Button(
                            onClick = {
                                if (readiness.canRequestPlay) {
                                    prepareEnvironment(fromManager = false)
                                } else {
                                    showSettings = false
                                    managerReturnToSettings = false
                                    showClientManagement = true
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForeverBurgundy,
                                contentColor = ForeverInk,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ForeverGold.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                imageVector = if (readiness.canRequestPlay) Icons.Default.PlayArrow else ForeverFunctionalIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (readiness.canRequestPlay) "Jugar" else "Completa la instalación")
                        }
                    }
                }
                is ForeverFunctionalLauncherUx.HomeState.Ready -> {
                    if (prepared == null) {
                        Button(
                            onClick = { prepareEnvironment(fromManager = false) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForeverBurgundy,
                                contentColor = ForeverInk,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ForeverGold.copy(alpha = 0.7f)),
                        ) { Text("Jugar") }
                    }
                }
            }
            if (launchRequested) {
                ForeverStonePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        ForeverSectionHeader("Presentación")
                        Text(
                            "Midiendo el área útil horizontal antes de crear la sesión Wine…",
                            modifier = Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { cancelDisplayLaunch() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 6.dp),
                        ) { Text("Cancelar") }
                    }
                }
            }
            // Prepared-proof gate: a successful prepare() is the strong launch
            // proof (its frozen storage target is revalidated again in
            // beforeLaunch), while metadata inspection stays presence-only and
            // deliberately reports managed builds as PRESENT_NOT_VERIFIED
            // (Incomplete), never READY. Storage-known/available plus
            // no-download exclusions keep stale or unavailable launches
            // blocked; consent and beforeLaunch stay authoritative, and no
            // integrity claim is made here.
            if (prepared != null && !launchRequested && !downloadBusy && !graphicsResetBusy && storageKnown && readiness.storageAvailable &&
                (homeState is ForeverFunctionalLauncherUx.HomeState.Ready ||
                    homeState is ForeverFunctionalLauncherUx.HomeState.Incomplete)
            ) {
                ForeverStonePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        ForeverSectionHeader("Listo para iniciar")
                        Text(
                            "Entorno preparado para ${settings.profile.displayName}.",
                            modifier = Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 8.dp),
                        ) {
                            Checkbox(checked = consent, onCheckedChange = { consent = it })
                            Text(
                                "Se abrirá WoW. El inicio de sesión será manual.",
                                modifier = Modifier.padding(start = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            enabled = consent && !graphicsResetBusy,
                            onClick = {
                                prepared?.let {
                                    try {
                                        ForeverFunctionalPoc.record(it.logFile, "launch_requested")
                                        runCatching { recordRemoteDiagnosticsAction(context, "launch", "pending") }
                                        if (it.memoryPatchEnabled && ForeverFunctionalPoc.patchLaunchHook == null) {
                                            status = "Parche no disponible; no se afirmará que fue aplicado"
                                        }
                                         // Display measurement and the one
                                         // beforeLaunch call are handled by
                                         // the two-frame gate above.
                                         val token = launchCoordinator.begin()
                                         if (token == null) {
                                             status = "El intento anterior aún está finalizando"
                                         } else {
                                             launchToken = token
                                             launchRequested = true
                                             status = "Midiendo presentación…"
                                         }
                                    } catch (failure: Throwable) {
                                        ForeverFunctionalPoc.record(
                                            it.logFile,
                                            "launch_blocked",
                                            org.json.JSONObject()
                                                .put("message", failure.message ?: failure::class.java.simpleName)
                                                .put("codeCaptureEnabled", it.codeCaptureEnabled)
                                                .put("readOnly", true)
                                                .put("bytesWritten", 0),
                                        )
                                        status = "WoW no lanzado"
                                        error = ForeverFunctionalUiText.userMessage(failure)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForeverBurgundyPressed,
                                contentColor = ForeverInk,
                            ),
                        ) { Text("Lanzar WoW") }
                    }
                }
            }
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = ForeverGold),
                enabled = !sessionOrIoLocked,
                onClick = {
                    showAdvancedSettings = false
                    showSettings = true
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(" Ajustes")
            }
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = ForeverGold),
                onClick = { showRemoteDiagnostics = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Enviar diagnóstico")
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Ajustes", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState())) {
                    ForeverSectionHeader("General")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ForeverSettingsAction(
                            label = "Cliente y actualizaciones",
                            icon = ForeverFunctionalIcons.Download,
                            enabled = !sessionOrIoLocked,
                            onClick = {
                                showSettings = false
                                managerReturnToSettings = true
                                showClientManagement = true
                            },
                        )
                        ForeverSettingsAction(
                            label = "Almacenamiento",
                            icon = ForeverFunctionalIcons.Storage,
                            enabled = !busy && !running && !launchRequested && !pendingDisplayIo && !storageBusy && !graphicsResetBusy,
                            onClick = {
                                showSettings = false
                                storagePendingId = storageSnapshot?.selected?.identity?.id
                                showStorageSettings = true
                                refreshStorage()
                            },
                        )
                        ForeverSettingsAction(
                            label = "Cuenta",
                            icon = ForeverFunctionalIcons.Account,
                            enabled = !sessionOrIoLocked,
                            onClick = {
                                showSettings = false
                                showAutologinSettings = true
                            },
                        )
                        ForeverSettingsAction(
                            label = "Pantalla / resolución: ${displayMode.label}",
                            icon = ForeverFunctionalIcons.Display,
                            enabled = !sessionOrIoLocked,
                            onClick = {
                                // Entering resolution auto-closes an idle,
                                // unlaunched preparation (US-004 FR-017): real
                                // session/IO work stays excluded above.
                                discardIdlePreparedSnapshot()
                                showSettings = false
                                showDisplayModeOptions = true
                            },
                        )
                        ForeverSettingsAction(
                            label = "Compatibilidad gráfica",
                            icon = ForeverFunctionalIcons.Graphics,
                            enabled = !sessionOrIoLocked,
                            onClick = {
                                showSettings = false
                                showGpuCompatibility = true
                                runCatching { recordRemoteDiagnosticsAction(context, "gpu-change", "ok") }
                            },
                        )
                        ForeverSettingsAction(
                            label = "Restablecer gráficos",
                            icon = ForeverFunctionalIcons.Graphics,
                            enabled = !sessionOrIoLocked && !downloadBusy && !graphicsResetBusy,
                            onClick = {
                                showGraphicsResetConfirm = true
                            },
                        )
                    }
                    if (sessionOrIoLocked) {
                        Text(
                            "Hay una operación en curso; espera a que termine para cambiar la resolución.",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { showAdvancedSettings = !showAdvancedSettings },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
                    ) {
                        Text(if (showAdvancedSettings) "Ocultar opciones avanzadas" else "Opciones avanzadas")
                    }
                    if (showAdvancedSettings) {
                        // The server profile is fixed at build time (Classic
                        // Forever); there is no runtime selection. Stored
                        // preferences from older installs are coerced on load.
                        ForeverSectionHeader("Perfil de servidor", modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "Servidor: ${settings.profile.displayName} (fijado por la compilación)",
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedTextField(
                            value = settings.portalOverride.orEmpty(),
                            onValueChange = { value ->
                                try {
                                    updateSettings(
                                        settings.copy(
                                            portalOverride = ForeverFunctionalServerProfiles.validateHostnameOverride(value),
                                        ),
                                    )
                                    error = null
                                } catch (_: IllegalArgumentException) {
                                    error = "Dirección no válida. Usa solo el nombre del servidor."
                                }
                            },
                            enabled = !controlsLocked,
                            label = { Text("Dirección personalizada (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        ForeverSectionHeader(
                            "Diagnósticos",
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = settings.memoryPatchEnabled,
                                enabled = !controlsLocked && !settings.codeCaptureEnabled,
                                onCheckedChange = { enabled -> updateSettings(settings.copy(memoryPatchEnabled = enabled)) },
                            )
                            Text(
                                "Parche de memoria (opt-in)",
                                modifier = Modifier.padding(start = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = settings.codeCaptureEnabled,
                                enabled = !controlsLocked && !settings.memoryPatchEnabled,
                                onCheckedChange = { enabled -> updateSettings(settings.copy(codeCaptureEnabled = enabled)) },
                            )
                            Text(
                                "Captura ARM64 (opt-in)",
                                modifier = Modifier.padding(start = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "El parche de memoria solo escribe la clave pública del grupo 8; la captura es solo diagnóstico. Ninguna opción garantiza el acceso.",
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSettings = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cerrar") }
            },
        )
    }

    // The graphics card lives here, one tap from Ajustes. Opening Ajustes
    // never composes it, so no EGL read runs until this dialog opens.
    if (showGpuCompatibility) {
        AlertDialog(
            onDismissRequest = {
                showGpuCompatibility = false
                showSettings = true
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Compatibilidad gráfica", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState())) {
                    ForeverFunctionalGpuCompatibilityCard(
                        preparedConfiguration = prepared?.let { ForeverFunctionalGpuPreparedConfiguration.fromPrepared(it) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGpuCompatibility = false
                        showSettings = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Volver") }
            },
        )
    }

    if (showClientManagement) {
        ForeverClientManagementDialog(
            onPlay = {
                // Single launch path: prepare here, then return to the home
                // consent screen. Consent is never pre-checked.
                showClientManagement = false
                showSettings = false
                prepareEnvironment(fromManager = true)
            },
            onDismiss = {
                showClientManagement = false
                showSettings = managerReturnToSettings
                refreshReadiness()
            },
            onClientChanged = {
                prepared = null
                consent = false
                status = "Cliente actualizado"
                refreshStorage()
                scope.launch {
                    val inspected = withContext(Dispatchers.IO) { ForeverFunctionalLauncherUx.inspectClient(context) }
                    readiness = inspected
                }
            },
        )
    }

    if (showStorageSettings) {
        ForeverFunctionalClientStorageDialog(
            snapshot = storageSnapshot,
            pendingId = storagePendingId,
            busy = storageBusy || pastedActionBusyTitle != null,
            onPendingChanged = { storagePendingId = it },
            onUse = { id ->
                storageSnapshot?.options?.firstOrNull { it.identity.id == id }?.let {
                    commitStorage(it.identity)
                }
            },
            onCancel = {
                storageGeneration.incrementAndGet()
                storageRequest?.cancel()
                storageJob?.cancel()
                storageJob = null
                storageBusy = false
                showStorageSettings = false
                showSettings = true
            },
            onRefresh = ::refreshStorage,
            onPreparePastedClient = ::preparePastedClientFolder,
            onReviewPastedClient = ::reviewPastedClient,
            pastedActionsEnabled = !downloadBusy && !running && !launchRequested &&
                !pendingDisplayIo && prepared == null,
        )
    }

    pastedActionBusyTitle?.let { title ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Un momento, por favor.", modifier = Modifier.padding(start = 12.dp))
                }
            },
            confirmButton = {},
        )
    }

    pastedFolderResult?.let { folder ->
        AlertDialog(
            onDismissRequest = ::returnToStorageAfterPastedAction,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Carpeta de cliente preparada", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    pastedClientFolderDetails(folder),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = ::returnToStorageAfterPastedAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Volver a almacenamiento")
                }
            },
        )
    }

    pastedClientReport?.let { report ->
        AlertDialog(
            onDismissRequest = ::returnToStorageAfterPastedAction,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Revisión del cliente pegado", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    pastedClientReportDetails(report),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = ::returnToStorageAfterPastedAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Volver a almacenamiento")
                }
            },
        )
    }

    pastedActionError?.let { message ->
        AlertDialog(
            onDismissRequest = ::returnToStorageAfterPastedAction,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(pastedActionErrorTitle, style = MaterialTheme.typography.titleLarge) },
            text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = ::returnToStorageAfterPastedAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Volver a almacenamiento")
                }
            },
        )
    }

    if (showDisplayModeOptions) {
        AlertDialog(
            onDismissRequest = {
                showDisplayModeOptions = false
                showSettings = true
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Resolución", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "Solo cambia la resolución, adaptada a la proporción útil de tu pantalla. " +
                            "La calidad se cambia dentro del juego y se conserva entre sesiones. " +
                            "El cambio se aplica al preparar la siguiente sesión.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ForeverFunctionalDisplayFit.Mode.values().forEach { mode ->
                        val selected = displayMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        displayMode = mode
                                        ForeverFunctionalDisplayFit.saveMode(context, mode)
                                        showDisplayModeOptions = false
                                        showSettings = true
                                        prepared = null
                                        consent = false
                                        status = "Ajustes guardados"
                                        runCatching { recordRemoteDiagnosticsAction(context, "resolution-change", "ok") }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(mode.label)
                                Text(
                                    when (mode) {
                                        ForeverFunctionalDisplayFit.Mode.AUTO ->
                                            "Se adapta a tu pantalla (altura objetivo 720)."
                                        ForeverFunctionalDisplayFit.Mode.PERFORMANCE ->
                                            "Menor resolución con la misma proporción."
                                        ForeverFunctionalDisplayFit.Mode.QUALITY ->
                                            "Mayor resolución con la misma proporción."
                                        ForeverFunctionalDisplayFit.Mode.NATIVE ->
                                            "Usa el área útil completa."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (displayMode.nativeWarning) {
                        Text(
                            "Nativa puede aumentar el uso de GPU y no garantiza 30 FPS.",
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisplayModeOptions = false
                        showSettings = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Volver") }
            },
        )
    }

    if (showGraphicsResetConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!graphicsResetBusy) showGraphicsResetConfirm = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Restablecer gráficos", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Vuelve a calidad 1, sin sombras y límite de 30 FPS la próxima vez que prepares WoW. " +
                        "No cambia la resolución, la cuenta, los controles, el servidor ni los datos del cliente. " +
                        "El juego debe estar cerrado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !sessionOrIoLocked && !downloadBusy && !graphicsResetBusy,
                    onClick = {
                        showGraphicsResetConfirm = false
                        resetGraphicsToDefaults()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Restablecer") }
            },
            dismissButton = {
                TextButton(
                    enabled = !graphicsResetBusy,
                    onClick = { showGraphicsResetConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancelar") }
            },
        )
    }

    if (showAutologinSettings) {
        ForeverFunctionalAutologinSettingsDialog(
            onDismiss = {
                showAutologinSettings = false
                showSettings = true
            },
            onChanged = { consent = false },
            runtimeReady = false,
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("No se pudo completar la acción", style = MaterialTheme.typography.titleLarge) },
            text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Button(
                    onClick = { error = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForeverBurgundy,
                        contentColor = ForeverInk,
                    ),
                ) { Text("Cerrar") }
            },
        )
    }

    // Diagnóstico remoto: sin red hasta Enviar; Cancelar no provoca red.
    if (showRemoteDiagnostics) {
        ForeverRemoteDiagnosticsDialog(attemptId = remoteAttemptId) {
            showRemoteDiagnostics = false
        }
    }
}

@Composable
private fun ForeverFunctionalClientStorageDialog(
    snapshot: ForeverFunctionalClientStorage.Snapshot?,
    pendingId: String?,
    busy: Boolean,
    onPendingChanged: (String) -> Unit,
    onUse: (String) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onPreparePastedClient: () -> Unit,
    onReviewPastedClient: () -> Unit,
    pastedActionsEnabled: Boolean,
) {
    val selected = snapshot?.selected
    var showLocationDetails by remember { mutableStateOf(false) }

    fun clientDataStatus(option: ForeverFunctionalClientStorage.StorageOption): String {
        val client = when (option.client.state) {
            ForeverFunctionalClientStorage.ClientInspection.State.NOT_INSTALLED -> "Cliente no instalado"
            ForeverFunctionalClientStorage.ClientInspection.State.INCOMPLETE -> "Cliente incompleto"
            ForeverFunctionalClientStorage.ClientInspection.State.PASTED_SOURCE -> "Cliente pegado detectado; sin verificar"
            ForeverFunctionalClientStorage.ClientInspection.State.PRESENT_NOT_VERIFIED,
            ForeverFunctionalClientStorage.ClientInspection.State.READY -> "Cliente presente"
        }
        val data = if (option.client.dataPresent) {
            "Data presente · integridad no verificada"
        } else {
            "Data no instalada"
        }
        return "$client · $data"
    }

    fun availabilityStatus(option: ForeverFunctionalClientStorage.StorageOption): String = when {
        !option.mounted -> "No conectada"
        option.writable == false -> "Solo lectura"
        option.writable == true -> "Escritura observada"
        else -> "Acceso por comprobar"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Almacenamiento", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (selected != null) {
                    Text(
                        "Ubicación actual: ${selected.label} · ${foreverFormatBytes(selected.availableBytes)} libres",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "${clientDataStatus(selected)} · ${availabilityStatus(selected)}",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { showLocationDetails = !showLocationDetails },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (showLocationDetails) "Ocultar detalles" else "Detalles de la ubicación") }
                    if (showLocationDetails) {
                        Text(
                            "Ruta: ${selected.route}\nAcceso: ${selected.writeState}\n${selected.descriptor}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val actionsUseCurrent = selected != null && pendingId == selected.identity.id
                if (!actionsUseCurrent && selected != null) {
                    Text(
                        "Guarda primero el cambio de ubicación para usarla en estas acciones.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = !busy && actionsUseCurrent && pastedActionsEnabled,
                    onClick = onPreparePastedClient,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Preparar carpeta de cliente") }
                TextButton(
                    enabled = !busy && actionsUseCurrent && pastedActionsEnabled,
                    onClick = onReviewPastedClient,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Revisar cliente pegado") }
                if (snapshot == null) {
                    Text("Buscando ubicaciones…", modifier = Modifier.padding(top = 8.dp))
                } else {
                    snapshot.options.forEach { option ->
                        val isCurrent = option.identity.id == selected?.identity?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics { role = Role.RadioButton },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pendingId == option.identity.id,
                                enabled = option.available && !busy,
                                onClick = { onPendingChanged(option.identity.id) },
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    "${option.label}${if (isCurrent) " · Ubicación actual" else ""}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (!isCurrent) {
                                    Text(
                                        "${clientDataStatus(option)} · ${availabilityStatus(option)} · ${foreverFormatBytes(option.availableBytes)} libres",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (snapshot.removableOptions.none { it.mounted }) {
                        Text(
                            "No hay una unidad USB o SD disponible.",
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (pendingId != null && pendingId == selected?.identity?.id) {
                    Text(
                        "Esta es tu ubicación actual.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (pendingId != null) {
                    Text(
                        "Cambiar la ubicación no mueve tus archivos.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = !busy,
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Buscar unidades") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pendingId != null && pendingId != selected?.identity?.id &&
                    snapshot?.options?.any { it.identity.id == pendingId && it.available } == true && !busy,
                onClick = { pendingId?.let(onUse) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Usar esta ubicación") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver") }
        },
    )
}

private fun pastedClientFolderDetails(folder: ForeverFunctionalClientStorage.PastedClientFolder): String = buildString {
    appendLine(if (folder.created) "Se ha creado la carpeta de pegado." else "La carpeta de pegado ya existía y no se ha vaciado ni modificado.")
    appendLine()
    appendLine("Ruta en el móvil:")
    appendLine(folder.clientRootPath)
    appendLine()
    appendLine("Ruta dentro del USB vista desde el PC:")
    appendLine(folder.pcPath)
    appendLine()
    appendLine("Build objetivo: el configurado en esta app (ver fichero LEEME). Al preparar la actualización se calculará qué falta por descargar.")
    appendLine()
    appendLine("Qué hacer:")
    appendLine("1. Desconecta el USB del móvil y conéctalo al PC.")
    appendLine("2. Pega la carpeta Data/ y el fichero .build.info en la ruta indicada.")
    appendLine("3. Expulsa el USB del PC y vuelve a conectarlo al móvil.")
    appendLine("4. Pulsa «Revisar cliente pegado».")
    appendLine()
    append("Instrucciones guardadas en: ${folder.readmePath}")
}

private fun pastedClientReportDetails(report: PastedClientReport): String = buildString {
    appendLine(ForeverFunctionalUiText.pastedClientVerdictText(report.verdict))
    appendLine()
    appendLine("Versión detectada: ${report.version ?: "No identificada"}")
    appendLine("Build: ${report.buildId.takeIf { it > 0 }?.toString() ?: "No identificado"}")
    appendLine("Producto: ${report.product ?: "No identificado"}")
    appendLine("Plataforma: ${report.platformTag ?: if (report.isArm64) "ARM64" else "No identificada"}")
    appendLine("Fuente CASC: ${if (report.hasCascData) "presente y legible" else "no detectada o no legible"}")
    if (report.present) {
        appendLine()
        appendLine("Esta revisión solo lee metadatos e índices; no valida el contenido. La reutilización real y lo que falte por descargar se calculan al preparar la actualización.")
    }
    if (report.unexpectedEntries.isNotEmpty()) {
        appendLine()
        appendLine("Hay ${report.unexpectedEntries.size} entradas ajenas en la carpeta del cliente. No se van a tocar.")
        report.unexpectedEntries.forEach { appendLine("• $it") }
    }
}

private fun foreverFormatBytes(value: Long): String {
    return ForeverFunctionalClientStorage.formatAvailableBytes(value)
}
