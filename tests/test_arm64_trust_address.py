"""Regression vectors for the directed ARM64 ADR/ADRP address decoder."""

from pathlib import Path
import subprocess


TRUST_FLOW = (
    Path(__file__).parents[1]
    / "scripts/diagnostics/reverse-engineering/TrustReferenceFlow.java"
)


def sign_extend(value: int, bits: int) -> int:
    sign = 1 << (bits - 1)
    return (value ^ sign) - sign


def decode_adr_target(word: int, pc: int, *, page: bool) -> int:
    imm_hi = (word >> 5) & 0x7FFFF
    imm_lo = (word >> 29) & 3
    imm21 = (imm_hi << 2) | imm_lo
    signed = sign_extend(imm21, 21)
    return (pc & ~0xFFF) + (signed << 12) if page else pc + signed


def encode_adr(imm21: int, rd: int = 20, *, page: bool) -> int:
    assert -(1 << 20) <= imm21 < (1 << 20)
    encoded = imm21 & ((1 << 21) - 1)
    imm_hi = encoded >> 2
    imm_lo = encoded & 3
    return (0x90000000 if page else 0x10000000) | (imm_lo << 29) | (imm_hi << 5) | rd


def test_known_adrp_distributes_immlo_and_matches_ghidra_operand() -> None:
    word = 0x9007AC94
    pc = 0x140E7B558
    assert ((word >> 5) & 0x7FFFF) == 0x3D64
    assert ((word >> 29) & 3) == 0
    assert decode_adr_target(word, pc, page=True) == 0x15040B000


def test_adr_positive_and_negative_low_two_bits() -> None:
    pc = 0x140100100
    for immediate in (1, 2, 3, 0x12345, -1, -2, -3, -0x12345):
        word = encode_adr(immediate, page=False)
        assert decode_adr_target(word, pc, page=False) == pc + immediate


def test_adrp_positive_and_negative_page_offsets() -> None:
    pc = 0x140100100
    for immediate in (1, 2, 3, 0x12345, -1, -2, -3, -0x12345):
        word = encode_adr(immediate, page=True)
        expected = (pc & ~0xFFF) + (immediate << 12)
        assert decode_adr_target(word, pc, page=True) == expected


def test_java_uses_arm64_imm21_layout_for_both_forms() -> None:
    source = TRUST_FLOW.read_text(encoding="utf-8")
    assert "((word >>> 5) & 0x7ffffL) << 2" in source
    assert "((word >>> 29) & 3L)" in source
    assert "return rva + signExtend(imm21, 21);" in source
    assert "return (rva & ~0xfffL) + signExtend(imm21, 21) * 0x1000L;" in source


def _java_method(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end].replace("private long", "public static long", 1)


def test_actual_java_decoder_matches_independent_vectors(tmp_path: Path) -> None:
    """Execute the actual Ghidra helper, not only a Python implementation of it."""
    source = TRUST_FLOW.read_text(encoding="utf-8")
    methods = "\n".join(
        _java_method(source, signature)
        for signature in (
            "private long signExtend(long value, int bits)",
            "private long armTarget(long rva, long word)",
        )
    )
    wrapper = tmp_path / "AddressDecoder.java"
    wrapper.write_text(
        "public class AddressDecoder {\n" + methods + "\n"
        "public static void main(String[] args) {\n"
        "for (int i = 0; i < args.length; i += 2) {\n"
        "System.out.println(Long.toHexString(armTarget("
        "Long.parseUnsignedLong(args[i], 16),"
        "Long.parseUnsignedLong(args[i + 1], 16))));\n"
        "}\n}\n}\n",
        encoding="utf-8",
    )
    subprocess.run(["javac", str(wrapper)], check=True, capture_output=True, timeout=30)
    vectors = [(0x140E7B558, 0x9007AC94, 0x15040B000)]
    pc = 0x140100103
    for page in (False, True):
        for immediate in (1, 2, 3, 0x12345, -1, -2, -3, -0x12345):
            expected = (pc & ~0xFFF) + (immediate << 12) if page else pc + immediate
            vectors.append((pc, encode_adr(immediate, page=page), expected))
    vectors.append((pc, 0, -1))
    result = subprocess.run(
        ["java", "-cp", str(tmp_path), "AddressDecoder"]
        + [format(value, "x") for address, word, _ in vectors for value in (address, word)],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.stdout.splitlines() == [
        format(expected & ((1 << 64) - 1), "x") for _, _, expected in vectors
    ]
