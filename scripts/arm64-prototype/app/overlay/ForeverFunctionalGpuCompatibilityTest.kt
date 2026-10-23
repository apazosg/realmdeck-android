package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalGpuCompatibilityTest {
    @Test
    fun realSnapshotWithPlannedProfileMapsKnownHistoricalRowWithoutEffectiveBackendProof() {
        val snapshot = snapshot()
        val result = ForeverFunctionalGpuCompatibility.classify(
            snapshot.matrixInput(ForeverFunctionalGpuCompatibility.fixedSelectedConfiguration()),
        )
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.KNOWN_HISTORICAL_RENDER, result.classification)
        assertFalse(snapshot.liveBackendVerified)
        assertTrue(result.reason.contains("gameplay not verified"))
    }

    @Test
    fun differentDeviceIsUnknown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(deviceCode = "other-device"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
    }

    @Test
    fun differentGpuFamilyIsUnknown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(hostGlRenderer = "Mali-G615 MC6"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
    }

    @Test
    fun differentApiIsUnknown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(apiLevel = 35))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
    }

    @Test
    fun kernel2099IsNotAcceptedAsKernel209() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(kernelRelease = "5.10.2099-android"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
    }

    @Test
    fun missingRendererIsUnknown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(hostGlRenderer = null))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
        assertTrue(result.reason.contains("renderer"))
    }

    @Test
    fun failedReadIsUnknownEvenWhenOtherFieldsLookKnown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(readError = "egl failure"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
        assertTrue(result.reason.contains("failed"))
    }

    @Test
    fun differentRuntimeProfileIsUnknown() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(runtimeProfile = "other-profile"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.UNKNOWN, result.classification)
        assertTrue(result.reason.contains("runtime profile"))
    }

    @Test
    fun selectedBackendIsConfigurationNotEffectiveBackendProof() {
        val result = ForeverFunctionalGpuCompatibility.classify(input(selectedBackend = "PanVK + DXVK"))
        assertEquals(ForeverFunctionalGpuCompatibility.Classification.KNOWN_HISTORICAL_RENDER, result.classification)
    }

    @Test
    fun timeoutKeepsProcessLeaseUntilTheInjectedWorkerReturns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val released = CountDownLatch(1)
        val retryReleased = CountDownLatch(1)
        val completions = AtomicInteger(0)
        val invocations = AtomicInteger(0)
        val firstResults = mutableListOf<ForeverFunctionalGpuReadResult>()
        val first = ForeverFunctionalGpuReader(context, timeoutMs = 1L, workerFunction = {
            started.countDown()
            try {
                if (invocations.getAndIncrement() == 0) release.await(2, TimeUnit.SECONDS)
                snapshot()
            } finally {
                finished.countDown()
            }
        }, workerReleasedObserver = {
            if (completions.incrementAndGet() == 1) released.countDown() else retryReleased.countDown()
        })
        val secondResults = mutableListOf<ForeverFunctionalGpuReadResult>()
        val second = ForeverFunctionalGpuReader(context, timeoutMs = 1L, workerFunction = { snapshot() })
        try {
            assertTrue(first.request { firstResults += it })
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(10L)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertTrue(firstResults.single().timedOut)

            assertFalse(second.request { secondResults += it })
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertTrue(secondResults.single().snapshot.error?.contains("busy") == true)
        } finally {
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(released.await(1, TimeUnit.SECONDS))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertTrue(first.request { firstResults += it })
            assertTrue(retryReleased.await(1, TimeUnit.SECONDS))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertFalse(firstResults.last().timedOut)
            first.close()
            second.close()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
    }

    @Test
    fun normalCompletionCancelsTimeoutAndDeliversExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val released = CountDownLatch(1)
        val results = mutableListOf<ForeverFunctionalGpuReadResult>()
        val reader = ForeverFunctionalGpuReader(
            context,
            timeoutMs = 5_000L,
            workerFunction = { snapshot() },
            workerReleasedObserver = { released.countDown() },
        )
        try {
            assertTrue(reader.request { results += it })
            assertTrue(released.await(1, TimeUnit.SECONDS))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(1, results.size)
            assertFalse(results.single().timedOut)
        } finally {
            reader.close()
        }
    }

    @Test
    fun timeoutAndLateCompletionDeliverExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val release = CountDownLatch(1)
        val released = CountDownLatch(1)
        val results = mutableListOf<ForeverFunctionalGpuReadResult>()
        val reader = ForeverFunctionalGpuReader(
            context,
            timeoutMs = 1L,
            workerFunction = {
                release.await(2, TimeUnit.SECONDS)
                snapshot()
            },
            workerReleasedObserver = { released.countDown() },
        )
        try {
            assertTrue(reader.request { results += it })
            Thread.sleep(10L)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(1, results.size)
            assertTrue(results.single().timedOut)
            release.countDown()
            assertTrue(released.await(1, TimeUnit.SECONDS))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(1, results.size)
        } finally {
            release.countDown()
            reader.close()
        }
    }

    @Test
    fun closeSuppressesLateInjectedWorkerCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val released = CountDownLatch(1)
        val results = mutableListOf<ForeverFunctionalGpuReadResult>()
        val reader = ForeverFunctionalGpuReader(context, timeoutMs = 5_000L, workerFunction = {
            started.countDown()
            try {
                release.await(2, TimeUnit.SECONDS)
                snapshot()
            } finally {
                finished.countDown()
            }
        }, workerReleasedObserver = { released.countDown() })
        try {
            assertTrue(reader.request { results += it })
            assertTrue(started.await(1, TimeUnit.SECONDS))
            reader.close()
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(released.await(1, TimeUnit.SECONDS))
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertTrue(results.isEmpty())
        } finally {
            reader.close()
        }
    }

    private fun snapshot(
        deviceCode: String? = "xaga",
        hostGlRenderer: String? = "Mali-G610 MC6",
        apiLevel: Int? = 34,
        kernelRelease: String? = "5.10.209-android",
    ) = ForeverFunctionalGpuIdentitySnapshot(
        capturedAtEpochMs = 1L,
        source = "test",
        deviceCode = deviceCode,
        model = "test-model",
        apiLevel = apiLevel,
        kernelRelease = kernelRelease,
        hostGlVendor = "ARM",
        hostGlRenderer = hostGlRenderer,
        hostGlVersion = "test-gl",
        vulkanHardwareLevelDeclared = true,
        vulkanHardwareVersionFeatureDeclared = true,
        vulkanHardwareVersionReported = null,
    )

    private fun input(
        hostGlRenderer: String? = "Mali-G610 MC6",
        selectedBackend: String? = ForeverFunctionalGpuCompatibility.PLANNED_BACKEND,
        runtimeProfile: String? = ForeverFunctionalGpuCompatibility.MATRIX_PROFILE,
        deviceCode: String? = "xaga",
        apiLevel: Int? = 34,
        kernelRelease: String? = "5.10.209-android",
        readError: String? = null,
    ) = ForeverFunctionalGpuCompatibility.MatrixInput(
        hostGlRenderer = hostGlRenderer,
        deviceCode = deviceCode,
        apiLevel = apiLevel,
        kernelRelease = kernelRelease,
        selectedBackend = selectedBackend,
        clientBuild = ForeverFunctionalGpuCompatibility.CLIENT_BUILD,
        runtimeProfile = runtimeProfile,
        readError = readError,
    )
}
