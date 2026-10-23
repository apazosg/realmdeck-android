package com.wowforever.runtimeprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Entry point for the self-contained runtime probe. It never selects a game folder. */
public final class ProbeLauncherActivity extends AppCompatActivity {
    private static final String CONTAINER_NAME = "Forever Runtime Probe";
    private static final String SHORTCUT_NAME = "Forever Runtime Probe";
    private static final long POLL_MS = 500L;

    private final Handler handler = new Handler();
    private TextView status;
    private Button runButton;
    private File pendingExport;
    private boolean rootFsReady;
    private boolean installRequested;
    private int exactLatestPolls;

    private static final String STATE_PENDING_EXPORT_PATH = "probe.pendingExportPath";
    private static final String STATE_PENDING_EXPORT_RUN_ID = "probe.pendingExportRunId";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.probe_launcher_activity);

        status = findViewById(R.id.ProbeStatus);
        runButton = findViewById(R.id.ProbeRun);
        runButton.setOnClickListener((view) -> runProbe());
        findViewById(R.id.ProbeExport).setOnClickListener((view) -> exportLatestReport());

        ProbeRunStore.reconcile(this);
        restorePendingExport(savedInstanceState);

        RootFS rootFS = RootFS.find(this);
        boolean needsInstall = !rootFS.isValid() || rootFsVersion(rootFS) < RootFSInstaller.LATEST_VERSION;
        installRequested = needsInstall;
        runButton.setEnabled(false);
        if (needsInstall) RootFSInstaller.installIfNeeded(this);
        updateStatus(false);
        handler.post(rootFsPoll);
    }

    private final Runnable rootFsPoll = new Runnable() {
        @Override
        public void run() {
            updateStatus(true);
            if (!rootFsReady) handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(rootFsPoll);
        super.onDestroy();
    }

    private void updateStatus(boolean poll) {
        RootFS rootFS = RootFS.find(this);
        boolean valid = rootFS.isValid();
        int version = rootFsVersion(rootFS);
        boolean exactLatest = valid && version == RootFSInstaller.LATEST_VERSION;
        if (installRequested) {
            if (poll && exactLatest) exactLatestPolls++;
            else if (poll) exactLatestPolls = 0;
            rootFsReady = exactLatestPolls >= 2;
        }
        else rootFsReady = valid && version >= RootFSInstaller.LATEST_VERSION;
        runButton.setEnabled(rootFsReady && !ProbeRunStore.isCleanupBlocked());
        TextView provenance = findViewById(R.id.ProbeProvenance);
        provenance.setText(getString(R.string.probe_provenance));
        if (!rootFsReady) {
            status.setText(R.string.probe_status_installing);
            return;
        }
        if (ProbeRunStore.isCleanupBlocked()) {
            status.setText(R.string.probe_status_cleanup_blocked);
            return;
        }
        File report = ProbeRunStore.latestReport(this);
        if (report != null) status.setText(ProbeRunStore.status(this));
        else status.setText(R.string.probe_status_ready);
    }

    private void runProbe() {
        if (!rootFsReady || ProbeRunStore.isCleanupBlocked()) return;
        runButton.setEnabled(false);
        status.setText(R.string.probe_status_preparing);
        try {
            ensureContainer();
        }
        catch (Exception e) {
            ProbeRunStore.recordPreparationFailure(this, 126, e.getMessage());
            status.setText(R.string.probe_status_prepare_failed);
            runButton.setEnabled(rootFsReady && !ProbeRunStore.isCleanupBlocked());
        }
    }

    private void ensureContainer() throws JSONException {
        ContainerManager manager = new ContainerManager(this);
        Container existing = null;
        for (Container candidate : manager.getContainers()) {
            if (CONTAINER_NAME.equals(candidate.getName())) {
                existing = candidate;
                break;
            }
        }
        if (existing != null) {
            launchProbe(existing);
            return;
        }

        JSONObject data = new JSONObject();
        data.put("name", CONTAINER_NAME);
        data.put("screenSize", "960x540");
        data.put("envVars", Container.DEFAULT_ENV_VARS);
        data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(this));
        data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
        data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("drives", Container.DEFAULT_DRIVES);
        data.put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE);
        manager.createContainerAsync(data, (container) -> {
            if (container == null) {
                ProbeRunStore.recordPreparationFailure(this, 126, "container creation failed");
                status.setText(R.string.probe_status_prepare_failed);
                runButton.setEnabled(rootFsReady && !ProbeRunStore.isCleanupBlocked());
            }
            else launchProbe(container);
        });
    }

    private void launchProbe(Container container) {
        try {
            String runId = ProbeRunStore.prepare(this, container);
            File shortcut = createShortcut(container, runId);
            Intent intent = new Intent(this, com.winlator.XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("shortcut_path", shortcut.getPath());
            intent.putExtra("probe_mode", true);
            intent.putExtra("probe_run_id", runId);
            startActivity(intent);
        }
        catch (Exception e) {
            ProbeRunStore.recordPreparationFailure(this, 126, e.getMessage());
            status.setText(R.string.probe_status_prepare_failed);
            runButton.setEnabled(rootFsReady && !ProbeRunStore.isCleanupBlocked());
        }
    }

    private File createShortcut(Container container, String runId) throws IOException {
        File desktop = new File(container.getUserDir(), "Desktop");
        if (!desktop.isDirectory() && !desktop.mkdirs()) throw new IOException("cannot create probe desktop");
        File shortcut = new File(desktop, SHORTCUT_NAME + ".desktop");
        String content = "[Desktop Entry]\n" +
            "Name=" + SHORTCUT_NAME + "\n" +
            "Exec=wine cmd /c C:\\\\wow\\\\diag\\\\" + runId + "\\\\run-probes.bat\n" +
            "StartupWMClass=runtime-probe.exe\n";
        FileUtils.writeString(shortcut, content);
        return shortcut;
    }

    private void exportLatestReport() {
        pendingExport = ProbeRunStore.latestReport(this);
        if (pendingExport == null) {
            status.setText(R.string.probe_status_no_report);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, pendingExport.getParentFile().getName() + ".txt");
        startActivityForResult(intent, 19);
    }

    private void restorePendingExport(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String path = savedInstanceState.getString(STATE_PENDING_EXPORT_PATH);
        String runId = savedInstanceState.getString(STATE_PENDING_EXPORT_RUN_ID);
        if (path == null || runId == null || !ProbeRunStore.isValidRunId(runId)) return;
        File report = new File(path);
        File parent = report.getParentFile();
        if (parent != null && runId.equals(parent.getName()) && report.isFile()) pendingExport = report;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingExport == null) return;
        File parent = pendingExport.getParentFile();
        if (parent == null || !ProbeRunStore.isValidRunId(parent.getName())) return;
        outState.putString(STATE_PENDING_EXPORT_PATH, pendingExport.getPath());
        outState.putString(STATE_PENDING_EXPORT_RUN_ID, parent.getName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 19 || resultCode != Activity.RESULT_OK || data == null || pendingExport == null) return;
        try {
            if (data.getData() == null) throw new IOException("SAF returned no destination URI");
            ProbeRunStore.export(this, pendingExport, data.getData());
            status.setText(R.string.probe_status_exported);
        }
        catch (IOException | RuntimeException e) {
            status.setText(R.string.probe_status_export_failed);
        }
        pendingExport = null;
    }

    private static int rootFsVersion(RootFS rootFS) {
        try {
            return rootFS.getVersion();
        }
        catch (RuntimeException ignored) {
            return 0;
        }
    }
}
