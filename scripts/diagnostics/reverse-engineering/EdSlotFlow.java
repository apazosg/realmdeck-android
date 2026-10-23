// Bounded, read-only Ghidra follow-up for the identical 32-byte .rdata slots.
//
// This is deliberately not a key scanner and does not execute a client.  The PE
// is imported with -noanalysis.  Independent instruction decoding is used only
// to select small seeds; every reported code reference is then confirmed by a
// fresh Ghidra disassembly/reference lookup and its PCode.  Data aliases are
// limited to initialized .data/.rdata bytes and the selected .pdata bodies are
// limited before disassembly/decompilation.
//
// Usage:
//   analyzeHeadless <fresh-project-root> <project> -import <PE> -noanalysis
//     -max-cpu 2 -scriptPath <this-directory>
//     -postScript EdSlotFlow.java <CREATE_NEW-report> <source-PE>
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.disassemble.Disassembler;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
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

public class EdSlotFlow extends GhidraScript {
    private static final long IMAGE_BASE = 0x140000000L;
    private static final int ARM64 = 0xaa64;
    private static final int X64 = 0x8664;
    private static final long ARM_SLOT_RVA = 0x4a5ff00L;
    private static final long X64_SLOT_RVA = 0x48f6870L;
    private static final long ARM_SLOT_RAW = 0x4a5f100L;
    private static final long X64_SLOT_RAW = 0x48f5a70L;
    private static final int SLOT_BYTES = 32;
    private static final int NEIGHBORHOOD_RADIUS = 256;
    private static final int MAX_CONTEXT_BYTES = 2048;
    private static final long MAX_METADATA_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ALIASES = 64;
    private static final int MAX_SEEDS = 512;
    private static final int MAX_BODIES = 8;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_REPORT_BYTES = 512 * 1024;
    private static final Pattern ARM_REGISTER = Pattern.compile("\\b[xw][0-9]{1,2}\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ARM_IMMEDIATE = Pattern.compile("#(-?(?:0x[0-9a-fA-F]+|[0-9]+))");

    private static final String ARM_SHA256 =
        "d0dd56226b077466aee75d522ea87232f81f0e03bfa9add374d62c6b45fc1f59";
    private static final String X64_SHA256 =
        "5c00a0df416c5a94827617d9f11ad583273b0c109c7b543020edef1f8cb1fa92";

    private Memory memory() { return currentProgram.getMemory(); }
    private long base() { return currentProgram.getImageBase().getOffset(); }
    private Address address(long rva) { return currentProgram.getImageBase().add(rva); }
    private String hex(long value) { return String.format(Locale.ROOT, "0x%x", value); }
    private String rva(Address value) { return hex(value.getOffset() - base()); }

    private static final class Report {
        final StringBuilder text = new StringBuilder();
        boolean truncated;
        void line(String value) {
            if (truncated) return;
            String line = value + "\n";
            if (text.toString().getBytes(StandardCharsets.UTF_8).length
                    + line.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
                truncated = true;
                text.append("REPORT_TRUNCATED max_bytes=").append(MAX_REPORT_BYTES).append('\n');
                return;
            }
            text.append(line);
        }
    }

    private static final class SourceHash {
        final long size;
        final String sha256;
        final long mtime;
        SourceHash(long size, String sha256, long mtime) {
            this.size = size; this.sha256 = sha256; this.mtime = mtime;
        }
        boolean same(SourceHash other) {
            return other != null && size == other.size && mtime == other.mtime
                && sha256.equals(other.sha256);
        }
    }

    private static final class Record {
        long begin;
        long end;
        long unwind;
        String kind;
        String status;
        boolean valid() { return status == null && end > begin; }
    }

    private static final class Target {
        final long start;
        final long end;
        final String label;
        final String source;
        final boolean exactSlot;
        Target(long start, long end, String label, String source, boolean exactSlot) {
            this.start = start; this.end = end; this.label = label;
            this.source = source; this.exactSlot = exactSlot;
        }
        boolean contains(long value) { return value < end && value + 1 > start; }
    }

    private static final class Alias {
        final long source;
        final long target;
        final int width;
        Alias(long source, long target, int width) {
            this.source = source; this.target = target; this.width = width;
        }
    }

    private static final class Seed {
        final long rva;
        final long target;
        final String kind;
        final int width;
        final boolean pageOnly;
        Seed(long rva, long target, String kind, int width, boolean pageOnly) {
            this.rva = rva; this.target = target; this.kind = kind;
            this.width = width; this.pageOnly = pageOnly;
        }
        String key() { return rva + ":" + target + ":" + kind; }
    }

    private static final class Confirmed {
        final Seed seed;
        final long instructionRva;
        final long targetRva;
        final Target target;
        final Instruction instruction;
        final String kind;
        Confirmed(Seed seed, long instructionRva, long targetRva, Target target,
                  Instruction instruction, String kind) {
            this.seed = seed; this.instructionRva = instructionRva;
            this.targetRva = targetRva; this.target = target;
            this.instruction = instruction; this.kind = kind;
        }
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

    private SourceHash sourceHash(Path path) throws Exception {
        java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(path,
            java.nio.file.attribute.BasicFileAttributes.class);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count != 0) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return new SourceHash(attrs.size(), value.toString(), attrs.lastModifiedTime().toMillis());
    }

    private long[] directory(long nt, int index) throws Exception {
        long optional = nt + 24;
        return new long[] { u32(address(optional + 112 + index * 8L)),
            u32(address(optional + 112 + index * 8L + 4)) };
    }

    private Map<Long, Integer> relocations(long rva, long size, Report out) throws Exception {
        Map<Long, Integer> result = new HashMap<>();
        long bounded = Math.min(size, MAX_METADATA_BYTES);
        if (rva == 0 || bounded < 8 || !inBlock(rva, bounded, false)) {
            out.line("RELOCATIONS status=unavailable_or_out_of_bounds");
            return result;
        }
        long cursor = rva;
        long limit = rva + bounded;
        while (cursor + 8 <= limit) {
            long page = u32(address(cursor));
            long blockSize = u32(address(cursor + 4));
            if (blockSize < 8 || cursor + blockSize > limit) break;
            for (long item = cursor + 8; item + 2 <= cursor + blockSize; item += 2) {
                int raw = (int)u32(address(item)) & 0xffff;
                int type = raw >>> 12;
                if (type != 0) result.put(page + (raw & 0xfff), type);
            }
            cursor += blockSize;
        }
        out.line("RELOCATIONS rva=" + hex(rva) + " declared_bytes=" + size
            + " examined_bytes=" + bounded + " entries=" + result.size());
        return result;
    }

    private List<Record> parsePdata(Report out, int machine) throws Exception {
        long nt = u32(address(0x3c));
        long[] exception = directory(nt, 3);
        int recordSize = machine == ARM64 ? 8 : machine == X64 ? 12 : 0;
        long bounded = Math.min(exception[1], MAX_METADATA_BYTES);
        List<Record> result = new ArrayList<>();
        out.line("PDATA rva=" + hex(exception[0]) + " declared_bytes=" + exception[1]
            + " examined_bytes=" + bounded + " record_size=" + recordSize
            + " metadata_is_bounds_not_code_proof=true");
        if (recordSize == 0 || bounded < recordSize || bounded % recordSize != 0
            || !inBlock(exception[0], bounded, false)) return result;
        long previous = -1;
        for (long cursor = exception[0]; cursor + recordSize <= exception[0] + bounded;
             cursor += recordSize) {
            Record item = new Record();
            item.begin = u32(address(cursor));
            if (machine == X64) {
                item.end = u32(address(cursor + 4));
                item.unwind = u32(address(cursor + 8));
                item.kind = "x64-runtime-function";
                if (item.unwind != 0 && !inBlock(item.unwind, 4, false))
                    item.status = "unwind_out_of_bounds";
            } else {
                item.unwind = u32(address(cursor + 4));
                int flag = (int)(item.unwind & 3);
                if (flag == 0 && inBlock(item.unwind & ~3L, 4, false)) {
                    item.kind = "arm64-xdata";
                    long header = u32(address(item.unwind & ~3L));
                    item.end = item.begin + (header & 0x3ffffL) * 4L;
                } else if (flag == 1 || flag == 2) {
                    item.kind = flag == 1 ? "arm64-packed" : "arm64-packed-fragment";
                    item.end = item.begin + ((item.unwind >>> 2) & 0x7ffL) * 4L;
                } else item.status = "invalid_arm64_unwind";
            }
            if (item.status == null && previous >= 0 && item.begin <= previous)
                item.status = "non_monotonic_begin";
            if (item.status == null && !inBlock(item.begin, item.end - item.begin, true))
                item.status = "body_outside_executable_memory";
            if (item.valid()) result.add(item);
            previous = item.begin;
        }
        out.line("PDATA_VALID records=" + result.size() + " invalid_or_skipped="
            + (((exception[1] / recordSize) > result.size()) ? (exception[1] / recordSize - result.size()) : 0));
        return result;
    }

    private Record recordFor(List<Record> records, long rva) {
        for (Record item : records) if (item.begin <= rva && rva < item.end) return item;
        return null;
    }

    private long signExtend(long value, int bits) {
        long sign = 1L << (bits - 1);
        return (value ^ sign) - sign;
    }

    // immhi is bits 23:5 and immlo is bits 30:29; the two fields are joined
    // before sign extension.  The returned value is an RVA, not a file offset.
    private long armAddressTarget(long pcRva, long word) {
        long immhi = (word >>> 5) & 0x7ffffL;
        long immlo = (word >>> 29) & 3L;
        long imm21 = (immhi << 2) | immlo;
        if ((word & 0x9f000000L) == 0x10000000L)
            return pcRva + signExtend(imm21, 21);
        if ((word & 0x9f000000L) == 0x90000000L)
            return (pcRva & ~0xfffL) + signExtend(imm21, 21) * 0x1000L;
        if ((word & 0x3b000000L) == 0x18000000L)
            return pcRva + signExtend((word >>> 5) & 0x7ffffL, 19) * 4L;
        return -1;
    }

    private boolean armAdrp(long word) { return (word & 0x9f000000L) == 0x90000000L; }
    private boolean armLdrLiteral(long word) { return (word & 0x3b000000L) == 0x18000000L; }

    private Target targetFor(List<Target> targets, long value) {
        for (Target target : targets) if (target.contains(value)) return target;
        return null;
    }

    private boolean overlaps(long start, long end, long wantedStart, long wantedEnd) {
        return start < wantedEnd && end > wantedStart;
    }

    private List<Alias> aliases(long slot, long neighborhoodStart, long neighborhoodEnd,
                                Report out) throws Exception {
        List<Alias> result = new ArrayList<>();
        long examined = 0;
        boolean limit = false;
        for (MemoryBlock block : memory().getBlocks()) {
            if (!block.isInitialized() || !(block.getName().equals(".data")
                || block.getName().equals(".rdata"))) continue;
            long start = block.getStart().getOffset() - base();
            long end = block.getEnd().getOffset() - base() + 1;
            for (long cursor = start; cursor + 8 <= end; cursor += 8) {
                if (examined + 8 > MAX_METADATA_BYTES) { limit = true; break; }
                long value = u64(address(cursor));
                long target = value >= base() && value < base() + 0x100000000L
                    ? value - base() : value;
                examined += 8;
                if (!overlaps(target, target + 8, neighborhoodStart, neighborhoodEnd)) continue;
                if (cursor >= neighborhoodStart && cursor < neighborhoodEnd) continue;
                if (result.size() >= MAX_ALIASES) { limit = true; break; }
                result.add(new Alias(cursor, target, 8));
            }
            if (limit) break;
        }
        out.line("DATA_ALIAS_SCAN examined_bytes=" + examined + " limit=" + MAX_METADATA_BYTES
            + " limit_hit=" + limit + " aliases=" + result.size()
            + " rule=initialized_data_only_absolute_va_or_rva");
        for (Alias item : result) {
            out.line("DATA_ALIAS source_rva=" + hex(item.source) + " source_section="
                + section(item.source) + " target_neighborhood_offset=" + (item.target - slot)
                + " width=" + item.width + " exact_slot_target=false"
                + " role=descriptor-candidate target_bytes_suppressed=true");
        }
        return result;
    }

    private List<Target> targets(long slot, long neighborhoodStart, long neighborhoodEnd,
                                 List<Alias> aliases) {
        List<Target> result = new ArrayList<>();
        result.add(new Target(slot, slot + SLOT_BYTES, "exact-slot", hex(slot), true));
        result.add(new Target(neighborhoodStart, neighborhoodEnd, "slot-neighborhood",
            hex(slot), false));
        for (Alias alias : aliases)
            result.add(new Target(alias.source, alias.source + alias.width,
                "descriptor-alias", hex(alias.target), false));
        return result;
    }

    private void addSeed(Map<String, Seed> seeds, Seed seed) {
        if (seeds.size() < MAX_SEEDS || seeds.containsKey(seed.key())) seeds.put(seed.key(), seed);
    }

    private List<Seed> rawSeeds(int machine, long neighborhoodStart, long neighborhoodEnd,
                                List<Target> targets, Report out) throws Exception {
        Map<String, Seed> found = new LinkedHashMap<>();
        long scanned = 0;
        for (MemoryBlock block : memory().getBlocks()) {
            if (!block.isExecute() || !block.isInitialized()) continue;
            long start = block.getStart().getOffset() - base();
            long end = block.getEnd().getOffset() - base() + 1;
            if (machine == ARM64) {
                for (long pc = start; pc + 4 <= end; pc += 4) {
                    long word = u32(address(pc)); scanned += 4;
                    long target = armAddressTarget(pc, word);
                    if (target < 0) continue;
                    boolean page = armAdrp(word);
                    if (page ? pageIntersectsAnyTarget(target, targets)
                             : targetFor(targets, target) != null) {
                        Target match = targetFor(targets, target);
                        addSeed(found, new Seed(pc, target, page ? "ADRP-page-anchor" :
                            armLdrLiteral(word) ? "LDR-literal" : "ADR", 8, page && match == null));
                    }
                }
            } else if (machine == X64) {
                for (long pc = start; pc + 7 <= end; pc++) {
                    long[] decoded = x64RipTarget(pc, end);
                    scanned++;
                    if (decoded == null) continue;
                    Target match = targetFor(targets, decoded[0]);
                    if (match != null) addSeed(found, new Seed(pc, decoded[0],
                        decoded[1] == 1 ? "RIP-data-load" : decoded[1] == 2 ? "RIP-data-store" :
                        "RIP-address-formation", (int)decoded[2], false));
                }
            }
        }
        out.line("RAW_SEEDS architecture=" + (machine == ARM64 ? "ARM64" : "x64")
            + " executable_bytes_examined=" + scanned + " candidates=" + found.size()
            + " independent_selector=true confirmation_required=true");
        for (Seed seed : found.values()) if (seed.pageOnly)
            out.line("ARM_PAGE_SEED instruction_rva=" + hex(seed.rva)
                + " target_page_rva=" + hex(seed.target) + " selector=page_intersects_any_fixed_target"
                + " confirmation=actual_ghidra_function_body_add_ldr_required");
        return new ArrayList<>(found.values());
    }

    private boolean pageIntersectsAnyTarget(long page, List<Target> targets) {
        long[][] intervals = new long[targets.size()][2];
        for (int i = 0; i < targets.size(); i++) {
            intervals[i][0] = targets.get(i).start;
            intervals[i][1] = targets.get(i).end;
        }
        return EdSlotArmSelector.pageIntersectsAnyTarget(page, intervals);
    }

    // Returns target RVA, operation (0=formation, 1=load, 2=store), width.
    private long[] x64RipTarget(long pc, long blockEnd) throws Exception {
        int available = (int)Math.min(15, blockEnd - pc);
        if (available <= 0) return null;
        byte[] bytes = new byte[available];
        for (int i = 0; i < available; i++) bytes[i] = memory().getByte(address(pc + i));
        EdSlotX64Decoder.Decoded decoded = EdSlotX64Decoder.decode(bytes, pc);
        return decoded == null ? null : new long[] { decoded.targetRva, decoded.operation, decoded.width };
    }

    private AddressSet boundedSet(long start, long bytes) {
        return new AddressSet(address(start), address(start + bytes - 1));
    }

    private List<String> armRegisters(String operands) {
        List<String> result = new ArrayList<>();
        Matcher matcher = ARM_REGISTER.matcher(operands);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return result;
    }

    private Long armImmediate(String operands) {
        Matcher matcher = ARM_IMMEDIATE.matcher(operands);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        boolean negative = value.startsWith("-");
        if (negative) value = value.substring(1);
        try {
            long parsed = value.startsWith("0x")
                ? Long.parseUnsignedLong(value.substring(2), 16) : Long.parseLong(value);
            return negative ? -parsed : parsed;
        } catch (NumberFormatException ignored) { return null; }
    }

    private void traceArmPageSeed(Seed seed, List<Target> targets, List<Record> records,
                                  List<Confirmed> confirmed, Report out) {
        Record body = recordFor(records, seed.rva);
        if (body == null) {
            out.line("ARM_PAGE_TRACE seed_rva=" + hex(seed.rva)
                + " actual_function_body=false status=no_pdata_body_no_consumer_claim=true");
            return;
        }
        long end = Math.min(body.end, seed.rva + 64);
        if (end - seed.rva < 4) return;
        InstructionIterator iterator = currentProgram.getListing().getInstructions(
            boundedSet(seed.rva, end - seed.rva), true);
        String tracked = null;
        long trackedValue = seed.target & ~0xfffL;
        boolean sawAdrp = false;
        while (iterator.hasNext()) {
            Instruction item = iterator.next();
            long itemRva = item.getAddress().getOffset() - base();
            if (itemRva < seed.rva || itemRva >= end || itemRva < body.begin || itemRva >= body.end)
                continue;
            String mnemonic = item.getMnemonicString().toUpperCase(Locale.ROOT);
            String text = item.toString();
            List<String> registers = armRegisters(text);
            if (!sawAdrp) {
                if (!mnemonic.equals("ADRP") || registers.isEmpty()) {
                    out.line("ARM_PAGE_TRACE seed_rva=" + hex(seed.rva)
                        + " actual_function_body=true status=seed_not_actual_adrp_no_consumer_claim=true");
                    return;
                }
                tracked = registers.get(0);
                sawAdrp = true;
                out.line("ARM_FLOW_PCODE instruction_rva=" + hex(itemRva) + " role=ADRP"
                    + " function_body=[" + hex(body.begin) + "," + hex(body.end) + ")");
                for (PcodeOp op : item.getPcode()) out.line("  op=" + op);
                continue;
            }
            if (mnemonic.equals("ADD") && registers.size() >= 2
                && registers.get(0).equals(tracked) && registers.get(1).equals(tracked)) {
                Long immediate = armImmediate(text);
                if (immediate == null) {
                    out.line("ARM_FLOW_PCODE instruction_rva=" + hex(itemRva)
                        + " role=ADD indexed_or_register_offset=unresolved");
                    break;
                }
                trackedValue += immediate;
                out.line("ARM_FLOW_PCODE instruction_rva=" + hex(itemRva) + " role=ADD"
                    + " resolved_base_rva=" + hex(trackedValue));
                for (PcodeOp op : item.getPcode()) out.line("  op=" + op);
                continue;
            }
            if ((mnemonic.startsWith("LDR") || mnemonic.startsWith("LDUR")
                    || mnemonic.startsWith("STR") || mnemonic.startsWith("STUR"))
                && text.contains("[" + tracked)) {
                int open = text.indexOf('[' + tracked);
                int close = text.indexOf(']', open);
                String memoryOperand = close > open ? text.substring(open, close) : "";
                Long immediate = armImmediate(memoryOperand);
                boolean registerIndex = memoryOperand.indexOf(',') >= 0
                    && (immediate == null || memoryOperand.indexOf('#') < 0);
                out.line("ARM_FLOW_PCODE instruction_rva=" + hex(itemRva) + " role=" + mnemonic
                    + " actual_function_body=true indexed_or_register_offset=" + registerIndex);
                for (PcodeOp op : item.getPcode()) out.line("  op=" + op);
                if (registerIndex || immediate == null) {
                    out.line("ARM_FLOW_UNRESOLVED instruction_rva=" + hex(itemRva)
                        + " reason=nonconstant_effective_address_no_consumer_claim=true");
                } else {
                    long effective = trackedValue + immediate;
                    Target target = targetFor(targets, effective);
                    if (target != null) {
                        out.line("ARM_FLOW_REFERENCE instruction_rva=" + hex(itemRva)
                            + " target_rva=" + hex(effective) + " label=" + target.label
                            + " actual_ghidra_instruction=true actual_pcode=true"
                            + " function_body=[" + hex(body.begin) + "," + hex(body.end) + ")");
                        confirmed.add(new Confirmed(seed, itemRva, effective, target, item,
                            "ARM-ADRP-ADD-LDR-or-STR"));
                    }
                }
                if (mnemonic.startsWith("LDR") || mnemonic.startsWith("LDUR")) continue;
            }
            if (mnemonic.startsWith("B") || mnemonic.equals("RET") || mnemonic.equals("BR")) break;
            if (!registers.isEmpty() && registers.get(0).equals(tracked)
                && !mnemonic.equals("NOP")) break;
        }
        if (!sawAdrp) out.line("ARM_PAGE_TRACE seed_rva=" + hex(seed.rva)
            + " actual_function_body=true status=no_trace=no_consumer_claim=true");
    }

    private List<Confirmed> confirmSeeds(List<Seed> seeds, List<Target> targets,
                                         List<Record> records, Report out) throws Exception {
        List<Confirmed> confirmed = new ArrayList<>();
        Disassembler disassembler = Disassembler.getDisassembler(currentProgram, monitor, null);
        int attempted = 0;
        for (Seed seed : seeds) {
            if (attempted++ >= MAX_SEEDS) break;
            if (!inBlock(seed.rva, 4, true)) continue;
            Address start = address(seed.rva);
            Record seedBody = recordFor(records, seed.rva);
            long disassemblyBytes = seedBody == null ? 64 : Math.min(64, seedBody.end - seed.rva);
            if (disassemblyBytes < 4) continue;
            disassembler.disassemble(start, boundedSet(seed.rva, disassemblyBytes));
            Instruction instruction = currentProgram.getListing().getInstructionAt(start);
            if (instruction == null || instruction.getMinAddress().getOffset() != start.getOffset()) continue;
            if (seed.pageOnly && seed.kind.equals("ADRP-page-anchor"))
                traceArmPageSeed(seed, targets, records, confirmed, out);
            for (Reference reference : currentProgram.getReferenceManager()
                    .getReferencesFrom(instruction.getAddress())) {
                long targetRva = reference.getToAddress().getOffset() - base();
                Target target = targetFor(targets, targetRva);
                if (target == null) continue;
                String kind = target.label;
                confirmed.add(new Confirmed(seed, seed.rva, targetRva, target, instruction, kind));
                out.line("GHIDRA_REFERENCE instruction_rva=" + hex(seed.rva)
                    + " reference_target_rva=" + hex(targetRva) + " section=" + section(targetRva)
                    + " ref_type=" + reference.getReferenceType() + " seed=" + seed.kind
                    + " classification=" + kind + " actual_instruction=true");
            }
            // ADRP/ADD/LDR and descriptor copies often have no direct reference
            // on the final load.  Preserve the actual Ghidra instruction/PCode
            // window so that the remaining edge is visible without guessing it.
            long contextBytes = seedBody == null ? MAX_CONTEXT_BYTES
                : Math.min(MAX_CONTEXT_BYTES, seedBody.end - seed.rva);
            if (contextBytes < 4) continue;
            InstructionIterator iterator = currentProgram.getListing().getInstructions(
                boundedSet(seed.rva, contextBytes), true);
            int emitted = 0;
            while (iterator.hasNext() && emitted++ < 32) {
                Instruction item = iterator.next();
                if (item.getAddress().getOffset() < start.getOffset()
                    || item.getAddress().getOffset() >= start.getOffset() + contextBytes) continue;
                for (PcodeOp op : item.getPcode()) {
                    if (op.getOpcode() == PcodeOp.LOAD || op.getOpcode() == PcodeOp.STORE
                        || op.getOpcode() == PcodeOp.CALL || op.getOpcode() == PcodeOp.CALLIND
                        || op.getOpcode() == PcodeOp.BRANCHIND) {
                        out.line("SEED_PCODE instruction_rva=" + rva(item.getAddress())
                            + " seed_rva=" + hex(seed.rva) + " op=" + op);
                    }
                }
            }
        }
        out.line("GHIDRA_CONFIRMATION attempted_seeds=" + attempted
            + " confirmed_references=" + confirmed.size()
            + " direct_reference_manager=true no_raw_slot_bytes=true");
        return confirmed;
    }

    private String functionName(Function function) {
        return function == null ? "<none>" : function.getName() + "@" + rva(function.getEntryPoint());
    }

    private String callers(Function function) {
        if (function == null) return "[]";
        List<String> result = new ArrayList<>();
        ReferenceIterator iterator = currentProgram.getReferenceManager()
            .getReferencesTo(function.getEntryPoint());
        while (iterator.hasNext() && result.size() < 16) {
            Reference item = iterator.next();
            result.add(rva(item.getFromAddress()) + ":" + item.getReferenceType());
        }
        return result.toString();
    }

    private String callees(Function function) {
        if (function == null) return "[]";
        Set<String> result = new LinkedHashSet<>();
        InstructionIterator iterator = currentProgram.getListing().getInstructions(function.getBody(), true);
        while (iterator.hasNext()) {
            for (Reference item : currentProgram.getReferenceManager()
                    .getReferencesFrom(iterator.next().getAddress())) {
                if (item.getReferenceType().isCall()) result.add(rva(item.getToAddress()));
            }
        }
        return result.toString();
    }

    private Function selectedFunction(Record record, Report out) throws Exception {
        if (record == null || record.end - record.begin > MAX_BODY_BYTES) return null;
        Address start = address(record.begin);
        AddressSet body = boundedSet(record.begin, record.end - record.begin);
        Disassembler disassembler = Disassembler.getDisassembler(currentProgram, monitor, null);
        disassembler.disassemble(start, body);
        Function function = currentProgram.getFunctionManager().getFunctionAt(start);
        if (function == null) function = createFunction(start, null);
        if (function != null) function.setBody(body);
        out.line("FUNCTION_BODY begin_rva=" + hex(record.begin) + " end_rva=" + hex(record.end)
            + " bytes=" + (record.end - record.begin) + " pdata_kind=" + record.kind
            + " function=" + functionName(function) + " disassembled_in_bounded_body=true");
        return function;
    }

    private void bodyEvidence(Function function, List<Target> targets, Report out) {
        if (function == null) return;
        InstructionIterator iterator = currentProgram.getListing().getInstructions(function.getBody(), true);
        while (iterator.hasNext()) {
            Instruction instruction = iterator.next();
            List<Reference> refs = new ArrayList<>();
            for (Reference reference : currentProgram.getReferenceManager()
                    .getReferencesFrom(instruction.getAddress())) {
                long targetRva = reference.getToAddress().getOffset() - base();
                if (targetFor(targets, targetRva) != null) refs.add(reference);
            }
            if (refs.isEmpty()) continue;
            out.line("CONSUMER_INSN instruction_rva=" + rva(instruction.getAddress())
                + " mnemonic=" + instruction.getMnemonicString() + " operands=" + instruction
                + " function=" + functionName(function));
            for (Reference reference : refs) {
                long targetRva = reference.getToAddress().getOffset() - base();
                Target target = targetFor(targets, targetRva);
                out.line("  CONSUMER_REFERENCE target_rva=" + hex(targetRva) + " section="
                    + section(targetRva) + " label=" + target.label + " ref_type="
                    + reference.getReferenceType());
            }
            for (PcodeOp op : instruction.getPcode())
                out.line("  CONSUMER_PCODE instruction_rva=" + rva(instruction.getAddress()) + " op=" + op);
        }
    }

    private void decompile(Function function, DecompInterface decompiler, Report out) throws Exception {
        if (function == null) return;
        DecompileResults result = decompiler.decompileFunction(function, 15, monitor);
        out.line("DECOMPILE function=" + functionName(function) + " timeout_seconds=15 complete="
            + result.decompileCompleted() + " callers=" + callers(function)
            + " callees=" + callees(function));
        if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
            String[] lines = result.getDecompiledFunction().getC().split("\\R");
            for (int i = 0; i < Math.min(lines.length, 300); i++) out.line("  " + lines[i]);
            if (lines.length > 300) out.line("  DECOMPILED_OUTPUT_TRUNCATED lines=" + lines.length);
        } else out.line("  error=" + result.getErrorMessage());
    }

    private FileChannel exclusive(Path path) throws Exception {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
        return channel;
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 2)
            throw new IllegalArgumentException("expected CREATE_NEW report path and source PE path");
        if (base() != IMAGE_BASE) throw new IllegalStateException("unexpected image base " + hex(base()));
        Path reportPath = Paths.get(getScriptArgs()[0]);
        Path sourcePe = Paths.get(getScriptArgs()[1]);
        Report out = new Report();
        SourceHash before = sourceHash(sourcePe);
        long nt = u32(address(0x3c));
        int machine = (int)(u32(address(nt + 4)) & 0xffff);
        long slot = machine == ARM64 ? ARM_SLOT_RVA : machine == X64 ? X64_SLOT_RVA : -1;
        long rawSlot = machine == ARM64 ? ARM_SLOT_RAW : machine == X64 ? X64_SLOT_RAW : -1;
        String expected = machine == ARM64 ? ARM_SHA256 : machine == X64 ? X64_SHA256 : "";
        if ((machine != ARM64 && machine != X64) || before.size <= 0)
            throw new IllegalStateException("unsupported PE machine or empty source");
        out.line("EdSlotFlow version=1 mode=host-only-read-only");
        out.line("constraints=no-client-execution no-ADB no-login no-process-memory no-PE-write"
            + " no-raw-32-byte-slot-output");
        out.line("SOURCE path=" + sourcePe + " size=" + before.size + " sha256_before=" + before.sha256
            + " expected_sha256=" + expected + " sha_match=" + before.sha256.equals(expected)
            + " machine=" + hex(machine) + " image_base=" + hex(base()));
        if (!before.sha256.equals(expected)) throw new IllegalStateException("source SHA-256 mismatch");
        if (machine == ARM64 && currentProgram.getLanguageID().toString().toLowerCase(Locale.ROOT).indexOf("aarch64") < 0)
            throw new IllegalStateException("ARM64 source imported with non-ARM64 language");
        if (machine == X64 && currentProgram.getLanguageID().toString().toLowerCase(Locale.ROOT).indexOf("x86") < 0)
            throw new IllegalStateException("x64 source imported with non-x86 language");
        long slotRawFromSections = -1;
        for (MemoryBlock block : memory().getBlocks()) {
            long start = block.getStart().getOffset() - base();
            if (start <= slot && slot + SLOT_BYTES - 1 <= block.getEnd().getOffset() - base()) {
                slotRawFromSections = rawSlot;
                out.line("SLOT rva=" + hex(slot) + " raw_offset=" + hex(rawSlot)
                    + " length=32 section=" + block.getName() + " exact_locator=true");
            }
        }
        if (slotRawFromSections < 0 || !inBlock(slot, SLOT_BYTES, false)
            || !".rdata".equals(section(slot))) throw new IllegalStateException("slot locator mismatch");
        long neighborhoodStart = Math.max(0, slot - NEIGHBORHOOD_RADIUS);
        long neighborhoodEnd = slot + SLOT_BYTES + NEIGHBORHOOD_RADIUS;
        out.line("DATA_NEIGHBORHOOD start_rva=" + hex(neighborhoodStart) + " end_rva="
            + hex(neighborhoodEnd) + " bytes=" + (neighborhoodEnd - neighborhoodStart)
            + " requested_radius=256 expanded_context_limit=" + MAX_CONTEXT_BYTES);
        long[] relocationDirectory = directory(nt, 5);
        Map<Long, Integer> relocationMap = relocations(relocationDirectory[0], relocationDirectory[1], out);
        int slotRelocations = 0;
        for (int i = 0; i < SLOT_BYTES; i++) if (relocationMap.containsKey(slot + i)) slotRelocations++;
        out.line("SLOT_RELOCATION_OVERLAP entries=" + slotRelocations + " expected_no_overlap=true");
        List<Alias> aliases = aliases(slot, neighborhoodStart, neighborhoodEnd, out);
        List<Target> targets = targets(slot, neighborhoodStart, neighborhoodEnd, aliases);
        List<Record> records = parsePdata(out, machine);
        List<Seed> seeds = rawSeeds(machine, neighborhoodStart, neighborhoodEnd, targets, out);
        List<Confirmed> confirmed = confirmSeeds(seeds, targets, records, out);

        Map<Record, Integer> scores = new LinkedHashMap<>();
        for (Confirmed item : confirmed) {
            Record record = recordFor(records, item.instructionRva);
            if (record != null) scores.put(record, scores.getOrDefault(record, 0) +
                (item.target.exactSlot ? 10 : item.target.label.equals("descriptor-alias") ? 5 : 2));
        }
        List<Record> selected = new ArrayList<>(scores.keySet());
        selected.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        int skippedLarge = 0;
        List<Function> functions = new ArrayList<>();
        for (Record record : selected) {
            if (functions.size() >= MAX_BODIES) break;
            if (record.end - record.begin > MAX_BODY_BYTES) { skippedLarge++; continue; }
            Function function = selectedFunction(record, out);
            if (function != null) {
                functions.add(function);
                bodyEvidence(function, targets, out);
            }
        }
        out.line("BODY_SELECTION candidate_functions=" + selected.size() + " selected=" + functions.size()
            + " max_bodies=" + MAX_BODIES + " max_body_bytes=" + MAX_BODY_BYTES
            + " skipped_large_bodies=" + skippedLarge);
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        try { for (Function function : functions) decompile(function, decompiler, out); }
        finally { decompiler.dispose(); }
        SourceHash after = sourceHash(sourcePe);
        out.line("SOURCE sha256_after=" + after.sha256 + " size_after=" + after.size
            + " stable=" + before.same(after));
        if (!before.same(after)) throw new IllegalStateException("source changed during read-only analysis");
        out.line("ASSESSMENT exact_or_neighborhood_consumer_proof=" + (!confirmed.isEmpty())
            + " selected_function_count=" + functions.size()
            + " auth_link=" + (confirmed.isEmpty() ? "not_proven" : "not_identified_from_bounded_slot_flow")
            + " group8_selection=" + "not_proven"
            + " init_timing=" + "not_proven_static_only"
            + " arm64_x64_equivalence=" + "not_inferred_same_rva_forbidden");
        out.line("LIMITATIONS remaining_edge=indirect_or_runtime_edges_not_reached_by_exact_neighborhood_aliases_or_selected_pdata_bodies"
            + " no_function_identifiable_is_not_packed_or_absent_claim=true");
        if (out.truncated) throw new IOException("report exceeded bounded 512 KiB");
        FileChannel channel = exclusive(reportPath);
        try { channel.write(ByteBuffer.wrap(out.text.toString().getBytes(StandardCharsets.UTF_8))); channel.force(true); }
        finally { channel.close(); }
        println("EdSlotFlow report written: " + reportPath);
    }
}

// Standalone production decoder used by EdSlotFlow.x64RipTarget and extracted by
// the host synthetic tests.  Keeping the bounded byte[] implementation separate
// from Ghidra's Memory API makes the selector testable without a client or a
// Ghidra project, while the production wrapper still calls this exact code.
final class EdSlotX64Decoder {
    static final class Decoded {
        final long targetRva;
        final int operation;
        final int width;
        final int length;
        Decoded(long targetRva, int operation, int width, int length) {
            this.targetRva = targetRva; this.operation = operation;
            this.width = width; this.length = length;
        }
    }

    static Decoded decode(byte[] bytes, long pcRva) {
        if (bytes == null || bytes.length < 3) return null;
        int cursor = 0;
        boolean operand16 = false;
        int rex = 0;
        while (cursor < bytes.length) {
            int value = bytes[cursor] & 0xff;
            if (value == 0x66) { operand16 = true; cursor++; continue; }
            if (value == 0xf2 || value == 0xf3) { cursor++; continue; }
            if ((value & 0xf0) == 0x40) { rex = value; cursor++; }
            break;
        }
        if (cursor >= bytes.length) return null;
        int opcode = bytes[cursor] & 0xff;
        int opcodeLength = 1;
        int operation;
        int width;
        if (opcode == 0x0f) {
            if (cursor + 1 >= bytes.length) return null;
            int second = bytes[cursor + 1] & 0xff;
            if (second != 0x10 && second != 0x11 && second != 0x6f && second != 0x7f)
                return null;
            opcodeLength = 2;
            operation = (second == 0x11 || second == 0x7f) ? 2 : 1;
            width = 16;
        } else {
            if (opcode != 0x8a && opcode != 0x88 && opcode != 0x8b
                && opcode != 0x89 && opcode != 0x8d) return null;
            operation = opcode == 0x8d ? 0 : (opcode == 0x88 || opcode == 0x89 ? 2 : 1);
            if (opcode == 0x8a || opcode == 0x88) width = 1;
            else if (opcode == 0x8d) width = (rex & 8) != 0 ? 8 : 4;
            else width = (rex & 8) != 0 ? 8 : operand16 ? 2 : 4;
        }
        // For one-byte opcodes this is cursor+1; for 0F opcodes cursor+2.
        // It must not include an extra byte before ModRM.
        int modrmAt = cursor + opcodeLength;
        if (modrmAt >= bytes.length) return null;
        int modrm = bytes[modrmAt] & 0xff;
        if ((modrm & 0xc7) != 0x05) return null;
        int displacementAt = modrmAt + 1;
        if (displacementAt + 4 > bytes.length) return null;
        int displacement = (bytes[displacementAt] & 0xff)
            | ((bytes[displacementAt + 1] & 0xff) << 8)
            | ((bytes[displacementAt + 2] & 0xff) << 16)
            | (bytes[displacementAt + 3] << 24);
        int length = displacementAt + 4;
        return new Decoded(pcRva + length + displacement, operation, width, length);
    }
}

final class EdSlotArmSelector {
    static boolean pageIntersectsAnyTarget(long page, long[][] intervals) {
        long pageEnd = page + 0x1000;
        for (long[] interval : intervals)
            if (interval != null && interval.length >= 2
                && page < interval[1] && pageEnd > interval[0]) return true;
        return false;
    }

    static boolean exactSlotReference(long target, long slotStart, long slotEnd) {
        return target >= slotStart && target < slotEnd;
    }
}
