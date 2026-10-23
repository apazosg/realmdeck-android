import com.wowforever.fexprobe.ProbeProtocol;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JVM-only protocol regression tests plus one real Wine batch run. */
public final class ProbeProtocolHostTest {
    private static final String[] EXE_NAMES = {"HelloProbe", "ApiProbe", "DynCodeProbe"};

    private ProbeProtocolHostTest() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Path runDir = options.runDir.toPath();
        Path guestDir = runDir.resolve("prefix/drive_c/wow/diag").resolve(options.id);
        Files.createDirectories(guestDir);
        for (String name : EXE_NAMES) {
            Path source = new File(options.probesDir, name + ".exe").toPath();
            require(Files.isRegularFile(source), "missing probe " + source);
            Files.copy(source, guestDir.resolve(name + ".exe"), StandardCopyOption.REPLACE_EXISTING);
        }

        String batch = ProbeProtocol.batch(options.id);
        require(!batch.matches("(?s).*echo [^\\r\\n]+ > .*"), "batch has trailing-space-prone echo redirection");
        Path batchFile = guestDir.resolve("run-probes.bat");
        Files.write(batchFile, batch.getBytes(StandardCharsets.UTF_8));

        long started = System.nanoTime();
        int processStatus = executeBatch(options, runDir, options.id);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        Map<String, ProbeProtocol.Artifact> actual = readArtifacts(guestDir, runDir.resolve("host-runtime.log"));
        ProbeProtocol.Report report = ProbeProtocol.parse(options.id, processStatus, false, actual);
        require(processStatus == 0, "Wine batch exit=" + processStatus);
        require(report.hello == ProbeProtocol.Status.PASS, "hello=" + report.hello);
        require(report.api == ProbeProtocol.Status.PASS, "api=" + report.api);
        require(report.dyn == ProbeProtocol.Status.PASS, "dyn=" + report.dyn);
        require(report.run == ProbeProtocol.Status.PASS, "run=" + report.run);
        require(report.pass, "actual batch did not PASS");
        require("42".equals(firstLine(actual.get("hello.exitcode"))), "hello exit is not 42");
        require("0".equals(firstLine(actual.get("api.exitcode"))), "api exit is not 0");
        require("0".equals(firstLine(actual.get("dyn.exitcode"))), "dyn exit is not 0");

        runActualApiMissingCase(options, runDir);
        parserRegressionCases(options.id, actual);
        System.out.println("HOST-WINE-PASS runDir=" + runDir + " durationMs=" + durationMs +
                " processExit=" + processStatus + " hello=" + report.hello +
                " api=" + report.api + " dyn=" + report.dyn + " run=" + report.run);
    }

    private static void runActualApiMissingCase(Options options, Path parentRunDir) throws Exception {
        String id = options.id + "-api-missing";
        Path runDir = parentRunDir.resolve("api-missing");
        Path guestDir = runDir.resolve("prefix/drive_c/wow/diag").resolve(id);
        Files.createDirectories(guestDir);
        Files.copy(new File(options.probesDir, "HelloProbe.exe").toPath(),
                guestDir.resolve("HelloProbe.exe"));
        // Deliberately omit ApiProbe.exe. The generated batch must still launch DynCodeProbe.
        Files.copy(new File(options.probesDir, "DynCodeProbe.exe").toPath(),
                guestDir.resolve("DynCodeProbe.exe"));
        Files.write(guestDir.resolve("run-probes.bat"),
                ProbeProtocol.batch(id).getBytes(StandardCharsets.UTF_8));
        int processStatus = executeBatch(options, runDir, id);
        Map<String, ProbeProtocol.Artifact> artifacts = readArtifacts(guestDir, runDir.resolve("host-runtime.log"));
        ProbeProtocol.Report report = ProbeProtocol.parse(id, processStatus, false, artifacts);
        require(processStatus != 0, "missing API executable unexpectedly returned 0");
        require(report.api == ProbeProtocol.Status.FAIL || report.api == ProbeProtocol.Status.INCOMPLETE,
                "missing API was not failure/incomplete: " + report.api);
        require(report.dyn == ProbeProtocol.Status.PASS, "Dyn did not run after missing API: " + report.dyn);
        require(!report.pass, "missing API falsely passed");
    }

    private static int executeBatch(Options options, Path runDir, String id)
            throws IOException, InterruptedException {
        Files.createDirectories(runDir);
        ProcessBuilder command = new ProcessBuilder(options.wine,
                "cmd", "/c", "C:\\wow\\diag\\" + id + "\\run-probes.bat");
        command.environment().put("WINEPREFIX", runDir.resolve("prefix").toString());
        command.environment().put("WINEDEBUG", "-all");
        command.redirectErrorStream(true);
        command.redirectOutput(runDir.resolve("host-runtime.log").toFile());
        Process process = command.start();
        if (!process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 124;
        }
        return process.exitValue();
    }

    private static void parserRegressionCases(String id, Map<String, ProbeProtocol.Artifact> actual) {
        Map<String, ProbeProtocol.Artifact> apiNonzero = copy(actual);
        apiNonzero.put("api.exitcode", ProbeProtocol.Artifact.text("7\n"));
        apiNonzero.put("api.finished", ProbeProtocol.Artifact.text("STAGE api COMPLETED exit=7\n"));
        apiNonzero.put("api-probe.txt", ProbeProtocol.Artifact.text("PROBE-END result=1\n"));
        ProbeProtocol.Report failedApi = ProbeProtocol.parse(id, 1, false, apiNonzero);
        require(failedApi.api == ProbeProtocol.Status.FAIL, "nonzero API was not FAIL");
        require(failedApi.dyn == ProbeProtocol.Status.PASS, "API failure stopped Dyn stage evidence");
        require(!failedApi.pass, "nonzero API falsely passed");

        Map<String, ProbeProtocol.Artifact> missingApiLog = copy(actual);
        missingApiLog.put("api-probe.txt", ProbeProtocol.Artifact.missing());
        ProbeProtocol.Report incompleteApi = ProbeProtocol.parse(id, 0, false, missingApiLog);
        require(incompleteApi.api == ProbeProtocol.Status.INCOMPLETE, "missing API log was not INCOMPLETE");
        require(!incompleteApi.pass, "missing API log falsely passed");

        Map<String, ProbeProtocol.Artifact> staleRun = copy(actual);
        staleRun.put("run.started", ProbeProtocol.Artifact.text("RUN-START stale-run-id\n"));
        ProbeProtocol.Report stale = ProbeProtocol.parse(id, 0, false, staleRun);
        require(stale.run == ProbeProtocol.Status.FAIL, "stale run marker was accepted");
        require(!stale.pass, "stale run marker falsely passed");

        Map<String, ProbeProtocol.Artifact> duplicateTerminal = copy(actual);
        duplicateTerminal.put("dyncode-probe.txt", ProbeProtocol.Artifact.text(
                actual.get("dyncode-probe.txt").text + "DYNCODE-END result=1 failures=1\n"));
        ProbeProtocol.Report duplicate = ProbeProtocol.parse(id, 0, false, duplicateTerminal);
        require(duplicate.dyn == ProbeProtocol.Status.FAIL, "contradictory terminal line was accepted");
        require(!duplicate.pass, "contradictory terminal line falsely passed");

        Map<String, ProbeProtocol.Artifact> truncated = copy(actual);
        truncated.put("api-probe.txt", ProbeProtocol.Artifact.truncated(actual.get("api-probe.txt").text));
        ProbeProtocol.Report truncatedReport = ProbeProtocol.parse(id, 0, false, truncated);
        require(truncatedReport.api == ProbeProtocol.Status.INCOMPLETE, "truncated log was not INCOMPLETE");
        require(!truncatedReport.pass, "truncated log falsely passed");

        Map<String, ProbeProtocol.Artifact> malformedHello = copy(actual);
        malformedHello.put("hello-probe.txt", ProbeProtocol.Artifact.text(
                "prefix HELLO-BEGIN arch=9\nHELLO-EXIT=42\n"));
        ProbeProtocol.Report malformed = ProbeProtocol.parse(id, 0, false, malformedHello);
        require(malformed.hello == ProbeProtocol.Status.INCOMPLETE, "malformed HELLO marker was accepted");
        require(!malformed.pass, "malformed HELLO marker falsely passed");
    }

    private static Map<String, ProbeProtocol.Artifact> readArtifacts(Path guestDir, Path hostLog)
            throws IOException {
        Map<String, ProbeProtocol.Artifact> result = ProbeProtocol.newArtifacts();
        for (String name : ProbeProtocol.guestArtifactNames()) {
            result.put(name, ProbeProtocol.read(guestDir.resolve(name).toFile()));
        }
        result.put("host-runtime.log", ProbeProtocol.read(hostLog.toFile()));
        return result;
    }

    private static Map<String, ProbeProtocol.Artifact> copy(Map<String, ProbeProtocol.Artifact> source) {
        Map<String, ProbeProtocol.Artifact> result = ProbeProtocol.newArtifacts();
        result.putAll(source);
        return result;
    }

    private static String firstLine(ProbeProtocol.Artifact artifact) {
        return artifact == null ? "" : artifact.text.replace("\r", "").split("\n", -1)[0];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Options {
        final String wine;
        final File probesDir;
        final File runDir;
        final String id;
        final long timeoutSeconds;

        private Options(String wine, File probesDir, File runDir, String id, long timeoutSeconds) {
            this.wine = wine;
            this.probesDir = probesDir;
            this.runDir = runDir;
            this.id = id;
            this.timeoutSeconds = timeoutSeconds;
        }

        static Options parse(String[] args) {
            String wine = null;
            File probes = null;
            File run = null;
            String id = null;
            long timeout = 60;
            for (int i = 0; i < args.length; i++) {
                String value = i + 1 < args.length ? args[++i] : null;
                if ("--wine".equals(args[i - 1])) wine = value;
                else if ("--probes".equals(args[i - 1])) probes = new File(value);
                else if ("--run-dir".equals(args[i - 1])) run = new File(value);
                else if ("--id".equals(args[i - 1])) id = value;
                else if ("--timeout-seconds".equals(args[i - 1])) timeout = Long.parseLong(value);
                else throw new IllegalArgumentException("unknown argument " + args[i - 1]);
            }
            if (wine == null || probes == null || run == null || id == null) {
                throw new IllegalArgumentException("usage: --wine WINE --probes DIR --run-dir DIR --id ID");
            }
            return new Options(wine, probes, run, id, timeout);
        }
    }
}
