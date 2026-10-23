package app.gamenative

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ForeverSignalTraceTest {
    private val identity = SignalTraceIdentity("run-signal", "01234567-89ab-cdef-0123-456789abcdef", 6424, 99)

    @Test fun retentionAndProtocolSelfTestUsesRealFilesWithoutJni() {
        ForeverSignalTraceFileSelfTest.main(emptyArray())
    }

    @Test fun truncatedStaleAndClockFailureAreNeverAttributed() {
        val raw = ForeverSignalTraceFileSelfTest.trace(identity, 777, 0, 3)
        assertEquals(SignalTraceDecodeStatus.VALID, ForeverSignalTraceDecoder.decode(raw, identity, 10_000, 10_000).status)
        for (length in listOf(1, 255, 257, 319, 513)) {
            assertEquals(SignalTraceDecodeStatus.INVALID, ForeverSignalTraceDecoder.decode(raw.copyOf(length), identity, 10_000, 10_000).status)
        }
        for (other in listOf(identity.copy(pid = 1), identity.copy(startTicks = 100), identity.copy(runId = "old-run"),
            identity.copy(bootId = "00000000-0000-0000-0000-000000000000"))) {
            assertEquals(SignalTraceDecodeStatus.INVALID, ForeverSignalTraceDecoder.decode(raw, other, 10_000, 10_000).status)
        }
        val clockFailed = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(312, 7) }
        assertEquals(SignalTraceDecodeStatus.INVALID, ForeverSignalTraceDecoder.decode(clockFailed, identity, 10_000, 10_000).status)
        val kernel = ForeverSignalTraceFileSelfTest.trace(identity, 0, 128, 1)
        val decoded = ForeverSignalTraceDecoder.decode(kernel, identity, 10_000, 10_000)
        assertEquals(SignalTraceDecodeStatus.VALID, decoded.status)
        assertNull(decoded.attributedEvent)
    }

    @Test fun refusedNativeObserverDoesNotClaimCoverage() {
        val adapter = object : NativeSignalTraceAdapter {
            override fun armSignalTrace(path: String, runId: String, bootId: String, expectedPid: Int, expectedStartTicks: Long) =
                "{\"schema\":1,\"status\":\"UNSUPPORTED\",\"reason\":\"ANDROID_SIGNAL_OWNERSHIP_UNVERIFIED\"}"
            override fun signalTraceStatus() = "{\"status\":\"UNSUPPORTED\"}"
        }
        val result = ForeverSignalTraceArm.arm(adapter, "/unused", identity)
        assertEquals("UNSUPPORTED", result.status)
        assertFalse(result.coverage)
    }

    @Test fun atomicSnapshotRetainsEndAndRejectsSymlinkAndTamperedMetadata() {
        val root = Files.createTempDirectory("forever-snapshot-test").toFile()
        try {
            BoundedLogRetention(root, "run-tail", "stderr").use {
                it.append(ByteArray(900 * 1024) { 'x'.code.toByte() })
                it.append("LATE-SEH-MARKER".toByteArray())
                it.flush()
                val saved = BoundedLogRetention.readPersisted(root, "run-tail", "stderr")
                assertEquals(128 * 1024, saved.head.size)
                assertEquals(384 * 1024, saved.tail.size)
                assertTrue(String(saved.tail).endsWith("LATE-SEH-MARKER"))
                assertTrue(saved.metadata.truncated)
            }
            val file = java.io.File(root, "run-tail-stderr-snapshot.json")
            val json = org.json.JSONObject(file.readText()).put("tailStart", 0)
            file.writeText(json.toString())
            assertTrue(runCatching { BoundedLogRetention.readPersisted(root, "run-tail", "stderr") }.isFailure)
            val outside = Files.createTempFile("forever-snapshot-outside", ".json")
            try {
                file.delete(); Files.createSymbolicLink(file.toPath(), outside)
                assertTrue(runCatching { BoundedLogRetention(root, "run-tail", "stderr", recoverExisting = true) }.isFailure)
            } finally { Files.deleteIfExists(outside) }
        } finally { root.deleteRecursively() }
    }
}
