package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForeverRemoteDiagnosticsFrozenConfigTest {
    private fun configurationReady(wine: String, dxvk: String) =
        """{"event":"configuration_ready","appId":"CUSTOM_GAME_1","wineEntry":"$wine","dxvkEntry":"$dxvk","graphicsSelected":"wrapper / android-system-vulkan"}"""

    private fun prepared(profile: String) =
        """{"event":"prepared","route":"functional-wow-aa64","profileId":"$profile","portalHost":"logon.classic-forever.gg","wine":"proton-11","dxvk":"dxvk-2.4.1"}"""

    @Test
    fun frozenJournalWinsForObservedComponents() {
        val requested = ForeverRemoteDiagnosticsFrozenConfig.extractRequested(
            configurationReady("proton-12", "dxvk-2.5") + "\n",
            prepared("classic_forever") + "\n",
        )
        assertEquals("proton-12", requested["wine"])
        assertEquals("dxvk-2.5", requested["dxvk"])
        assertEquals("classic_forever", requested["profileId"])
        assertEquals("wrapper / android-system-vulkan", requested["graphics"])
        assertNull(requested["panvk"])
        assertNull(requested["profileRevision"])
    }

    @Test
    fun livePrefsChangeDoesNotRewriteFrozenRun() {
        val before = ForeverRemoteDiagnosticsFrozenConfig.extractRequested(
            configurationReady("proton-11", "dxvk-2.4.1") + "\n",
            prepared("classic_forever") + "\n",
        )
        // Las prefs cambian después; lo congelado del journal no cambia.
        val after = ForeverRemoteDiagnosticsFrozenConfig.extractRequested(
            configurationReady("proton-11", "dxvk-2.4.1") + "\n",
            prepared("other_profile") + "\n",
        )
        assertEquals(before["wine"], "proton-11")
        assertEquals(after["profileId"], "other_profile")
    }

    @Test
    fun combineKeepsIntentChoiceWithJournalEvidence() {
        val combined = ForeverRemoteDiagnosticsFrozenConfig.combine(
            mapOf("profileId" to "classic_forever", "resolution" to "1280x720"),
            mapOf("profileId" to null, "resolution" to null, "wine" to "proton-11",
                "dxvk" to "dxvk-2.4.1", "gpuBackend" to null, "profileRevision" to null,
                "panvk" to null, "graphics" to "wrapper"),
        )
        // Elección del usuario (intento) gana; evidencia del journal para el resto.
        assertEquals("classic_forever", combined["profileId"])
        assertEquals("1280x720", combined["resolution"])
        assertEquals("proton-11", combined["wine"])
        assertEquals("wrapper", combined["graphics"])
    }

    @Test
    fun emptyEvidenceStaysNullNeverInvented() {
        val requested = ForeverRemoteDiagnosticsFrozenConfig.extractRequested("", "")
        assertTrue(requested.values.all { it == null })
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
