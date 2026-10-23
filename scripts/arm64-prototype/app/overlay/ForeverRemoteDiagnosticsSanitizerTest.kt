package app.gamenative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeverRemoteDiagnosticsSanitizerTest {
    @Test
    fun seededAutologinSecretsAreRemovedWithoutLogging() {
        val user = "DiagUser9".toCharArray()
        val pass = "s3cr3t-Pass!".toCharArray()
        val text = "login user=DiagUser9 password=s3cr3t-Pass! done"
        val result = ForeverRemoteDiagnosticsSanitizer.sanitizeText(text, listOf(user, pass))
        assertFalse(result.text.contains("DiagUser9"))
        assertFalse(result.text.contains("s3cr3t-Pass!"))
        assertTrue(result.redactions >= 2)
        // Los secretos siguen utilizables por quien los posee; no se copiaron a String registrado.
        assertEquals("DiagUser9", String(user))
        assertEquals("s3cr3t-Pass!", String(pass))
        user.fill('\u0000')
        pass.fill('\u0000')
    }

    @Test
    fun knownPatternsAreRedacted() {
        val text = "password=hunter2 token: abc123 user bot@example.com ip 192.168.7.21 " +
            "serial=SN9988 path /data/data/com.example/secret key=bearer XYZ.123"
        val result = ForeverRemoteDiagnosticsSanitizer.sanitizeText(text)
        assertFalse(result.text.contains("hunter2"))
        assertFalse(result.text.contains("abc123"))
        assertFalse(result.text.contains("bot@example.com"))
        assertFalse(result.text.contains("192.168.7.21"))
        assertFalse(result.text.contains("SN9988"))
        assertFalse(result.text.contains("/data/data/com.example/secret"))
        assertTrue(result.text.contains("[redacted]"))
    }

    @Test
    fun privatePathsAndTerminalEscapesAreRemoved() {
        val text = "\u001B[31mred\u001B[0m /home/player/notes C:\\Users\\player\\doc content://media/x"
        val result = ForeverRemoteDiagnosticsSanitizer.sanitizeText(text)
        assertFalse(result.text.contains("\u001B"))
        assertFalse(result.text.contains("/home/player"))
        assertFalse(result.text.contains("C:\\Users"))
        assertFalse(result.text.contains("content://"))
    }

    @Test
    fun commentIsCappedAt4000Chars() {
        val long = "x".repeat(5000)
        val result = ForeverRemoteDiagnosticsSanitizer.sanitizeComment(long)
        assertTrue(result.truncated)
        assertTrue(result.text.length <= 4000 + 64)
    }

    @Test
    fun onlyAllowlistedActionsPass() {
        assertEquals("launch", ForeverRemoteDiagnosticsSanitizer.sanitizeAction("launch"))
        assertEquals("precondition-failed", ForeverRemoteDiagnosticsSanitizer.sanitizeAction("precondition-failed"))
        assertEquals("wine-exit", ForeverRemoteDiagnosticsSanitizer.sanitizeAction("wine-exit"))
        assertNull(ForeverRemoteDiagnosticsSanitizer.sanitizeAction("launch_intended"))
        assertNull(ForeverRemoteDiagnosticsSanitizer.sanitizeAction("key_press_a"))
        assertNull(ForeverRemoteDiagnosticsSanitizer.sanitizeAction("password_field_changed"))
        assertNull(ForeverRemoteDiagnosticsSanitizer.sanitizeAction("touch_at_120_340"))
    }
}
