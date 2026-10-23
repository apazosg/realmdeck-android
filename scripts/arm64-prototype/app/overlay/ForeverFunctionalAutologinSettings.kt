package app.gamenative

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only persisted autologin preference. Credentials never enter this store. */
object ForeverFunctionalAutologinPreferences {
    data class Preferences(val enabled: Boolean = false)

    private const val PREFS = "forever_functional_autologin_preferences"
    private const val ENABLED_KEY = "enabled"

    fun load(context: Context): Preferences {
        val applicationContext = context.applicationContext
        return Preferences(
            enabled = applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_KEY, false),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val applicationContext = context.applicationContext
        check(
            applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(ENABLED_KEY, enabled)
                .commit(),
        ) { "autologin preference write failed" }
    }
}

internal interface ForeverFunctionalAutologinSettingsPreferencesPort {
    fun load(): ForeverFunctionalAutologinPreferences.Preferences
    fun setEnabled(enabled: Boolean)
}

internal interface ForeverFunctionalAutologinSettingsStorePort {
    fun save(credentials: ForeverFunctionalAutologinCredentials, remember: Boolean)
    fun acquire(): ForeverFunctionalAutologinCredentials?
    fun settingsSummary(): ForeverFunctionalAutologinSettingsSummary
    fun disableAndClear()
}

internal data class ForeverFunctionalAutologinSettingsLoad(
    val desiredEnabled: Boolean,
    val effectiveEnabled: Boolean,
    val pendingVerification: Boolean,
    val summary: ForeverFunctionalAutologinSettingsSummary,
    val stateChanged: Boolean = false,
    val publishable: Boolean = true,
)

internal data class ForeverFunctionalAutologinSettingsOperation(
    val success: Boolean,
    val cancelled: Boolean = false,
    val errorCode: String? = null,
    val summary: ForeverFunctionalAutologinSettingsSummary? = null,
)

/** Serializes complete preference/vault transactions and rejects stale dialog owners. */
internal class ForeverFunctionalAutologinSettingsCoordinator {
    private val generation = AtomicLong(0L)
    private val transactionLock = Any()

    fun newOwner(): Long = generation.incrementAndGet()

    fun invalidate(owner: Long) {
        generation.compareAndSet(owner, owner + 1L)
    }

    fun isCurrent(owner: Long): Boolean = generation.get() == owner

    fun <T> transaction(owner: Long, block: () -> T): T = synchronized(transactionLock) {
        check(isCurrent(owner)) { "stale autologin settings owner" }
        block()
    }
}

internal object ForeverFunctionalAutologinSettingsCoordinators {
    private val coordinators = ConcurrentHashMap<String, ForeverFunctionalAutologinSettingsCoordinator>()

    fun forApplication(context: Context): ForeverFunctionalAutologinSettingsCoordinator {
        val applicationContext = context.applicationContext
        return coordinators.computeIfAbsent(applicationContext.packageName) {
            ForeverFunctionalAutologinSettingsCoordinator()
        }
    }
}

/** Injectable, bounded transaction boundary for the boolean preference and credential vault. */
internal class ForeverFunctionalAutologinSettingsService(
    private val preferences: ForeverFunctionalAutologinSettingsPreferencesPort,
    private val store: ForeverFunctionalAutologinSettingsStorePort,
    private val coordinator: ForeverFunctionalAutologinSettingsCoordinator =
        ForeverFunctionalAutologinSettingsCoordinator(),
) {
    private val owner = coordinator.newOwner()

    fun invalidateOwner() = coordinator.invalidate(owner)

    fun isOwnerCurrent(): Boolean = coordinator.isCurrent(owner)

    fun load(): ForeverFunctionalAutologinSettingsLoad {
        return coordinator.transaction(owner) {
            val persisted = preferences.load()
            val current = store.settingsSummary()
            if (current.requiresReentry) {
                val changed = persisted.enabled
                try {
                    if (!coordinator.isCurrent(owner)) throw IllegalStateException()
                    preferences.setEnabled(false)
                } catch (_: Throwable) {
                    throw ForeverFunctionalAutologinSettingsFailure("AUTLOGIN_LOAD_RECONCILE_FAILED")
                }
                return@transaction ForeverFunctionalAutologinSettingsLoad(
                    desiredEnabled = false,
                    effectiveEnabled = false,
                    pendingVerification = false,
                    summary = current,
                    stateChanged = changed,
                    publishable = coordinator.isCurrent(owner),
                )
            }
            val pending = persisted.enabled && !current.sessionAvailable
            ForeverFunctionalAutologinSettingsLoad(
                // A remembered file is not treated as validated until the store acquires it.
                desiredEnabled = persisted.enabled,
                effectiveEnabled = persisted.enabled && current.sessionAvailable,
                pendingVerification = pending,
                summary = current,
                publishable = coordinator.isCurrent(owner),
            )
        }
    }

    fun save(
        requestedEnabled: Boolean,
        username: CharArray,
        password: CharArray,
        remember: Boolean,
        checkActive: () -> Unit,
    ): ForeverFunctionalAutologinSettingsOperation {
        return coordinator.transaction(owner) {
            val failures = mutableListOf<String>()
            var cancelled = false
            try {
                checkOwnerAndActive(checkActive)
                preferences.setEnabled(false)
                checkOwnerAndActive(checkActive)
                if (username.isEmpty() && password.isNotEmpty()) {
                    throw ForeverFunctionalAutologinSettingsFailure("AUTLOGIN_CREDENTIALS_REQUIRED")
                }
                if (password.isEmpty()) {
                    val existing = store.acquire()
                        ?: throw ForeverFunctionalAutologinSettingsFailure("AUTLOGIN_CREDENTIALS_REQUIRED")
                    try {
                        if (username.isNotEmpty() && !existing.useValues { savedUser, _ -> savedUser.contentEquals(username) }) {
                            throw ForeverFunctionalAutologinSettingsFailure("AUTLOGIN_CREDENTIALS_REQUIRED")
                        }
                        store.save(existing, remember)
                    } finally {
                        existing.close()
                    }
                } else {
                    val credentials = ForeverFunctionalAutologinCredentials(username, password)
                    try {
                        store.save(credentials, remember)
                    } finally {
                        credentials.close()
                    }
                }
                checkOwnerAndActive(checkActive)
                preferences.setEnabled(requestedEnabled)
                checkOwnerAndActive(checkActive)
                return@transaction ForeverFunctionalAutologinSettingsOperation(
                    success = true,
                    summary = store.settingsSummary(),
                )
            } catch (failure: Throwable) {
                cancelled = failure is CancellationException
                failures += if (cancelled) "AUTLOGIN_CANCELLED" else failureCode(failure, "AUTLOGIN_SAVE_FAILED")
            }

            // This remains under the complete-transaction lock. New work cannot interleave.
            try {
                store.disableAndClear()
            } catch (_: Throwable) {
                failures += "AUTLOGIN_COMPENSATION_FAILED"
            }
            try {
                preferences.setEnabled(false)
            } catch (_: Throwable) {
                failures += "AUTLOGIN_FALSE_COMMIT_FAILED"
            }
            ForeverFunctionalAutologinSettingsOperation(
                success = false,
                cancelled = cancelled,
                errorCode = if (failures.any { it == "AUTLOGIN_COMPENSATION_FAILED" || it == "AUTLOGIN_FALSE_COMMIT_FAILED" }) {
                    "AUTLOGIN_SAVE_COMPENSATION_FAILED"
                } else {
                    failures.firstOrNull() ?: "AUTLOGIN_SAVE_FAILED"
                },
            )
        }
    }

    fun savedUsername(): String? = coordinator.transaction(owner) {
        val credentials = store.acquire() ?: return@transaction null
        try {
            credentials.useValues { user, _ -> String(user) }
        } finally {
            credentials.close()
        }
    }

    fun cleanup(): ForeverFunctionalAutologinSettingsOperation = try {
        coordinator.transaction(owner) { cleanupLocked() }
    } catch (_: Throwable) {
        // A stale owner is not permitted to touch either preference or vault.
        ForeverFunctionalAutologinSettingsOperation(
            success = false,
            errorCode = if (coordinator.isCurrent(owner)) {
                "AUTLOGIN_CLEANUP_FAILED"
            } else {
                "AUTLOGIN_STALE_OWNER"
            },
        )
    }

    private fun cleanupLocked(): ForeverFunctionalAutologinSettingsOperation {
        // transaction(owner) already admitted this owner under transactionLock.
        // Finish both destructive legs if the UI closes while we hold it: a
        // newer owner's writes cannot begin until this transaction releases it.
        var failure = false
        try {
            preferences.setEnabled(false)
        } catch (_: Throwable) {
            failure = true
        }
        try {
            store.disableAndClear()
        } catch (_: Throwable) {
            failure = true
        }
        if (failure) {
            // Retry the false commit after vault cleanup if the first commit failed.
            try {
                preferences.setEnabled(false)
            } catch (_: Throwable) {
                // The opaque cleanup code remains the only user-visible result.
            }
            return ForeverFunctionalAutologinSettingsOperation(
                success = false,
                errorCode = "AUTLOGIN_CLEANUP_FAILED",
            )
        }
        return ForeverFunctionalAutologinSettingsOperation(
            success = true,
            summary = store.settingsSummary(),
        )
    }

    private fun checkOwnerAndActive(checkActive: () -> Unit) {
        check(coordinator.isCurrent(owner)) { "stale autologin settings owner" }
        checkActive()
        check(coordinator.isCurrent(owner)) { "stale autologin settings owner" }
    }

    private fun failureCode(failure: Throwable, fallback: String): String = when (failure) {
        is ForeverFunctionalAutologinSettingsFailure -> failure.errorCode
        is ForeverFunctionalAutologinStoreFailure -> failure.code
        else -> fallback
    }
}

internal class ForeverFunctionalAutologinSettingsFailure(val errorCode: String) : RuntimeException()

private class ForeverFunctionalAutologinContextPreferencesPort(context: Context) : ForeverFunctionalAutologinSettingsPreferencesPort {
    private val applicationContext = context.applicationContext

    override fun load(): ForeverFunctionalAutologinPreferences.Preferences =
        ForeverFunctionalAutologinPreferences.load(applicationContext)

    override fun setEnabled(enabled: Boolean) =
        ForeverFunctionalAutologinPreferences.setEnabled(applicationContext, enabled)
}

private class ForeverFunctionalAutologinContextStorePort(context: Context) : ForeverFunctionalAutologinSettingsStorePort {
    private val store = ForeverFunctionalAutologinStores.forApplication(context.applicationContext)

    override fun save(credentials: ForeverFunctionalAutologinCredentials, remember: Boolean) =
        store.save(credentials, remember)

    override fun acquire(): ForeverFunctionalAutologinCredentials? = store.acquire()

    override fun settingsSummary(): ForeverFunctionalAutologinSettingsSummary = store.settingsSummary()

    override fun disableAndClear() = store.disableAndClear()
}

internal fun foreverFunctionalAutologinSettingsService(context: Context): ForeverFunctionalAutologinSettingsService =
    ForeverFunctionalAutologinSettingsService(
        ForeverFunctionalAutologinContextPreferencesPort(context),
        ForeverFunctionalAutologinContextStorePort(context),
        ForeverFunctionalAutologinSettingsCoordinators.forApplication(context),
    )

/** Password fields get a no-op toolbar: Compose never receives copy/cut/paste callbacks. */
internal object ForeverFunctionalAutologinNoopTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

/**
 * Credential configuration only. This dialog deliberately has no runtime or input adapter hooks.
 * Text values are transient Compose input state; they are cleared on save, dismissal, and dispose.
 * Android/Compose may necessarily create transient String objects while rendering text fields.
 */
@Composable
fun ForeverFunctionalAutologinSettingsDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    runtimeReady: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val applicationContext = remember(context) { context.applicationContext }
    val service = remember(applicationContext) {
        foreverFunctionalAutologinSettingsService(applicationContext)
    }
    val scope = rememberCoroutineScope()
    val alive = remember { AtomicBoolean(true) }
    var enabled by remember { mutableStateOf(false) }
    var desiredEnabled by remember { mutableStateOf(false) }
    var pendingVerification by remember { mutableStateOf(false) }
    var rememberCredentials by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<ForeverFunctionalAutologinSettingsSummary?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun clearInput() {
        username = ""
        password = ""
    }

    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            service.invalidateOwner()
            clearInput()
        }
    }

    LaunchedEffect(service) {
        try {
            val (loaded, savedUser) = withContext(Dispatchers.IO) { service.load() to service.savedUsername() }
            if (alive.get() && loaded.publishable) {
                loading = false
                enabled = loaded.effectiveEnabled
                desiredEnabled = loaded.desiredEnabled
                pendingVerification = loaded.pendingVerification
                summary = loaded.summary
                username = savedUser.orEmpty()
                // This is only the encrypted-vault presence bit; it does not acquire or decrypt data.
                rememberCredentials = loaded.summary.remembered
                if (loaded.stateChanged) onChanged()
            }
        } catch (_: CancellationException) {
            // Disposal has no successful load to publish.
        } catch (_: Throwable) {
            val cleanup = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { service.cleanup() }.getOrNull()
            }
            if (alive.get()) {
                clearInput()
                loading = false
                enabled = false
                desiredEnabled = false
                pendingVerification = false
                summary = null
                errorCode = if (cleanup?.success == true) {
                    "AUTLOGIN_LOAD_FAILED"
                } else {
                    "AUTLOGIN_LOAD_CLEANUP_FAILED"
                }
                onChanged()
            }
        }
    }

    fun publishFailure(code: String) {
        enabled = false
        desiredEnabled = false
        errorCode = code
        clearInput()
        onChanged()
    }

    fun runSave() {
        if (busy || loading) return
        busy = true
        errorCode = null
        val requestedEnabled = runtimeReady && desiredEnabled
        val inputUsername = username
        val inputPassword = password
        val requestedRemember = rememberCredentials
        clearInput()
        scope.launch {
            var inputUserChars: CharArray? = null
            var inputPasswordChars: CharArray? = null
            try {
                // Allocation is inside the coroutine's outer finally: a canceled job before
                // execution can never strand an owned array.
                inputUserChars = inputUsername.toCharArray()
                inputPasswordChars = inputPassword.toCharArray()
                val operationJob = currentCoroutineContext()[Job]
                val operation = withContext(NonCancellable + Dispatchers.IO) {
                    service.save(
                        requestedEnabled = requestedEnabled,
                        username = requireNotNull(inputUserChars),
                        password = requireNotNull(inputPasswordChars),
                        remember = requestedRemember,
                        checkActive = {
                            if (operationJob != null) operationJob.ensureActive()
                        },
                    )
                }
                if (!alive.get() || !service.isOwnerCurrent() || operationJob?.isActive == false) {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { service.cleanup() } }
                } else if (operation.success) {
                    withContext(Dispatchers.Main) {
                         summary = operation.summary
                         username = inputUsername
                        enabled = requestedEnabled
                        desiredEnabled = requestedEnabled
                        pendingVerification = false
                        busy = false
                        onChanged()
                    }
                } else if (!operation.cancelled) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        publishFailure(operation.errorCode ?: "AUTLOGIN_SAVE_FAILED")
                    }
                }
            } catch (_: CancellationException) {
                // Also compensate if cancellation arrived at the NonCancellable boundary after
                // the service's final active check but before its result reached this coroutine.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { service.cleanup() }
                }
            } catch (_: Throwable) {
                val cleanup = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { service.cleanup() }.getOrNull()
                }
                if (alive.get()) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        publishFailure(
                            if (cleanup?.success == true) "AUTLOGIN_SAVE_FAILED" else "AUTLOGIN_SAVE_COMPENSATION_FAILED",
                        )
                    }
                }
            } finally {
                inputUserChars?.fill('\u0000')
                inputPasswordChars?.fill('\u0000')
            }
        }
    }

    fun runCleanup() {
        if (busy || loading) return
        busy = true
        errorCode = null
        scope.launch {
            try {
                val operation = withContext(NonCancellable + Dispatchers.IO) { service.cleanup() }
                if (alive.get() && currentCoroutineContext()[Job]?.isActive != false) {
                    withContext(Dispatchers.Main) {
                        summary = operation.summary
                        enabled = false
                        desiredEnabled = false
                        pendingVerification = false
                        rememberCredentials = false
                        clearInput()
                        busy = false
                        if (operation.success) onChanged()
                        else publishFailure(operation.errorCode ?: "AUTLOGIN_CLEANUP_FAILED")
                    }
                }
            } catch (_: CancellationException) {
                // Non-cancellable cleanup normally prevents this path.
            } catch (_: Throwable) {
                if (alive.get()) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        publishFailure("AUTLOGIN_CLEANUP_FAILED")
                    }
                }
            }
        }
    }

    fun dismiss() {
        if (busy || loading) return
        clearInput()
        onDismiss()
    }

    val currentSummary = summary
    val genericStatus = when {
        currentSummary?.remembered == true -> "Credenciales guardadas · cifradas"
        currentSummary?.status == ForeverFunctionalAutologinStatus.PERSISTED -> "Credenciales guardadas · cifradas"
        currentSummary?.status == ForeverFunctionalAutologinStatus.MEMORY_ONLY -> "Credenciales en memoria hasta cerrar"
        currentSummary?.status == ForeverFunctionalAutologinStatus.REENTRY_REQUIRED -> "Vuelve a introducir las credenciales"
        else -> "Sin credenciales guardadas"
    }
    val configurationStatus = when {
        desiredEnabled && (!runtimeReady || pendingVerification) -> "Configurado; inicio automático no disponible"
        enabled -> "Inicio automático activado"
        else -> "Inicio automático desactivado"
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .imePadding(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Cuenta", style = MaterialTheme.typography.titleLarge)
                if (runtimeReady) {
                    Text(
                        "Guardar no inicia sesión.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (runtimeReady) {
                    Text(
                        configurationStatus,
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    genericStatus,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompositionLocalProvider(
                    LocalTextToolbar provides ForeverFunctionalAutologinNoopTextToolbar,
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        enabled = !busy && !loading,
                        singleLine = true,
                        label = { Text("Usuario") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrect = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        enabled = !busy && !loading,
                        singleLine = true,
                        label = { Text(if (summary?.sessionAvailable == true || summary?.remembered == true) "Contraseña guardada · escribe para cambiarla" else "Contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrect = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberCredentials,
                        enabled = !busy && !loading,
                        onCheckedChange = { rememberCredentials = it },
                    )
                    Text(
                        "Recordar en este dispositivo",
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Sin marcar, la cuenta se conserva solo mientras la app está abierta.",
                    modifier = Modifier.padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (runtimeReady) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = desiredEnabled,
                            enabled = !busy && !loading,
                            onCheckedChange = { desiredEnabled = it },
                        )
                        Text(
                            "Permitir inicio automático",
                            modifier = Modifier.padding(start = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                errorCode?.let {
                    Text(
                        "No se pudo completar la acción. Inténtalo de nuevo.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = !busy && !loading,
                        onClick = ::dismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Volver") }
                    TextButton(
                        enabled = !busy && !loading,
                        onClick = ::runCleanup,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Olvidar credenciales") }
                    Button(
                        enabled = !busy && !loading,
                        onClick = ::runSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Guardar cuenta")
                    }
                }
            }
        }
    }
}
