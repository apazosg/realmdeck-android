package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacidad contra la clase REAL de credenciales: useValues anula sus
 * temporales al retornar, así que el pegamento DEBE copiar dentro y anular
 * fuera. Sin password=: el literal del secreto también debe redactarse.
 */
class ForeverRemoteDiagnosticsCredentialPrivacyTest {
    private fun credentials() = ForeverFunctionalAutologinCredentials(
        "operator-alice".toCharArray(),
        "hunter2-secret".toCharArray(),
    )

    @Test
    fun useValuesWipesTemporariesAfterReturn() {
        val creds = credentials()
        // Patrón erróneo: retener los temporales fuera del callback.
        var leakedUser: CharArray? = null
        var leakedPass: CharArray? = null
        creds.useValues { user, pass ->
            leakedUser = user
            leakedPass = pass
        }
        // Al retornar, la clase real los anula: quien llama DEBE copiar dentro.
        assertTrue(requireNotNull(leakedUser).all { it == '\u0000' })
        assertTrue(requireNotNull(leakedPass).all { it == '\u0000' })
        // El original sigue intacto para el siguiente uso.
        creds.useValues { user, pass ->
            assertEquals("operator-alice", String(user))
            assertEquals("hunter2-secret", String(pass))
        }
        creds.close()
    }

    @Test
    fun ownedCopiesSurviveAndRedactLoginLiteralWithoutPasswordMarker() {
        val creds = credentials()
        val copies = ForeverRemoteDiagnosticsSanitizer.ownedSecretCopies(creds)
        try {
            // Copias propias utilizables fuera del callback.
            assertEquals("operator-alice", String(copies[0]))
            val text = "autologin operator-alice ok"
            val redacted = ForeverRemoteDiagnosticsSanitizer.sanitizeText(text, copies).text
            assertFalse(redacted.contains("operator-alice"))
            assertTrue(redacted.contains("[redacted]"))
            // Patrón clásico con marcador también se redacta.
            val withMarker = ForeverRemoteDiagnosticsSanitizer.sanitizeText(
                "login password=hunter2-secret done", copies).text
            assertFalse(withMarker.contains("hunter2-secret"))
        } finally {
            copies.forEach { it.fill('\u0000') }
            creds.close()
        }
    }

    @Test
    fun availabilityGatingBlocksBrokenStore() {
        ForeverRemoteDiagnosticsSanitizer.requireSecretsAvailable(
            ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.AVAILABLE)
        ForeverRemoteDiagnosticsSanitizer.requireSecretsAvailable(
            ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.NONE)
        try {
            ForeverRemoteDiagnosticsSanitizer.requireSecretsAvailable(
                ForeverRemoteDiagnosticsSanitizer.SecretsAvailability.STORE_UNAVAILABLE)
            throw AssertionError("store unavailable must block")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.contains("credential store unavailable"))
        }
    }
}
