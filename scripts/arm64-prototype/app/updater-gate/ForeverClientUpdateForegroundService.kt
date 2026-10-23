package app.gamenative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-private bridge between the management dialog, the notification and the foreground service. */
object ForeverClientUpdateExecution {
    /** Set by the management dialog before the service starts; the plan object stays in-process. */
    @Volatile var pendingPlan: ForeverClientUpdatePlan? = null

    /** Latest shared inspection from the service, used by the dialog without a redundant re-detect. */
    val inspection = MutableStateFlow<ForeverClientUpdateService.Inspection?>(null)

    val busy = MutableStateFlow(false)
    val applying = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    /**
     * Structured per-invocation detail behind the human text: numbers for the
     * home bar, published together with [progress]. Volatile live state only;
     * after process death the download is gone and the journal owns the truth.
     */
    val detail = MutableStateFlow<ForeverClientUpdateProgress?>(null)
    val outcome = MutableStateFlow<Outcome?>(null)

    internal val cancel = AtomicBoolean(false)

    /** Terse terminal outcomes; the dialog owns the user-facing wording. */
    sealed interface Outcome {
        object Done : Outcome
        object Paused : Outcome
        data class Failed(val failure: Exception) : Outcome
    }

    /** Clear per-run volatile state before a fresh foreground run. */
    fun resetFor(plan: ForeverClientUpdatePlan) {
        check(!busy.value) { "client download is already running" }
        pendingPlan = plan
        cancel.set(false)
        outcome.value = null
        inspection.value = null
        applying.value = false
        detail.value = null
        progress.value = "Iniciando descarga…"
    }
}

/** Keeps the 85-GiB-class download alive behind a notification when the dialog or the app goes away. */
class ForeverClientUpdateForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationMillis = 0L
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        Log.i(TAG, "foreground start id=$startId busy=${ForeverClientUpdateExecution.busy.value} pause=${intent?.action == ACTION_PAUSE}")
        if (intent?.action == ACTION_PAUSE) {
            ForeverClientUpdateExecution.cancel.set(true)
            if (!ForeverClientUpdateExecution.busy.value) stopSelf(startId)
            return START_NOT_STICKY
        }
        // A repeated confirmation/start must not stop the service that already
        // owns the download. stopSelf() here used to orphan its work coroutine.
        if (ForeverClientUpdateExecution.busy.value) return START_NOT_STICKY
        val plan = ForeverClientUpdateExecution.pendingPlan
        if (plan == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        run(plan)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Request a cooperative pause if Android destroys an active service.
        // Do not interrupt a transaction mid-write or leave downloading work
        // running indefinitely without foreground-service protection.
        if (ForeverClientUpdateExecution.busy.value) {
            Log.w(TAG, "foreground service destroyed during client execution; requesting pause")
            ForeverClientUpdateExecution.cancel.set(true)
        } else {
            releaseLocks()
        }
        super.onDestroy()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun run(plan: ForeverClientUpdatePlan) {
        val execution = ForeverClientUpdateExecution
        execution.busy.value = true
        execution.pendingPlan = null
        Log.i(TAG, "foreground execution started")
        createChannel()
        acquireLocks()
        startForeground(
            NOTIFICATION_ID,
            notification(execution.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            val service = ForeverClientUpdateService(applicationContext)
            try {
                val consent = ForeverClientUpdateConsent(
                    plan.planId,
                    plan.downloadBytes,
                    plan.peakAdditionalBytes,
                    plan.privateWorkBytes,
                    plan.destinationBytes,
                )
                executeWithAutoRecovery(service, plan, consent)
                execution.outcome.value = if (execution.cancel.get()) {
                    ForeverClientUpdateExecution.Outcome.Paused
                } else {
                    ForeverClientUpdateExecution.Outcome.Done
                }
            } catch (cancelled: CancellationException) {
                Log.w(TAG, "client execution paused", cancelled)
                execution.outcome.value = ForeverClientUpdateExecution.Outcome.Paused
            } catch (failure: Exception) {
                Log.w(TAG, "client execution failed", failure)
                execution.outcome.value = ForeverClientUpdateExecution.Outcome.Failed(failure)
            } finally {
                try {
                    execution.inspection.value = service.inspect()
                } catch (inspectionFailure: Exception) {
                    Log.w(TAG, "post-execution inspection failed", inspectionFailure)
                }
                try {
                    service.close()
                } finally {
                    // onStartCommand also runs on Main: a successor cannot start
                    // between clearing busy and retiring this run's notification
                    // and locks. Include handled duplicate/pause start IDs, but
                    // do not stop an as-yet unhandled newer start.
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        releaseLocks()
                        execution.applying.value = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        execution.busy.value = false
                        Log.i(TAG, "foreground execution finished id=$latestStartId cancelled=${execution.cancel.get()}")
                        stopSelf(latestStartId)
                    }
                }
            }
        }
    }

    /** An interrupted prior download recovers its own journal here, then the same consent is reused. */
    private fun executeWithAutoRecovery(
        service: ForeverClientUpdateService,
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
    ) {
        val execution = ForeverClientUpdateExecution
        try {
            service.execute(plan, consent, execution.cancel::get, ::publish)
        } catch (blocked: IllegalStateException) {
            if (blocked.message?.contains("recuperación") != true) throw blocked
            if (execution.cancel.get()) throw CancellationException("encoded-object transfer cancelled")
            Log.w(TAG, "pending recovery detected; recovering before retry", blocked)
            execution.applying.value = true
            execution.progress.value = "Recuperando la descarga anterior…"
            publishNotification(execution.progress.value)
            try {
                service.recover(::publish)
                if (execution.cancel.get()) throw CancellationException("encoded-object transfer cancelled")
                service.execute(plan, consent, execution.cancel::get, ::publish)
            } finally {
                execution.applying.value = false
            }
        }
    }

    /** Mirrors the dialog wording so the notification and the dialog never disagree. */
    fun publish(update: ForeverClientUpdateProgress) {
        val execution = ForeverClientUpdateExecution
        val atomicPhase = ForeverFunctionalUiText.isAtomicPhase(update.phase)
        execution.applying.value = atomicPhase
        // Structured numbers are reset per invocation (see resetFor) and
        // published together with the human text every callback.
        execution.detail.value = update
        val text = ForeverFunctionalUiText.downloadStatusText(update, execution.progress.value)
        execution.progress.value = text
        publishNotification(text)
    }

    private fun publishNotification(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMillis <= NOTIFICATION_THROTTLE_MILLIS) return
        lastNotificationMillis = now
        runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
            .onFailure { Log.w(TAG, "progress notification failed", it) }
    }

    private fun notification(text: String): Notification {
        val pause = PendingIntent.getService(
            this,
            0,
            Intent(this, ForeverClientUpdateForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launch = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando WoW")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Pausar", pause)
            .build()
    }

    private fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Descarga de WoW", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun acquireLocks() {
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG:wakelock",
            ).apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        } catch (failure: Exception) {
            Log.w(TAG, "wake lock unavailable", failure)
        }
        try {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$TAG:wifilock",
            ).apply { setReferenceCounted(false); acquire() }
        } catch (failure: Exception) {
            Log.w(TAG, "wifi lock unavailable", failure)
        }
    }

    internal companion object {
        const val TAG = "ForeverClientUpdate"
        const val CHANNEL_ID = "forever-client-update"
        const val NOTIFICATION_ID = 0x70450
        const val ACTION_PAUSE = "app.gamenative.FOREVER_CLIENT_UPDATE_PAUSE"
        const val NOTIFICATION_THROTTLE_MILLIS = 800L
        const val WAKE_LOCK_TIMEOUT_MILLIS = 24L * 60L * 60L * 1000L
    }
}
