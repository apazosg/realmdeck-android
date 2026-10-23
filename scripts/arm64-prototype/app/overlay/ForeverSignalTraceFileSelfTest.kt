package app.gamenative

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Pure JVM file/decoder coverage; it never loads JNI or starts Wine. */
object ForeverSignalTraceFileSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        statAndGroupFixture()
        traceFixture()
        boundedFileFixture()
        println("ForeverSignalTraceFileSelfTest: PASS")
    }

    private fun statAndGroupFixture() {
        val line = "123 (name with ) close) S 1 222 333 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 888"
        check(ForeverProcStatParser.parse(line) == ForeverProcStat(123, 1, 888, 222, 333))
        check(ForeverProcStatParser.parse("123 (bad) S 1 2") == null)
    }

    private fun traceFixture() {
        val identity = SignalTraceIdentity("run-signal", "01234567-89ab-cdef-0123-456789abcdef", 6424, 99)
        val raw = trace(identity, 777, 0, 3)
        val decoded = ForeverSignalTraceDecoder.decode(raw, identity, 10_000, 10_000)
        check(decoded.status == SignalTraceDecodeStatus.VALID && decoded.attributedEvent?.senderPid == 777)
        check(ForeverSignalTraceDecoder.decode(raw.copyOf(319), identity, 10_000, 10_000).status == SignalTraceDecodeStatus.INVALID)
        check(ForeverSignalTraceDecoder.decode(raw.copyOf(256), identity, 10_000, 10_000).status == SignalTraceDecodeStatus.NOT_CAPTURED)
        check(ForeverSignalTraceDecoder.decode(raw, identity.copy(runId = "old"), 10_000, 10_000).status == SignalTraceDecodeStatus.INVALID)
    }

    private fun boundedFileFixture() {
        val root = Files.createTempDirectory("forever-bounded").toFile()
        val retention = BoundedLogRetention(root, "run-output", "stderr")
        val body = ByteArray(700 * 1024) { (it * 31).toByte() }
        retention.append(body)
        retention.append("FINAL-MARKER\n".toByteArray())
        retention.startPeriodicPersistence()
        Thread.sleep(900)
        check(retention.snapshot().totalBytes == body.size + 13L)
        check(retention.boundedText().contains("FINAL-MARKER"))
        retention.close()
        val persisted = BoundedLogRetention.readPersisted(root, "run-output", "stderr")
        check(persisted.head.size <= BoundedLogRetention.HEAD_LIMIT && persisted.tail.size <= BoundedLogRetention.TAIL_LIMIT)
        check(String(persisted.tail).contains("FINAL-MARKER"))
        val recovered = BoundedLogRetention(root, "run-output", "stderr", recoverExisting = true)
        check(recovered.snapshot().totalBytes == body.size + 13L)
        recovered.close()
        val drainerRoot = Files.createTempDirectory("forever-drainer").toFile()
        val drainerRetention = BoundedLogRetention(drainerRoot, "run-drain", "stdout")
        val drainer = ProcessOutputDrainer(ByteArrayInputStream("quiet-end".toByteArray()), drainerRetention)
        drainer.start(); check(drainer.join(2_000)); drainerRetention.close()
    }

    internal fun trace(identity: SignalTraceIdentity, senderPid: Int, siCode: Int, flags: Int): ByteArray {
        val bytes = ByteArray(320)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "WFSIG001".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        b.putInt(8, 1).putInt(12, 256).putInt(16, identity.pid).putInt(20, 1000)
            .putLong(24, identity.startTicks).putLong(32, 100).putLong(40, 100)
            .putInt(48, 10).putInt(52, 10).putInt(56, identity.runId.length).putInt(60, identity.bootId.length)
        identity.runId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 64)
        identity.bootId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 192)
        "WFSIGEV1".toByteArray(Charsets.US_ASCII).copyInto(bytes, 256)
        b.putInt(264, 1).putInt(268, 64).putInt(272, 2).putInt(276, siCode).putInt(280, identity.pid)
            .putInt(284, 6425).putInt(288, senderPid).putInt(292, if (flags and 2 != 0) 1000 else 0)
            .putLong(296, 200).putLong(304, 200).putInt(312, flags)
        return bytes
    }
}
