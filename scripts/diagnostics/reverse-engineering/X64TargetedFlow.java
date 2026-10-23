// Bounded Ghidra-only x64 follow-up. It locates the named strings and decodes only
// RIP-relative references to them, then lets Ghidra disassemble/decompile those
// concrete callers without waiting for the full x64 auto-analysis.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.TaskMonitor;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class X64TargetedFlow extends GhidraScript {
    private static final String[] TERMS = {
        "NetClient::CertStore", "Cert store init failed", "PublicKeyEntry",
        "allowDevPublicKey", "wowstatic_camelot", "WOWSTATIC_CAMELOT",
        "-----BEGIN", "PEM_read", "X509", "SSL_CTX", "https://", "certificate",
        "signature", "ed25519", "Ed25519"
    };

    private String rva(Address address) {
        return String.format("0x%x", address.getOffset() - currentProgram.getImageBase().getOffset());
    }

    private String fn(Function function) {
        return function == null ? "<no-function>" : function.getName() + "@" + rva(function.getEntryPoint());
    }

    private Address findString(String term) throws Exception {
        for (ghidra.program.model.mem.MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isRead() || block.isExecute()) continue;
            Address found = currentProgram.getMemory().findBytes(block.getStart(), block.getEnd(),
                term.getBytes(StandardCharsets.US_ASCII), null, true, monitor);
            if (found != null) return found;
        }
        return null;
    }

    private Map<Long, List<Address>> findRipReferences(Set<Long> stringAddresses) throws Exception {
        Map<Long, List<Address>> references = new LinkedHashMap<>();
        Memory memory = currentProgram.getMemory();
        for (ghidra.program.model.mem.MemoryBlock block : memory.getBlocks()) {
            if (!block.isExecute()) continue;
            Address p = block.getStart();
            while (p.compareTo(block.getEnd().subtract(7)) <= 0 && !monitor.isCancelled()) {
                int b0 = Byte.toUnsignedInt(memory.getByte(p));
                int b1 = Byte.toUnsignedInt(memory.getByte(p.add(1)));
                int rex = b0 & 0xf0;
                if ((rex == 0x40 || rex == 0x48) && (b1 == 0x8b || b1 == 0x8d || b1 == 0x89)) {
                    int modrm = Byte.toUnsignedInt(memory.getByte(p.add(2)));
                    if ((modrm & 0xc7) == 0x05) {
                        int displacement = memory.getInt(p.add(3));
                        long target = p.getOffset() + 7L + displacement;
                        if (stringAddresses.contains(target)) {
                            references.computeIfAbsent(target, ignored -> new ArrayList<>()).add(p);
                        }
                    }
                }
                p = p.add(1);
            }
        }
        return references;
    }

    private String callers(Function function) {
        Set<String> result = new LinkedHashSet<>();
        ghidra.program.model.symbol.ReferenceIterator iterator = currentProgram.getReferenceManager()
            .getReferencesTo(function.getEntryPoint());
        while (iterator.hasNext()) {
            ghidra.program.model.symbol.Reference reference = iterator.next();
            Function caller = currentProgram.getFunctionManager().getFunctionContaining(reference.getFromAddress());
            if (caller != null && caller != function) result.add(fn(caller));
        }
        return result.toString();
    }

    private String callees(Function function) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (Function callee : function.getCalledFunctions(monitor)) result.add(fn(callee));
        return result.toString();
    }

    private void decompile(BufferedWriter out, Set<Function> functions) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        for (Function function : functions) {
            DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
            out.write("FUNCTION " + fn(function) + " callers=" + callers(function)
                + " callees=" + callees(function) + " complete=" + result.decompileCompleted() + "\n");
            if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                String[] lines = result.getDecompiledFunction().getC().split("\\R");
                for (int i = 0; i < Math.min(lines.length, 180); i++) out.write("  " + lines[i] + "\n");
            } else out.write("  error=" + result.getErrorMessage() + "\n");
        }
        decompiler.dispose();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) return;
        String path = getScriptArgs()[0];
        FileOutputStream stream = new FileOutputStream(path, false);
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            out.write("program=" + currentProgram.getName() + " image_base=" + currentProgram.getImageBase()
                + " language=" + currentProgram.getLanguageID() + "\n");
            Set<Function> functions = new LinkedHashSet<>();
            Map<String, Address> strings = new LinkedHashMap<>();
            for (String term : TERMS) {
                Address stringAddress = findString(term);
                out.write("STRING term=" + term + " address=" + stringAddress
                    + (stringAddress == null ? "\n" : " rva=" + rva(stringAddress) + "\n"));
                if (stringAddress != null) strings.put(term, stringAddress);
            }
            Set<Long> stringAddresses = new LinkedHashSet<>();
            for (Address address : strings.values()) stringAddresses.add(address.getOffset());
            Map<Long, List<Address>> references = findRipReferences(stringAddresses);
            for (Map.Entry<String, Address> string : strings.entrySet()) {
                for (Address reference : references.getOrDefault(string.getValue().getOffset(), List.of())) {
                    boolean disassembled = disassemble(reference);
                    Function function = getFunctionContaining(reference);
                    if (function == null && disassembled) function = createFunction(reference, null);
                    out.write("  RIP_XREF=" + reference + " rva=" + rva(reference)
                        + " target_term=" + string.getKey() + " disassembled=" + disassembled
                        + " function=" + fn(function) + "\n");
                    if (function != null) functions.add(function);
                }
            }
            Address entry = currentProgram.getImageBase().add(0x1d8b10L);
            boolean entryDisassembled = disassemble(entry);
            Function entryFunction = getFunctionContaining(entry);
            if (entryFunction == null && entryDisassembled) entryFunction = createFunction(entry, null);
            out.write("ENTRY_POINT address=" + entry + " rva=0x1d8b10 disassembled="
                + entryDisassembled + " function=" + fn(entryFunction) + "\n");
            if (entryFunction != null) functions.add(entryFunction);
            out.write("CANDIDATES count=" + functions.size() + "\n");
            decompile(out, functions);
            out.flush();
            stream.getFD().sync();
        }
        println("X64TargetedFlow report written: " + path);
    }
}
