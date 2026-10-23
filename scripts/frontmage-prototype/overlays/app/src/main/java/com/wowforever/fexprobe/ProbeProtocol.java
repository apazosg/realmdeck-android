package com.wowforever.fexprobe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure batch/report protocol shared by the Android store and host tests. */
public final class ProbeProtocol {
    public static final int MAX_ARTIFACT_BYTES = 256 * 1024;

    private static final String[] GUEST_ARTIFACT_NAMES = {
            "hello-probe.txt", "api-probe.txt", "dyncode-probe.txt",
            "hello.started", "hello.finished", "hello.exitcode",
            "api.started", "api.finished", "api.exitcode",
            "dyn.started", "dyn.finished", "dyn.exitcode",
            "run.started", "run.finished", "run.exitcode"
    };
    private static final String[] REPORT_ARTIFACT_NAMES = {
            "hello-probe.txt", "api-probe.txt", "dyncode-probe.txt",
            "hello.started", "hello.finished", "hello.exitcode",
            "api.started", "api.finished", "api.exitcode",
            "dyn.started", "dyn.finished", "dyn.exitcode",
            "run.started", "run.finished", "run.exitcode", "host-runtime.log"
    };

    private static final Pattern HELLO_BEGIN = Pattern.compile("HELLO-BEGIN arch=\\d+");
    private ProbeProtocol() {}

    public enum Status {
        PASS, FAIL, NOT_RUN, INCOMPLETE
    }

    /** A bounded file snapshot. Missing and truncated are intentionally distinct. */
    public static final class Artifact {
        public final String text;
        public final boolean present;
        public final boolean truncated;

        public Artifact(String text, boolean present, boolean truncated) {
            this.text = text == null ? "" : text;
            this.present = present;
            this.truncated = truncated;
        }

        public static Artifact missing() {
            return new Artifact("", false, false);
        }

        public static Artifact text(String value) {
            return new Artifact(value, true, false);
        }

        public static Artifact truncated(String value) {
            return new Artifact(value, true, true);
        }
    }

    public static final class Report {
        public final Status hello;
        public final Status api;
        public final Status dyn;
        public final Status run;
        public final boolean pass;
        public final boolean truncated;
        private final Map<String, Artifact> artifacts;

        private Report(Status hello, Status api, Status dyn, Status run, boolean pass,
                       boolean truncated, Map<String, Artifact> artifacts) {
            this.hello = hello;
            this.api = api;
            this.dyn = dyn;
            this.run = run;
            this.pass = pass;
            this.truncated = truncated;
            this.artifacts = artifacts;
        }

        public Artifact artifact(String name) {
            Artifact artifact = artifacts.get(name);
            return artifact == null ? Artifact.missing() : artifact;
        }

        public Map<String, Artifact> artifacts() {
            return artifacts;
        }
    }

    public static String[] guestArtifactNames() {
        return GUEST_ARTIFACT_NAMES.clone();
    }

    public static String[] reportArtifactNames() {
        return REPORT_ARTIFACT_NAMES.clone();
    }

    public static Map<String, Artifact> newArtifacts() {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (String name : REPORT_ARTIFACT_NAMES) artifacts.put(name, Artifact.missing());
        return artifacts;
    }

    /** Reads at most MAX_ARTIFACT_BYTES and records whether more bytes existed. */
    public static Artifact read(File file) throws IOException {
        if (file == null || !file.isFile()) return Artifact.missing();
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            boolean truncated = false;
            while ((count = input.read(buffer)) != -1) {
                int remaining = MAX_ARTIFACT_BYTES - total;
                if (remaining > 0) {
                    int copy = Math.min(count, remaining);
                    bytes.write(buffer, 0, copy);
                    total += copy;
                }
                if (count > remaining) truncated = true;
            }
            return new Artifact(bytes.toString(StandardCharsets.UTF_8.name()), true, truncated);
        }
    }

    /** The exact batch used by the Android run and by the host Wine harness. */
    public static String batch(String id) {
        if (id == null || !id.matches("[0-9A-Za-z_-]+")) throw new IllegalArgumentException("invalid run id");
        String p = "C:\\wow\\diag\\" + id + "\\";
        return "@echo off\r\n" +
                writeLine(p + "run.started", "RUN-START " + id) +
                writeLine(p + "hello.started", "STAGE hello STARTED " + id) +
                p + "HelloProbe.exe --log " + p + "hello-probe.txt\r\n" +
                "set \"HELLO=%ERRORLEVEL%\"\r\n" +
                writeLine(p + "hello.exitcode", "%HELLO%") +
                writeLine(p + "hello.finished", "STAGE hello COMPLETED exit=%HELLO%") +
                "set \"RUN=0\"\r\n" +
                "if not \"%HELLO%\"==\"42\" set \"RUN=1\"\r\n" +
                writeLine(p + "api.started", "STAGE api STARTED " + id) +
                p + "ApiProbe.exe --log " + p + "api-probe.txt\r\n" +
                "set \"API=%ERRORLEVEL%\"\r\n" +
                writeLine(p + "api.exitcode", "%API%") +
                writeLine(p + "api.finished", "STAGE api COMPLETED exit=%API%") +
                "if not \"%API%\"==\"0\" set \"RUN=1\"\r\n" +
                writeLine(p + "dyn.started", "STAGE dyn STARTED " + id) +
                p + "DynCodeProbe.exe --log " + p + "dyncode-probe.txt\r\n" +
                "set \"DYN=%ERRORLEVEL%\"\r\n" +
                writeLine(p + "dyn.exitcode", "%DYN%") +
                writeLine(p + "dyn.finished", "STAGE dyn COMPLETED exit=%DYN%") +
                "if not \"%DYN%\"==\"0\" set \"RUN=1\"\r\n" +
                writeLine(p + "run.exitcode", "%RUN%") +
                writeLine(p + "run.finished", "RUN-END exit=%RUN%") +
                "exit /b %RUN%\r\n";
    }

    private static String writeLine(String path, String value) {
        // Redirection before ECHO is deliberate: `echo X > file` can preserve
        // the separator as data under cmd implementations, breaking exact lines.
        return "> \"" + path + "\" echo " + value + "\r\n";
    }

    public static Report parse(String id, Integer processStatus, boolean snapshot,
                               Map<String, Artifact> input) {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        if (input != null) artifacts.putAll(input);
        Status hello = parseHello(id, artifacts);
        Status api = parseApi(id, artifacts);
        Status dyn = parseDyn(id, artifacts);
        Status run = parseRun(id, artifacts);
        boolean truncated = false;
        for (Artifact artifact : artifacts.values()) if (artifact != null && artifact.truncated) truncated = true;
        boolean pass = !snapshot && processStatus != null && processStatus == 0 &&
                !truncated && hello == Status.PASS && api == Status.PASS &&
                dyn == Status.PASS && run == Status.PASS;
        return new Report(hello, api, dyn, run, pass, truncated, artifacts);
    }

    private static Status parseHello(String id, Map<String, Artifact> a) {
        return parseStage(id, a, "hello", "hello-probe.txt", "42", HELLO_BEGIN, "HELLO-EXIT=42",
                "HELLO-BEGIN", "HELLO-EXIT=");
    }

    private static Status parseApi(String id, Map<String, Artifact> a) {
        return parseStage(id, a, "api", "api-probe.txt", "0", null, "PROBE-END result=0",
                null, "PROBE-END result=");
    }

    private static Status parseDyn(String id, Map<String, Artifact> a) {
        return parseStage(id, a, "dyn", "dyncode-probe.txt", "0", null, "DYNCODE-END result=0 failures=0",
                null, "DYNCODE-END result=");
    }

    private static Status parseStage(String id, Map<String, Artifact> a, String stage, String logName,
                                     String expectedExit,
                                     Pattern beginPattern, String expectedLogTerminal,
                                     String logBeginPrefix, String logTerminalPrefix) {
        Artifact log = get(a, logName);
        Artifact started = get(a, stage + ".started");
        Artifact finished = get(a, stage + ".finished");
        Artifact exit = get(a, stage + ".exitcode");
        boolean attempted = log.present || started.present || finished.present || exit.present;
        if (!attempted) return Status.NOT_RUN;
        if (log.truncated || started.truncated || finished.truncated || exit.truncated) return Status.INCOMPLETE;

        boolean stageStarted = exactlyOne(started.text, "STAGE " + stage + " STARTED " + id);
        boolean stageFinished = oneExactWithPrefix(finished.text, "STAGE " + stage + " COMPLETED exit=");
        String exitText = firstLine(exit.text);
        boolean badTerminal = contradictoryLines(log.text, logBeginPrefix, beginPattern, logTerminalPrefix,
                expectedLogTerminal);
        boolean logComplete = expectedLogTerminal == null ? false :
                exactlyOne(log.text, expectedLogTerminal);
        if (stage.equals("hello")) {
            logComplete = exactlyOneMatching(log.text, HELLO_BEGIN) && exactlyOne(log.text, "HELLO-EXIT=42");
        }
        if (stage.equals("dyn")) logComplete = exactlyOne(log.text, "DYNCODE-END result=0 failures=0");
        if (stage.equals("api")) logComplete = exactlyOne(log.text, "PROBE-END result=0");
        if (badTerminal || (exit.present && !expectedExit.equals(exitText)) ||
                (finished.present && !stageFinished) || (started.present && !stageStarted)) return Status.FAIL;
        if (stageStarted && stageFinished && expectedExit.equals(exitText) && logComplete) return Status.PASS;
        return Status.INCOMPLETE;
    }

    private static Status parseRun(String id, Map<String, Artifact> a) {
        Artifact started = get(a, "run.started");
        Artifact finished = get(a, "run.finished");
        Artifact exit = get(a, "run.exitcode");
        boolean attempted = started.present || finished.present || exit.present;
        if (!attempted) return Status.NOT_RUN;
        if (started.truncated || finished.truncated || exit.truncated) return Status.INCOMPLETE;
        boolean startedOk = exactlyOne(started.text, "RUN-START " + id);
        boolean finishedOk = exactlyOne(finished.text, "RUN-END exit=0");
        String exitText = firstLine(exit.text);
        if (contradictoryLines(started.text, "RUN-START", null, null, "RUN-START " + id) ||
                contradictoryLines(finished.text, "RUN-END", null, "RUN-END exit=", "RUN-END exit=0") ||
                (exit.present && !"0".equals(exitText))) return Status.FAIL;
        if (startedOk && finishedOk && "0".equals(exitText)) return Status.PASS;
        return Status.INCOMPLETE;
    }

    private static Artifact get(Map<String, Artifact> artifacts, String name) {
        Artifact artifact = artifacts.get(name);
        return artifact == null ? Artifact.missing() : artifact;
    }

    private static String firstLine(String text) {
        String[] lines = lines(text);
        return lines.length == 0 ? "" : lines[0];
    }

    private static String[] lines(String text) {
        return (text == null ? "" : text).replace("\r", "").split("\n", -1);
    }

    private static boolean exactlyOne(String text, String expected) {
        int count = 0;
        for (String line : lines(text)) if (expected.equals(line)) count++;
        return count == 1;
    }

    private static boolean exactlyOneMatching(String text, Pattern pattern) {
        int count = 0;
        for (String line : lines(text)) if (pattern.matcher(line).matches()) count++;
        return count == 1;
    }

    private static boolean oneExactWithPrefix(String text, String prefix) {
        int count = 0;
        for (String line : lines(text)) if (line.startsWith(prefix)) count++;
        return count == 1;
    }

    private static boolean contradictoryLines(String text, String prefix, Pattern expectedPattern,
                                              String terminalPrefix, String expectedTerminal) {
        int matching = 0;
        int terminalMatching = 0;
        for (String line : lines(text)) {
            if (prefix != null && line.startsWith(prefix)) {
                if (expectedTerminal != null && expectedTerminal.equals(line)) matching++;
                else if (expectedPattern != null && expectedPattern.matcher(line).matches()) matching++;
                else return true;
            }
            if (terminalPrefix != null && line.startsWith(terminalPrefix)) {
                if (expectedTerminal == null || !expectedTerminal.equals(line)) return true;
                terminalMatching++;
            }
        }
        return matching > 1 || terminalMatching > 1;
    }
}
