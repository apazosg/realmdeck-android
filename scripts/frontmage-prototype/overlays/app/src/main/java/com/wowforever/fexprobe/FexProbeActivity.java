package com.wowforever.fexprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.ImageFsInstaller;

import org.json.JSONObject;

import java.io.File;

/** Minimal foreground-only launcher; the guest uses the existing XServer screen. */
public final class FexProbeActivity extends AppCompatActivity {
    private final Handler handler = new Handler();
    private TextView status;
    private Button run;
    private Button export;
    private File pendingReport;
    private boolean initReady;
    private boolean busy;
    private long initDeadline;
    private long preparationDeadline;
    private boolean destroyed;
    private boolean installStarted;
    private boolean installCallbackCompleted;
    private boolean initTimeoutReported;
    private final Object lifecycleLock = new Object();

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        AppUtils.keepScreenOn(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Forever FEX Probe\nFrontMage 4293b75b / FEX2508 + Proton10 ARM64EC");
        status = new TextView(this);
        run = new Button(this);
        run.setText("Run one staged diagnostic");
        export = new Button(this);
        export.setText("Export latest report");
        root.addView(title);
        root.addView(status);
        root.addView(run);
        root.addView(export);
        setContentView(root);

        ProbeRunStore.reconcile(this);
        run.setOnClickListener(view -> startRun());
        export.setOnClickListener(view -> exportLatest());
        refreshReport();
        initialiseImageFs();
    }

    private void initialiseImageFs() {
        synchronized (lifecycleLock) {
            if (destroyed || installStarted) return;
            installStarted = true;
            installCallbackCompleted = false;
            initTimeoutReported = false;
            initDeadline = System.currentTimeMillis() + ProbeRunStore.INIT_TIMEOUT_MS;
        }
        status.setText("Verifying/recovering ImageFS 21 and Proton ARM64EC…");
        run.setEnabled(false);
        handler.postDelayed(() -> {
            boolean reportFailure = false;
            synchronized (lifecycleLock) {
                if (!destroyed && !installCallbackCompleted && !initTimeoutReported
                        && System.currentTimeMillis() >= initDeadline) {
                    initTimeoutReported = true;
                    reportFailure = true;
                }
            }
            if (reportFailure) {
                ProbeRunStore.recordPreparationFailure(this,
                        "INIT-TIMEOUT phase=ImageFS/proton extraction timeoutMs=" + ProbeRunStore.INIT_TIMEOUT_MS);
                status.setText("ImageFS installation is still running; Run remains disabled.");
                refreshReport();
            }
        }, ProbeRunStore.INIT_TIMEOUT_MS);
        ImageFsInstaller.installIfNeeded(this, success -> {
            synchronized (lifecycleLock) {
                if (destroyed) return;
            }
            runOnUiThread(() -> onImageFsReady(success));
        });
    }

    private void onImageFsReady(boolean success) {
        synchronized (lifecycleLock) {
            if (destroyed) return;
            installCallbackCompleted = true;
        }
        handler.removeCallbacksAndMessages(null);
        ImageFs imageFs = ImageFs.find(this);
        File wine = new File(imageFs.getRootDir(), "opt/proton-10-arm64ec/bin/wine");
        boolean confirmed = success && imageFs.isValid()
                && imageFs.getVersion() == ImageFsInstaller.LATEST_VERSION
                && wine.isFile() && wine.canExecute();
        if (confirmed) {
            initReady = true;
            status.setText("ImageFS 21 + Proton10 ARM64EC ready. Foreground run only; no WoW client is launched.");
            run.setEnabled(!busy);
        }
        else {
            initReady = false;
            status.setText(initTimeoutReported
                    ? "ImageFS installation did not complete; Run remains disabled."
                    : "ImageFS preflight failed; export the report if one exists.");
            run.setEnabled(false);
        }
    }

    private void startRun() {
        synchronized (lifecycleLock) {
            if (destroyed || !initReady || busy || initTimeoutReported) return;
            busy = true;
            preparationDeadline = System.currentTimeMillis() + ProbeRunStore.INIT_TIMEOUT_MS;
        }
        run.setEnabled(false);
        status.setText("Creating/preflighting one real container…");
        handler.postDelayed(() -> {
            boolean timedOut = false;
            synchronized (lifecycleLock) {
                if (!destroyed && busy && System.currentTimeMillis() >= preparationDeadline) {
                    busy = false;
                    timedOut = true;
                }
            }
            if (timedOut) {
                ProbeRunStore.recordPreparationFailure(this, "PREP-TIMEOUT phase=container/preflight timeoutMs=" + ProbeRunStore.INIT_TIMEOUT_MS);
                status.setText("Container preflight timeout; report is exportable.");
                refreshReport();
                run.setEnabled(initReady);
            }
        }, ProbeRunStore.INIT_TIMEOUT_MS);

        ContainerManager manager = new ContainerManager(this);
        Container existing = null;
        for (Container candidate : manager.getContainers()) {
            if ("Forever FEX Probe".equals(candidate.getName())) { existing = candidate; break; }
        }
        if (existing != null) {
            if (!canContinue()) return;
            manager.activateContainer(existing);
            prepareAndLaunch(existing);
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("name", "Forever FEX Probe");
            data.put("screenSize", "960x540");
            data.put("envVars", Container.DEFAULT_ENV_VARS + " HODLL=libwow64fex.dll");
            data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("dxwrapper", "wined3d");
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("wincomponents", Container.FALLBACK_WINCOMPONENTS);
            data.put("drives", "E:" + new File(getFilesDir(), "storage").getPath());
            data.put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE);
            data.put("wineVersion", "proton-10-arm64ec");
            manager.createContainerAsync(data, container -> {
                if (!canContinue()) return;
                if (container == null) preparationFailed("container creation failed");
                else { manager.activateContainer(container); prepareAndLaunch(container); }
            });
        }
        catch (Exception error) { preparationFailed(error.toString()); }
    }

    private void prepareAndLaunch(Container container) {
        new Thread(() -> {
            try {
                if (!canContinue()) return;
                String id = ProbeRunStore.prepare(this, container);
                runOnUiThread(() -> {
                    if (!canContinue()) {
                        ProbeRunStore.snapshot(this, id, "activity-destroyed before runtime launch");
                        return;
                    }
                    synchronized (lifecycleLock) { busy = false; }
                    Intent intent = new Intent(this, com.winlator.XServerDisplayActivity.class);
                    intent.putExtra("container_id", container.id);
                    intent.putExtra("fex_probe_mode", true);
                    intent.putExtra("fex_probe_run_id", id);
                    startActivity(intent);
                    status.setText("Runtime screen opened for " + id + "; export after completion.");
                    refreshReport();
                });
            }
            catch (Throwable error) { runOnUiThread(() -> preparationFailed(error.toString())); }
        }, "fex-probe-preflight").start();
    }

    private void preparationFailed(String reason) {
        synchronized (lifecycleLock) {
            if (destroyed || !busy) return;
            busy = false;
        }
        ProbeRunStore.recordPreparationFailure(this, reason);
        status.setText("Preflight failed: " + reason);
        refreshReport();
        run.setEnabled(initReady);
    }

    private void refreshReport() {
        pendingReport = ProbeRunStore.latestReport(this);
        export.setEnabled(pendingReport != null);
        if (pendingReport != null) status.setText("Latest report is ready to export: " + pendingReport.getParentFile().getName());
    }

    private void exportLatest() {
        pendingReport = ProbeRunStore.latestReport(this);
        if (pendingReport == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, pendingReport.getParentFile().getName() + ".txt");
        startActivityForResult(intent, 19);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 19 || resultCode != Activity.RESULT_OK || data == null) return;
        try {
            ProbeRunStore.export(this, pendingReport, data.getData());
            status.setText("Report exported.");
        }
        catch (Exception error) { status.setText("Export failed: " + error); }
    }

    @Override
    protected void onDestroy() {
        synchronized (lifecycleLock) {
            destroyed = true;
            busy = false;
            initReady = false;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean canContinue() {
        synchronized (lifecycleLock) {
            return !destroyed;
        }
    }
}
