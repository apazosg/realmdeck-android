// Directed, read-only Ghidra report for the ARM64 CertStore string/data leads.
//
// This script intentionally does not run a client, use ADB, inspect a process, or
// scan key material.  It imports one PE with -noanalysis, recovers only PE .pdata
// body bounds, and asks Ghidra to disassemble only the resulting AddressSet.  The
// four string leads and the previously observed structural-table reference are
// the complete data set followed here.
//
// Usage:
//   analyzeHeadless ... -noanalysis -scriptPath <this-dir> \
//     -postScript TrustReferenceFlow.java <CREATE_NEW-report> <source-PE>
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.TaskMonitor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrustReferenceFlow extends GhidraScript {
    private static final String[] TERMS = {
        "NetClient.cpp", "Cert store init failed", "PublicKeyEntry", "allowDevPublicKey",
        "VerifyWithCertStore", "VerifyWithGivenTrustCertificate",
        "AddServerCertificatesToCertStore", "AddTrustCertificatesToCertStore"
    };
    // Decimal RVAs are retained as supplied by the preceding static data report.
    private static final long STRUCTURAL_ROOT = 80082408L;
    private static final long STRUCTURAL_REFERENCE = 80084664L;
    private static final long FOCUS_SEED_RVA = 0xe7b558L;
    private static final long FOCUS_BODY_START_RVA = 0xe7ad70L;
    private static final long FOCUS_BODY_END_RVA = 0xe7b99cL;
    private static final long VERIFY_PAGE_RVA = 0x4bdf000L;
    private static final long VERIFY_STRING_RVA = 0x4bdfbe8L;
    private static final long FOCUS_WINDOW_BYTES = 64L;
    private static final int FOCUS_MAX_INSTRUCTIONS = 64;
    private static final int FOCUS_MAX_BLOCKS = 4;
    private static final long MAX_REPORT_BYTES = 400 * 1024;
    private static final int MAX_CANDIDATES = 24;
    private static final int MAX_DECOMPILATIONS = 6;
    private static final Pattern REGISTER = Pattern.compile("\\b([xw][0-9]{1,2}|sp|xzr|wzr)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern IMMEDIATE = Pattern.compile("#(-?(?:0x[0-9a-fA-F]+|[0-9]+))");

    private Memory memory() { return currentProgram.getMemory(); }

    private static final class HashState {
        final long size;
        final String sha256;
        final long mtime;
        HashState(long size, String sha256, long mtime) {
            this.size = size; this.sha256 = sha256; this.mtime = mtime;
        }
    }

    private static final class SectionInfo {
        final String name;
        final long rva;
        final long size;
        final MemoryBlock block;
        SectionInfo(String name, long rva, long size, MemoryBlock block) {
            this.name = name; this.rva = rva; this.size = size; this.block = block;
        }
    }

    private static final class Record {
        final long begin;
        long end;
        final long unwind;
        String kind;
        String status;
        Record(long begin, long unwind) { this.begin = begin; this.unwind = unwind; }
        boolean valid() { return status == null && end > begin; }
    }

    private static final class Interesting {
        final long rva;
        final String label;
        final boolean string;
        Interesting(long rva, String label, boolean string) {
            this.rva = rva; this.label = label; this.string = string;
        }
    }

    private static final class DataAlias {
        final long rva;
        final long pointsTo;
        final int relocationType;
        DataAlias(long rva, long pointsTo, int relocationType) {
            this.rva = rva; this.pointsTo = pointsTo; this.relocationType = relocationType;
        }
    }

    private static final class RegValue {
        final long value;
        final String source;
        RegValue(long value, String source) { this.value = value; this.source = source; }
    }

    private static final class Evidence {
        final Record record;
        final Instruction instruction;
        final String kind;
        final Interesting target;
        final String detail;
        final boolean stringEvidence;
        Evidence(Record record, Instruction instruction, String kind, Interesting target,
                 String detail, boolean stringEvidence) {
            this.record = record; this.instruction = instruction; this.kind = kind;
            this.target = target; this.detail = detail; this.stringEvidence = stringEvidence;
        }
        String key() { return record.begin + ":" + instruction.getAddress() + ":" + kind + ":" + target.label; }
    }

    private static final class Candidate {
        final Record record;
        final List<Evidence> evidence = new ArrayList<>();
        int score;
        Candidate(Record record) { this.record = record; }
        boolean stringEvidence() {
            for (Evidence item : evidence) if (item.stringEvidence) return true;
            return false;
        }
    }

    private static final class Coverage {
        long bodyBytes;
        long analysisBodyBytes;
        long decodedBytes;
        long instructionCount;
        long disassemblyRanges;
        int invalidRecords;
        int unsupportedUnwind;
        int chainedOrFragment;
        Map<String, Integer> invalidReasons = new LinkedHashMap<>();
    }

    private static final class TraceValue {
        final long value;
        final String source;
        TraceValue(long value, String source) { this.value = value; this.source = source; }
    }

    private static final class TraceWork {
        final long start;
        final Map<String, TraceValue> registers;
        TraceWork(long start, Map<String, TraceValue> registers) {
            this.start = start; this.registers = registers;
        }
    }

    private long base() { return currentProgram.getImageBase().getOffset(); }
    private Address address(long rva) { return currentProgram.getImageBase().add(rva); }
    private String rva(Address address) { return String.format("0x%x", address.getOffset() - base()); }
    private String rva(long value) { return String.format("0x%x", value); }
    private String text(long value) { return value < 0 ? "<invalid>" : rva(value); }

    private int u16(Address a) throws Exception {
        return Byte.toUnsignedInt(memory().getByte(a))
            | (Byte.toUnsignedInt(memory().getByte(a.add(1))) << 8);
    }

    private long u32(Address a) throws Exception { return Integer.toUnsignedLong(memory().getInt(a)); }

    private long u64(Address a) throws Exception {
        long lo = u32(a);
        long hi = u32(a.add(4));
        return lo | (hi << 32);
    }

    private boolean inBlock(long rva, long size, boolean execute) {
        if (size <= 0 || rva < 0 || rva > 0xffffffffL || size > 0x100000000L - rva) return false;
        Address start = address(rva);
        Address end = address(rva + size - 1);
        MemoryBlock block = memory().getBlock(start);
        return block != null && block.isInitialized() && block.contains(end)
            && (!execute || block.isExecute());
    }

    private List<SectionInfo> sections() {
        List<SectionInfo> result = new ArrayList<>();
        for (MemoryBlock block : memory().getBlocks()) {
            long rva = block.getStart().getOffset() - base();
            result.add(new SectionInfo(block.getName(), rva, block.getSize(), block));
        }
        return result;
    }

    private HashState hash(Path path) throws Exception {
        java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(path,
            java.nio.file.attribute.BasicFileAttributes.class);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count != 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return new HashState(attrs.size(), hex.toString(), attrs.lastModifiedTime().toMillis());
    }

    private Map<Long, Integer> relocations(long rva, long size) throws Exception {
        Map<Long, Integer> result = new HashMap<>();
        if (rva == 0 || size < 8 || !inBlock(rva, size, false)) return result;
        long cursor = rva;
        long end = rva + size;
        while (cursor + 8 <= end) {
            long page = u32(address(cursor));
            long blockSize = u32(address(cursor + 4));
            if (blockSize < 8 || cursor + blockSize > end) break;
            for (long p = cursor + 8; p + 2 <= cursor + blockSize; p += 2) {
                int raw = u16(address(p));
                int type = raw >>> 12;
                if (type != 0) result.put(page + (raw & 0xfff), type);
            }
            cursor += blockSize;
        }
        return result;
    }

    private List<Record> recoverPdata(BufferedWriter out, Coverage coverage) throws Exception {
        long nt = u32(address(0x3c));
        int machine = u16(address(nt + 4));
        int optionalSize = u16(address(nt + 20));
        long optional = nt + 24;
        long exceptionRva = u32(address(optional + 112 + 3 * 8));
        long exceptionSize = u32(address(optional + 112 + 3 * 8 + 4));
        int recordSize = machine == 0xaa64 ? 8 : machine == 0x8664 ? 12 : 0;
        out.write("PE machine=" + String.format("0x%x", machine)
            + " optional_header_size=" + optionalSize
            + " exception_rva=" + rva(exceptionRva) + " exception_size=" + exceptionSize
            + " record_size=" + recordSize + "\n");
        List<Record> valid = new ArrayList<>();
        if (recordSize == 0 || exceptionSize < recordSize || exceptionSize % recordSize != 0
            || !inBlock(exceptionRva, exceptionSize, false)) {
            coverage.invalidRecords++;
            coverage.invalidReasons.merge("exception_directory_shape_or_bounds", 1, Integer::sum);
            return valid;
        }
        long previous = -1;
        for (long p = exceptionRva; p + recordSize <= exceptionRva + exceptionSize; p += recordSize) {
            long begin = u32(address(p));
            long unwind = u32(address(p + (machine == 0xaa64 ? 4 : 8)));
            Record record = new Record(begin, unwind);
            if (machine == 0x8664) {
                record.kind = "x64-runtime-function";
                record.end = u32(address(p + 4));
                if (unwind != 0 && inBlock(unwind, 4, false)) {
                    int header = Byte.toUnsignedInt(memory().getByte(address(unwind)));
                    if ((header & 7) != 1) record.status = "unsupported_unwind_version_" + (header & 7);
                    if ((header & 0x20) != 0) coverage.chainedOrFragment++;
                } else if (unwind != 0) record.status = "unwind_rva_out_of_initialized_memory";
            } else {
                int flag = (int)(unwind & 3);
                if (flag == 0) {
                    record.kind = "arm64-xdata";
                    long xdata = unwind & ~3L;
                    if (!inBlock(xdata, 4, false)) {
                        record.status = "xdata_rva_out_of_initialized_memory";
                    } else {
                        long header = u32(address(xdata));
                        int version = (int)((header >>> 18) & 3);
                        long words = header & 0x3ffffL;
                        record.end = begin + words * 4L;
                        if (version != 0) record.status = "unsupported_xdata_version_" + version;
                    }
                } else if (flag == 1 || flag == 2) {
                    record.kind = flag == 1 ? "arm64-packed" : "arm64-packed-fragment";
                    record.end = begin + ((unwind >>> 2) & 0x7ffL) * 4L;
                    if (flag == 2) coverage.chainedOrFragment++;
                } else {
                    record.kind = "arm64-reserved-flag-3";
                    record.status = "reserved_pdata_flag_3";
                }
            }
            if (previous >= 0 && begin <= previous && record.status == null)
                record.status = "non_monotonic_begin_rva";
            if (record.status == null && (!inBlock(begin, record.end - begin, true)))
                record.status = "function_body_out_of_executable_bounds";
            if (record.status == null) valid.add(record);
            else {
                coverage.invalidRecords++;
                coverage.invalidReasons.merge(record.status, 1, Integer::sum);
                if (record.status.startsWith("unsupported_")) coverage.unsupportedUnwind++;
            }
            previous = begin;
        }
        valid.sort(Comparator.comparingLong(item -> item.begin));
        for (Record record : valid) coverage.bodyBytes += record.end - record.begin;
        coverage.disassemblyRanges = valid.size();
        out.write("Pdata records_total=" + ((exceptionSize) / recordSize)
            + " valid=" + valid.size() + " invalid=" + coverage.invalidRecords
            + " unsupported_unwind=" + coverage.unsupportedUnwind
            + " chained_or_fragment=" + coverage.chainedOrFragment
            + " invalid_reasons=" + coverage.invalidReasons + "\n");
        return valid;
    }

    private List<Interesting> locateInterests(BufferedWriter out) throws Exception {
        List<Interesting> result = new ArrayList<>();
        MemoryBlock rdata = null;
        for (MemoryBlock block : memory().getBlocks()) {
            if (".rdata".equals(block.getName())) { rdata = block; break; }
        }
        if (rdata == null) {
            out.write("RDATA absent; no string/data search performed\n");
            return result;
        }
        for (String term : TERMS) {
            byte[] needle = term.getBytes(StandardCharsets.US_ASCII);
            Address cursor = rdata.getStart();
            int found = 0;
            while (cursor.compareTo(rdata.getEnd()) <= 0 && !monitor.isCancelled()) {
                Address hit = memory().findBytes(cursor, rdata.getEnd(), needle, null, true, monitor);
                if (hit == null) break;
                Address stringStart = hit;
                while (stringStart.compareTo(rdata.getStart()) > 0
                    && Byte.toUnsignedInt(memory().getByte(stringStart.subtract(1))) != 0)
                    stringStart = stringStart.subtract(1);
                long target = stringStart.getOffset() - base();
                result.add(new Interesting(target, "string:" + term, true));
                found++;
                // Advance from the matched substring, not the containing string
                // start; otherwise a substring such as NetClient.cpp repeats
                // forever after its leading NUL has been recovered.
                cursor = hit.add(Math.max(1, needle.length));
            }
            out.write("STRING term=" + term + " matches=" + found);
            for (Interesting item : result) {
                if (item.label.equals("string:" + term)) out.write(" rva=" + rva(item.rva));
            }
            out.write("\n");
        }
        result.add(new Interesting(STRUCTURAL_ROOT, "structural_root_12x40", false));
        result.add(new Interesting(STRUCTURAL_REFERENCE, "structural_reference_to_root", false));
        return result;
    }

    private List<DataAlias> aliases(List<Interesting> interests, Map<Long, Integer> relocations,
                                    BufferedWriter out) throws Exception {
        List<MemoryBlock> pointerBlocks = new ArrayList<>();
        for (MemoryBlock block : memory().getBlocks()) {
            if (".rdata".equals(block.getName()) || ".text".equals(block.getName()))
                pointerBlocks.add(block);
        }
        if (pointerBlocks.isEmpty()) return List.of();
        Map<Long, Interesting> targets = new LinkedHashMap<>();
        for (Interesting item : interests) targets.put(base() + item.rva, item);
        List<DataAlias> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        // Three bounded pointer-table passes follow only pointers to the named
        // strings or the supplied structural root/reference; this is not a key scan.
        for (int pass = 0; pass < 3; pass++) {
            for (MemoryBlock block : pointerBlocks) {
                for (Address p = block.getStart(); p.add(8).compareTo(block.getEnd()) <= 0; p = p.add(4)) {
                    long value = u64(p);
                    Interesting target = targets.get(value);
                    if (target == null) continue;
                    long slot = p.getOffset() - base();
                    if (seen.add(slot)) {
                        DataAlias alias = new DataAlias(slot, target.rva, relocations.getOrDefault(slot, 0));
                        result.add(alias);
                        targets.put(base() + slot, new Interesting(slot,
                            "indirect_slot_for:" + target.label, target.string));
                    }
                }
            }
        }
        out.write("RDATA_INDIRECT_ALIASES count=" + result.size() + "\n");
        for (DataAlias alias : result) {
            out.write("  slot_rva=" + rva(alias.rva) + " points_to=" + rva(alias.pointsTo)
                + " relocation_type=" + (alias.relocationType == 0 ? "none" : alias.relocationType)
                + "\n");
        }
        return result;
    }

    private Map<Long, Interesting> interestMap(List<Interesting> interests, List<DataAlias> aliases) {
        Map<Long, Interesting> result = new LinkedHashMap<>();
        for (Interesting item : interests) result.put(item.rva, item);
        for (DataAlias alias : aliases) {
            Interesting target = result.get(alias.pointsTo);
            result.put(alias.rva, new Interesting(alias.rva,
                "indirect_slot_for:" + (target == null ? rva(alias.pointsTo) : target.label),
                target != null && target.string));
        }
        return result;
    }

    private Record recordFor(List<Record> records, long rva) {
        int low = 0, high = records.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Record item = records.get(mid);
            if (rva < item.begin) high = mid - 1;
            else if (rva >= item.end) low = mid + 1;
            else return item;
        }
        return null;
    }

    private long signExtend(long value, int bits) {
        long sign = 1L << (bits - 1);
        return (value ^ sign) - sign;
    }

    private boolean arm64Language() {
        return currentProgram.getLanguageID().toString().toLowerCase(Locale.ROOT).contains("aarch64");
    }

    private long armTarget(long rva, long word) {
        long imm21 = (((word >>> 5) & 0x7ffffL) << 2) | ((word >>> 29) & 3L);
        if ((word & 0x9f000000L) == 0x10000000L) {
            return rva + signExtend(imm21, 21);
        }
        if ((word & 0x9f000000L) == 0x90000000L) {
            return (rva & ~0xfffL) + signExtend(imm21, 21) * 0x1000L;
        }
        if ((word & 0x3b000000L) == 0x18000000L) {
            return rva + signExtend((word >>> 5) & 0x7ffffL, 19) * 4L;
        }
        return -1;
    }

    private List<Record> prefilterRecords(List<Record> records, Map<Long, Interesting> interests,
                                           BufferedWriter out) throws Exception {
        Set<Record> chosen = new LinkedHashSet<>();
        Map<Record, String> reasons = new LinkedHashMap<>();
        if (arm64Language()) {
            for (Record record : records) {
                Map<Integer, Long> registerValues = new HashMap<>();
                for (long p = record.begin; p + 4 <= record.end; p += 4) {
                    long word = u32(address(p));
                    long target = armTarget(p, word);
                    if (target >= 0 && (interests.containsKey(target)
                        || samePage(interests, target) != null)) {
                        chosen.add(record);
                        reasons.putIfAbsent(record, "instruction_rva=" + rva(p)
                            + " formation=" + (((word & 0x9f000000L) == 0x90000000L) ? "ADRP" : "ADR/LDR-literal")
                            + " target_page=" + rva(target & ~0xfffL)
                            + " target_label=" + samePage(interests, target).label);
                    }
                    if (target >= 0) {
                        Long loaded = loadedQword(target);
                        if (loaded != null && interests.containsKey(loaded - base())) {
                            chosen.add(record);
                            reasons.putIfAbsent(record, "instruction_rva=" + rva(p)
                                + " formation=literal-pointer target=" + rva(loaded - base()));
                        }
                    }
                    int rd = (int)(word & 31);
                    int rn = (int)((word >>> 5) & 31);
                    if ((word & 0x9f000000L) == 0x90000000L) {
                        registerValues.put(rd, target);
                    } else if ((word & 0x7f000000L) == 0x11000000L) {
                        long imm = ((word >>> 10) & 0xfffL) << (((word >>> 22) & 1) == 0 ? 0 : 12);
                        Long baseValue = registerValues.get(rn);
                        if (baseValue != null) {
                            long value = ((word >>> 30) & 1) == 0 ? baseValue + imm : baseValue - imm;
                            registerValues.put(rd, value);
                            if (interests.containsKey(value)) chosen.add(record);
                        } else registerValues.remove(rd);
                    } else if ((word & 0x7f800000L) == 0x52800000L
                        || (word & 0x7f800000L) == 0x72800000L) {
                        long imm = (word >>> 5) & 0xffffL;
                        long value = imm << (((word >>> 21) & 3) * 16);
                        if ((word & 0x7f800000L) == 0x72800000L && registerValues.containsKey(rd)) {
                            long mask = 0xffffL << (((word >>> 21) & 3) * 16);
                            value = (registerValues.get(rd) & ~mask) | value;
                        }
                        registerValues.put(rd, value);
                        if (interests.containsKey(value)) chosen.add(record);
                    } else if ((word & 0x1f000000L) == 0x14000000L
                        || (word & 0x7e000000L) == 0x34000000L) {
                        // A linear register state is not carried over an obvious branch.
                        registerValues.clear();
                    }
                }
            }
        } else {
            for (Record record : records) {
                for (long p = record.begin; p + 7 <= record.end; p++) {
                    int b0 = Byte.toUnsignedInt(memory().getByte(address(p)));
                    int prefix = 0;
                    if ((b0 & 0xf0) == 0x40) prefix = 1;
                    int opcode = Byte.toUnsignedInt(memory().getByte(address(p + prefix)));
                    if (opcode != 0x8b && opcode != 0x8d && opcode != 0x89
                        && opcode != 0x8a && opcode != 0x88) continue;
                    int modrm = Byte.toUnsignedInt(memory().getByte(address(p + prefix + 1)));
                    if ((modrm & 0xc7) != 0x05) continue;
                    long displacement = (int)memory().getInt(address(p + prefix + 2));
                    long target = p + prefix + 6L + displacement;
                    if (interests.containsKey(target)) {
                        chosen.add(record);
                        reasons.putIfAbsent(record, "instruction_rva=" + rva(p)
                            + " formation=RIP-relative target=" + rva(target));
                        break;
                    }
                }
            }
        }
        List<Record> result = new ArrayList<>(chosen);
        result.sort(Comparator.comparingLong(item -> item.begin));
        out.write("PREFILTER architecture=" + (arm64Language() ? "ARM64" : "x64")
            + " candidate_records=" + result.size() + " of_valid_pdata=" + records.size()
            + " method=bounded_instruction_address_formation_and_rip_relative_data\n");
        for (Record record : result)
            out.write("  PREFILTER_RECORD begin=" + rva(record.begin) + " end=" + rva(record.end)
                + " evidence=" + reasons.getOrDefault(record, "target-page-or-literal-pointer")
                + "; exact-consumer-test=pending\n");
        return result;
    }

    private String reg(String value) {
        Matcher matcher = REGISTER.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private List<String> regs(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = REGISTER.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return result;
    }

    private Long immediate(String value) {
        Matcher matcher = IMMEDIATE.matcher(value == null ? "" : value);
        if (!matcher.find()) return null;
        String raw = matcher.group(1);
        boolean negative = raw.startsWith("-");
        String digits = negative ? raw.substring(1) : raw;
        long parsed;
        try { parsed = digits.startsWith("0x") ? Long.parseUnsignedLong(digits.substring(2), 16)
            : Long.parseLong(digits); }
        catch (NumberFormatException ignored) { return null; }
        return negative ? -parsed : parsed;
    }

    private Interesting exact(Map<Long, Interesting> map, long value) { return map.get(value); }

    private Interesting samePage(Map<Long, Interesting> map, long value) {
        long page = value & ~0xfffL;
        for (Interesting item : map.values())
            if ((item.rva & ~0xfffL) == page) return item;
        return null;
    }

    private void addEvidence(Map<String, Candidate> candidates, Record record, Instruction instruction,
                             String kind, Interesting target, String detail, boolean stringEvidence) {
        if (record == null || target == null) return;
        Candidate candidate = candidates.computeIfAbsent(Long.toString(record.begin), key -> new Candidate(record));
        Evidence evidence = new Evidence(record, instruction, kind, target, detail, stringEvidence);
        for (Evidence existing : candidate.evidence) if (existing.key().equals(evidence.key())) return;
        candidate.evidence.add(evidence);
        candidate.score += stringEvidence ? (kind.contains("direct") ? 10 : 7) : 2;
    }

    private long referencedTarget(Instruction instruction, Map<Long, Interesting> interests) {
        Reference[] references = currentProgram.getReferenceManager()
            .getReferencesFrom(instruction.getAddress());
        for (Reference reference : references) {
            long target = reference.getToAddress().getOffset() - base();
            if (interests.containsKey(target)) return target;
        }
        return -1;
    }

    private Long loadedQword(long rva) {
        if (!inBlock(rva, 8, false)) return null;
        try { return u64(address(rva)); } catch (Exception ignored) { return null; }
    }

    private void inspectArmInstruction(Instruction instruction, Record record,
                                       Map<Long, Interesting> interests, Map<String, Candidate> candidates,
                                       Map<String, RegValue> state) {
        String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
        String operand = instruction.toString();
        int split = operand.indexOf(' ');
        String ops = split < 0 ? "" : operand.substring(split + 1);
        List<String> registers = regs(ops);
        String destination = registers.isEmpty() ? null : registers.get(0);
        long directRva = referencedTarget(instruction, interests);
        if (directRva >= 0) {
            Interesting target = interests.get(directRva);
            addEvidence(candidates, record, instruction, "direct_reference", target,
                "Ghidra instruction reference", target.string);
            if (destination != null && (mnemonic.startsWith("ADR") || mnemonic.startsWith("LDR")
                || mnemonic.startsWith("ADRP"))) state.put(destination,
                new RegValue(directRva, "instruction-reference:" + target.label));
        } else if (mnemonic.startsWith("ADR") || mnemonic.startsWith("ADRP")) {
            Reference[] references = currentProgram.getReferenceManager()
                .getReferencesFrom(instruction.getAddress());
            if (references.length != 0) {
                long target = references[0].getToAddress().getOffset() - base();
                state.put(destination, new RegValue(target & (mnemonic.startsWith("ADRP") ? ~0xfffL : -1L),
                    mnemonic + " reference"));
            }
        }
        if (destination == null) return;
        if (mnemonic.equals("MOV") && registers.size() > 1) {
            RegValue source = state.get(registers.get(1));
            if (source != null) state.put(destination, source);
            else state.remove(destination);
            return;
        }
        if (mnemonic.equals("MOVZ") || mnemonic.equals("MOVN")) {
            Long imm = immediate(ops);
            if (imm != null) {
                Long shift = immediate(ops.replaceFirst("#-?(?:0x[0-9a-fA-F]+|[0-9]+)", ""));
                long value = imm << (shift == null ? 0 : shift);
                state.put(destination, new RegValue(value, mnemonic));
                Interesting target = exact(interests, value);
                if (target != null) addEvidence(candidates, record, instruction,
                    "mov_immediate_address", target, "MOVZ/MOVN address formation", target.string);
            }
            return;
        }
        if (mnemonic.equals("MOVK")) {
            Long imm = immediate(ops);
            RegValue previous = state.get(destination);
            if (imm != null && previous != null) {
                Long shift = immediate(ops.replaceFirst("#-?(?:0x[0-9a-fA-F]+|[0-9]+)", ""));
                long mask = 0xffffL << (shift == null ? 0 : shift);
                long value = (previous.value & ~mask) | (imm << (shift == null ? 0 : shift));
                state.put(destination, new RegValue(value, "MOVK"));
                Interesting target = exact(interests, value);
                if (target != null) addEvidence(candidates, record, instruction,
                    "movk_immediate_address", target, "MOVZ/MOVK address formation", target.string);
            }
            return;
        }
        if (mnemonic.equals("ADD") || mnemonic.equals("SUB")) {
            if (registers.size() > 1) {
                RegValue source = state.get(registers.get(1));
                Long imm = immediate(ops);
                if (source != null && imm != null) {
                    long value = mnemonic.equals("ADD") ? source.value + imm : source.value - imm;
                    state.put(destination, new RegValue(value, source.source + "+immediate"));
                    Interesting target = exact(interests, value);
                    if (target != null) addEvidence(candidates, record, instruction,
                        "formed_address", target, "ADRP/ADR + ADD/SUB", target.string);
                } else state.remove(destination);
            }
            return;
        }
        if (mnemonic.startsWith("LDR") || mnemonic.startsWith("LDUR")
            || mnemonic.startsWith("STR") || mnemonic.startsWith("STUR")) {
            int left = ops.indexOf('[');
            int right = ops.indexOf(']');
            if (left >= 0 && right > left) {
                String inside = ops.substring(left + 1, right);
                List<String> memoryRegs = regs(inside);
                if (!memoryRegs.isEmpty()) {
                    RegValue baseValue = state.get(memoryRegs.get(0));
                    Long imm = immediate(inside);
                    if (baseValue != null) {
                        long effective = baseValue.value + (imm == null ? 0 : imm);
                        Interesting target = exact(interests, effective);
                        if (target != null) addEvidence(candidates, record, instruction,
                            memoryRegs.size() > 1 ? "scaled_index_table_access" : "memory_access",
                            target, memoryRegs.size() > 1 ? "base + scaled register index" : "base + immediate",
                            target.string);
                        Long loaded = loadedQword(effective);
                        if (loaded != null) {
                            Interesting loadedTarget = exact(interests, loaded - base());
                            if (loadedTarget != null && destination != null && mnemonic.startsWith("LD")) {
                                state.put(destination, new RegValue(loaded - base(),
                                    "qword-at:" + rva(effective)));
                                addEvidence(candidates, record, instruction, "indirect_data_load",
                                    loadedTarget, "qword slot resolves to " + rva(loaded - base()),
                                    loadedTarget.string);
                            }
                        }
                    }
                }
            }
        }
        if (!mnemonic.equals("NOP") && !mnemonic.startsWith("B")) {
            if (!mnemonic.startsWith("LDR") && !mnemonic.startsWith("LDUR")) state.remove(destination);
        }
    }

    private Map<String, Candidate> inspectInstructions(List<Record> records, AddressSetView restricted,
                                                       Map<Long, Interesting> interests, Coverage coverage,
                                                       BufferedWriter out) throws Exception {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        InstructionIterator iterator = currentProgram.getListing().getInstructions(restricted, true);
        long decodedEnd = 0;
        long count = 0;
        Map<String, RegValue> state = new HashMap<>();
        Record current = null;
        while (iterator.hasNext() && !monitor.isCancelled()) {
            Instruction instruction = iterator.next();
            Record owner = recordFor(records, instruction.getAddress().getOffset() - base());
            if (owner == null) continue;
            if (owner != current) { state = new HashMap<>(); current = owner; }
            count++;
            decodedEnd += instruction.getLength();
            if (arm64Language())
                inspectArmInstruction(instruction, owner, interests, candidates, state);
            else {
                long target = referencedTarget(instruction, interests);
                if (target >= 0) {
                    Interesting item = interests.get(target);
                    addEvidence(candidates, owner, instruction, "direct_or_rip_reference", item,
                        "Ghidra x64 reference", item.string);
                }
            }
        }
        coverage.instructionCount = count;
        coverage.decodedBytes = decodedEnd;
        out.write("DISASSEMBLY restricted_ranges=" + coverage.disassemblyRanges
            + " selected_body_bytes=" + coverage.analysisBodyBytes
            + " valid_pdata_body_bytes=" + coverage.bodyBytes
            + " decoded_instruction_bytes=" + decodedEnd
            + " decoded_instructions=" + count
            + " selected_uncovered_bytes=" + Math.max(0, coverage.analysisBodyBytes - decodedEnd)
            + " selected_coverage_ratio=" + (coverage.analysisBodyBytes == 0 ? "0" :
                String.format(Locale.ROOT, "%.6f", (double)decodedEnd / coverage.analysisBodyBytes)) + "\n");
        return candidates;
    }

    private String functionName(Function function) {
        return function == null ? "<none>" : function.getName() + "@" + rva(function.getEntryPoint());
    }

    private Function boundedFunction(Record record) {
        Address start = address(record.begin);
        Function function = currentProgram.getFunctionManager().getFunctionAt(start);
        try {
            if (function == null) function = createFunction(start, null);
            if (function != null) function.setBody(new AddressSet(start, address(record.end - 1)));
        } catch (Exception ignored) { }
        return function;
    }

    private String callers(Function function) {
        if (function == null) return "[]";
        Set<String> result = new LinkedHashSet<>();
        ReferenceIterator iterator = currentProgram.getReferenceManager()
            .getReferencesTo(function.getEntryPoint());
        while (iterator.hasNext()) {
            Reference reference = iterator.next();
            result.add(rva(reference.getFromAddress()) + ":" + reference.getReferenceType());
            if (result.size() >= 12) break;
        }
        return result.toString();
    }

    private String callees(Function function) {
        if (function == null) return "[]";
        Set<String> result = new LinkedHashSet<>();
        InstructionIterator iterator = currentProgram.getListing().getInstructions(function.getBody(), true);
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            Reference[] refs = currentProgram.getReferenceManager()
                .getReferencesFrom(instruction.getAddress());
            for (Reference reference : refs) {
                if (reference.getReferenceType().isCall()) {
                    result.add(rva(reference.getToAddress()) + "@" + reference.getReferenceType());
                    if (result.size() >= 12) return result.toString();
                }
            }
        }
        return result.toString();
    }

    private void writeWindow(BufferedWriter out, Evidence evidence) throws IOException {
        Instruction before = evidence.instruction;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            before = before.getPrevious();
            if (before == null) break;
            lines.add(0, "    " + rva(before.getAddress()) + " " + before);
        }
        lines.add("    * " + rva(evidence.instruction.getAddress()) + " " + evidence.instruction);
        Instruction after = evidence.instruction;
        for (int i = 0; i < 4; i++) {
            after = after.getNext();
            if (after == null) break;
            lines.add("    " + rva(after.getAddress()) + " " + after);
        }
        for (String line : lines) out.write(line + "\n");
    }

    private void writeCandidates(BufferedWriter out, List<Candidate> candidates) throws Exception {
        candidates.sort((a, b) -> Integer.compare(b.score, a.score));
        out.write("CANDIDATES count=" + Math.min(MAX_CANDIDATES, candidates.size())
            + " total_evidence_functions=" + candidates.size() + "\n");
        List<Function> decompile = new ArrayList<>();
        int index = 0;
        for (Candidate candidate : candidates) {
            if (index++ >= MAX_CANDIDATES) break;
            Function function = boundedFunction(candidate.record);
            out.write("CANDIDATE function=" + functionName(function)
                + " body=[" + rva(candidate.record.begin) + "," + rva(candidate.record.end)
                + ") score=" + candidate.score + " string_evidence=" + candidate.stringEvidence()
                + " callers=" + callers(function) + " callees=" + callees(function) + "\n");
            candidate.evidence.sort(Comparator.comparingLong(item -> item.instruction.getAddress().getOffset()));
            int evidenceCount = 0;
            for (Evidence evidence : candidate.evidence) {
                if (evidenceCount++ >= 12) break;
                out.write("  EVIDENCE kind=" + evidence.kind + " target=" + evidence.target.label
                    + " target_rva=" + rva(evidence.target.rva) + " confidence="
                    + (evidence.stringEvidence ? "high" : "medium") + " detail=" + evidence.detail + "\n");
                writeWindow(out, evidence);
            }
            if (decompile.size() < MAX_DECOMPILATIONS && candidate.stringEvidence()) decompile.add(function);
        }
        out.write("DECOMPILATIONS count=" + decompile.size() + " timeout_seconds_per_function=15\n");
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        for (Function function : decompile) {
            if (function == null) continue;
            DecompileResults result = decompiler.decompileFunction(function, 15, monitor);
            out.write("DECOMPILE function=" + functionName(function)
                + " complete=" + result.decompileCompleted() + "\n");
            if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                String[] lines = result.getDecompiledFunction().getC().split("\\R");
                for (int i = 0; i < Math.min(120, lines.length); i++) out.write("  " + lines[i] + "\n");
            } else out.write("  error=" + result.getErrorMessage() + "\n");
        }
        decompiler.dispose();
    }

    private String canonicalRegister(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("wzr")) return "xzr";
        if (lower.equals("xzr")) return "xzr";
        if (lower.startsWith("w") && lower.length() > 1) return "x" + lower.substring(1);
        return lower;
    }

    private Map<String, TraceValue> copyRegisters(Map<String, TraceValue> source) {
        return new HashMap<>(source);
    }

    private String trackedRegisters(Map<String, TraceValue> registers) {
        List<String> values = new ArrayList<>();
        List<String> keys = new ArrayList<>(registers.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            TraceValue value = registers.get(key);
            values.add(key + "=" + rva(value.value) + "(" + value.source + ")");
        }
        return values.toString();
    }

    private Map<Long, String> focusTargets() {
        Map<Long, String> targets = new LinkedHashMap<>();
        targets.put(0x4bdfbe8L, "VerifyWithCertStore");
        targets.put(0x4bdf960L, "AddServerCertificatesToCertStore");
        targets.put(0x4bdf9a0L, "AddTrustCertificatesToCertStore");
        targets.put(0x4bdfac8L, "VerifyWithGivenTrustCertificate[0]");
        targets.put(0x4bdfb10L, "VerifyWithGivenTrustCertificate[1]");
        targets.put(0x4bdfb60L, "VerifyWithGivenTrustCertificate[2]");
        targets.put(0x4bdfba8L, "VerifyWithGivenTrustCertificate[3]");
        targets.put(0x498b860L, "NetClient.cpp");
        targets.put(0x498bb50L, "Cert store init failed");
        targets.put(0x498cc10L, "PublicKeyEntry");
        targets.put(0x498b440L, "allowDevPublicKey");
        return targets;
    }

    private Set<Long> focusNetClientSlots() {
        return new LinkedHashSet<>(Arrays.asList(
            0x498bbf0L, 0x498bc60L, 0x498bd20L, 0x498bd70L, 0x498bdf0L,
            0x498bed0L, 0x498bfe0L, 0x498c080L, 0x498c180L, 0x498c210L,
            0x498c2a0L, 0x498c370L, 0x498c410L, 0x498c5b0L, 0x498c640L,
            0x498c6b0L, 0x498c720L
        ));
    }

    private boolean targetPresentInRdata(long targetRva, String marker) throws Exception {
        MemoryBlock rdata = null;
        for (MemoryBlock block : memory().getBlocks()) {
            if (".rdata".equals(block.getName())) { rdata = block; break; }
        }
        if (rdata == null || !inBlock(targetRva, 1, false)) return false;
        Address start = address(targetRva);
        Address end = start.add(0x200);
        if (end.compareTo(rdata.getEnd()) > 0) end = rdata.getEnd();
        return memory().findBytes(start, end, marker.getBytes(StandardCharsets.US_ASCII),
            null, true, monitor) != null;
    }

    private String referencesText(Instruction instruction) {
        List<String> refs = new ArrayList<>();
        for (Reference reference : currentProgram.getReferenceManager()
            .getReferencesFrom(instruction.getAddress())) {
            refs.add(rva(reference.getToAddress()) + ":" + reference.getReferenceType());
        }
        return refs.toString();
    }

    private long firstReferenceRva(Instruction instruction) {
        Reference[] refs = currentProgram.getReferenceManager()
            .getReferencesFrom(instruction.getAddress());
        return refs.length == 0 ? -1 : refs[0].getToAddress().getOffset() - base();
    }

    private String branchTargets(Instruction instruction) {
        List<String> targets = new ArrayList<>();
        for (Reference reference : currentProgram.getReferenceManager()
            .getReferencesFrom(instruction.getAddress())) {
            if (reference.getReferenceType().isJump())
                targets.add(rva(reference.getToAddress()));
        }
        return targets.toString();
    }

    private void traceFocusedArm64(BufferedWriter out, Record record) throws Exception {
        Map<Long, String> targets = focusTargets();
        Set<Long> netClientSlots = focusNetClientSlots();
        long windowStart = Math.max(record.begin, FOCUS_SEED_RVA - FOCUS_WINDOW_BYTES);
        long windowEnd = Math.min(record.end - 1, FOCUS_SEED_RVA + FOCUS_WINDOW_BYTES);
        Address seed = address(FOCUS_SEED_RVA);
        AddressSet restricted = new AddressSet(address(windowStart), address(windowEnd));
        // Seed the observed ADRP and every aligned address in this tiny window.
        // The latter is intentional: a flow-only seed stops at the ADRP in this
        // PE, while the requested bounded forward trace must distinguish real
        // instructions from an undefined suffix without widening the range.
        AddressSet startSet = new AddressSet(restricted);
        DisassembleCommand command = new DisassembleCommand(startSet, restricted, false);
        command.enableCodeAnalysis(false);
        boolean applied = command.applyTo(currentProgram, monitor);
        int supplementalSeeds = 0;
        int supplementalDecoded = 0;
        for (long p = FOCUS_SEED_RVA; p <= windowEnd && supplementalSeeds < FOCUS_MAX_INSTRUCTIONS; p += 4) {
            if (currentProgram.getListing().getInstructionAt(address(p)) != null) continue;
            supplementalSeeds++;
            AddressSet oneSeed = new AddressSet(address(p));
            DisassembleCommand one = new DisassembleCommand(oneSeed, restricted, false);
            one.enableCodeAnalysis(false);
            if (one.applyTo(currentProgram, monitor)
                && currentProgram.getListing().getInstructionAt(address(p)) != null) supplementalDecoded++;
        }
        Instruction seedInstruction = currentProgram.getListing().getInstructionAt(seed);
        out.write("FOCUS seed_rva=" + rva(FOCUS_SEED_RVA) + " body=[" + rva(record.begin)
            + "," + rva(record.end) + ") window=[" + rva(windowStart) + "," + rva(windowEnd + 1)
            + ") window_bytes=" + (windowEnd - windowStart + 1)
            + " max_forward_instructions=" + FOCUS_MAX_INSTRUCTIONS
            + " max_basic_blocks=" + FOCUS_MAX_BLOCKS + "\n");
        out.write("FOCUS_DISASSEMBLER applied=" + applied + " status=" + command.getStatusMsg()
            + " seed_in_start_set=true seed_defined=" + (seedInstruction != null)
            + " supplemental_seeds=" + supplementalSeeds + " supplemental_decoded=" + supplementalDecoded + "\n");
        for (Map.Entry<Long, String> target : targets.entrySet()) {
            out.write("RDATA_TARGET rva=" + rva(target.getKey()) + " label=" + target.getValue()
                + " present_at_expected_rva=" + targetPresentInRdata(target.getKey(),
                    target.getValue().startsWith("VerifyWithCertStore") ? "VerifyWithCertStore"
                    : target.getValue().startsWith("AddServer") ? "AddServerCertificatesToCertStore"
                    : target.getValue().startsWith("AddTrust") ? "AddTrustCertificatesToCertStore"
                    : target.getValue().startsWith("VerifyWithGiven") ? "VerifyWithGivenTrustCertificate"
                    : target.getValue().startsWith("NetClient") ? "NetClient.cpp"
                    : target.getValue()) + "\n");
        }
        if (seedInstruction == null) {
            out.write("DISPROOF reason=seed_not_decoded_at_exact_rva; no_consumer_claim\n");
            return;
        }
        String seedMnemonic = seedInstruction.getMnemonicString().toUpperCase(Locale.ROOT);
        long seedReference = firstReferenceRva(seedInstruction);
        long seedWord = u32(address(FOCUS_SEED_RVA));
        long rawSeedTarget = armTarget(FOCUS_SEED_RVA, seedWord);
        out.write("SEED mnemonic=" + seedMnemonic + " operands=" + seedInstruction
            + " refs=" + referencesText(seedInstruction) + " raw_word=" + String.format("0x%x", seedWord)
            + " raw_adrp_target_rva=" + rva(rawSeedTarget)
            + " raw_adrp_target_va=" + String.format("0x%x", base() + rawSeedTarget)
            + " ghidra_ref_rva=" + rva(seedReference)
            + " ghidra_operand_mismatch=" + (seedReference != rawSeedTarget) + "\n");
        if (!seedMnemonic.equals("ADRP") || rawSeedTarget != VERIFY_PAGE_RVA) {
            out.write("DISPROOF reason=seed_is_not_ADRP_to_VerifyWithCertStore_page"
                + " expected_page=" + rva(VERIFY_PAGE_RVA) + " observed_raw=" + rva(rawSeedTarget) + "\n");
            return;
        }

        java.util.ArrayDeque<TraceWork> work = new java.util.ArrayDeque<>();
        work.add(new TraceWork(FOCUS_SEED_RVA, new HashMap<>()));
        Set<Long> visited = new LinkedHashSet<>();
        int blockNumber = 0;
        int instructionCount = 0;
        boolean exactFormation = false;
        boolean registerClobberBeforeUse = false;
        boolean outsideBranch = false;
        List<String> stopReasons = new ArrayList<>();
        while (!work.isEmpty() && blockNumber < FOCUS_MAX_BLOCKS
            && instructionCount < FOCUS_MAX_INSTRUCTIONS) {
            TraceWork item = work.removeFirst();
            if (!visited.add(item.start)) continue;
            blockNumber++;
            Map<String, TraceValue> registers = copyRegisters(item.registers);
            long cursor = item.start;
            boolean stopBlock = false;
            out.write("BASIC_BLOCK number=" + blockNumber + " start=" + rva(cursor) + "\n");
            while (!stopBlock && cursor >= windowStart && cursor <= windowEnd
                && cursor >= record.begin && cursor < record.end
                && instructionCount < FOCUS_MAX_INSTRUCTIONS) {
                Instruction instruction = currentProgram.getListing().getInstructionAt(address(cursor));
                if (instruction == null) {
                    ghidra.program.model.listing.Data data = currentProgram.getListing()
                        .getDefinedDataAt(address(cursor));
                    out.write("  GAP rva=" + rva(cursor)
                        + " reason=Ghidra_disassembler_no_instruction_at_next_aligned_address"
                        + " listing_unit=" + (data == null ? "undefined" : "defined_data") + "\n");
                    stopReasons.add("Ghidra could not decode next aligned address " + rva(cursor));
                    break;
                }
                instructionCount++;
                String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
                String rendered = instruction.toString();
                int split = rendered.indexOf(' ');
                String operands = split < 0 ? "" : rendered.substring(split + 1);
                List<String> parsed = regs(operands);
                String rd = parsed.isEmpty() ? null : canonicalRegister(parsed.get(0));
                TraceValue beforeRd = rd == null ? null : registers.get(rd);
                String action = "none";
                Long effectiveAddress = null;
                InterestingOutcome outcome = new InterestingOutcome();
                long directReference = firstReferenceRva(instruction);
                long rawWord = u32(address(cursor));
                long rawAddressTarget = armTarget(cursor, rawWord);

                if (mnemonic.equals("ADRP") && rd != null) {
                    long adrpTarget = rawAddressTarget >= 0 ? rawAddressTarget : directReference;
                    if (adrpTarget >= 0) {
                        registers.remove("xzr".equals(rd) ? rd : "");
                        if (!rd.equals("xzr")) registers.put(rd,
                            new TraceValue(adrpTarget, "ADRP(raw)"));
                        action = "form_page=" + rva(adrpTarget)
                            + (directReference != adrpTarget ? ";ghidra_ref_mismatch" : "");
                    } else {
                        registers.remove(rd);
                        action = "clobber_no_reference";
                    }
                } else if ((mnemonic.equals("ADD") || mnemonic.equals("SUB")) && parsed.size() > 1
                    && rd != null) {
                    String rn = canonicalRegister(parsed.get(1));
                    Long imm = immediate(operands);
                    TraceValue source = registers.get(rn);
                    if (!rd.equals("xzr") && source != null && imm != null) {
                        long value = mnemonic.equals("ADD") ? source.value + imm : source.value - imm;
                        registers.put(rd, new TraceValue(value, mnemonic + "+immediate"));
                        effectiveAddress = value;
                        String label = targets.get(value);
                        long low12 = value - VERIFY_PAGE_RVA;
                        action = "form_address_from=" + rn + " low12_offset=" + rva(low12)
                            + " target=" + (label == null ? "none" : label);
                        if (label != null) { exactFormation = true; outcome.label = label; }
                    } else {
                        if (!rd.equals("xzr")) registers.remove(rd);
                        action = "clobber_ADD_invalid_source_or_immediate";
                        if (source == null && "x20".equals(rn)) registerClobberBeforeUse = true;
                    }
                } else if (mnemonic.equals("MOV") && parsed.size() > 1 && rd != null) {
                    String rn = canonicalRegister(parsed.get(1));
                    TraceValue source = registers.get(rn);
                    if (!rd.equals("xzr") && source != null) registers.put(rd, source);
                    else if (!rd.equals("xzr")) registers.remove(rd);
                    action = source == null ? "clobber_MOV_invalid_source" : "copy_from=" + rn;
                } else if (mnemonic.startsWith("LDR") || mnemonic.startsWith("LDUR")
                    || mnemonic.startsWith("STR") || mnemonic.startsWith("STUR")) {
                    int left = operands.indexOf('[');
                    int right = operands.indexOf(']');
                    if (left >= 0 && right > left) {
                        String memoryOperand = operands.substring(left + 1, right);
                        List<String> memoryRegisters = regs(memoryOperand);
                        Long imm = immediate(memoryOperand);
                        TraceValue source = memoryRegisters.isEmpty() ? null
                            : registers.get(canonicalRegister(memoryRegisters.get(0)));
                        if (source != null) {
                            effectiveAddress = source.value + (imm == null ? 0 : imm);
                            String label = targets.get(effectiveAddress);
                            boolean nearPage = Math.abs(effectiveAddress - VERIFY_PAGE_RVA) <= 0x1000;
                            if (label != null) {
                                exactFormation = true; outcome.label = label;
                                action = "exact_memory_consumer=" + label;
                            } else if (netClientSlots.contains(effectiveAddress)) {
                                action = nearPage ? "NetClient_slot_xref_follow_allowed"
                                    : "NetClient_slot_xref_not_followed_source_gt_4KiB";
                                if (nearPage && inBlock(effectiveAddress, 8, false)) {
                                    Long loaded = loadedQword(effectiveAddress);
                                    String loadedLabel = loaded == null ? null : targets.get(loaded - base());
                                    action += " loaded_target=" + (loadedLabel == null ? "none" : loadedLabel);
                                    if (loadedLabel != null) exactFormation = true;
                                }
                            } else if (memoryRegisters.size() > 1 && nearPage) {
                                action = "scaled_index_access_base_near_verify_page";
                            } else {
                                action = "memory_access_effective=" + rva(effectiveAddress);
                            }
                            if (mnemonic.startsWith("LDR") || mnemonic.startsWith("LDUR")) {
                                if (!rd.equals("xzr") && effectiveAddress != null && inBlock(effectiveAddress, 8, false)) {
                                    Long loaded = loadedQword(effectiveAddress);
                                    String loadedLabel = loaded == null ? null : targets.get(loaded - base());
                                    if (loadedLabel != null) {
                                        registers.put(rd, new TraceValue(loaded - base(), "LDR-pointer"));
                                        exactFormation = true; outcome.label = loadedLabel;
                                    } else if (!rd.equals("xzr")) registers.remove(rd);
                                } else if (!rd.equals("xzr")) registers.remove(rd);
                            }
                        } else {
                            if (rd != null && !rd.equals("xzr")) registers.remove(rd);
                            action = "clobber_memory_base_invalid";
                        }
                    }
                } else if (rd != null && !rd.equals("xzr")
                    && !instruction.getFlowType().isJump() && !instruction.getFlowType().isTerminal()) {
                    registers.remove(rd);
                    action = "register_clobber=" + rd;
                    if (rd.equals("x20")) registerClobberBeforeUse = true;
                }
                if (mnemonic.startsWith("BL") || instruction.getFlowType().isCall()) {
                    for (int i = 0; i <= 17; i++) registers.remove("x" + i);
                    action += "; call_clobber_model=x0-x17";
                }
                out.write("  INSN rva=" + rva(cursor) + " mnemonic=" + mnemonic
                    + " operands=" + operands + " refs=" + referencesText(instruction)
                    + " raw_word=" + String.format("0x%x", rawWord)
                    + (rawAddressTarget >= 0 ? " raw_address_target=" + rva(rawAddressTarget) : "")
                    + " rd=" + (rd == null ? "none" : rd)
                    + " before_rd=" + (beforeRd == null ? "invalid" : rva(beforeRd.value))
                    + " action=" + action + " after_tracked=" + trackedRegisters(registers)
                    + (outcome.label == null ? "" : " exact_target=" + outcome.label) + "\n");

                if (instruction.getFlowType().isJump() || instruction.getFlowType().isConditional()
                    || instruction.getFlowType().isTerminal()) {
                    List<Long> nextBlocks = new ArrayList<>();
                    for (Reference reference : currentProgram.getReferenceManager()
                        .getReferencesFrom(instruction.getAddress())) {
                        if (!reference.getReferenceType().isJump()) continue;
                        long target = reference.getToAddress().getOffset() - base();
                        nextBlocks.add(target);
                    }
                    if (instruction.getFlowType().isConditional()) nextBlocks.add(cursor + instruction.getLength());
                    for (long target : nextBlocks) {
                        if (target >= windowStart && target <= windowEnd
                            && target >= record.begin && target < record.end) {
                            work.add(new TraceWork(target, copyRegisters(registers)));
                        } else {
                            outsideBranch = true;
                            out.write("  BRANCH target=" + rva(target)
                                + " followed=false reason=outside_focus_window_or_pdata\n");
                        }
                    }
                    stopBlock = true;
                } else {
                    cursor += instruction.getLength();
                }
            }
        }
        out.write("FOCUS_SUMMARY blocks_followed=" + blockNumber + " instructions_followed="
            + instructionCount + " exact_pointer_or_memory_formation=" + exactFormation
            + " register_clobber_before_use=" + registerClobberBeforeUse
            + " outside_branch=" + outsideBranch + " visited_blocks=" + visited
            + " stop_reasons=" + stopReasons + "\n");
        if (exactFormation) {
            out.write("DISPOSITION confidence=medium; actionable_consumer=bounded_exact_pointer_or_memory_use\n");
        } else {
            out.write("DISPOSITION confidence=high_for_page_only_disproof; actionable_consumer=none; reason="
                + (registerClobberBeforeUse ? "x20_clobbered_or_invalid_before_exact_use"
                : outsideBranch ? "control_flow_left_bounded_window"
                : "no_ADD_or_LDR_exact_target_after_ADRP") + "\n");
        }
        out.write("DECOMPILATION skipped=true reason=no_credible_bound_and_control_flow; no_function_created\n");
        out.write("CALLER_LINKS skipped=true reason=no_credible_function_or_call_graph\n");
    }

    private static final class InterestingOutcome {
        String label;
    }

    private void exclusiveReport(Path path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) throw new IOException("report exceeds 400 KiB");
        try (var channel = java.nio.channels.FileChannel.open(path,
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2) throw new IllegalArgumentException(
            "expected CREATE_NEW report path and original source PE path");
        Path reportPath = Paths.get(getScriptArgs()[0]);
        Path sourcePath = Paths.get(getScriptArgs()[1]);
        HashState before = hash(sourcePath);
        StringBuilder buffer = new StringBuilder(120 * 1024);
        try (var writer = new StringWriterWriter(buffer)) {
            writer.write("TrustReferenceFlow schema=3 directed_prefilter_address_fixed=true\n");
            writer.write("program=" + currentProgram.getName() + " image_base=" + currentProgram.getImageBase()
                + " language=" + currentProgram.getLanguageID() + " compiler=" + currentProgram.getCompiler() + "\n");
            writer.write("source=" + sourcePath + " size=" + before.size + " sha256_before=" + before.sha256 + "\n");
            writer.write("scope=host-only read-only; bounded .pdata candidate prefilter only; no execution/client mutation/ADB/login/runtime/downloads/raw-key analysis\n");
            Coverage coverage = new Coverage();
            long nt = u32(address(0x3c));
            long optional = nt + 24;
            long relocRva = u32(address(optional + 112 + 5 * 8));
            long relocSize = u32(address(optional + 112 + 5 * 8 + 4));
            List<Record> records = recoverPdata(writer, coverage);
            List<Interesting> interests = locateInterests(writer);
            Map<Long, Integer> relocationMap = relocations(relocRva, relocSize);
            writer.write("RELOCATIONS directory_rva=" + rva(relocRva) + " size=" + relocSize
                + " parsed_entries=" + relocationMap.size() + "\n");
            List<DataAlias> aliasesFound = aliases(interests, relocationMap, writer);
            Map<Long, Interesting> interestMap = interestMap(interests, aliasesFound);
            writer.write("TARGETS\n");
            for (Interesting item : interestMap.values()) {
                writer.write("  rva=" + rva(item.rva) + " label=" + item.label
                    + " string=" + item.string + "\n");
            }
            List<Record> selectedRecords = prefilterRecords(records, interestMap, writer);
            AddressSet restricted = new AddressSet();
            for (Record record : selectedRecords) {
                restricted.add(address(record.begin), address(record.end - 1));
                coverage.analysisBodyBytes += record.end - record.begin;
            }
            coverage.disassemblyRanges = selectedRecords.size();
            writer.write("RESTRICTED_ADDRESS_SET ranges=" + restricted.getNumAddressRanges()
                + " addresses=" + restricted.getNumAddresses() + " no_flow_recursion_outside=true\n");
            boolean disassemblyApplied = false;
            String disassemblyStatus = "skipped_empty_evidence_set";
            if (!restricted.isEmpty()) {
                DisassembleCommand command = new DisassembleCommand(restricted, restricted, false);
                command.enableCodeAnalysis(false);
                disassemblyApplied = command.applyTo(currentProgram, monitor);
                disassemblyStatus = command.getStatusMsg();
            }
            writer.write("GHIDRA_DISASSEMBLER applied=" + disassemblyApplied
                + " status=" + disassemblyStatus + "\n");
            Map<String, Candidate> candidateMap = inspectInstructions(records, restricted,
                interestMap, coverage, writer);
            List<Candidate> candidates = new ArrayList<>(candidateMap.values());
            writer.write("STRING_XREF_CANDIDATE_FUNCTIONS=" + candidates.stream()
                .filter(Candidate::stringEvidence).count() + "\n");
            if (candidates.stream().noneMatch(Candidate::stringEvidence)) {
                writer.write("STRUCTURAL_FALLBACK=enabled; table consumers remain unresolved-role evidence, not public-key/crypto labels\n");
            }
            writeCandidates(writer, candidates);
            HashState after = hash(sourcePath);
            writer.write("source_after_size=" + after.size + " sha256_after=" + after.sha256
                + " mtime_before=" + before.mtime + " mtime_after=" + after.mtime
                + " stable=" + (before.size == after.size && before.mtime == after.mtime
                    && before.sha256.equals(after.sha256)) + "\n");
        }
        exclusiveReport(reportPath, buffer.toString());
        println("TrustReferenceFlow report written: " + reportPath + " bytes="
            + buffer.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    // Small append-only Writer adapter keeps report construction in memory so the
    // final artifact can be created with CREATE_NEW and fsynced exactly once.
    private static final class StringWriterWriter extends BufferedWriter {
        private final StringBuilder target;
        StringWriterWriter(StringBuilder target) { super(new OutputStreamWriter(java.io.OutputStream.nullOutputStream())); this.target = target; }
        @Override public void write(String value) { target.append(value); }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
