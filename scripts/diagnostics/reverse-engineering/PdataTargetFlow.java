// Bounded PE exception-metadata recovery probe.
// PE .pdata supplies metadata-derived function-body candidates, not universal proof
// of boundaries.  The bootstrap follow-up uses the restricted disassembler API;
// this older probe is retained for comparison and must not be read as a strict CFG.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.task.TaskMonitor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PdataTargetFlow extends GhidraScript {
    private static final long IMAGE_BASE = 0x140000000L;
    private static final long CODE_CANDIDATE_START = 0x3b8e1d8L;
    private static final long CODE_CANDIDATE_END = 0x3b8e278L;
    // These are only data-reference probes; they are not interpreted as keys.
    private static final long[] STRUCTURE_RVAS = { 80082408L, 80525912L, 79741832L };
    private static final String[] CERTSTORE_TERMS = {
        "NetClient::CertStore", "Cert store init failed", "PublicKeyEntry", "allowDevPublicKey"
    };

    private static final class Record {
        long begin;
        long end;
        long unwind;
        String source;
        String status;
        boolean valid;
        boolean chained;
        Record(long begin) { this.begin = begin; }
    }

    private static final class Coverage {
        long decoded;
        long bodyBytes;
        int invalid;
        int outOfBounds;
        int instructions;
        int calls;
        int branches;
        final List<String> callTargets = new ArrayList<>();
        final Set<Long> referencedData = new LinkedHashSet<>();
    }

    private Memory memory() { return currentProgram.getMemory(); }

    private Address address(long rva) {
        if (rva < 0 || rva > 0xffffffffL) return null;
        try { return currentProgram.getImageBase().add(rva); }
        catch (RuntimeException e) { return null; }
    }

    private long u32(Address a) throws Exception {
        return Integer.toUnsignedLong(memory().getInt(a));
    }

    private int u16(Address a) throws Exception {
        int lo = Byte.toUnsignedInt(memory().getByte(a));
        int hi = Byte.toUnsignedInt(memory().getByte(a.add(1)));
        return lo | (hi << 8);
    }

    private boolean span(long rva, long size, boolean execute) {
        if (size <= 0 || rva < 0 || rva > 0xffffffffL || size > 0x100000000L - rva) return false;
        Address start = address(rva);
        Address end = address(rva + size - 1);
        if (start == null || end == null) return false;
        MemoryBlock block = memory().getBlock(start);
        return block != null && block.contains(end) && block.isInitialized()
            && (!execute || block.isExecute());
    }

    private String rva(long value) { return String.format("0x%x", value); }

    private String addressText(long value) {
        Address a = address(value);
        return a == null ? "<invalid>" : a.toString();
    }

    private Address findDataString(String term) throws Exception {
        byte[] bytes = term.getBytes(StandardCharsets.US_ASCII);
        byte[] wideBytes = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) wideBytes[i * 2] = bytes[i];
        for (MemoryBlock block : memory().getBlocks()) {
            if (block.isExecute() || !block.isRead()) continue;
            Address narrow = memory().findBytes(block.getStart(), block.getEnd(), bytes,
                null, true, monitor);
            Address wide = memory().findBytes(block.getStart(), block.getEnd(), wideBytes,
                null, true, monitor);
            Address found = narrow;
            boolean isWide = wide != null && (found == null || wide.compareTo(found) < 0);
            if (isWide) found = wide;
            if (found != null) {
                Address start = found;
                while (start.compareTo(block.getStart()) > 0) {
                    if (!isWide) {
                        if (Byte.toUnsignedInt(memory().getByte(start.subtract(1))) == 0) break;
                        start = start.subtract(1);
                    } else {
                        if (start.compareTo(block.getStart().add(1)) <= 0
                            || Byte.toUnsignedInt(memory().getByte(start.subtract(1))) != 0
                            || Byte.toUnsignedInt(memory().getByte(start.subtract(2))) != 0) break;
                        start = start.subtract(2);
                    }
                }
                return start;
            }
        }
        return null;
    }

    private long optionalHeader(long nt) throws Exception { return nt + 24; }

    private List<Record> parseRecords(int machine, long exceptionRva, long exceptionSize,
                                      long textRva, long textSize, BufferedWriter out) throws Exception {
        List<Record> valid = new ArrayList<>();
        int recordSize = machine == 0xaa64 ? 8 : machine == 0x8664 ? 12 : 0;
        if (recordSize == 0 || exceptionSize < recordSize || exceptionSize % recordSize != 0) {
            out.write("PdataShape invalid_record_size machine=" + String.format("0x%x", machine)
                + " size=" + exceptionSize + " record_size=" + recordSize + "\n");
            return valid;
        }
        int total = 0;
        int invalid = 0;
        int chained = 0;
        Map<String, Integer> invalidReasons = new LinkedHashMap<>();
        long previousBegin = -1;
        for (long offset = 0; offset + recordSize <= exceptionSize && !monitor.isCancelled();
             offset += recordSize) {
            total++;
            Address entry = address(exceptionRva + offset);
            long begin = u32(entry);
            Record record = new Record(begin);
            if (machine == 0x8664) {
                record.end = u32(entry.add(4));
                record.unwind = u32(entry.add(8));
                record.source = "x64-runtime-function";
                if (record.unwind != 0 && span(record.unwind, 4, false)) {
                    int header = Byte.toUnsignedInt(memory().getByte(address(record.unwind)));
                    int version = header & 7;
                    int flags = (header >>> 3) & 7;
                    record.chained = (flags & 4) != 0;
                    if (record.chained) chained++;
                    if (version != 1) record.status = "unsupported_unwind_version_" + version;
                } else if (record.unwind != 0) {
                    record.status = "unwind_rva_out_of_initialized_memory";
                }
            } else {
                record.unwind = u32(entry.add(4));
                int flag = (int)(record.unwind & 3);
                if (flag == 0) {
                    record.source = "arm64-xdata";
                    long xdataRva = record.unwind & ~3L;
                    if (!span(xdataRva, 4, false)) {
                        record.status = "xdata_rva_out_of_initialized_memory";
                    } else {
                        long header = u32(address(xdataRva));
                        int version = (int)((header >>> 18) & 3);
                        long lengthWords = header & 0x3ffffL;
                        record.end = begin + lengthWords * 4L;
                        if (version != 0) record.status = "unsupported_xdata_version_" + version;
                    }
                } else if (flag == 1 || flag == 2) {
                    record.source = flag == 1 ? "arm64-packed" : "arm64-packed-fragment";
                    long lengthWords = (record.unwind >>> 2) & 0x7ffL;
                    record.end = begin + lengthWords * 4L;
                    record.chained = flag == 2;
                    if (record.chained) chained++;
                } else {
                    record.source = "arm64-reserved-flag";
                    record.status = "reserved_pdata_flag_3";
                }
            }
            boolean ordering = previousBegin < 0 || begin > previousBegin;
            boolean body = record.end > begin && record.end - begin <= textSize
                && begin >= textRva && record.end <= textRva + textSize;
            if (!ordering && record.status == null) record.status = "non_monotonic_begin_rva";
            if (!body && record.status == null) record.status = "function_body_out_of_text_bounds";
            if (record.status == null) {
                record.valid = true;
                valid.add(record);
            } else {
                invalid++;
                invalidReasons.merge(record.status == null ? "invalid" : record.status, 1, Integer::sum);
            }
            previousBegin = begin;
        }
        out.write("PdataSummary records=" + total + " valid=" + valid.size()
            + " invalid=" + invalid + " chained_or_fragment=" + chained
            + " record_size=" + recordSize + "\n");
        out.write("PdataInvalidReasons " + invalidReasons + "\n");
        return valid;
    }

    private List<Record> choose(List<Record> records, long entryRva, long textRva, long textSize,
                                Set<Long> stringAddresses) {
        Map<Long, Record> chosen = new LinkedHashMap<>();
        for (Record record : records) {
            if (record.begin == entryRva ||
                (record.begin < CODE_CANDIDATE_END && record.end > CODE_CANDIDATE_START)) {
                chosen.put(record.begin, record);
            }
        }
        int evenly = Math.min(8, records.size());
        for (int i = 0; i < evenly; i++) {
            int index = evenly == 1 ? 0 : (int)((long)i * (records.size() - 1) / (evenly - 1));
            Record record = records.get(index);
            chosen.put(record.begin, record);
        }
        // Reserve room for metadata functions that later prove to reference the
        // named strings or the previously reported structural data.
        for (Record record : records) {
            if (chosen.size() >= 64) break;
            for (long target : stringAddresses) {
                if (record.begin <= target && target < record.end) {
                    chosen.put(record.begin, record);
                    break;
                }
            }
        }
        List<Record> result = new ArrayList<>(chosen.values());
        result.sort(Comparator.comparingLong(r -> r.begin));
        return result.subList(0, Math.min(64, result.size()));
    }

    private String symbolName(long target) {
        SymbolTable symbols = currentProgram.getSymbolTable();
        Address targetAddress = currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(target);
        Symbol symbol = targetAddress == null ? null : symbols.getPrimarySymbol(targetAddress);
        return symbol == null ? "" : symbol.getName();
    }

    private Coverage decodeBody(Record record, boolean arm64, BufferedWriter out) throws Exception {
        Coverage coverage = new Coverage();
        coverage.bodyBytes = record.end - record.begin;
        Address start = address(record.begin);
        Address end = address(record.end);
        Address cursor = start;
        while (cursor != null && cursor.compareTo(end) < 0 && !monitor.isCancelled()) {
            boolean did = disassemble(cursor);
            Instruction instruction = currentProgram.getListing().getInstructionAt(cursor);
            if (!did || instruction == null) {
                coverage.invalid++;
                // ARM64 remains word-aligned after an invalid decode.  For x64,
                // advancing one byte would manufacture mid-instruction decodes;
                // stop instead and report the unresolved suffix as coverage loss.
                if (!arm64) break;
                cursor = cursor.add(4);
                continue;
            }
            long next = cursor.getOffset() + instruction.getLength();
            if (next > end.getOffset()) {
                coverage.outOfBounds++;
                break;
            }
            coverage.instructions++;
            coverage.decoded += instruction.getLength();
            if (instruction.getFlowType().isCall()) coverage.calls++;
            if (instruction.getFlowType().isJump() || instruction.getFlowType().isConditional()) coverage.branches++;
            Reference[] references = currentProgram.getReferenceManager()
                .getReferencesFrom(instruction.getAddress());
            for (Reference reference : references) {
                long target = reference.getToAddress().getOffset();
                coverage.referencedData.add(target);
                if (reference.getReferenceType().isCall() && coverage.callTargets.size() < 16) {
                    String name = symbolName(target);
                    coverage.callTargets.add(rva(target - currentProgram.getImageBase().getOffset())
                        + (name.isEmpty() ? "" : ":" + name));
                }
            }
            cursor = address(next - currentProgram.getImageBase().getOffset());
        }
        return coverage;
    }

    private String functionName(Function function) {
        return function == null ? "<none>" : function.getName() + "@" + rva(function.getEntryPoint().getOffset()
            - currentProgram.getImageBase().getOffset());
    }

    private void decompileCandidates(BufferedWriter out, List<Function> functions) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        int count = 0;
        for (Function function : functions) {
            if (count++ >= 8) break;
            DecompileResults result = decompiler.decompileFunction(function, 15, monitor);
            out.write("DECOMPILE function=" + functionName(function)
                + " complete=" + result.decompileCompleted() + "\n");
            if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                String[] lines = result.getDecompiledFunction().getC().split("\\R");
                for (int i = 0; i < Math.min(lines.length, 100); i++) out.write("  " + lines[i] + "\n");
            } else out.write("  error=" + result.getErrorMessage() + "\n");
        }
        decompiler.dispose();
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
        if (getScriptArgs().length != 1) throw new IllegalArgumentException("one CREATE_NEW report path required");
        if (currentProgram.getImageBase().getOffset() != IMAGE_BASE) {
            throw new IllegalStateException("unexpected image base " + currentProgram.getImageBase());
        }
        Path reportPath = Paths.get(getScriptArgs()[0]);
        FileChannel channel = openPrivateExclusive(reportPath);
        try (BufferedWriter out = new BufferedWriter(
            new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8))) {
            long base = currentProgram.getImageBase().getOffset();
            Address dos = address(0);
            long ntRva = u32(dos.add(0x3c));
            Address ntAddress = address(ntRva);
            long signature = u32(ntAddress);
            int machine = u16(ntAddress.add(4));
            int sections = u16(ntAddress.add(6));
            int optionalSize = u16(ntAddress.add(20));
            long optional = optionalHeader(ntRva);
            int magic = u16(address(optional));
            long entryRva = u32(address(optional + 16));
            long imageSize = u32(address(optional + 56));
            long dirs = u32(address(optional + 108));
            long exceptionRva = 0;
            long exceptionSize = 0;
            if (dirs > 3 && optionalSize >= 112 + 4 * 8) {
                exceptionRva = u32(address(optional + 112 + 3 * 8));
                exceptionSize = u32(address(optional + 112 + 3 * 8 + 4));
            }
            MemoryBlock text = null;
            for (MemoryBlock block : memory().getBlocks()) if (block.isExecute()) { text = block; break; }
            long textRva = text == null ? 0 : text.getStart().getOffset() - base;
            long textSize = text == null ? 0 : text.getSize();
            boolean headerValid = signature == 0x00004550L && magic == 0x20b
                && sections > 0 && imageSize > entryRva && text != null
                && span(exceptionRva, exceptionSize, false);
            out.write("program=" + currentProgram.getName() + "\n");
            out.write("image_base=" + currentProgram.getImageBase() + "\n");
            out.write("pe_signature=" + String.format("0x%x", signature) + " machine="
                + String.format("0x%x", machine) + " sections=" + sections + " optional_magic="
                + String.format("0x%x", magic) + "\n");
            out.write("entry_rva=" + rva(entryRva) + " exception_rva=" + rva(exceptionRva)
                + " exception_size=" + exceptionSize + " text_rva=" + rva(textRva)
                + " text_size=" + textSize + " header_bounds_valid=" + headerValid + "\n");
            if (!headerValid) throw new IllegalStateException("PE metadata bounds invalid");
            boolean arm64 = machine == 0xaa64;
            if (!arm64 && machine != 0x8664) throw new IllegalStateException("unsupported machine");
            Map<String, Address> strings = new LinkedHashMap<>();
            for (String term : CERTSTORE_TERMS) {
                Address found = findDataString(term);
                if (found != null) strings.put(term, found);
            }
            Set<Long> targets = new LinkedHashSet<>();
            for (Map.Entry<String, Address> string : strings.entrySet()) {
                long stringRva = string.getValue().getOffset() - base;
                targets.add(stringRva);
                out.write("CertstoreString term=" + string.getKey() + " rva=" + rva(stringRva)
                    + " address=" + string.getValue() + "\n");
            }
            for (long structure : STRUCTURE_RVAS) targets.add(structure);
            for (long structure : STRUCTURE_RVAS)
                out.write("StructuralProbeOnly rva=" + rva(structure) + " address="
                    + addressText(structure) + "\n");
            List<Record> records = parseRecords(machine, exceptionRva, exceptionSize, textRva, textSize, out);
            List<Record> selected = choose(records, entryRva, textRva, textSize, targets);
            out.write("SelectedFunctions count=" + selected.size() + " max=64\n");
            Map<Long, Function> functions = new LinkedHashMap<>();
            List<Function> decompile = new ArrayList<>();
            for (Record record : selected) {
                Coverage coverage = decodeBody(record, arm64, out);
                Function function = getFunctionAt(address(record.begin));
                if (function == null && coverage.instructions > 0) function = createFunction(address(record.begin), null);
                if (function != null) {
                    try { function.setBody(new AddressSet(address(record.begin), address(record.end - 1))); }
                    catch (Exception ignored) { }
                    functions.put(record.begin, function);
                    if (decompile.size() < 8) decompile.add(function);
                }
                Set<Long> namedRefs = new LinkedHashSet<>();
                for (long referenced : coverage.referencedData) namedRefs.add(referenced - base);
                namedRefs.retainAll(targets);
                out.write("FUNCTION_META begin=" + rva(record.begin) + " end=" + rva(record.end)
                    + " unwind=" + rva(record.unwind) + " source=" + record.source
                    + " chained_or_fragment=" + record.chained + " decoded_bytes=" + coverage.decoded
                    + " body_bytes=" + coverage.bodyBytes + " instructions=" + coverage.instructions
                    + " invalid_decode=" + coverage.invalid + " out_of_bounds=" + coverage.outOfBounds
                    + " calls=" + coverage.calls + " branches=" + coverage.branches
                    + " call_targets=" + coverage.callTargets + " target_refs=" + namedRefs + "\n");
            }
            out.write("GhidraFunctionsCreated count=" + functions.size() + "\n");
            decompileCandidates(out, decompile);
            out.flush();
            channel.force(true);
        } finally {
            channel.close();
        }
        println("PdataTargetFlow report written: " + reportPath);
    }
}
