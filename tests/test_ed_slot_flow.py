from pathlib import Path
import subprocess


SOURCE = Path(__file__).parents[1] / "scripts/diagnostics/reverse-engineering/EdSlotFlow.java"


def test_ed_slot_flow_is_bounded_and_uses_supplied_images() -> None:
    text = SOURCE.read_text()
    assert "ARM_SLOT_RVA = 0x4a5ff00L" in text
    assert "X64_SLOT_RVA = 0x48f6870L" in text
    assert "ARM_SLOT_RAW = 0x4a5f100L" in text
    assert "X64_SLOT_RAW = 0x48f5a70L" in text
    assert "MAX_METADATA_BYTES = 32L * 1024L * 1024L" in text
    assert "MAX_CONTEXT_BYTES = 2048" in text
    assert "MAX_BODIES = 8" in text
    assert "MAX_BODY_BYTES = 64 * 1024" in text
    assert "decompileFunction(function, 15" in text
    assert "CREATE_NEW" in text
    assert "channel.force(true)" in text


def test_arm_address_decode_joins_immlo_and_immhi_before_sign_extension() -> None:
    text = SOURCE.read_text()
    assert "long immhi = (word >>> 5) & 0x7ffffL" in text
    assert "long immlo = (word >>> 29) & 3L" in text
    assert "long imm21 = (immhi << 2) | immlo" in text
    assert "return (pcRva & ~0xfffL) + signExtend(imm21, 21) * 0x1000L" in text


def test_production_x64_decoder_vectors(tmp_path: Path) -> None:
    """Compile and execute the exact standalone decoder used by Ghidra flow."""
    source = SOURCE.read_text()
    start = source.index("final class EdSlotX64Decoder")
    end = source.index("final class EdSlotArmSelector")
    decoder = source[start:end]
    decoder_path = tmp_path / "EdSlotX64Decoder.java"
    harness_path = tmp_path / "DecoderHarness.java"
    decoder_path.write_text(decoder)
    harness_path.write_text(
        """
public class DecoderHarness {
    private static byte[] b(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte)values[i];
        return result;
    }
    private static void check(byte[] bytes, long pc, long target, int width) {
        EdSlotX64Decoder.Decoded value = EdSlotX64Decoder.decode(bytes, pc);
        if (value == null || value.targetRva != target || value.width != width)
            throw new AssertionError("decoder mismatch");
    }
    public static void main(String[] args) {
        check(b(0x48, 0x8d, 0x05, 0x10, 0, 0, 0), 0x1000, 0x1017, 8);
        check(b(0x8b, 0x05, 0xf9, 0xff, 0xff, 0xff), 0x2000, 0x1fff, 4);
        check(b(0xf3, 0x0f, 0x6f, 0x05, 0x10, 0, 0, 0), 0x3000, 0x3018, 16);
        check(b(0x0f, 0x10, 0x05, 0x10, 0, 0, 0), 0x4000, 0x4017, 16);
        check(b(0x48, 0x8b, 0x05, 0xf0, 0xff, 0xff, 0xff), 0x5000, 0x4ff7, 8);
        check(b(0x66, 0x8b, 0x05, 0x10, 0, 0, 0), 0x6000, 0x6017, 2);
        if (EdSlotX64Decoder.decode(b(0x48, 0x8b, 0x44, 0x24, 0x08), 0x7000) != null)
            throw new AssertionError("non-RIP ModRM accepted");
    }
}
"""
    )
    subprocess.run(
        ["javac", str(decoder_path), str(harness_path)],
        check=True,
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        ["java", "-cp", str(tmp_path), "DecoderHarness"],
        check=True,
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )


def test_production_arm_page_selector_distinguishes_alias_and_direct_reference(tmp_path: Path) -> None:
    source = SOURCE.read_text()
    start = source.index("final class EdSlotArmSelector")
    selector_path = tmp_path / "EdSlotArmSelector.java"
    harness_path = tmp_path / "ArmHarness.java"
    selector_path.write_text(source[start:])
    harness_path.write_text(
        """
public class ArmHarness {
    public static void main(String[] args) {
        long slot = 0x4a5ff00L;
        long[][] targets = {
            {0x4a5fe00L, 0x4a60020L},
            {0x4cb63b8L, 0x4cb63c0L}
        };
        if (!EdSlotArmSelector.pageIntersectsAnyTarget(0x4a5f000L, targets))
            throw new AssertionError("primary page not selected");
        if (!EdSlotArmSelector.pageIntersectsAnyTarget(0x4cb6000L, targets))
            throw new AssertionError("alias page not selected");
        if (EdSlotArmSelector.pageIntersectsAnyTarget(0x4cb7000L, targets))
            throw new AssertionError("wrong page selected");
        if (EdSlotArmSelector.exactSlotReference(0x4cb6000L, slot, slot + 32))
            throw new AssertionError("page seed promoted to direct slot reference");
        if (!EdSlotArmSelector.exactSlotReference(slot + 8, slot, slot + 32))
            throw new AssertionError("exact slot reference rejected");
    }
}
"""
    )
    subprocess.run(
        ["javac", str(selector_path), str(harness_path)],
        check=True,
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        ["java", "-cp", str(tmp_path), "ArmHarness"],
        check=True,
        cwd=tmp_path,
        capture_output=True,
        text=True,
    )


def test_report_proves_genuine_ghidra_reference_and_pcode_paths_without_execution() -> None:
    text = SOURCE.read_text()
    assert "getReferenceManager()" in text
    assert "getReferencesFrom" in text
    assert "getPcode()" in text
    assert "GHIDRA_REFERENCE" in text
    assert "CONSUMER_PCODE" in text
    assert "no-client-execution" in text
    assert "no-process-memory" in text
    assert "ProcessBuilder" not in text
    assert "Runtime.getRuntime().exec" not in text
    assert "-postScript EdSlotFlow.java" in text


def test_slot_bytes_are_not_emitted() -> None:
    text = SOURCE.read_text()
    assert "getByte(address(slot" not in text
    assert "target_bytes_suppressed=true" in text
