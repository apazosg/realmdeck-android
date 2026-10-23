package com.wowforever.fexprobe;

import android.content.Context;
import android.net.Uri;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.ImageFsInstaller;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Owns run-isolated artifacts and the conservative terminal report. */
public final class ProbeRunStore {
    public static final String SOURCE_COMMIT = "4293b75b449d11fdf66da567791c9165fa6df6a0";
    public static final String RUNTIME = "FrontMage " + SOURCE_COMMIT +
            " / FEX2508 0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3" +
            " / Proton10 2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5";
    public static final long INIT_TIMEOUT_MS = 120_000L;
    public static final long RUNTIME_TIMEOUT_MS = 180_000L;
    public static final String FEX_SHA256 = "0afc4ded5830c2038237498ffe34c830533c6152ca7197349c06de4f44aee4a3";
    public static final String PROTON10_SHA256 = "2501af75ffe4cffad0671e4e6613f0c8e6113fcf16f5a474a4dd3569135010d5";
    public static final String HODLL = "libwow64fex.dll";
    public static final String LAUNCH_PATH = "GuestProgramLauncherComponent -> direct Proton10 ARM64EC wine -> cmd.exe";
    private static final String[] ARTIFACTS = ProbeProtocol.guestArtifactNames();
    private static final Pattern RUN_ID = Pattern.compile("[0-9]{8}-[0-9]{6}-[0-9a-f]{8}");
    private static final ConcurrentHashMap<String, Boolean> TERMINAL = new ConcurrentHashMap<>();

    private ProbeRunStore() {}

    public static boolean isValidRunId(String value) {
        return value != null && RUN_ID.matcher(value).matches();
    }

    public static File latestReport(Context context) {
        File[] runs = runsDir(context).listFiles(File::isDirectory);
        File latest = null;
        if (runs != null) for (File run : runs) {
            File report = new File(run, "report.txt");
            File latestRun = latest == null ? null : latest.getParentFile();
            if (report.isFile() && (latestRun == null || run.getName().compareTo(latestRun.getName()) > 0)) latest = report;
        }
        return latest;
    }

    public static void reconcile(Context context) {
        File[] runs = runsDir(context).listFiles(File::isDirectory);
        if (runs == null) return;
        for (File run : runs) {
            if (isValidRunId(run.getName()) && "RUNNING".equals(read(new File(run, "state")).trim())) {
                snapshot(context, run.getName(), "PROCESS-RECREATED termination_unknown=true");
            }
        }
    }

    public static String prepare(Context context, Container container) throws Exception {
        if (container == null || container.getRootDir() == null) throw new IOException("container root is missing");
        ImageFs imageFs = ImageFs.find(context);
        if (!imageFs.isValid() || imageFs.getVersion() != ImageFsInstaller.LATEST_VERSION) {
            throw new IOException("ImageFS version is not exactly " + ImageFsInstaller.LATEST_VERSION + ": " + imageFs.getRootDir());
        }
        File wine = new File(imageFs.getRootDir(), "opt/proton-10-arm64ec/bin/wine");
        if (!wine.isFile() || !wine.canExecute()) throw new IOException("Proton 10 ARM64EC wine missing/not executable: " + wine);
        requireAsset(context, "fexcore/fexcore-2508.tzst");
        requireAsset(context, "preinstall/proton-10-arm64ec.wcp.xz");
        requireNative(context, "libproot.so");
        requireNative(context, "libproot-loader.so");

        File root = imageFs.getRootDir();
        File active = new File(root, "home/xuser");
        File selected = container.getRootDir().getCanonicalFile();
        if (!FileUtils.isSymlink(active) || !active.getCanonicalFile().equals(selected)) {
            throw new IOException("active Wine container symlink is not the selected container: " + active + " -> " + selected);
        }
        File prefix = new File(selected, ".wine");
        if (!prefix.isDirectory()) throw new IOException("Wine prefix missing: " + prefix);
        File system32 = new File(prefix, "drive_c/windows/system32");
        if (!system32.isDirectory() && !system32.mkdirs()) throw new IOException("cannot create FEX bridge directory: " + system32);
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "fexcore/fexcore-2508.tzst", system32)) {
            throw new IOException("FEX2508 bridge extraction failed into " + system32);
        }
        requireFile(new File(system32, "libwow64fex.dll"), "FEX WoW64 bridge");
        requireFile(new File(system32, "libarm64ecfex.dll"), "FEX ARM64EC bridge");
        String fexAssetSha256 = sha256Asset(context, "fexcore/fexcore-2508.tzst");
        String wow64FexSha256 = sha256(new File(system32, "libwow64fex.dll"));
        String arm64ecFexSha256 = sha256(new File(system32, "libarm64ecfex.dll"));

        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        File run = new File(runsDir(context), id);
        if (!run.mkdirs()) throw new IOException("run directory collision: " + id);
        File diag = new File(prefix, "drive_c/wow/diag/" + id);
        if (!diag.mkdirs()) throw new IOException("diagnostic directory collision: " + diag);
        copyAsset(context, "probes/HelloProbe.exe", new File(diag, "HelloProbe.exe"));
        copyAsset(context, "probes/ApiProbe.exe", new File(diag, "ApiProbe.exe"));
        copyAsset(context, "probes/DynCodeProbe.exe", new File(diag, "DynCodeProbe.exe"));
        write(new File(diag, "run-probes.bat"), ProbeProtocol.batch(id));
        write(new File(run, "state"), "RUNNING\n");
        JSONObject metadata = new JSONObject();
        metadata.put("runId", id);
        metadata.put("sourceCommit", SOURCE_COMMIT);
        metadata.put("runtime", RUNTIME);
        metadata.put("imageFs", root.getAbsolutePath());
        metadata.put("winePrefix", prefix.getAbsolutePath());
        metadata.put("wine", wine.getAbsolutePath());
        metadata.put("fexBridge", new File(system32, "libwow64fex.dll").getAbsolutePath());
        metadata.put("fexAsset", "fexcore/fexcore-2508.tzst");
        metadata.put("fexAssetSha256", fexAssetSha256);
        metadata.put("libwow64fexSha256", wow64FexSha256);
        metadata.put("libarm64ecfexSha256", arm64ecFexSha256);
        metadata.put("hodll", HODLL);
        metadata.put("launchPath", LAUNCH_PATH);
        metadata.put("guestCommand", "wine cmd /c C:\\wow\\diag\\" + id + "\\run-probes.bat");
        metadata.put("helloExpectedExit", 42);
        metadata.put("helloSha256", sha256(new File(diag, "HelloProbe.exe")));
        metadata.put("apiSha256", sha256(new File(diag, "ApiProbe.exe")));
        metadata.put("dynSha256", sha256(new File(diag, "DynCodeProbe.exe")));
        write(new File(run, "metadata.json"), metadata.toString(2) + "\n");
        appendHostLog(context, id, "PREFLIGHT-PASS imagefs=" + root + " wine=" + wine + " bridge=" + system32
                + " fexAssetSha256=" + fexAssetSha256
                + " libwow64fexSha256=" + wow64FexSha256
                + " libarm64ecfexSha256=" + arm64ecFexSha256);
        return id;
    }

    public static void appendHostLog(Context context, String id, String line) {
        if (!isValidRunId(id)) return;
        File file = new File(runDir(context, id), "host-runtime.log");
        try {
            file.getParentFile().mkdirs();
            try (OutputStream output = new FileOutputStream(file, true)) {
                output.write((System.currentTimeMillis() + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (IOException ignored) {}
    }

    public static void finish(Context context, String id, int processStatus) {
        if (!isValidRunId(id) || TERMINAL.putIfAbsent(id, true) != null) return;
        finishInternal(context, id, processStatus, false, "");
    }

    public static void snapshot(Context context, String id, String reason) {
        if (!isValidRunId(id) || TERMINAL.putIfAbsent(id, true) != null) return;
        appendHostLog(context, id, reason);
        finishInternal(context, id, null, true, reason);
    }

    public static void recordPreparationFailure(Context context, String reason) {
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        File run = runDir(context, id);
        try {
            if (!run.mkdirs()) return;
            write(new File(run, "state"), "RUNNING\n");
            JSONObject metadata = new JSONObject();
            metadata.put("runId", id);
            metadata.put("sourceCommit", SOURCE_COMMIT);
            metadata.put("runtime", RUNTIME);
            metadata.put("preparationError", reason == null ? "unknown" : reason);
            write(new File(run, "metadata.json"), metadata.toString(2) + "\n");
            TERMINAL.put(id, true);
            finishInternal(context, id, 126, false, reason);
        }
        catch (Exception ignored) {}
    }

    public static void export(Context context, File report, Uri destination) throws IOException {
        if (destination == null || report == null || !report.isFile()) throw new IOException("no report available");
        try (InputStream input = new FileInputStream(report); OutputStream output = context.getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IOException("SAF returned no output stream");
            copy(input, output);
        }
    }

    private static void finishInternal(Context context, String id, Integer processStatus, boolean snapshot, String reason) {
        try {
            File run = runDir(context, id);
            copyRunArtifacts(run);
            JSONObject metadata = new JSONObject(read(new File(run, "metadata.json")));
            Map<String, ProbeProtocol.Artifact> artifacts = new LinkedHashMap<>();
            for (String name : ProbeProtocol.reportArtifactNames()) {
                artifacts.put(name, ProbeProtocol.read(new File(run, name)));
            }
            ProbeProtocol.Report parsed = ProbeProtocol.parse(id, processStatus, snapshot, artifacts);
            String helloExit = parsed.artifact("hello.exitcode").text.trim();
            String apiExit = parsed.artifact("api.exitcode").text.trim();
            String dynExit = parsed.artifact("dyn.exitcode").text.trim();
            String runExit = parsed.artifact("run.exitcode").text.trim();
            boolean pass = parsed.pass;
            String outcome = snapshot ? "SNAPSHOT" : processStatus == null ? "UNKNOWN" : pass ? "PASS" : "FAIL";
            String report = "fex-standalone-diagnostics\n" +
                    "runId=" + id + "\n" + "outcome=" + outcome + "\n" +
                    "stage.hello=" + parsed.hello + "\n" +
                    "stage.api=" + parsed.api + "\n" +
                    "stage.dyn=" + parsed.dyn + "\n" +
                    "stage.run=" + parsed.run + "\n" +
                    "artifactsTruncated=" + parsed.truncated + "\n" +
                    "processExitCode=" + (processStatus == null ? "UNKNOWN" : processStatus) + "\n" +
                    "helloExitCode=" + helloExit + "\napiExitCode=" + apiExit + "\ndynExitCode=" + dynExit + "\nrunExitCode=" + runExit + "\n" +
                    "sourceCommit=" + metadata.optString("sourceCommit") + "\n" +
                    "runtime=" + metadata.optString("runtime") + "\n" +
                    "hodll=" + metadata.optString("hodll") + "\n" +
                    "launchPath=" + metadata.optString("launchPath") + "\n" +
                     "guestCommand=" + metadata.optString("guestCommand") + "\n" +
                     "fexAsset=" + metadata.optString("fexAsset") + "\n" +
                     "fexAssetSha256=" + metadata.optString("fexAssetSha256") + "\n" +
                     "libwow64fexSha256=" + metadata.optString("libwow64fexSha256") + "\n" +
                     "libarm64ecfexSha256=" + metadata.optString("libarm64ecfexSha256") + "\n" +
                     "imageFs=" + metadata.optString("imageFs") + "\n" +
                    "winePrefix=" + metadata.optString("winePrefix") + "\n" +
                    "preparationError=" + metadata.optString("preparationError") + "\n" +
                    "terminalReason=" + reason + "\n" + renderArtifacts(parsed) + "\n";
            write(new File(run, "report.txt"), report);
            write(new File(run, "state"), snapshot ? "SNAPSHOT\n" : "DONE\n");
        }
        catch (Exception error) {
            appendHostLog(context, id, "REPORT-ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static void copyRunArtifacts(File run) throws Exception {
        JSONObject metadata = new JSONObject(read(new File(run, "metadata.json")));
        String prefix = metadata.optString("winePrefix", "");
        if (prefix.isEmpty()) return;
        File diag = new File(new File(prefix), "drive_c/wow/diag/" + run.getName());
        for (String name : ARTIFACTS) copyIfPresent(new File(diag, name), new File(run, name));
    }

    private static String renderArtifacts(ProbeProtocol.Report parsed) {
        StringBuilder result = new StringBuilder("\n[artifacts]\n");
        for (String name : ProbeProtocol.reportArtifactNames()) {
            ProbeProtocol.Artifact artifact = parsed.artifact(name);
            result.append("-- ").append(name)
                    .append(" present=").append(artifact.present)
                    .append(" truncated=").append(artifact.truncated).append(" --\n")
                    .append(artifact.text);
            if (!artifact.text.endsWith("\n")) result.append('\n');
        }
        return result.toString();
    }

    private static File runsDir(Context context) { File dir = new File(context.getFilesDir(), "fex-probe-runs"); dir.mkdirs(); return dir; }
    private static File runDir(Context context, String id) { return new File(runsDir(context), id); }
    private static void requireAsset(Context context, String name) throws IOException { try (InputStream ignored = context.getAssets().open(name)) {} }
    private static void requireNative(Context context, String name) throws IOException { requireFile(new File(context.getApplicationInfo().nativeLibraryDir, name), "native " + name); }
    private static void requireFile(File file, String label) throws IOException { if (!file.isFile()) throw new IOException(label + " missing: " + file); }
    private static void copyAsset(Context context, String asset, File destination) throws IOException { try (InputStream input = context.getAssets().open(asset); OutputStream output = new FileOutputStream(destination)) { copy(input, output); } destination.setExecutable(true, false); }
    private static void copyIfPresent(File source, File destination) throws IOException { if (!source.isFile()) return; File tmp = new File(destination.getPath() + ".tmp"); try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(tmp)) { copy(input, output); } if (!tmp.renameTo(destination)) throw new IOException("cannot publish " + destination); }
    private static void copy(InputStream input, OutputStream output) throws IOException { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); }
    private static void write(File file, String text) throws IOException { file.getParentFile().mkdirs(); try (OutputStream output = new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); } }
    private static String read(File file) {
        try { return ProbeProtocol.read(file).text; }
        catch (IOException ignored) { return ""; }
    }
    private static String sha256(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); } StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value)); return result.toString(); }
    private static String sha256Asset(Context context, String name) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = context.getAssets().open(name)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); } StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value)); return result.toString(); }
}
