package app.gamenative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForeverRemoteDiagnosticsWineLogTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun appendsAndReadsBoundedHead() {
        val dir = temporary.newFolder("wine")
        repeat(50) { index ->
            ForeverRemoteDiagnosticsWineLog.append(dir, "attempt-1", "wine line $index")
        }
        val read = ForeverRemoteDiagnosticsWineLog.read(dir, "attempt-1")
        assertTrue(read.present)
        assertFalse(read.truncated)
        assertTrue(read.text.contains("wine line 0"))
        assertTrue(read.text.contains("wine line 49"))
    }

    @Test
    fun rotationKeepsBoundedTotalWithFlag() {
        val dir = temporary.newFolder("wine2")
        val big = "x".repeat(4096)
        // ~128 líneas de 4KiB superan 512KiB y fuerzan rotación.
        repeat(140) { index ->
            ForeverRemoteDiagnosticsWineLog.append(dir, "attempt-9", "$index:$big")
        }
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(total <= 2 * ForeverRemoteDiagnosticsWineLog.MAX_BYTES + 8192)
        val read = ForeverRemoteDiagnosticsWineLog.read(dir, "attempt-9")
        assertTrue(read.present)
        assertTrue(read.truncated)
        assertTrue(read.text.contains("139:"))
        assertTrue(read.text.toByteArray(Charsets.UTF_8).size <= 640 * 1024)
    }

    @Test
    fun unsafeAttemptIdsAreRejected() {
        val dir = temporary.newFolder("wine3")
        ForeverRemoteDiagnosticsWineLog.append(dir, "../evil", "x")
        ForeverRemoteDiagnosticsWineLog.append(dir, "a/b", "x")
        assertEquals(0, dir.listFiles()?.size ?: 0)
        val read = ForeverRemoteDiagnosticsWineLog.read(dir, "../evil")
        assertFalse(read.present)
    }

    @Test
    fun linesAreSingleLineAndCapped() {
        val dir = temporary.newFolder("wine4")
        ForeverRemoteDiagnosticsWineLog.append(dir, "a1", "one\ntwo\n" + "z".repeat(9000))
        val read = ForeverRemoteDiagnosticsWineLog.read(dir, "a1")
        assertTrue(read.present)
        assertEquals(1, read.text.trim().lines().size)
        assertTrue(read.text.contains("one two"))
        assertTrue(read.text.toByteArray(Charsets.UTF_8).size <=
            ForeverRemoteDiagnosticsWineLog.MAX_LINE_CHARS + 8)
    }
}
