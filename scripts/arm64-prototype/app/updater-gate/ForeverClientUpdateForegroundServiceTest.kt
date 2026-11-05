package app.gamenative

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
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
        ForeverClientUpdateExecution.pendingPortabilityAuthorization = null
        ForeverClientUpdateExecution.resumePlan = null
        ForeverClientUpdateExecution.rate.reset()
        ForeverClientUpdateExecution.outcome.value = null
        ForeverClientUpdateExecution.inspection.value = null
        ForeverClientUpdateExecution.applying.value = false
        ForeverClientUpdateExecution.detail.value = null
        ForeverClientUpdateExecution.progress.value = ""
    }

    @Test
    fun downloadNotificationShowsDeterminateProgress() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val detail = ForeverClientUpdateProgress(
                ForeverClientUpdateProgress.Phase.DOWNLOADING,
                completedObjects = 0,
                totalObjects = 0,
                transferredBytes = 512,
                totalTransferBytes = 1024,
            )
            val notification = controller.get().notification("Descargando 512 B de 1 KiB", detail)
            assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(50, notification.extras.getInt(Notification.EXTRA_PROGRESS))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        } finally {
            controller.destroy()
        }
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

    @Test
    fun burstProgressIsCoalescedButPhaseAndCompletionStayImmediate() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            val first = ForeverClientUpdateProgress(ForeverClientUpdateProgress.Phase.DOWNLOADING, 0, 10, 1, 10000)
            service.publish(first)
            repeat(100) { service.publish(first.copy(transferredBytes = it + 2L)) }
            assertEquals(first, ForeverClientUpdateExecution.detail.value)
            service.publish(first.copy(transferredBytes = 10000))
            assertEquals(10000L, ForeverClientUpdateExecution.detail.value?.transferredBytes)
            service.publish(first.copy(phase = ForeverClientUpdateProgress.Phase.APPLYING))
            assertTrue(ForeverClientUpdateExecution.applying.value)
            assertEquals(ForeverClientUpdateProgress.Phase.APPLYING, ForeverClientUpdateExecution.detail.value?.phase)
        } finally { controller.destroy() }
    }

    private fun pendingPlanFixture(planId: String = "pending-plan"): ForeverClientUpdatePlan =
        ForeverClientUpdatePlan(
            planId = planId,
            version = "1.60.1.70425",
            downloadBytes = 10,
            privateWorkBytes = 10,
            destinationBytes = 10,
            peakAdditionalBytes = 20,
            privateWorkAvailableBytes = 100,
            destinationAvailableBytes = 100,
            privateAndDestinationShareFileStore = false,
            hasEnoughSpace = true,
            totalObjects = 1,
            reusableObjects = 0,
            missingObjects = 1,
            looseChanges = emptyList(),
            transactionFileCount = 1,
            updaterToken = "token",
            storageId = "storage",
            destinationPath = "destination",
            sourceRevision = "revision",
            snapshotHash = "snapshot",
            cascNewSegmentBytes = 0,
            cascIndexBytes = 0,
            cascNewSegmentCount = 0,
            looseChangePaths = emptySet(),
        )

    private fun portabilityAuthorizationFixture(): ForeverClientUpdateService.PortabilityContinuationAuthorization =
        ForeverClientUpdateService.PortabilityContinuationAuthorization(
            binding = ForeverClientUpdater.PortabilityIntentBinding(
                operationId = "operation",
                generation = "generation",
                stageId = "stage",
                catalogDigest = "catalog",
                planDigest = "plan",
                inventoryDigest = "inventory",
                checkpointDigest = "checkpoint",
                checkpointIdentityDigest = "checkpoint-identity",
                oldMarkerDigest = "old-marker",
                newMarkerDigest = "new-marker",
                oldWorkspaceDigest = null,
                oldCatalogReceiptDigest = null,
                newCatalogReceiptDigest = "new-catalog-receipt",
            ),
            storageId = "storage",
            destinationPath = "destination",
            catalogDigest = "catalog",
            snapshotHash = "snapshot",
            downloadCeiling = 10,
            peakCeiling = 20,
            privateCeiling = 10,
            destinationCeiling = 10,
            totalObjects = 1,
            looseChangePaths = emptySet(),
        )

    @Test
    fun resetForPublishesPendingPlanBeforeServiceStart() {
        val plan = pendingPlanFixture()
        ForeverClientUpdateExecution.resetFor(plan)
        // The service has not marked itself busy yet, but the handed-off plan
        // is already pending: the dialog must treat this as executing work.
        assertSame(plan, ForeverClientUpdateExecution.pendingPlan)
        assertSame(plan, ForeverClientUpdateExecution.resumePlan)
        assertFalse(ForeverClientUpdateExecution.busy.value)
        assertFalse(ForeverClientUpdateExecution.cancel.get())
        assertEquals("Iniciando descarga…", ForeverClientUpdateExecution.progress.value)
    }

    @Test
    fun secondResetForWhilePendingIsRejected() {
        val first = pendingPlanFixture("first")
        ForeverClientUpdateExecution.resetFor(first)
        assertThrows(IllegalStateException::class.java) {
            ForeverClientUpdateExecution.resetFor(pendingPlanFixture("second"))
        }
        assertSame(first, ForeverClientUpdateExecution.pendingPlan)
    }

    @Test
    fun serviceStartConsumesPendingPlan() {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val service = controller.get()
            val plan = pendingPlanFixture()
            val authorization = portabilityAuthorizationFixture()
            ForeverClientUpdateExecution.resetForWithPortabilityAuthorization(plan, authorization)
            assertSame(plan, ForeverClientUpdateExecution.pendingPlan)
            assertSame(authorization, ForeverClientUpdateExecution.pendingPortabilityAuthorization)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 7))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(ForeverClientUpdateExecution.busy.value)
            assertEquals(null, ForeverClientUpdateExecution.pendingPlan)
            assertEquals(null, ForeverClientUpdateExecution.pendingPortabilityAuthorization)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun ordinaryRunResetDoesNotCarryPortabilityAuthorizationForward() {
        ForeverClientUpdateExecution.pendingPortabilityAuthorization = portabilityAuthorizationFixture()

        ForeverClientUpdateExecution.resetFor(pendingPlanFixture())

        assertEquals(null, ForeverClientUpdateExecution.pendingPortabilityAuthorization)
    }

    @Test
    fun privateExecutionDispatchPassesPlanConsentAndSamePortabilityAuthorization() {
        val plan = pendingPlanFixture()
        val consent = consentFor(plan)
        val authorization = portabilityAuthorizationFixture()
        val updateService = mock<ForeverClientUpdateService>()

        invokePrivateExecutionDispatch(plan, consent, authorization, updateService)

        verify(updateService).executeWithPortabilityAuthorization(
            eq(plan), eq(consent), same(authorization), any(), any(),
        )
        verify(updateService, never()).execute(any(), any(), any(), any())
    }

    @Test
    fun privateExecutionDispatchUsesOrdinaryMethodWithoutPortabilityAuthorization() {
        val plan = pendingPlanFixture()
        val consent = consentFor(plan)
        val updateService = mock<ForeverClientUpdateService>()

        invokePrivateExecutionDispatch(plan, consent, null, updateService)

        verify(updateService).execute(eq(plan), eq(consent), any(), any())
        verify(updateService, never()).executeWithPortabilityAuthorization(any(), any(), any(), any(), any())
    }

    private fun consentFor(plan: ForeverClientUpdatePlan) = ForeverClientUpdateConsent(
        plan.planId, plan.downloadBytes, plan.peakAdditionalBytes, plan.privateWorkBytes, plan.destinationBytes,
    )

    /**
     * Calls only the private synchronous dispatch branch. The real onStartCommand/run coroutine is not covered:
     * mockConstruction is thread-local, while run constructs the update service on Dispatchers.IO.
     */
    private fun invokePrivateExecutionDispatch(
        plan: ForeverClientUpdatePlan,
        consent: ForeverClientUpdateConsent,
        portabilityAuthorization: ForeverClientUpdateService.PortabilityContinuationAuthorization?,
        updateService: ForeverClientUpdateService,
    ) {
        val controller = Robolectric.buildService(ForeverClientUpdateForegroundService::class.java).create()
        try {
            val dispatch = controller.get().javaClass.getDeclaredMethod(
                "executeWithAutoRecovery",
                ForeverClientUpdateService::class.java,
                ForeverClientUpdatePlan::class.java,
                ForeverClientUpdateConsent::class.java,
                ForeverClientUpdateService.PortabilityContinuationAuthorization::class.java,
            ).apply { isAccessible = true }
            dispatch.invoke(controller.get(), updateService, plan, consent, portabilityAuthorization)
        } finally {
            controller.destroy()
        }
    }
}
