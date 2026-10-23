package com.wowforever.runtimeprobe;

import android.content.Context;
import android.net.Uri;

import com.winlator.R;
import com.winlator.core.FileUtils;
import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owns per-run files and makes the report exportable without ADB. */
public final class ProbeRunStore {
    public static final String SOURCE_COMMIT = "0c8068f617737d9c0fb6246f37b54f85b620b09d";
    private static final String RUNTIME = "wow-mobile 0.1.2 / Wine 10.10 / Box64 0.4.0 / diagnostics experimental2";
    private static final String[] LOGS = {"api-probe.txt", "dyncode-probe.txt"};
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[0-9]{8}-[0-9]{6}-[0-9a-f]{8}");
    private static volatile boolean cleanupBlocked;

    private ProbeRunStore() {}

    public static boolean isCleanupBlocked() { return cleanupBlocked; }

    public static void blockNewRuns() { cleanupBlocked = true; }

    public static boolean isValidRunId(String runId) {
        return runId != null && RUN_ID_PATTERN.matcher(runId).matches();
    }

    public static String prepare(Context context, Container container) throws Exception {
        if (container == null || container.getRootDir() == null) throw new IOException("container has no root directory");
        File winePrefix = ProbePrefix.concreteWinePrefix(container.getRootDir());
        if (!winePrefix.isDirectory()) throw new IOException("container Wine prefix is missing: " + winePrefix);
        String runId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) +
            "-" + UUID.randomUUID().toString().substring(0, 8);
        File run = runDir(context, runId);
        if (!run.mkdirs()) throw new IOException("run directory collision");

        File rootDiag = new File(winePrefix, "drive_c/wow/diag");
        File diag = new File(rootDiag, runId);
        if (!diag.mkdirs()) throw new IOException("diagnostic directory collision");
        copyAsset(context, "probes/ApiProbe.exe", new File(diag, "ApiProbe.exe"));
        copyAsset(context, "probes/DynCodeProbe.exe", new File(diag, "DynCodeProbe.exe"));

        String dos = "C:\\wow\\diag\\" + runId + "\\";
        String batch = "@echo off\r\n" +
            "echo RUN-START " + runId + " > " + dos + "run.started\r\n" +
            dos + "ApiProbe.exe --log " + dos + "api-probe.txt\r\n" +
            "set API=%ERRORLEVEL%\r\n" +
            "echo %API% > " + dos + "api.exitcode\r\n" +
            "if not \"%API%\"==\"0\" (echo RUN-END exit=41 > " + dos + "run.finished & exit /b 41)\r\n" +
            dos + "DynCodeProbe.exe --log " + dos + "dyncode-probe.txt\r\n" +
            "set DYN=%ERRORLEVEL%\r\n" +
            "echo %DYN% > " + dos + "dyn.exitcode\r\n" +
            "if not \"%DYN%\"==\"0\" (echo RUN-END exit=42 > " + dos + "run.finished & exit /b 42)\r\n" +
            "echo 0 > " + dos + "run.exitcode\r\n" +
            "echo RUN-END exit=0 > " + dos + "run.finished\r\n" +
            "exit /b 0\r\n";
        write(new File(diag, "run-probes.bat"), batch);
        write(new File(run, "state"), "RUNNING\n");

        JSONObject metadata = new JSONObject();
        metadata.put("runId", runId);
        metadata.put("sourceCommit", SOURCE_COMMIT);
        metadata.put("rootfsVersion", RootFS.find(context).getVersion());
        metadata.put("runtime", RUNTIME);
        metadata.put("winePrefix", winePrefix.getAbsolutePath());
        metadata.put("apiProbeSha256", sha256(new File(diag, "ApiProbe.exe")));
        metadata.put("dynCodeProbeSha256", sha256(new File(diag, "DynCodeProbe.exe")));
        write(new File(run, "metadata.json"), metadata.toString(2));
        appendHostLog(context, runId, "PROBE-BEGIN source=" + SOURCE_COMMIT);
        return runId;
    }

    /** Leaves an exportable report when container creation or preparation fails before a run exists. */
    public static void recordPreparationFailure(Context context, int processStatus, String reason) {
        synchronized (ProbeRunStore.class) {
            try {
                String runId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) +
                    "-" + UUID.randomUUID().toString().substring(0, 8);
                File run = runDir(context, runId);
                if (!run.mkdirs()) throw new IOException("run directory collision");
                JSONObject metadata = new JSONObject();
                metadata.put("runId", runId);
                metadata.put("sourceCommit", SOURCE_COMMIT);
                metadata.put("runtime", RUNTIME);
                metadata.put("preparationError", reason == null ? "unknown" : reason);
                write(new File(run, "metadata.json"), metadata.toString(2));
                write(new File(run, "state"), "RUNNING\n");
                appendHostLog(context, runId, "PREPARATION-ERROR " + metadata.optString("preparationError"));
                finish(context, runId, processStatus);
            }
            catch (Exception e) {
                // There is no valid run id to attach a second-order reporting error to.
            }
        }
    }

    public static boolean isContainerActivated(Context context, Container container) {
        return container != null && ProbePrefix.isActivated(
            new File(RootFS.find(context).getRootDir(), "home/" + RootFS.USER),
            container.getRootDir());
    }

    /** Converts abandoned runs into reports without inventing a process exit status. */
    public static void reconcile(Context context) {
        synchronized (ProbeRunStore.class) {
            File[] runs = runsDir(context).listFiles(File::isDirectory);
            if (runs == null) return;
            for (File run : runs) {
                String runId = run.getName();
                if (!isValidRunId(runId) || !"RUNNING".equals(read(new File(run, "state")).trim())) continue;
                try {
                    copyRunArtifacts(context, runId, run);
                    if (!new File(run, "report.txt").isFile()) writeReport(context, runId, null, true, false);
                    write(new File(run, "state"), "INCOMPLETE\n");
                }
                catch (Exception e) {
                    appendHostLog(context, runId, "RECONCILE-ERROR " + e.getClass().getSimpleName());
                }
            }
        }
    }

    public static void appendHostLog(Context context, String runId, String line) {
        if (!isValidRunId(runId)) return;
        synchronized (ProbeRunStore.class) {
            try {
                File file = new File(runDir(context, runId), "host-runtime.log");
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream output = new FileOutputStream(file, true)) {
                    output.write((System.currentTimeMillis() + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            catch (IOException ignored) {}
        }
    }

    public static void finish(Context context, String runId, int processStatus) {
        finish(context, runId, processStatus, false, true);
    }

    public static void finish(Context context, String runId, int processStatus,
                              boolean terminationUnknown, boolean cleanupQuiescent) {
        if (!isValidRunId(runId)) return;
        synchronized (ProbeRunStore.class) {
            try {
                File run = runDir(context, runId);
                copyRunArtifacts(context, runId, run);
                writeReport(context, runId, processStatus, terminationUnknown, cleanupQuiescent);
                write(new File(run, "state"), terminationUnknown ? "SNAPSHOT\n" : processStatus == 124 ? "TIMEOUT\n" : "DONE\n");
            }
            catch (Exception e) {
                appendHostLog(context, runId, "REPORT-ERROR " + e.getClass().getSimpleName());
            }
        }
    }

    public static File latestReport(Context context) {
        File[] runs = runsDir(context).listFiles((file, name) -> new File(file, name + "/report.txt").isFile());
        if (runs == null || runs.length == 0) return null;
        File latest = runs[0];
        for (File run : runs) if (run.getName().compareTo(latest.getName()) > 0) latest = run;
        return new File(latest, "report.txt");
    }

    public static String status(Context context) {
        File report = latestReport(context);
        if (report == null) return context.getString(R.string.probe_status_ready);
        String text = read(report);
        int start = text.indexOf("outcome=");
        int end = text.indexOf('\n', start);
        return start >= 0 ? text.substring(start, end > start ? end : text.length()) : "Report available";
    }

    public static void export(Context context, File report, Uri destination) throws IOException {
        if (destination == null) throw new IOException("SAF returned no destination URI");
        try (InputStream input = new FileInputStream(report); OutputStream output = context.getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IOException("SAF returned no output stream");
            copy(input, output);
        }
    }

    private static void writeReport(Context context, String runId, Integer processStatus,
                                    boolean terminationUnknown, boolean cleanupQuiescent) throws Exception {
        File run = runDir(context, runId);
        String api = read(new File(run, "api-probe.txt"));
        String dyn = read(new File(run, "dyncode-probe.txt"));
        String apiExit = read(new File(run, "api.exitcode")).trim();
        String dynExit = read(new File(run, "dyn.exitcode")).trim();
        String runExit = read(new File(run, "run.exitcode")).trim();
        boolean markers = api.contains("PROBE-END result=0") && dyn.contains("DYNCODE-END result=0 failures=0");
        boolean exitcodes = "0".equals(apiExit) && "0".equals(dynExit) && "0".equals(runExit);
        boolean pass = processStatus != null && processStatus == 0 && markers && exitcodes && read(new File(run, "run.finished")).contains("exit=0");
        String outcome = processStatus == null ? "INCOMPLETE" : terminationUnknown ? "SNAPSHOT" : pass ? "PASS" : markers ? "FAIL" : "INCOMPLETE";
        String processExit = processStatus == null ? "UNKNOWN" : String.valueOf(processStatus);
        JSONObject metadata = new JSONObject(read(new File(run, "metadata.json")));
        String report = "integrated-runtime-prototype\n" +
            "runId=" + runId + "\n" + "outcome=" + outcome + "\n" +
            "processExitCode=" + processExit + "\n" +
            "apiExitCode=" + apiExit + "\n" + "dynExitCode=" + dynExit + "\n" +
             "runExitCode=" + runExit + "\n" +
             "runStarted=" + read(new File(run, "run.started")).trim() + "\n" +
             "runFinished=" + read(new File(run, "run.finished")).trim() + "\n" +
            "termination_unknown=" + terminationUnknown + "\n" +
            "cleanup_quiescent=" + cleanupQuiescent + "\n" +
            "sourceCommit=" + metadata.optString("sourceCommit") + "\n" +
            "rootfsVersion=" + metadata.optInt("rootfsVersion") + "\n" +
            "runtime=" + metadata.optString("runtime") + "\n" +
            "winePrefix=" + metadata.optString("winePrefix") + "\n" +
            "preparationError=" + metadata.optString("preparationError") + "\n" +
            "apiProbeSha256=" + metadata.optString("apiProbeSha256") + "\n" +
            "dynCodeProbeSha256=" + metadata.optString("dynCodeProbeSha256") + "\n\n" +
            "[host-runtime]\n" + read(new File(run, "host-runtime.log")) +
            "\n[ApiProbe]\n" + api + "\n[DynCodeProbe]\n" + dyn;
        write(new File(run, "report.txt"), report);
    }

    private static void copyRunArtifacts(Context context, String runId, File run) throws IOException {
        JSONObject metadata;
        try {
            metadata = new JSONObject(read(new File(run, "metadata.json")));
        }
        catch (Exception e) {
            throw new IOException("cannot read run metadata", e);
        }
        String prefixPath = metadata.optString("winePrefix", "");
        if (prefixPath.isEmpty()) {
            if (metadata.has("preparationError")) return;
            throw new IOException("run metadata has no concrete Wine prefix");
        }
        File diag = new File(new File(prefixPath), "drive_c/wow/diag/" + runId);
        for (String name : LOGS) copyIfPresent(new File(diag, name), new File(run, name));
        for (String name : new String[]{"api.exitcode", "dyn.exitcode", "run.exitcode", "run.started", "run.finished"}) {
            copyIfPresent(new File(diag, name), new File(run, name));
        }
    }

    private static File runsDir(Context context) {
        File dir = new File(context.getFilesDir(), "probe-runs");
        dir.mkdirs();
        return dir;
    }

    private static File runDir(Context context, String runId) {
        return new File(runsDir(context), runId);
    }

    private static void copyAsset(Context context, String asset, File destination) throws IOException {
        try (InputStream input = context.getAssets().open(asset); OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
        destination.setExecutable(true, false);
    }

    private static void copyIfPresent(File source, File destination) throws IOException {
        if (source.isFile()) {
            File parent = destination.getParentFile();
            if (parent != null) parent.mkdirs();
            File temporary = new File(destination.getPath() + ".tmp");
            try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(temporary)) {
                copy(input, output);
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new IOException("cannot publish copied artifact");
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) {
        return file.isFile() ? FileUtils.readString(file) : "<missing>\n";
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }
}
