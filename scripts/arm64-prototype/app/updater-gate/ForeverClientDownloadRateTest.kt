package app.gamenative

import org.junit.Assert.*
import org.junit.Test

class ForeverClientDownloadRateTest {
    private fun progress(bytes: Long) = ForeverClientUpdateProgress(
        ForeverClientUpdateProgress.Phase.DOWNLOADING, 0, 10, bytes, 10_000_000,
    )

    @Test fun networkSamplesExcludeResumeBaselineAndExpireDuringAStall() {
        val rate = ForeverClientDownloadRate()
        rate.record(progress(5_000_000), 0)
        assertNull(rate.estimate(0))
        rate.record(progress(6_000_000), 1_000)
        val estimate = requireNotNull(rate.estimate(1_000))
        assertEquals(1_000_000.0, estimate.bytesPerSecond, 0.01)
        assertEquals(4L, estimate.remainingSeconds)
        assertTrue(estimate.text().contains("1.0 MB/s"))
        assertTrue(ForeverClientDownloadRate.Estimate(1_000_000.0, 7_199).text().contains("2 h 0 min"))
        assertNull(rate.estimate(11_000))
        rate.reset()
        assertNull(rate.estimate(11_001))
    }

    @Test fun shortFileGapsContributeToTheTenSecondMeanWithoutHidingIt() {
        val rate = ForeverClientDownloadRate()
        rate.record(progress(0), 0)
        rate.record(progress(1_000_000), 1_000)
        assertEquals(250_000.0, requireNotNull(rate.estimate(4_000)).bytesPerSecond, 0.01)
        rate.record(progress(2_000_000), 5_000)
        assertEquals(400_000.0, requireNotNull(rate.estimate(5_000)).bytesPerSecond, 0.01)
        // The oldest sample is trimmed even with no incoming progress callbacks.
        assertEquals(100_000.0, requireNotNull(rate.estimate(11_000)).bytesPerSecond, 0.01)
        assertNull(rate.estimate(15_000))
    }

    @Test fun verificationIsNotNetworkAndCountersCannotRunBackwards() {
        val rate = ForeverClientDownloadRate()
        rate.record(progress(0), 0)
        rate.record(progress(1_000_000).copy(phase = ForeverClientUpdateProgress.Phase.VERIFYING), 1_000)
        assertNull(rate.estimate(1_000))
        rate.record(progress(2_000_000), 2_000)
        assertEquals(1_000_000.0, requireNotNull(rate.estimate(2_000)).bytesPerSecond, 0.01)
        rate.record(progress(1_000_000), 3_000)
        assertNull(rate.estimate(3_000))
        rate.record(progress(2_000_000), 4_000)
        assertNotNull(rate.estimate(4_000))
        rate.record(progress(3_000_000).copy(totalTransferBytes = 20_000_000), 5_000)
        assertNull(rate.estimate(5_000))
    }

    @Test fun preparationCountersRetainUnitsAndUnknownTotals() {
        assertEquals("Consultando índices: 3 de 8 índices", ForeverFunctionalUiText.preparationStatus("cdn-indexes", 3, 8))
        assertEquals("Preparando catálogo: 25 objetos", ForeverFunctionalUiText.preparationStatus("catalog-objects", 25, 0))
        assertEquals("Relacionando archivos: 4096 registros", ForeverFunctionalUiText.preparationStatus("parse-encoding-content", 4096, 0))
        assertEquals("Descargando metadatos: 2.0 KiB", ForeverFunctionalUiText.preparationStatus("raw-encoding", 2048, 0))
        assertEquals("Verificando metadatos: 1.0 KiB de 2.0 KiB", ForeverFunctionalUiText.preparationStatus("decoded-root", 1024, 2048))
    }
}
