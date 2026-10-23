package com.wowforever.fexprobe;

import com.winlator.core.Callback;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic-only process adapter. The returned PID is only provenance; cleanup
 * always uses this owned Process token and never signals an arbitrary PID.
 */
public final class FexProcessDiagnostics {
    private static volatile boolean enabled;
    private static volatile ProcessToken active;
    private static volatile boolean terminationUnknown;
    private static final Object ACTIVE_LOCK = new Object();

    private FexProcessDiagnostics() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        synchronized (ACTIVE_LOCK) {
            // A timed-out process remains a session-wide safety stop until the
            // owned process is gone. A later, clean session may start fresh.
            if (value && !enabled && active == null) terminationUnknown = false;
            enabled = value;
        }
    }

    public static int exec(
            String[] argv,
            String[] envp,
            File workingDir,
            Callback<Integer> terminationCallback,
            List<Callback<String>> sinks) {
        final List<Callback<String>> fixedSinks = new ArrayList<>(sinks);
        final AtomicBoolean callbackOnce = new AtomicBoolean(false);
        final java.lang.Process process;
        final ProcessToken token;
        try {
            synchronized (ACTIVE_LOCK) {
                if (terminationUnknown) {
                    emit(fixedSinks, "PROCESS-START-ERROR termination-unknown session-stopped");
                    complete(terminationCallback, callbackOnce, 125);
                    return -2;
                }
                if (active != null) {
                    emit(fixedSinks, "PROCESS-START-ERROR already-active");
                    complete(terminationCallback, callbackOnce, 125);
                    return -2;
                }
                ProcessBuilder builder = new ProcessBuilder(argv);
                if (workingDir != null) builder.directory(workingDir);
                if (envp != null) {
                    builder.environment().clear();
                    for (String value : envp) {
                        int separator = value.indexOf('=');
                        if (separator > 0) {
                            builder.environment().put(value.substring(0, separator), value.substring(separator + 1));
                        }
                    }
                }
                Map<String, String> actualEnvironment = builder.environment();
                boolean guestWine = isGuestWine(argv);
                emit(fixedSinks, "PROCESS-EXEC guest_wine=" + guestWine + " " + renderArgv(argv));
                emit(fixedSinks, "PROCESS-ENV HODLL=" + value(actualEnvironment, "HODLL")
                        + " WINEPREFIX=" + value(actualEnvironment, "WINEPREFIX")
                        + " PATH=" + value(actualEnvironment, "PATH")
                        + " DISPLAY=" + value(actualEnvironment, "DISPLAY"));
                if (guestWine) {
                    String hodll = value(actualEnvironment, "HODLL");
                    if (!ProbeRunStore.HODLL.equals(hodll)) {
                        emit(fixedSinks, "PROCESS-START-ERROR non-FEX-HODLL=" + hodll);
                        complete(terminationCallback, callbackOnce, 126);
                        return -1;
                    }
                    if (!isDirectProton10Arm64EcWine(argv[0])) {
                        emit(fixedSinks, "PROCESS-START-ERROR non-ARM64EC-Wine path=" + argv[0]);
                        complete(terminationCallback, callbackOnce, 126);
                        return -1;
                    }
                }
                process = builder.start();
                token = new ProcessToken(process);
                active = token;
            }
        }
        catch (Throwable error) {
            emit(fixedSinks, "PROCESS-START-ERROR " + error.getClass().getSimpleName() + ": " + safe(error));
            complete(terminationCallback, callbackOnce, 127);
            return -1;
        }

        emit(fixedSinks, "PROCESS-START pid=" + token.pid);
        Thread stdout = stream(process.getInputStream(), "PROCESS-STDOUT", fixedSinks);
        Thread stderr = stream(process.getErrorStream(), "PROCESS-STDERR", fixedSinks);
        stdout.start();
        stderr.start();
        Thread waiter = new Thread(() -> {
            int status;
            try {
                status = process.waitFor();
                stdout.join();
                stderr.join();
                emit(fixedSinks, "PROCESS-WAIT-EXIT status=" + status);
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                emit(fixedSinks, "PROCESS-WAIT-ERROR InterruptedException: " + safe(error));
                status = 125;
            }
            finally {
                token.finished = true;
                if (active == token) active = null;
            }
            complete(terminationCallback, callbackOnce, status);
        }, "fex-probe-process-wait");
        waiter.start();
        return token.pid;
    }

    public static boolean awaitQuiescence(long timeoutMs) {
        ProcessToken token = active;
        if (token == null) return true;
        return token.await(timeoutMs);
    }

    public static boolean stopAndAwait(long timeoutMs, List<Callback<String>> sinks) {
        ProcessToken token = active;
        if (token == null) return true;
        emit(sinks, "PROCESS-CLEANUP destroy-owned-process pid=" + token.pid);
        token.process.destroy();
        boolean done = token.await(timeoutMs);
        if (!done) terminationUnknown = true;
        emit(sinks, "PROCESS-CLEANUP completed=" + done + " termination_unknown=" + !done);
        return done;
    }

    private static boolean isGuestWine(String[] argv) {
        if (argv == null || argv.length == 0 || argv[0] == null) return false;
        String executable = argv[0].replace('\\', '/');
        int slash = executable.lastIndexOf('/');
        String name = slash >= 0 ? executable.substring(slash + 1) : executable;
        return "wine".equalsIgnoreCase(name) || "wine64".equalsIgnoreCase(name);
    }

    private static boolean isDirectProton10Arm64EcWine(String executable) {
        if (executable == null) return false;
        String normalized = executable.replace('\\', '/').toLowerCase(java.util.Locale.US);
        return normalized.contains("/proton-10-arm64ec/") && normalized.endsWith("/bin/wine");
    }

    private static String value(Map<String, String> environment, String name) {
        String result = environment.get(name);
        return result == null || result.isEmpty() ? "<unset>" : result;
    }

    private static String renderArgv(String[] argv) {
        StringBuilder result = new StringBuilder("argv=");
        if (argv == null) return result.append("[]").toString();
        result.append('[');
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) result.append(", ");
            result.append('[').append(argv[i]).append(']');
        }
        return result.append(']').toString();
    }

    private static Thread stream(InputStream input, String label, List<Callback<String>> sinks) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) emit(sinks, label + " " + line);
            }
            catch (IOException error) {
                emit(sinks, "PROCESS-STREAM-ERROR " + label + " " + error.getClass().getSimpleName() + ": " + safe(error));
            }
        }, "fex-probe-process-stream");
    }

    private static void complete(Callback<Integer> callback, AtomicBoolean once, int status) {
        if (callback != null && once.compareAndSet(false, true)) callback.call(status);
    }

    private static void emit(List<Callback<String>> sinks, String line) {
        for (Callback<String> sink : sinks) {
            try {
                sink.call(line);
            }
            catch (Throwable ignored) {}
        }
    }

    private static String safe(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static int readPid(java.lang.Process process) {
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            boolean accessible = field.isAccessible();
            field.setAccessible(true);
            int pid = field.getInt(process);
            field.setAccessible(accessible);
            return pid > 0 ? pid : -1;
        }
        catch (Throwable ignored) {
            return -1;
        }
    }

    private static final class ProcessToken {
        final java.lang.Process process;
        final int pid;
        volatile boolean finished;

        ProcessToken(java.lang.Process process) {
            this.process = process;
            this.pid = readPid(process);
        }

        boolean await(long timeoutMs) {
            if (finished) return true;
            try {
                process.waitFor(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
                return processExited();
            }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean processExited() {
            try { process.exitValue(); return true; }
            catch (IllegalThreadStateException stillRunning) { return false; }
        }
    }
}
