package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForeverClientFinalizationProgressTest {
    private fun applying(filesCompleted: Long?, filesTotal: Long?): ForeverClientUpdateProgress {
        val detail = if (filesCompleted == null || filesTotal == null) {
            null
        } else {
            ForeverClientUpdateProgress.FinalizationDetail(filesCompleted, filesTotal, filesCompleted * 100L, filesTotal * 100L)
        }
        return ForeverClientUpdateProgress(
            ForeverClientUpdateProgress.Phase.APPLYING,
            0,
            0,
            0,
            0,
            detail,
        )
    }

    @Test
    fun atomicPhaseWithValidFinalizationShowsFileCounts() {
        assertEquals(
            "Aplicando los archivos… 2 de 5",
            ForeverFunctionalUiText.downloadStatusText(applying(2, 5), "fallback"),
        )
    }

    @Test
    fun atomicPhaseWithInvalidFinalizationKeepsFixedSentence() {
        assertEquals(
            "Aplicando los archivos…",
            ForeverFunctionalUiText.downloadStatusText(applying(6, 5), "fallback"),
        )
        assertEquals(
            "Aplicando los archivos…",
            ForeverFunctionalUiText.downloadStatusText(applying(0, 0), "fallback"),
        )
    }

    @Test
    fun atomicPhaseWithoutDetailKeepsFixedSentence() {
        assertEquals(
            "Aplicando los archivos…",
            ForeverFunctionalUiText.downloadStatusText(applying(null, null), "fallback"),
        )
    }

    @Test
    fun atomicPhaseNeverPublishesAFraction() {
        assertNull(ForeverFunctionalUiText.downloadFraction(applying(4, 5)))
        assertNull(ForeverFunctionalUiText.downloadPercent(applying(4, 5)))
    }
}
