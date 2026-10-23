package com.winlator.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Probe-only supervision which retains Process objects if PID reflection fails. */
public final class RuntimeProcessDiagnostics {
    private static final int START_FAILURE_STATUS = 127;
    private static final int WAIT_FAILURE_STATUS = 126;
    private static final String[] ALLOWED_ENVIRONMENT = {
        "WINEPREFIX", "PATH", "DISPLAY", "WINEDEBUG", "BOX64_LOG"
    };
    private static final Object LOCK = new Object();
    private static final Map<java.lang.Process, ActiveProcess> ACTIVE = new IdentityHashMap<>();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private RuntimeProcessDiagnostics() {}

    public static boolean isEnabled() { return ENABLED.get(); }

    public static void setEnabled(boolean enabled) { ENABLED.set(enabled); }

    public static int exec(String[] argv, EnvVars envVars, File workingDir,
                           Callback<Integer> terminationCallback, Callback<String> lineCallback) {
        AtomicBoolean callbackDelivered = new AtomicBoolean(false);
        RunSink sink = new RunSink(lineCallback);
        emit(sink, "PROCESS-EXEC-BEGIN argv=" + formatArgv(argv) + " cwd=" + formatPath(workingDir));
        java.lang.Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDir);
            Map<String, String> environment = builder.environment();
            if (envVars != null) {
                for (String name : envVars) environment.put(name, envVars.get(name));
            }
            emit(sink, "PROCESS-START-BEFORE argv=" + formatArgv(argv) + " cwd=" + formatPath(workingDir)
                + " env=" + formatAllowedEnvironment(environment));
            process = builder.start();
            ActiveProcess active = new ActiveProcess(process, sink);
            synchronized (LOCK) { ACTIVE.put(process, active); }
            emit(sink, "PROCESS-START-AFTER");
        }
        catch (Exception e) {
            emit(sink, "PROCESS-START-ERROR " + exceptionEvidence(e));
            complete(sink, terminationCallback, callbackDelivered, START_FAILURE_STATUS, "start-error");
            sink.close();
            return -1;
        }

        ActiveProcess active;
        synchronized (LOCK) { active = ACTIVE.get(process); }
        int pid = readPidForDiagnostics(process, sink);
        createStreamThread(process.getInputStream(), "stdout", sink, active.readersDone);
        createStreamThread(process.getErrorStream(), "stderr", sink, active.readersDone);
        createWaitThread(active, terminationCallback, callbackDelivered);
        return pid;
    }

    /** Stops retained owner processes, never by guessed PID. */
    public static void stopAll() {
        ArrayList<ActiveProcess> processes;
        synchronized (LOCK) { processes = new ArrayList<>(ACTIVE.values()); }
        for (ActiveProcess active : processes) {
            try {
                if (active.process.isAlive()) active.process.destroy();
            }
            catch (RuntimeException ignored) {}
        }
    }

    /** Bounded owner-process/reader drain. Descendants are intentionally not claimed. */
    public static boolean stopAllAndAwait(long timeoutMs) {
        stopAll();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (true) {
            ArrayList<ActiveProcess> processes;
            synchronized (LOCK) {
                if (ACTIVE.isEmpty()) return true;
                processes = new ArrayList<>(ACTIVE.values());
            }
            for (ActiveProcess active : processes) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L || !await(active.quiesced, remaining)) {
                    closeActiveSinks();
                    return false;
                }
            }
        }
    }

    public static void awaitAllAsync(long timeoutMs, Callback<Boolean> completion) {
        startThread("runtime-process-cleanup", () -> {
            boolean quiescent = stopAllAndAwait(timeoutMs);
            if (completion != null) {
                try { completion.call(quiescent); }
                catch (Exception ignored) {}
            }
        });
    }

    static int readPidForDiagnostics(java.lang.Process process, Callback<String> lineCallback) {
        Field pidField = null;
        boolean accessible = false;
        try {
            pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            accessible = true;
            long pid = ((Number)pidField.get(process)).longValue();
            if (pid <= 0 || pid > Integer.MAX_VALUE) throw new IllegalStateException("invalid pid=" + pid);
            emit(lineCallback, "PROCESS-PID pid=" + pid);
            return (int)pid;
        }
        catch (Exception e) {
            emit(lineCallback, "PROCESS-PID-ERROR " + exceptionEvidence(e));
            return -1;
        }
        finally {
            if (accessible && pidField != null) {
                try { pidField.setAccessible(false); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    private static void createStreamThread(final InputStream input, final String stream,
                                           final RunSink sink, final CountDownLatch done) {
        startThread("runtime-process-" + stream, () -> {
            emit(sink, "PROCESS-READER-BEGIN stream=" + stream);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) emit(sink, "PROCESS-" + stream.toUpperCase() + " " + line);
                emit(sink, "PROCESS-READER-END stream=" + stream);
            }
            catch (IOException e) {
                emit(sink, "PROCESS-STREAM-ERROR stream=" + stream + " " + exceptionEvidence(e));
            }
            finally { done.countDown(); }
        });
    }

    private static void createWaitThread(final ActiveProcess active, final Callback<Integer> callback,
                                         final AtomicBoolean callbackDelivered) {
        emit(active.sink, "PROCESS-WAIT-BEGIN");
        startThread("runtime-process-wait", () -> {
            try {
                int status = active.process.waitFor();
                emit(active.sink, "PROCESS-WAIT-EXIT status=" + status);
                if (!await(active.readersDone, TimeUnit.SECONDS.toNanos(5))) {
                    emit(active.sink, "PROCESS-READER-DRAIN-TIMEOUT");
                }
                complete(active.sink, callback, callbackDelivered, status, "wait");
                active.sink.close();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emit(active.sink, "PROCESS-WAIT-ERROR " + exceptionEvidence(e));
                complete(active.sink, callback, callbackDelivered, WAIT_FAILURE_STATUS, "wait-error");
                active.sink.close();
            }
            finally {
                synchronized (LOCK) { ACTIVE.remove(active.process); }
                active.quiesced.countDown();
            }
        });
    }

    static boolean await(CountDownLatch latch, long nanos) {
        try { return latch.await(Math.max(0L, nanos), TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void complete(Callback<String> lineCallback, Callback<Integer> callback,
                                 AtomicBoolean delivered, int status, String source) {
        if (callback == null || !delivered.compareAndSet(false, true)) return;
        if (lineCallback != null) emit(lineCallback, "PROCESS-CALLBACK status=" + status + " source=" + source);
        try { callback.call(status); }
        catch (Exception e) { if (lineCallback != null) emit(lineCallback, "PROCESS-CALLBACK-ERROR " + exceptionEvidence(e)); }
    }

    private static void closeActiveSinks() {
        ArrayList<ActiveProcess> processes;
        synchronized (LOCK) { processes = new ArrayList<>(ACTIVE.values()); }
        for (ActiveProcess active : processes) active.sink.close();
    }

    private static void startThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void emit(Callback<String> callback, String line) {
        if (callback == null) return;
        try { callback.call(line); }
        catch (Exception ignored) {}
    }

    private static String formatArgv(String[] argv) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) result.append(", ");
            result.append(safeValue(argv[i]));
        }
        return result.append(']').toString();
    }

    private static String formatPath(File path) { return path == null ? "<default>" : safeValue(path.getPath()); }

    private static String formatAllowedEnvironment(Map<String, String> environment) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (String name : ALLOWED_ENVIRONMENT) {
            if (!environment.containsKey(name)) continue;
            if (!first) result.append(", ");
            result.append(name).append('=').append(safeValue(environment.get(name)));
            first = false;
        }
        return result.append('}').toString();
    }

    private static String exceptionEvidence(Exception e) {
        String message = e.getMessage();
        return "type=" + e.getClass().getSimpleName() + " message=" + safeValue(message == null ? "<none>" : message);
    }

    private static String safeValue(String value) {
        if (value == null) return "<null>";
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        return sanitized.length() > 512 ? sanitized.substring(0, 512) + "…" : sanitized;
    }

    private static final class ActiveProcess {
        private final java.lang.Process process;
        private final RunSink sink;
        private final CountDownLatch readersDone = new CountDownLatch(2);
        private final CountDownLatch quiesced = new CountDownLatch(1);

        private ActiveProcess(java.lang.Process process, RunSink sink) {
            this.process = process;
            this.sink = sink;
        }
    }

    private static final class RunSink implements Callback<String> {
        private final Callback<String> delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RunSink(Callback<String> delegate) { this.delegate = delegate; }

        @Override
        public void call(String line) {
            if (!closed.get() && delegate != null) delegate.call(line);
        }

        private void close() { closed.set(true); }
    }
}
