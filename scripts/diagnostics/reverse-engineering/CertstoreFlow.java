// Bounded, read-only Ghidra headless report for the Beta CertStore/auth flow.
// Usage: analyzeHeadless ... -postScript CertstoreFlow.java <report-path>
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.task.TaskMonitor;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CertstoreFlow extends GhidraScript {
    private static final String[] TERMS = {
        "NetClient::CertStore", "Cert store init failed", "PublicKeyEntry",
        "allowDevPublicKey", "wowstatic_camelot", "WOWSTATIC_CAMELOT",
        "-----BEGIN", "PEM_read", "X509", "SSL_CTX", "https://", "certificate",
        "signature", "ed25519", "Ed25519"
    };

    private static final class Hit {
        final Address address;
        final String value;
        Hit(Address address, String value) { this.address = address; this.value = value; }
    }

    private String rva(Address address) {
        long value = address.getOffset() - currentProgram.getImageBase().getOffset();
        return String.format("0x%x", value);
    }

    private String functionName(Function function) {
        if (function == null) return "<no-function>";
        return function.getName() + "@" + rva(function.getEntryPoint());
    }

    private List<Hit> findStrings() {
        List<Hit> hits = new ArrayList<>();
        Listing listing = currentProgram.getListing();
        DataIterator iterator = listing.getDefinedData(true);
        while (iterator.hasNext() && !monitor.isCancelled()) {
            Data data = iterator.next();
            Object value = data.getValue();
            if (!(value instanceof String)) continue;
            String text = (String)value;
            for (String term : TERMS) {
                if (text.contains(term)) {
                    hits.add(new Hit(data.getAddress(), text));
                    break;
                }
            }
        }
        return hits;
    }

    private Set<Function> xrefFunctions(Address address) {
        Set<Function> result = new LinkedHashSet<>();
        ReferenceManager manager = currentProgram.getReferenceManager();
        ReferenceIterator references = manager.getReferencesTo(address);
        while (references.hasNext()) {
            Reference reference = references.next();
            Function function = currentProgram.getFunctionManager()
                .getFunctionContaining(reference.getFromAddress());
            if (function != null) result.add(function);
        }
        return result;
    }

    private String callers(Function function) {
        Set<String> callers = new LinkedHashSet<>();
        ReferenceIterator references = currentProgram.getReferenceManager()
            .getReferencesTo(function.getEntryPoint());
        while (references.hasNext()) {
            Reference reference = references.next();
            Function caller = currentProgram.getFunctionManager()
                .getFunctionContaining(reference.getFromAddress());
            if (caller != null && caller != function) callers.add(functionName(caller));
        }
        return callers.toString();
    }

    private String callees(Function function) throws Exception {
        Set<String> callees = new LinkedHashSet<>();
        Set<Function> iterator = function.getCalledFunctions(monitor);
        for (Function callee : iterator) callees.add(functionName(callee));
        return callees.toString();
    }

    private void writeCoverage(BufferedWriter out) throws IOException {
        int functions = 0;
        FunctionIterator functionIterator = currentProgram.getFunctionManager().getFunctions(true);
        while (functionIterator.hasNext()) { functionIterator.next(); functions++; }
        int instructions = 0;
        InstructionIterator instructionIterator = currentProgram.getListing().getInstructions(true);
        while (instructionIterator.hasNext()) { instructionIterator.next(); instructions++; }
        out.write("functions_defined=" + functions + " instructions_defined=" + instructions + "\n");
        out.write("MEMORY_BLOCKS\n");
        for (ghidra.program.model.mem.MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            out.write("  " + block.getName() + " start=" + block.getStart() + " end="
                + block.getEnd() + " size=" + block.getSize() + " initialized="
                + block.isInitialized() + " execute=" + block.isExecute() + " write="
                + block.isWrite() + "\n");
        }
    }

    private void writeTarget(BufferedWriter out, long targetRva) throws IOException {
        Address address = currentProgram.getImageBase().add(targetRva);
        out.write("TARGET rva=" + String.format("0x%x", targetRva) + " address=" + address);
        out.write(" defined=" + (currentProgram.getListing().getDefinedDataAt(address) != null));
        out.write(" refs_to=" + currentProgram.getReferenceManager().getReferenceCountTo(address) + "\n");
        Instruction instruction = currentProgram.getListing().getInstructionContaining(address);
        if (instruction == null) return;
        out.write("  containing_instruction=" + instruction.getAddress() + " "
            + instruction.getMnemonicString() + " " + instruction + "\n");
        Function function = currentProgram.getFunctionManager().getFunctionContaining(instruction.getAddress());
        out.write("  containing_function=" + functionName(function) + " callers="
            + (function == null ? "[]" : callers(function)) + "\n");
        Instruction previous = instruction;
        for (int i = 0; i < 6; i++) {
            previous = previous.getPrevious();
            if (previous == null) break;
            out.write("    prev " + previous.getAddress() + " " + previous + "\n");
        }
        Instruction next = instruction;
        for (int i = 0; i < 6; i++) {
            next = next.getNext();
            if (next == null) break;
            out.write("    next " + next.getAddress() + " " + next + "\n");
        }
    }

    private void decompile(BufferedWriter out, List<Function> functions) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        decompiler.setSimplificationStyle("decompile");
        for (Function function : functions) {
            DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
            out.write("FUNCTION " + functionName(function) + "\n");
            out.write("  callers=" + callers(function) + "\n");
            out.write("  callees=" + callees(function) + "\n");
            out.write("  decompile_completed=" + result.decompileCompleted() + "\n");
            if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                String c = result.getDecompiledFunction().getC();
                String[] lines = c.split("\\R");
                int limit = Math.min(lines.length, 180);
                for (int i = 0; i < limit; i++) out.write("  " + lines[i] + "\n");
            } else {
                out.write("  error=" + result.getErrorMessage() + "\n");
            }
        }
        decompiler.dispose();
    }

    @Override
    public void run() throws Exception {
        if (getScriptArgs().length != 1) {
            printerr("Expected exactly one report path");
            return;
        }
        String reportPath = getScriptArgs()[0];
        List<Hit> hits = findStrings();
        Map<Function, Set<String>> functionReasons = new LinkedHashMap<>();
        FileOutputStream reportStream = new FileOutputStream(reportPath, false);
        try (BufferedWriter out = new BufferedWriter(
            new OutputStreamWriter(reportStream, StandardCharsets.UTF_8))) {
            out.write("program=" + currentProgram.getName() + "\n");
            out.write("image_base=" + currentProgram.getImageBase() + "\n");
            out.write("language=" + currentProgram.getLanguageID() + "\n");
            out.write("compiler=" + currentProgram.getCompiler() + "\n");
            out.write("strings_found=" + hits.size() + "\n\n");
            writeCoverage(out);
            out.write("TARGETS\n");
            writeTarget(out, 0x3b8e274L);
            writeTarget(out, 0x498b860L);
            // The earlier structural reports printed these three RVAs as decimal.
            writeTarget(out, 80082408L);
            writeTarget(out, 80525912L);
            writeTarget(out, 79741832L);
            out.write("\n");
            for (Hit hit : hits) {
                out.write("STRING address=" + hit.address + " rva=" + rva(hit.address)
                    + " value=" + hit.value.replace('\n', ' ') + "\n");
                for (Function function : xrefFunctions(hit.address)) {
                    out.write("  xref_function=" + functionName(function) + "\n");
                    functionReasons.computeIfAbsent(function, ignored -> new LinkedHashSet<>())
                        .add(hit.value);
                }
            }
            out.write("\nCANDIDATES count=" + functionReasons.size() + "\n");
            List<Function> candidates = new ArrayList<>(functionReasons.keySet());
            candidates.sort(Comparator.comparingLong(f -> f.getEntryPoint().getOffset()));
            for (Function function : candidates) {
                out.write("CANDIDATE " + functionName(function) + " reasons="
                    + functionReasons.get(function) + "\n");
            }
            out.write("\nDECOMPILATIONS\n");
            decompile(out, candidates);
            out.flush();
            reportStream.getFD().sync();
        }
        println("CertstoreFlow report written: " + reportPath + " candidates=" + functionReasons.size());
    }
}
