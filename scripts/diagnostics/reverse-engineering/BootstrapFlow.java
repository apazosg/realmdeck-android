// Narrow, read-only PE TLS/bootstrap follow-up.
// It catalogs the PE TLS directory and callback pointer table, resolves only the
// callbacks and entry point named by the image plus their .pdata records, and
// disassembles/decompiles those bodies only.  No client is executed.
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
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.symbol.FlowType;
import ghidra.util.task.TaskMonitor;

import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BootstrapFlow extends GhidraScript {
    private static final long IMAGE_BASE = 0x140000000L;
    private static final long MAX_REPORT_CHARS = 180000;
    private static final int TLS_DIRECTORY_INDEX = 9;
    private static final String[] INTERESTING_IMPORTS = {
        "virtualprotect", "flushinstructioncache", "ntprotectvirtualmemory",
        "getprocaddress", "loadlibrary"
    };

    private static final class Report {
        final StringBuilder text = new StringBuilder();
        boolean truncated;
        void line(String value) {
            if (text.length() + value.length() + 1 > MAX_REPORT_CHARS) {
                truncated = true;
                return;
            }
            text.append(value).append('\n');
        }
    }

    private static final class Record {
        long begin;
        long end;
        long unwind;
        String source;
        boolean chained;
        String status;
    }

    private static final class ImageInfo {
        int machine;
        long entryRva;
        long imageSize;
        long exceptionRva;
        long exceptionSize;
        long tlsRva;
        long tlsSize;
        long tlsDirectoryVa;
        long callbacksVa;
        long callbacksRva;
    }

    private Memory memory() { return currentProgram.getMemory(); }

    private Address va(long value) {
        if (value < 0) return null;
        try { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(value); }
        catch (RuntimeException e) { return null; }
    }

    private Address rva(long value) {
        if (value < 0 || value > 0xffffffffL) return null;
        return va(IMAGE_BASE + value);
    }

    private long readU16(Address address) throws Exception {
        return Byte.toUnsignedInt(memory().getByte(address))
            | ((long)Byte.toUnsignedInt(memory().getByte(address.add(1))) << 8);
    }

    private long readU32(Address address) throws Exception {
        long value = 0;
        for (int i = 0; i < 4; i++)
            value |= (long)Byte.toUnsignedInt(memory().getByte(address.add(i))) << (8 * i);
        return value;
    }

    private long readU64(Address address) throws Exception {
        long value = 0;
        for (int i = 0; i < 8; i++)
            value |= (long)Byte.toUnsignedInt(memory().getByte(address.add(i))) << (8 * i);
        return value;
    }

    private boolean span(long rvaValue, long size, boolean execute) {
        if (size <= 0 || rvaValue < 0 || rvaValue > 0xffffffffL
            || size > 0x100000000L - rvaValue) return false;
        Address start = rva(rvaValue);
        Address end = rva(rvaValue + size - 1);
        if (start == null || end == null) return false;
        MemoryBlock block = memory().getBlock(start);
        return block != null && block.contains(end) && block.isInitialized()
            && (!execute || block.isExecute());
    }

    private String hex(long value) { return String.format("0x%x", value); }

    private long rvaOf(long address) { return address - IMAGE_BASE; }

    private MemoryBlock block(long address) {
        Address target = va(address);
        return target == null ? null : memory().getBlock(target);
    }

    private String section(long address) {
        MemoryBlock block = block(address);
        if (block == null) return "<unmapped>";
        return block.getName() + "[" + hex(rvaOf(block.getStart().getOffset())) + ".."
            + hex(rvaOf(block.getEnd().getOffset())) + "]";
    }

    private String addressDescription(long address) {
        return hex(address) + " rva=" + hex(rvaOf(address)) + " section=" + section(address);
    }

    private ImageInfo parseImage(Report report) throws Exception {
        ImageInfo image = new ImageInfo();
        Address dos = rva(0);
        long ntRva = readU32(dos.add(0x3c));
        Address nt = rva(ntRva);
        long signature = readU32(nt);
        image.machine = (int)readU16(nt.add(4));
        int optionalSize = (int)readU16(nt.add(20));
        Address optional = nt.add(24);
        long magic = readU16(optional);
        image.entryRva = readU32(optional.add(16));
        image.imageSize = readU32(optional.add(56));
        long directories = readU32(optional.add(108));
        if (signature != 0x4550 || magic != 0x20b || directories <= TLS_DIRECTORY_INDEX
            || optionalSize < 112 + (TLS_DIRECTORY_INDEX + 1) * 8)
            throw new IllegalStateException("PE/TLS directory header invalid");
        Address exceptionDirectory = optional.add(112 + 3 * 8);
        image.exceptionRva = readU32(exceptionDirectory);
        image.exceptionSize = readU32(exceptionDirectory.add(4));
        Address tlsDirectory = optional.add(112 + TLS_DIRECTORY_INDEX * 8);
        image.tlsRva = readU32(tlsDirectory);
        image.tlsSize = readU32(tlsDirectory.add(4));
        if (!span(image.tlsRva, image.tlsSize, false))
            throw new IllegalStateException("TLS directory outside initialized image");
        image.tlsDirectoryVa = IMAGE_BASE + image.tlsRva;
        image.callbacksVa = readU64(va(image.tlsDirectoryVa).add(24));
        image.callbacksRva = image.callbacksVa >= IMAGE_BASE ? image.callbacksVa - IMAGE_BASE : -1;
        report.line("PE machine=" + hex(image.machine) + " entry=" + addressDescription(IMAGE_BASE + image.entryRva));
        report.line("PE exception_directory rva=" + hex(image.exceptionRva) + " size=" + image.exceptionSize);
        report.line("TLS directory rva=" + hex(image.tlsRva) + " va=" + hex(image.tlsDirectoryVa)
            + " size=" + image.tlsSize + " section=" + section(image.tlsDirectoryVa));
        report.line("TLS AddressOfCallBacks va=" + hex(image.callbacksVa) + " rva="
            + hex(image.callbacksRva) + " section=" + section(image.callbacksVa));
        return image;
    }

    private List<Long> catalogCallbacks(ImageInfo image, Report report) throws Exception {
        List<Long> callbacks = new ArrayList<>();
        if (image.callbacksVa == 0 || block(image.callbacksVa) == null) {
            report.line("TLS callback_table unavailable_or_null");
            return callbacks;
        }
        for (int index = 0; index < 64; index++) {
            long slot = image.callbacksVa + index * 8L;
            if (block(slot) == null) {
                report.line("TLS callback_table stop=unmapped slot_va=" + hex(slot));
                break;
            }
            long target = readU64(va(slot));
            report.line("TLS_CALLBACK index=" + index + " slot_va=" + hex(slot) + " slot_rva="
                + hex(rvaOf(slot)) + " slot_section=" + section(slot) + " target_va=" + hex(target)
                + " target_rva=" + hex(target >= IMAGE_BASE ? rvaOf(target) : -1)
                + " target_section=" + section(target));
            if (target == 0) break;
            if (target >= IMAGE_BASE && block(target) != null) callbacks.add(target);
        }
        return callbacks;
    }

    private Map<Long, Record> parsePdata(ImageInfo image, Report report) throws Exception {
        Map<Long, Record> records = new HashMap<>();
        int recordSize = image.machine == 0xaa64 ? 8 : image.machine == 0x8664 ? 12 : 0;
        if (recordSize == 0 || image.exceptionSize < recordSize || image.exceptionSize % recordSize != 0
            || !span(image.exceptionRva, image.exceptionSize, false)) {
            report.line("Pdata unavailable_or_invalid_shape");
            return records;
        }
        long previous = -1;
        int valid = 0;
        int invalid = 0;
        for (long offset = 0; offset < image.exceptionSize; offset += recordSize) {
            Address entry = rva(image.exceptionRva + offset);
            Record record = new Record();
            record.begin = readU32(entry);
            if (image.machine == 0x8664) {
                record.end = readU32(entry.add(4));
                record.unwind = readU32(entry.add(8));
                record.source = "x64-runtime-function";
                if (!span(record.unwind, 4, false)) record.status = "unwind_out_of_bounds";
                else {
                    int header = Byte.toUnsignedInt(memory().getByte(rva(record.unwind)));
                    record.chained = ((header >>> 3) & 7) == 4;
                    if ((header & 7) != 1) record.status = "unsupported_unwind_version";
                }
            } else {
                record.unwind = readU32(entry.add(4));
                int flag = (int)(record.unwind & 3);
                if (flag == 0 && span(record.unwind & ~3L, 4, false)) {
                    record.source = "arm64-xdata";
                    long header = readU32(rva(record.unwind & ~3L));
                    record.end = record.begin + (header & 0x3ffffL) * 4L;
                    if (((header >>> 18) & 3) != 0) record.status = "unsupported_xdata_version";
                } else if (flag == 1 || flag == 2) {
                    record.source = flag == 1 ? "arm64-packed" : "arm64-packed-fragment";
                    record.end = record.begin + ((record.unwind >>> 2) & 0x7ffL) * 4L;
                    record.chained = flag == 2;
                } else record.status = "invalid_arm64_pdata";
            }
            boolean ordered = previous < 0 || record.begin > previous;
            boolean body = record.end > record.begin && record.begin >= 0x1000
                && record.end <= image.imageSize && span(record.begin, record.end - record.begin, true);
            if (record.status == null && (!ordered || !body)) record.status = "bounds_or_order_invalid";
            if (record.status == null) { records.put(record.begin, record); valid++; }
            else invalid++;
            previous = record.begin;
        }
        report.line("Pdata selected_lookup_records=" + valid + " invalid_records=" + invalid
            + " record_size=" + recordSize + " (metadata-derived, not universal boundary proof)");
        return records;
    }

    private String symbolName(long target) {
        Address address = va(target);
        if (address == null) return "";
        SymbolTable symbols = currentProgram.getSymbolTable();
        Symbol symbol = symbols.getPrimarySymbol(address);
        return symbol == null ? "" : symbol.getName();
    }

    private String importKind(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String candidate : INTERESTING_IMPORTS)
            if (lower.contains(candidate)) return name;
        return "";
    }

    private long constantAddress(Varnode node) {
        if (node == null || !node.isConstant()) return -1;
        long raw = node.getOffset();
        if (block(raw) != null) return raw;
        if (raw >= 0 && raw <= 0xffffffffL && block(IMAGE_BASE + raw) != null) return IMAGE_BASE + raw;
        return -1;
    }

    private long parseImmediate(String value) {
        String text = value.trim().replace("#", "");
        boolean negative = text.startsWith("-");
        if (negative) text = text.substring(1);
        try {
            long parsed = text.startsWith("0x") || text.startsWith("0X")
                ? Long.parseUnsignedLong(text.substring(2), 16) : Long.parseLong(text);
            return negative ? -parsed : parsed;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private String registerExpression(String register, Map<String, String> expressions) {
        return expressions.get(register.toLowerCase(Locale.ROOT));
    }

    private long expressionBase(String expression) {
        if (expression == null) return -1;
        long total = 0;
        String[] terms = expression.split(" \\+ ");
        for (String term : terms) {
            long value = parseImmediate(term);
            if (value == Long.MIN_VALUE) break;
            total += value;
        }
        return total == 0 ? -1 : total;
    }

    private void inspectInstructions(AddressSet body, long start, long end, boolean arm64, Report report) {
        int count = 0;
        Address begin = va(IMAGE_BASE + start);
        Address limit = va(IMAGE_BASE + end - 1);
        Map<String, String> registerExpressions = new HashMap<>();
        InstructionIterator iterator = currentProgram.getListing().getInstructions(body, true);
        while (iterator.hasNext() && count++ < 20000) {
            Instruction instruction = iterator.next();
            if (instruction.getMinAddress().compareTo(begin) < 0
                || instruction.getMaxAddress().compareTo(limit) > 0) continue;
            if (arm64 && ((instruction.getMinAddress().getOffset() - start) & 3) != 0) {
                report.line("INSTRUCTION_REJECTED reason=arm64_unaligned address=" + instruction.getMinAddress());
                continue;
            }
            StringBuilder line = new StringBuilder("INSTRUCTION address=" + instruction.getMinAddress()
                + " len=" + instruction.getLength() + " text=" + instruction.toString()
                + " flow=" + instruction.getFlowType());
            String text = instruction.toString().toLowerCase(Locale.ROOT);
            int separator = text.indexOf(' ');
            String mnemonic = separator < 0 ? text : text.substring(0, separator);
            String operands = separator < 0 ? "" : text.substring(separator + 1).replace(" ", "");
            String[] operandParts = operands.split(",");
            if (mnemonic.equals("adrp") && operandParts.length >= 2) {
                long page = parseImmediate(operandParts[1]);
                if (page != Long.MIN_VALUE)
                    registerExpressions.put(operandParts[0], hex(page));
            } else if (mnemonic.equals("add") && operandParts.length >= 3) {
                String source = registerExpression(operandParts[1], registerExpressions);
                long immediate = parseImmediate(operandParts[2]);
                if (source != null) {
                    if (immediate != Long.MIN_VALUE)
                        registerExpressions.put(operandParts[0], source + " + " + hex(immediate));
                    else
                        registerExpressions.put(operandParts[0], source + " + " + operandParts[2]);
                } else registerExpressions.remove(operandParts[0]);
            } else if (mnemonic.startsWith("mov") || mnemonic.startsWith("ldr")
                || mnemonic.startsWith("ldp") || mnemonic.startsWith("sub")) {
                if (operandParts.length > 0) registerExpressions.remove(operandParts[0]);
            }
            if (mnemonic.startsWith("str") && operands.contains("[")) {
                int open = operands.indexOf('[');
                int close = operands.indexOf(']', open);
                if (close > open) {
                    String[] memoryParts = operands.substring(open + 1, close).split(",");
                    String expression = memoryParts.length > 0
                        ? registerExpression(memoryParts[0], registerExpressions) : null;
                    long offset = memoryParts.length > 1 ? parseImmediate(memoryParts[1]) : 0;
                    if (expression != null && offset != Long.MIN_VALUE) {
                        long base = expressionBase(expression);
                        if (base >= 0) {
                            long target = base + offset;
                            report.line("WRITE_TARGET_DERIVED instruction=" + instruction.getMinAddress()
                                + " target_expr=" + expression + (offset == 0 ? "" : " + " + hex(offset))
                                + " base=" + addressDescription(base) + " target=" + addressDescription(target)
                                + " confidence=instruction_address_construction");
                        }
                    }
                }
            }
            Address[] flows = instruction.getFlows();
            if (flows.length > 0) {
                line.append(" flows=[");
                for (int i = 0; i < flows.length; i++) {
                    if (i != 0) line.append(',');
                    line.append(flows[i]);
                    if (!body.contains(flows[i])) line.append("(outside-bound)");
                }
                line.append(']');
            }
            report.line(line.toString());
            for (Reference reference : instruction.getReferencesFrom()) {
                String name = symbolName(reference.getToAddress().getOffset());
                if (reference.getReferenceType().isCall() || reference.getReferenceType().isJump())
                    report.line("EDGE instruction=" + instruction.getMinAddress() + " type="
                        + reference.getReferenceType() + " target=" + reference.getToAddress()
                        + " name=" + name + " in_bound=" + body.contains(reference.getToAddress()));
                if (reference.getReferenceType().isWrite()) {
                    long target = reference.getToAddress().getOffset();
                    report.line("WRITE_DIRECT instruction=" + instruction.getMinAddress() + " target="
                        + addressDescription(target) + " ref_type=" + reference.getReferenceType());
                }
                String interesting = importKind(name);
                if (!interesting.isEmpty()) report.line("IMPORT_EDGE instruction=" + instruction.getMinAddress()
                    + " import=" + interesting + " reference_type=" + reference.getReferenceType());
            }
            for (PcodeOp op : instruction.getPcode()) {
                if (op.getOpcode() == PcodeOp.STORE) {
                    Varnode[] inputs = op.getInputs();
                    Varnode addressInput = inputs.length > 1 ? inputs[1] : null;
                    long target = constantAddress(addressInput);
                    if (target >= 0)
                        report.line("WRITE_PCODE_DIRECT instruction=" + instruction.getMinAddress()
                            + " target=" + addressDescription(target) + " pcode=" + op);
                    else report.line("WRITE_PCODE_INDIRECT instruction=" + instruction.getMinAddress()
                        + " address_operand=" + (addressInput == null ? "<missing>" : addressInput)
                        + " effect=unresolved");
                }
                if (op.getOpcode() == PcodeOp.CALLIND || op.getOpcode() == PcodeOp.BRANCHIND
                    || op.getOpcode() == PcodeOp.CALLOTHER)
                    report.line("INDIRECT_EDGE instruction=" + instruction.getMinAddress()
                        + " opcode=" + op.getMnemonic() + " effect=unresolved_indirect_target");
            }
        }
    }

    private Function prepareFunction(long start, Record record, boolean arm64, Report report) {
        if (record == null) {
            report.line("FUNCTION target=" + hex(start) + " status=no_matching_pdata_record");
            return null;
        }
        Address begin = va(IMAGE_BASE + start);
        Address end = va(IMAGE_BASE + record.end - 1);
        AddressSet body = new AddressSet(begin, end);
        Disassembler disassembler = Disassembler.getDisassembler(currentProgram, monitor, null);
        AddressSet decoded = disassembler.disassemble(begin, body);
        report.line("FUNCTION target=" + addressDescription(begin.getOffset()) + " pdata_end="
            + hex(record.end) + " source=" + record.source + " chained=" + record.chained
            + " decoded_ranges=" + decoded.printRanges());
        inspectInstructions(body, start, record.end, arm64, report);
        Function function = getFunctionAt(begin);
        try {
            if (function == null && !decoded.isEmpty()) function = createFunction(begin, null);
            if (function != null) function.setBody(body);
        } catch (Exception e) {
            report.line("FUNCTION_BODY_SET_ERROR target=" + begin + " error=" + e.getMessage());
        }
        return function;
    }

    private void decompile(List<Function> functions, Report report) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        int count = 0;
        for (Function function : functions) {
            if (count++ >= 6) break;
            DecompileResults result = decompiler.decompileFunction(function, 15, monitor);
            report.line("DECOMPILE function=" + function.getName() + "@" + function.getEntryPoint()
                + " complete=" + result.decompileCompleted() + " body="
                + new AddressSet(function.getBody()).printRanges());
            if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                String[] lines = result.getDecompiledFunction().getC().split("\\R");
                int limit = Math.min(lines.length, 400);
                for (int i = 0; i < limit; i++) report.line("  " + lines[i]);
                if (lines.length > limit) report.line("  DECOMPILER_OUTPUT_TRUNCATED lines=" + lines.length);
            } else report.line("  error=" + result.getErrorMessage());
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
        if (currentProgram.getImageBase().getOffset() != IMAGE_BASE)
            throw new IllegalStateException("unexpected image base " + currentProgram.getImageBase());
        Report report = new Report();
        ImageInfo image = parseImage(report);
        List<Long> callbacks = catalogCallbacks(image, report);
        Map<Long, Record> pdata = parsePdata(image, report);
        boolean arm64 = image.machine == 0xaa64;
        List<Function> functions = new ArrayList<>();
        Map<Long, Function> unique = new LinkedHashMap<>();
        int callbackLimit = Math.min(5, callbacks.size());
        for (int i = 0; i < callbackLimit; i++) {
            long target = callbacks.get(i);
            Function function = prepareFunction(rvaOf(target), pdata.get(rvaOf(target)), arm64, report);
            if (function != null) unique.put(target, function);
        }
        long entry = IMAGE_BASE + image.entryRva;
        Function entryFunction = prepareFunction(image.entryRva, pdata.get(image.entryRva), arm64, report);
        if (entryFunction != null) unique.put(entry, entryFunction);
        functions.addAll(unique.values());
        report.line("DECOMPILE_SELECTION callbacks_cataloged=" + callbacks.size()
            + " callbacks_decompiled_max=" + callbackLimit + " entry_included=" + (entryFunction != null)
            + " functions_selected=" + functions.size());
        decompile(functions, report);
        if (report.truncated) report.line("REPORT_TRUNCATED max_chars=" + MAX_REPORT_CHARS);
        Path path = Paths.get(getScriptArgs()[0]);
        FileChannel channel = openPrivateExclusive(path);
        try {
            channel.write(ByteBuffer.wrap(report.text.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } finally {
            channel.close();
        }
        println("BootstrapFlow report written: " + path);
    }
}
