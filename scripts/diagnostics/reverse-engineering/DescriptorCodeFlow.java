// Bounded host-only follow-up for four ARM64 DIR64 code-pointer seeds.
//
// The input PE is imported with -noanalysis.  Only the four exact .pdata
// metadata bodies supplied by the preceding report are disassembled, and the
// decompiler is invoked once for each bounded body.  This is a read-only
// investigation: it does not execute a client, access ADB/Wine, scan keys, or
// write a PE or process.
//
// Usage:
//   analyzeHeadless <fresh-project-root> <project> -import <ARM64-PE>
//     -noanalysis -max-cpu 2 -scriptPath <script-dir>
//     -postScript DescriptorCodeFlow.java <input-report> <CREATE_NEW-output>
//       <source-PE>
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.disassemble.Disassembler;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.lang.InstructionPrototype;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.TaskMonitor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DescriptorCodeFlow extends GhidraScript {
    private static final long IMAGE_BASE = 0x140000000L;
    private static final String EXPECTED_SOURCE_SHA256 =
        "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59";
    private static final int MAX_REPORT_BYTES = 200 * 1024;
    private static final int MAX_DECOMPILED_LINES = 400;
    private static final long SHA512_ED_CLUSTER_START = 0x4e53940L;
    private static final long SHA512_ED_CLUSTER_END = 0x4e53cd0L;

    private static final long[] BODY_STARTS = {
        0x37b1998L, 0x37b1af8L, 0x223c820L, 0x223cc08L
    };
    private static final long[] BODY_ENDS = {
        0x37b19d4L, 0x37b1bd0L, 0x223cc08L, 0x223cde8L
    };
    private static final long[] SLOT_RVAS = {
        0x4c5f710L, 0x4c60b20L, 0x4c60b28L, 0x498b558L, 0x498b568L
    };
    private static final long[] SLOT_TARGETS = {
        0x4c60b08L, 0x37b1998L, 0x37b1af8L, 0x223c820L, 0x223cc08L
    };

    private Memory memory() { return currentProgram.getMemory(); }
    private long base() { return currentProgram.getImageBase().getOffset(); }
    private Address address(long rva) { return currentProgram.getImageBase().add(rva); }
    private String rva(long value) { return String.format(Locale.ROOT, "0x%x", value); }
    private String rva(Address value) { return rva(value.getOffset() - base()); }

    private static final class Report {
        final StringBuilder text = new StringBuilder();
        boolean truncated;

        void line(String value) {
            if (truncated) return;
            String line = value + "\n";
            int current = text.toString().getBytes(StandardCharsets.UTF_8).length;
            int next = current + line.getBytes(StandardCharsets.UTF_8).length;
            if (next > MAX_REPORT_BYTES) {
                truncated = true;
                text.append("REPORT_TRUNCATED max_bytes=").append(MAX_REPORT_BYTES).append('\n');
                return;
            }
            text.append(line);
        }
    }

    private static final class PdataRecord {
        long begin;
        long end;
        long unwind;
        String kind;
        String status;
    }

    private static final class BodyResult {
        final long begin;
        final long end;
        int decodedInstructions;
        int decodedBytes;
        int invalidStops;
        final List<String> invalidDetails = new ArrayList<>();
        AddressSet decoded;
        Function function;

        BodyResult(long begin, long end) { this.begin = begin; this.end = end; }
    }

    private int u16(Address a) throws Exception {
        return Byte.toUnsignedInt(memory().getByte(a))
            | (Byte.toUnsignedInt(memory().getByte(a.add(1))) << 8);
    }

    private long u32(Address a) throws Exception {
        return Integer.toUnsignedLong(memory().getInt(a));
    }

    private long u64(Address a) throws Exception {
        return u32(a) | (u32(a.add(4)) << 32);
    }

    private boolean inBlock(long rva, long size, boolean executable) {
        if (rva < 0 || size <= 0 || size > 0x100000000L - rva) return false;
        Address start = address(rva);
        Address end = address(rva + size - 1);
        MemoryBlock block = memory().getBlock(start);
        return block != null && block.isInitialized() && block.contains(end)
            && (!executable || block.isExecute());
    }

    private String section(long rva) {
        MemoryBlock block = memory().getBlock(address(rva));
        return block == null ? "<unmapped>" : block.getName();
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count != 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private String reportSha256(Path path) throws Exception { return sha256(path); }

    private void validateInputReport(Path path, Report out) throws Exception {
        String input = Files.readString(path, StandardCharsets.UTF_8);
        out.line("INPUT_REPORT path=" + path + " sha256=" + reportSha256(path));
        String[] required = {
            "0x4c5f5e8", "0x4c5f710", "0x4c60b20", "0x4c60b28",
            "0x498b558", "0x498b568", "0x37b1998", "0x37b1af8",
            "0x223c820", "0x223cc08", "\"max_bodies\": 4",
            "\"noanalysis\": true", "\"overall_timeout_seconds\": 180"
        };
        for (String token : required) {
            if (!input.contains(token)) throw new IllegalArgumentException(
                "input report missing required token " + token);
        }
        out.line("INPUT_REPORT required_seed_and_budget_tokens=present");
    }

    private long parseNtRva() throws Exception { return u32(address(0x3c)); }

    private long[] directory(long nt, int index) throws Exception {
        long optional = nt + 24;
        return new long[] { u32(address(optional + 112 + index * 8L)),
            u32(address(optional + 112 + index * 8L + 4)) };
    }

    private Map<Long, Integer> relocationTypes(long relocationRva, long relocationSize) throws Exception {
        Map<Long, Integer> result = new HashMap<>();
        if (relocationRva == 0 || relocationSize < 8 || !inBlock(relocationRva, relocationSize, false))
            return result;
        long cursor = relocationRva;
        long limit = relocationRva + relocationSize;
        while (cursor + 8 <= limit) {
            long page = u32(address(cursor));
            long blockSize = u32(address(cursor + 4));
            if (blockSize < 8 || cursor + blockSize > limit) break;
            for (long item = cursor + 8; item + 2 <= cursor + blockSize; item += 2) {
                int raw = u16(address(item));
                int type = raw >>> 12;
                if (type != 0) result.put(page + (raw & 0xfff), type);
            }
            cursor += blockSize;
        }
        return result;
    }

    private String slotSnapshot(Map<Long, Integer> relocations) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < SLOT_RVAS.length; i++) {
            long slot = SLOT_RVAS[i];
            long value = u64(address(slot));
            long target = value >= base() ? value - base() : -1;
            int type = relocations.getOrDefault(slot, 0);
            result.append("slot_rva=").append(rva(slot))
                .append(" value_va=").append(String.format(Locale.ROOT, "0x%x", value))
                .append(" target_rva=").append(rva(target))
                .append(" section=").append(target < 0 ? "<non-image>" : section(target))
                .append(" relocation=").append(type == 10 ? "DIR64(10)" : Integer.toString(type))
                .append(" expected_target=").append(rva(SLOT_TARGETS[i])).append('\n');
            if (target != SLOT_TARGETS[i] || type != 10)
                throw new IllegalStateException("DIR64 slot validation failed at " + rva(slot));
        }
        return result.toString();
    }

    private List<PdataRecord> parsePdata(Report out) throws Exception {
        long nt = parseNtRva();
        int machine = u16(address(nt + 4));
        long[] exception = directory(nt, 3);
        long[] relocation = directory(nt, 5);
        out.line("PE image_base=" + String.format(Locale.ROOT, "0x%x", base())
            + " machine=" + String.format(Locale.ROOT, "0x%x", machine)
            + " exception_rva=" + rva(exception[0]) + " exception_size=" + exception[1]
            + " relocation_rva=" + rva(relocation[0]) + " relocation_size=" + relocation[1]);
        if (machine != 0xaa64) throw new IllegalStateException("expected ARM64 PE machine 0xaa64");
        if (exception[1] < 8 || exception[1] % 8 != 0 || !inBlock(exception[0], exception[1], false))
            throw new IllegalStateException("invalid ARM64 .pdata directory");
        List<PdataRecord> result = new ArrayList<>();
        long previous = -1;
        int invalid = 0;
        for (long p = exception[0]; p + 8 <= exception[0] + exception[1]; p += 8) {
            PdataRecord record = new PdataRecord();
            record.begin = u32(address(p));
            record.unwind = u32(address(p + 4));
            int flag = (int)(record.unwind & 3);
            if (flag == 0) {
                record.kind = "arm64-xdata";
                long xdata = record.unwind & ~3L;
                if (!inBlock(xdata, 4, false)) record.status = "xdata_out_of_bounds";
                else {
                    long header = u32(address(xdata));
                    record.end = record.begin + (header & 0x3ffffL) * 4L;
                    if (((header >>> 18) & 3) != 0) record.status = "unsupported_xdata_version";
                }
            } else if (flag == 1 || flag == 2) {
                record.kind = flag == 1 ? "arm64-packed" : "arm64-packed-fragment";
                record.end = record.begin + ((record.unwind >>> 2) & 0x7ffL) * 4L;
                // This is ARM64 UNWIND metadata, not client compression.
            } else record.status = "reserved_pdata_flag_3";
            if (previous >= 0 && record.begin <= previous && record.status == null)
                record.status = "non_monotonic_begin";
            if (record.status == null && !inBlock(record.begin, record.end - record.begin, true))
                record.status = "body_outside_executable_memory";
            if (record.status == null) result.add(record); else invalid++;
            previous = record.begin;
        }
        out.line("PDATA records_total=" + (exception[1] / 8) + " valid=" + result.size()
            + " invalid=" + invalid + " packed_is_unwind_metadata_not_client_compression=true");
        for (int i = 0; i < BODY_STARTS.length; i++) {
            PdataRecord found = null;
            for (PdataRecord record : result) if (record.begin == BODY_STARTS[i]) { found = record; break; }
            if (found == null || found.end != BODY_ENDS[i])
                throw new IllegalStateException(".pdata mismatch for body " + rva(BODY_STARTS[i]));
            out.line("PDATA_TARGET begin=" + rva(found.begin) + " end=" + rva(found.end)
                + " bytes=" + (found.end - found.begin) + " kind=" + found.kind
                + " unwind_rva=" + rva(found.unwind & ~3L) + " verified_current_pe=true");
        }
        return result;
    }

    private String readPublicString(long target) throws Exception {
        if (!inBlock(target, 1, false)) return "";
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < 160 && inBlock(target + i, 1, false); i++) {
            int value = Byte.toUnsignedInt(memory().getByte(address(target + i)));
            if (value == 0) break;
            if (value < 0x20 || value > 0x7e) return "";
            ascii.append((char)value);
        }
        return ascii.length() >= 3 ? ascii.toString() : "";
    }

    private long signExtend(long value, int bits) {
        long sign = 1L << (bits - 1);
        return (value ^ sign) - sign;
    }

    private long rawAddressTarget(long rva, long word) {
        if ((word & 0x9f000000L) == 0x10000000L) {
            long imm21 = (((word >>> 5) & 0x7ffffL) << 2) | ((word >>> 29) & 3L);
            return rva + signExtend(imm21, 21);
        }
        if ((word & 0x9f000000L) == 0x90000000L) {
            long imm21 = (((word >>> 5) & 0x7ffffL) << 2) | ((word >>> 29) & 3L);
            return (rva & ~0xfffL) + signExtend(imm21, 21) * 0x1000L;
        }
        if ((word & 0x3b000000L) == 0x18000000L)
            return rva + signExtend((word >>> 5) & 0x7ffffL, 19) * 4L;
        return -1;
    }

    private String dataDescription(long target) throws Exception {
        StringBuilder result = new StringBuilder();
        result.append("target_rva=").append(rva(target)).append(" section=").append(section(target));
        String text = readPublicString(target);
        if (!text.isEmpty()) result.append(" public_string=").append(text.replace('\n', ' '));
        if (target >= SHA512_ED_CLUSTER_START && target < SHA512_ED_CLUSTER_END)
            result.append(" sha512_ed_cluster=true");
        return result.toString();
    }

    private String refsFrom(Instruction instruction) throws Exception {
        List<String> refs = new ArrayList<>();
        for (Reference reference : currentProgram.getReferenceManager()
                .getReferencesFrom(instruction.getAddress())) {
            long target = reference.getToAddress().getOffset() - base();
            refs.add(rva(target) + ":" + reference.getReferenceType() + ":" + section(target));
        }
        return refs.toString();
    }

    private String functionName(Function function) {
        return function == null ? "<none>" : function.getName() + "@" + rva(function.getEntryPoint());
    }

    private String callers(Function function) {
        if (function == null) return "[]";
        List<String> result = new ArrayList<>();
        ReferenceIterator iterator = currentProgram.getReferenceManager()
            .getReferencesTo(function.getEntryPoint());
        while (iterator.hasNext() && result.size() < 32) {
            Reference reference = iterator.next();
            result.add(rva(reference.getFromAddress()) + ":" + reference.getReferenceType());
        }
        return result.toString();
    }

    private String callees(Function function) {
        if (function == null) return "[]";
        Set<String> result = new LinkedHashSet<>();
        InstructionIterator iterator = currentProgram.getListing().getInstructions(function.getBody(), true);
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            for (Reference reference : currentProgram.getReferenceManager()
                    .getReferencesFrom(instruction.getAddress())) {
                if (reference.getReferenceType().isCall())
                    result.add(rva(reference.getToAddress()) + ":" + reference.getReferenceType());
            }
        }
        return result.toString();
    }

    private Function makeFunction(long begin, long end) throws Exception {
        Address start = address(begin);
        Function function = currentProgram.getFunctionManager().getFunctionAt(start);
        if (function == null) function = createFunction(start, null);
        if (function != null) function.setBody(new AddressSet(start, address(end - 1)));
        return function;
    }

    private BodyResult disassembleBody(long begin, long end, Report out) throws Exception {
        BodyResult result = new BodyResult(begin, end);
        Address start = address(begin);
        AddressSet body = new AddressSet(start, address(end - 1));
        Disassembler disassembler = Disassembler.getDisassembler(currentProgram, monitor, null);
        result.decoded = disassembler.disassemble(start, (AddressSetView)body);
        for (long cursor = begin; cursor < end; cursor += 4) {
            Instruction instruction = currentProgram.getListing().getInstructionAt(address(cursor));
            if (instruction == null || instruction.getMinAddress().getOffset() != address(cursor).getOffset()
                || instruction.getLength() != 4) {
                result.invalidStops++;
                if (result.invalidDetails.size() < 32) {
                    long word = u32(address(cursor));
                    result.invalidDetails.add("rva=" + rva(cursor) + " raw_word="
                        + String.format(Locale.ROOT, "0x%08x", word)
                        + " reason=no_instruction_in_entry_reachable_listing"
                        + " independent_word_decode_attempted=false");
                }
                continue;
            }
            result.decodedInstructions++;
            result.decodedBytes += instruction.getLength();
        }
        result.function = makeFunction(begin, end);
        out.line("BODY begin=" + rva(begin) + " end=" + rva(end) + " bytes=" + (end - begin)
            + " disassemble_ranges=" + (result.decoded == null ? "[]" : result.decoded.printRanges())
            + " decoded_instructions=" + result.decodedInstructions
            + " decoded_bytes=" + result.decodedBytes + " coverage="
            + String.format(Locale.ROOT, "%.6f", (double)result.decodedBytes / (end - begin))
            + " unlisted_words=" + result.invalidStops + " function=" + functionName(result.function));
        for (String invalid : result.invalidDetails) out.line("  UNLISTED_WORD " + invalid);
        if (result.invalidStops != 0)
            out.line("  LIMITATION undefined_listing_is_not_an_independent_invalid_opcode_test=true"
                + " cannot_infer_packed_all_text=true");
        return result;
    }

    private void writeInstructions(BodyResult body, Report out) throws Exception {
        InstructionIterator iterator = currentProgram.getListing().getInstructions(
            new AddressSet(address(body.begin), address(body.end - 1)), true);
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            long at = instruction.getAddress().getOffset() - base();
            if (at < body.begin || at >= body.end) continue;
            long word = u32(instruction.getAddress());
            long rawTarget = rawAddressTarget(at, word);
            StringBuilder line = new StringBuilder("  INSN rva=").append(rva(at))
                .append(" mnemonic=").append(instruction.getMnemonicString())
                .append(" operands=").append(instruction.toString())
                .append(" raw_word=").append(String.format(Locale.ROOT, "0x%08x", word))
                .append(" flow=").append(instruction.getFlowType())
                .append(" refs=").append(refsFrom(instruction));
            if (rawTarget >= 0 && inBlock(rawTarget, 1, false))
                line.append(" raw_address_target=").append(dataDescription(rawTarget));
            out.line(line.toString());
            for (PcodeOp op : instruction.getPcode()) {
                if (op.getOpcode() == PcodeOp.LOAD || op.getOpcode() == PcodeOp.STORE
                    || op.getOpcode() == PcodeOp.CALL || op.getOpcode() == PcodeOp.CALLIND
                    || op.getOpcode() == PcodeOp.RETURN)
                    out.line("    PCODE " + op);
            }
        }
    }

    private void decompile(BodyResult body, Report out, DecompInterface decompiler) throws Exception {
        Function function = body.function;
        out.line("DECOMPILE body=[" + rva(body.begin) + "," + rva(body.end) + ") function="
            + functionName(function) + " timeout_seconds=15");
        if (function == null) {
            out.line("  complete=false error=no_function_created");
            return;
        }
        DecompileResults result = decompiler.decompileFunction(function, 15, monitor);
        out.line("  complete=" + result.decompileCompleted() + " error=" + result.getErrorMessage());
        if (!result.decompileCompleted() || result.getDecompiledFunction() == null) return;
        String[] lines = result.getDecompiledFunction().getC().split("\\R");
        out.line("  decompiled_lines=" + lines.length + " output_cap=" + MAX_DECOMPILED_LINES);
        int limit = Math.min(lines.length, MAX_DECOMPILED_LINES);
        for (int i = 0; i < limit; i++) out.line("  " + lines[i]);
        if (lines.length > limit) out.line("  DECOMPILER_OUTPUT_TRUNCATED lines=" + lines.length);
    }

    private FileChannel openPrivateExclusive(Path path) throws Exception {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
        return channel;
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 3)
            throw new IllegalArgumentException("expected input report, CREATE_NEW output, source PE");
        if (base() != IMAGE_BASE) throw new IllegalStateException("unexpected image base " + currentProgram.getImageBase());
        Path inputReport = Paths.get(getScriptArgs()[0]);
        Path outputReport = Paths.get(getScriptArgs()[1]);
        Path sourcePe = Paths.get(getScriptArgs()[2]);
        Report out = new Report();
        out.line("DescriptorCodeFlow version=1 mode=host-only-read-only");
        out.line("runtime=Ghidra-12.1.4 noanalysis ARM64-PC bounded-four-body");
        out.line("constraints=disassemble(start,AddressSetView)-inside-each-pdata-body;no-recursive-unbounded-disassemble;no-client-execution;no-ADB;no-Wine;no-credentials;no-memory-or-PE-writes");
        validateInputReport(inputReport, out);
        String beforeSha = sha256(sourcePe);
        out.line("SOURCE_PE path=" + sourcePe + " sha256_before=" + beforeSha
            + " expected=" + EXPECTED_SOURCE_SHA256 + " sha_match=" + beforeSha.equals(EXPECTED_SOURCE_SHA256));
        if (!beforeSha.equals(EXPECTED_SOURCE_SHA256)) throw new IllegalStateException("source PE SHA-256 mismatch");
        if (base() != IMAGE_BASE) throw new IllegalStateException("imported image base mismatch");
        long nt = parseNtRva();
        Map<Long, Integer> relocations = relocationTypes(directory(nt, 5)[0], directory(nt, 5)[1]);
        out.line("DIR64_SLOTS before\n" + slotSnapshot(relocations));
        parsePdata(out);

        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        List<BodyResult> bodies = new ArrayList<>();
        try {
            for (int i = 0; i < BODY_STARTS.length; i++) {
                BodyResult body = disassembleBody(BODY_STARTS[i], BODY_ENDS[i], out);
                writeInstructions(body, out);
                out.line("CALL_GRAPH body=[" + rva(body.begin) + "," + rva(body.end) + ") callers="
                    + callers(body.function) + " callees=" + callees(body.function));
                bodies.add(body);
            }
            for (BodyResult body : bodies) decompile(body, out, decompiler);
        } finally {
            decompiler.dispose();
        }
        String afterSha = sha256(sourcePe);
        out.line("DIR64_SLOTS after\n" + slotSnapshot(relocations));
        out.line("SOURCE_PE sha256_after=" + afterSha + " unchanged=" + beforeSha.equals(afterSha));
        if (!beforeSha.equals(afterSha)) throw new IllegalStateException("source PE changed during read-only analysis");
        out.line("INTERPRETATION_LIMITS exact-four-bodies-only=true root_reachable_trust_edge=false lexical_proximity_is_not_causality=true code_equivalence_claims=false");
        if (out.truncated) throw new IOException("report exceeded 200 KiB");
        FileChannel channel = openPrivateExclusive(outputReport);
        try {
            channel.write(ByteBuffer.wrap(out.text.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } finally {
            channel.close();
        }
        println("DescriptorCodeFlow report written: " + outputReport);
    }
}
