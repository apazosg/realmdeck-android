package app.gamenative

import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ForeverClientUpdateForegroundServiceTest {
    @After
    fun resetExecution() {
        ForeverClientUpdateExecution.busy.value = false
        ForeverClientUpdateExecution.cancel.set(false)
        ForeverClientUpdateExecution.pendingPlan = null
        ForeverClientUpdateExecution.outcome.value = null
        ForeverClientUpdateExecution.inspection.value = null
        ForeverClientUpdateExecution.applying.value = false
        ForeverClientUpdateExecution.detail.value = null
        ForeverClientUpdateExecution.progress.value = ""
    }

    @Test
    fun repeatedStartDoesNotStopActiveDownloadService() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            ForeverClientUpdateExecution.busy.value = true
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 2))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertFalse(ForeverClientUpdateExecution.cancel.get())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun destroyedActiveServiceRequestsCooperativePause() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        ForeverClientUpdateExecution.busy.value = true
        controller.destroy()
        assertTrue(ForeverClientUpdateExecution.cancel.get())
    }

    @Test
    fun startWithoutPlanStopsIdleService() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun pauseWithoutWorkDoesNotLeaveIdleServiceRunning() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent().setAction(ForeverClientUpdateForegroundService.ACTION_PAUSE), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(ForeverClientUpdateExecution.cancel.get())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun destroyingIdleServiceDoesNotSetCancellation() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        controller.destroy()
        assertFalse(ForeverClientUpdateExecution.cancel.get())
    }

    @Test
    fun publishResetsDetailPerInvocationAndSharesText() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(null, ForeverClientUpdateExecution.detail.value)
            service.publish(
                ForeverClientUpdateProgress(
                    ForeverClientUpdateProgress.Phase.DOWNLOADING, 3, 10, 2048, 10240,
                ),
            )
            val detail = ForeverClientUpdateExecution.detail.value
            assertEquals(2048L, detail?.transferredBytes)
            assertEquals("Descargando 2.0 KiB de 10.0 KiB", ForeverClientUpdateExecution.progress.value)
            assertFalse(ForeverClientUpdateExecution.applying.value)
            service.publish(
                ForeverClientUpdateProgress(
                    ForeverClientUpdateProgress.Phase.APPLYING, 10, 10, 10240, 10240,
                ),
            )
            assertTrue(ForeverClientUpdateExecution.applying.value)
            assertEquals("Aplicando los archivos…", ForeverClientUpdateExecution.progress.value)
        } finally {
            controller.destroy()
        }
    }
}
