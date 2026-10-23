package app.gamenative

import com.wowforever.launcher.ForeverPatchBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SignalTraceIdentity(val runId: String, val bootId: String, val pid: Int, val startTicks: Long)

data class SignalTraceHeader(
    val hostPid: Int,
    val hostUid: Long,
    val hostStartTicks: Long,
    val armedRealtimeMs: Long,
    val armedMonotonicNanos: Long,
    val hostPgid: Int,
    val hostSid: Int,
    val runId: String,
    val bootId: String,
)

data class SignalTraceEvent(
    val signal: Int,
    val siCode: Int,
    val receivingPid: Int,
    val receivingTid: Int,
    val senderPid: Int,
    val senderUid: Long,
    val realtimeMs: Long,
    val monotonicNanos: Long,
    val flags: Int,
    val senderKnown: Boolean,
)

enum class SignalTraceDecodeStatus { VALID, NOT_CAPTURED, INVALID }

data class SignalTraceDecode(
    val status: SignalTraceDecodeStatus,
    val reason: String,
    val header: SignalTraceHeader? = null,
    val events: List<SignalTraceEvent> = emptyList(),
) {
    val attributedEvent: SignalTraceEvent? get() = events.firstOrNull { it.senderKnown }

    fun boundedJson(maxBytes: Int = 16 * 1024): String {
        require(maxBytes >= 256)
        val h = header
        val result = buildString {
            append("{\"schema\":1,\"status\":").quote(status.name)
            append(",\"reason\":").quote(reason)
            if (h != null) {
                append(",\"header\":{\"hostPid\":${h.hostPid},\"hostStartTicks\":${h.hostStartTicks},")
                append("\"hostUid\":${h.hostUid},\"hostPgid\":${h.hostPgid},\"hostSid\":${h.hostSid},")
                append("\"armedRealtimeMs\":${h.armedRealtimeMs},\"armedMonotonicNanos\":${h.armedMonotonicNanos},")
                append("\"runId\":").quote(h.runId)
                append(",\"bootId\":").quote(h.bootId)
                append('}')
            }
            append(",\"events\":[")
            events.forEachIndexed { index, event ->
                if (index != 0) append(',')
                append("{\"signal\":${event.signal},\"siCode\":${event.siCode},")
                append("\"receivingPid\":${event.receivingPid},\"receivingTid\":${event.receivingTid},\"senderPid\":${event.senderPid},")
                append("\"senderUid\":${event.senderUid},\"realtimeMs\":${event.realtimeMs},\"monotonicNanos\":${event.monotonicNanos},\"flags\":${event.flags},")
                append("\"senderKnown\":${event.senderKnown}}")
            }
            append("]}")
        }
        return if (result.toByteArray(Charsets.UTF_8).size <= maxBytes) result
        else "{\"schema\":1,\"status\":\"INVALID\",\"reason\":\"decoded_json_bound\",\"events\":[]}"
    }

    private fun StringBuilder.quote(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> if (character.code < 0x20) append('?') else append(character)
            }
        }
        append('"')
    }
}

object ForeverSignalTraceDecoder {
    const val HEADER_BYTES = 256
    const val EVENT_BYTES = 64
    const val MAX_BYTES = HEADER_BYTES + EVENT_BYTES * 4
    private val headerMagic = "WFSIG001".toByteArray(Charsets.US_ASCII)
    private val eventMagic = "WFSIGEV1".toByteArray(Charsets.US_ASCII)
    private val bootPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun decode(
        bytes: ByteArray,
        expected: SignalTraceIdentity,
        nowRealtimeMs: Long = System.currentTimeMillis(),
        nowMonotonicNanos: Long = System.nanoTime(),
    ): SignalTraceDecode {
        if (bytes.isEmpty()) return SignalTraceDecode(SignalTraceDecodeStatus.NOT_CAPTURED, "not_captured")
        if (bytes.size > MAX_BYTES) return invalid("oversize")
        if (bytes.size < HEADER_BYTES) return invalid("truncated_header")
        if ((bytes.size - HEADER_BYTES) % EVENT_BYTES != 0) return invalid("truncated_event")
        if (expected.pid <= 0 || expected.startTicks <= 0 ||
            !expected.runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) ||
            !bootPattern.matches(expected.bootId)) return invalid("invalid_expected_identity")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!bytes.copyOfRange(0, 8).contentEquals(headerMagic)) return invalid("wrong_magic")
        if (u32(b, 8) != 1L || u32(b, 12) != HEADER_BYTES.toLong()) return invalid("wrong_header_version_or_size")
        val hostPid = b.getInt(16)
        val hostUid = u32(b, 20)
        val hostTicks = u64(b, 24) ?: return invalid("header_integer_overflow")
        val armedRealtime = u64(b, 32) ?: return invalid("header_integer_overflow")
        val armedMono = u64(b, 40) ?: return invalid("header_integer_overflow")
        val runLength = u32(b, 56).toInt()
        val bootLength = u32(b, 60).toInt()
        if (runLength !in 1..100 || bootLength != 36) return invalid("invalid_identity_lengths")
        val runId = ascii(bytes, 64, 128, runLength) ?: return invalid("invalid_run_id")
        val bootId = ascii(bytes, 192, 36, bootLength) ?: return invalid("invalid_boot_id")
        if (!bootPattern.matches(bootId) || !zero(bytes, 228, 28)) return invalid("invalid_header_padding")
        if (hostPid != expected.pid || hostTicks != expected.startTicks || runId != expected.runId || bootId != expected.bootId) {
            return invalid("identity_mismatch_or_pid_reuse")
        }
        if (armedRealtime <= 0L || armedMono <= 0L || future(armedRealtime, nowRealtimeMs, 5_000L) || future(armedMono, nowMonotonicNanos, 5_000_000_000L)) {
            return invalid("invalid_arm_time")
        }
        val header = SignalTraceHeader(hostPid, hostUid, hostTicks, armedRealtime, armedMono, b.getInt(48), b.getInt(52), runId, bootId)
        val events = ArrayList<SignalTraceEvent>(4)
        var offset = HEADER_BYTES
        repeat((bytes.size - HEADER_BYTES) / EVENT_BYTES) {
            if (!bytes.copyOfRange(offset, offset + 8).contentEquals(eventMagic)) return invalid("wrong_event_magic")
            if (leU32(bytes, offset + 8) != 1L || leU32(bytes, offset + 12) != EVENT_BYTES.toLong()) return invalid("wrong_event_version_or_size")
            val signal = leI32(bytes, offset + 16)
            val siCode = leI32(bytes, offset + 20)
            val receivingPid = leI32(bytes, offset + 24)
            val receivingTid = leI32(bytes, offset + 28)
            val senderPid = leI32(bytes, offset + 32)
            val senderUid = leU32(bytes, offset + 36) ?: return invalid("event_integer_overflow")
            val realtime = leU64(bytes, offset + 40) ?: return invalid("event_integer_overflow")
            val monotonic = leU64(bytes, offset + 48) ?: return invalid("event_integer_overflow")
            val flags = leU32(bytes, offset + 56)?.toInt() ?: return invalid("event_integer_overflow")
            if (flags and 0xFFFFFFF8.toInt() != 0 || flags and 1 == 0 || !zero(bytes, offset + 60, 4)) return invalid("event_flags_or_reserved")
            if (flags and 4 != 0) return invalid("event_clock_error")
            if (signal != 2 || receivingPid != hostPid || receivingTid <= 0) return invalid("event_receiver_or_signal_mismatch")
            val known = (siCode == 0 || siCode == -1 || siCode == -6) && senderPid > 0 && flags and 2 != 0
            if (flags and 2 != 0 && !known || flags and 2 == 0 && (senderPid != 0 || senderUid != 0L)) return invalid("invalid_sender_claim")
            if (future(realtime, nowRealtimeMs, 5_000L) || future(monotonic, nowMonotonicNanos, 5_000_000_000L)) return invalid("event_time_in_future")
            if (flags and 4 == 0 && (realtime < armedRealtime || monotonic < armedMono)) return invalid("event_time_before_arm")
            events += SignalTraceEvent(signal, siCode, receivingPid, receivingTid, senderPid, senderUid, realtime, monotonic, flags, known)
            offset += EVENT_BYTES
        }
        return if (events.isEmpty()) SignalTraceDecode(SignalTraceDecodeStatus.NOT_CAPTURED, "not_captured", header)
        else SignalTraceDecode(SignalTraceDecodeStatus.VALID, "ok", header, events)
    }

    private fun invalid(reason: String) = SignalTraceDecode(SignalTraceDecodeStatus.INVALID, reason)
    private fun u32(b: ByteBuffer, offset: Int) = b.getInt(offset).toLong() and 0xffffffffL
    private fun u64(b: ByteBuffer, offset: Int) = b.getLong(offset).takeUnless { it < 0 }
    private fun leI32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun leU32(bytes: ByteArray, offset: Int) = leI32(bytes, offset).toLong() and 0xffffffffL
    private fun leU64(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long.takeUnless { it < 0 }
    private fun ascii(bytes: ByteArray, offset: Int, fieldBytes: Int, length: Int): String? {
        if (length > fieldBytes || (length until fieldBytes).any { bytes[offset + it].toInt() != 0 }) return null
        val value = bytes.copyOfRange(offset, offset + length)
        if (value.any { it.toInt() !in 0x20..0x7e }) return null
        return value.toString(Charsets.US_ASCII)
    }
    private fun zero(bytes: ByteArray, offset: Int, length: Int) = (offset until offset + length).all { bytes[it].toInt() == 0 }
    private fun future(value: Long, now: Long, tolerance: Long): Boolean {
        if (value < 0 || now < 0) return true
        return value > if (now > Long.MAX_VALUE - tolerance) Long.MAX_VALUE else now + tolerance
    }
}

interface NativeSignalTraceAdapter {
    fun armSignalTrace(path: String, runId: String, bootId: String, expectedPid: Int, expectedStartTicks: Long): String
    fun signalTraceStatus(): String
}

object ForeverNativeSignalTraceAdapter : NativeSignalTraceAdapter {
    override fun armSignalTrace(path: String, runId: String, bootId: String, expectedPid: Int, expectedStartTicks: Long) =
        ForeverPatchBridge.armSignalTrace(path, runId, bootId, expectedPid, expectedStartTicks)
    override fun signalTraceStatus() = ForeverPatchBridge.signalTraceStatus()
}

data class SignalTraceArmOutcome(val status: String, val detail: String, val coverage: Boolean)

object ForeverSignalTraceArm {
    fun arm(adapter: NativeSignalTraceAdapter, path: String, identity: SignalTraceIdentity): SignalTraceArmOutcome = try {
        val raw = adapter.armSignalTrace(path, identity.runId, identity.bootId, identity.pid, identity.startTicks)
        val status = Regex("\"status\"\\s*:\\s*\"([A-Z]+)\"").find(raw)?.groupValues?.get(1) ?: "ERROR"
        SignalTraceArmOutcome(status, raw.take(2_048), status == "ARMED")
    } catch (error: Throwable) {
        SignalTraceArmOutcome("ERROR", "${error::class.java.simpleName}: ${error.message}".take(2_048), false)
    }
}
