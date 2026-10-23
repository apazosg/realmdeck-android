package com.winlator.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeProcessDiagnosticsHarness {
    public static void main(String[] args) throws Exception {
        testParser();
        testPidFailure();
        testSuccessCapturesBothStreams();
        testRunSinkIsolation();
        testPartialFinalLineIsCaptured();
        testStartFailureCallbackOnce();
        testStopRetainsProcessSafety();
        testBoundedDrainTimeout();
        System.out.println("RUNTIME-PROCESS-DIAGNOSTICS-PASS parser-pid-failure-start-failure-streams-wait-sink-isolation-partial-line-bounded-drain");
    }

    private static void testParser() {
        String[] argv = ProcessHelper.splitCommand("tool one\\ two \"quoted value\"");
        if (argv.length != 3 || !"tool".equals(argv[0]) || !"one two".equals(argv[1]) ||
            !"\"quoted value\"".equals(argv[2])) throw new AssertionError("argv parser drift");
    }

    private static void testPidFailure() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        if (RuntimeProcessDiagnostics.readPidForDiagnostics(new FakeProcess(), events::add) != -1) {
            throw new AssertionError("fake process unexpectedly produced a PID");
        }
        if (!contains(events, "PROCESS-PID-ERROR")) throw new AssertionError("PID failure was not logged");
    }

    private static void testRunSinkIsolation() throws Exception {
        List<String> oldRun = Collections.synchronizedList(new ArrayList<>());
        List<String> newRun = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch oldFinished = new CountDownLatch(1);
        CountDownLatch newFinished = new CountDownLatch(1);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classPath = System.getProperty("java.class.path");
        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(oldRun::add);
        RuntimeProcessDiagnostics.setEnabled(true);
        ProcessHelper.exec(java + " -cp " + classPath + " com.winlator.core.RuntimeProcessDiagnosticsHarness$DelayedChild", null,
            new File("."), status -> oldFinished.countDown());
        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(newRun::add);
        ProcessHelper.exec(java + " -cp " + classPath + " com.winlator.core.RuntimeProcessDiagnosticsHarness$Child", null,
            new File("."), status -> newFinished.countDown());
        if (!oldFinished.await(5, TimeUnit.SECONDS) || !newFinished.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("sink isolation process timeout");
        }
        if (!contains(oldRun, "old-run-late") || contains(oldRun, "child-stdout") ||
            !contains(newRun, "child-stdout") || contains(newRun, "old-run-late")) {
            throw new AssertionError("run sink crossed callback generations: old=" + oldRun + " new=" + newRun);
        }
    }

    private static void testPartialFinalLineIsCaptured() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(1);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classPath = System.getProperty("java.class.path");
        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(events::add);
        ProcessHelper.exec(java + " -cp " + classPath + " com.winlator.core.RuntimeProcessDiagnosticsHarness$PartialChild", null,
            new File("."), status -> finished.countDown());
        if (!finished.await(5, TimeUnit.SECONDS) || !contains(events, "PROCESS-STDOUT partial-stdout") ||
            !contains(events, "PROCESS-STDERR partial-stderr")) {
            throw new AssertionError("partial final line was dropped: " + events);
        }
    }

    private static void testSuccessCapturesBothStreams() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classPath = System.getProperty("java.class.path");
        EnvVars env = new EnvVars().put("PATH", "diagnostic-path").put("SECRET_TOKEN", "must-not-log");
        RuntimeProcessDiagnostics.setEnabled(true);
        ProcessHelper.addDebugCallback(events::add);
        int pid = ProcessHelper.exec(java + " -cp " + classPath + " com.winlator.core.RuntimeProcessDiagnosticsHarness$Child", env,
            new File("."), status -> { if (status != 0) throw new AssertionError("child status=" + status); callbacks.incrementAndGet(); finished.countDown(); });
        if (pid == 0) throw new AssertionError("unsafe zero PID returned");
        if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("success callback timeout");
        if (callbacks.get() != 1 || !contains(events, "PROCESS-STDOUT child-stdout") ||
            !contains(events, "PROCESS-STDERR child-stderr") || !contains(events, "PROCESS-WAIT-EXIT status=0")) {
            throw new AssertionError("success diagnostics incomplete: " + events);
        }
        if (contains(events, "SECRET_TOKEN") || !contains(events, "PATH=diagnostic-path")) {
            throw new AssertionError("environment allowlist failed: " + events);
        }
    }

    private static void testStartFailureCallbackOnce() throws Exception {
        List<String> events = new ArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        RuntimeProcessDiagnostics.exec(new String[]{"/definitely/missing/runtime-probe"}, null, new File("."), status -> {
            if (status == 0) throw new AssertionError("start failure returned success");
            callbacks.incrementAndGet();
            finished.countDown();
        }, events::add);
        if (!finished.await(2, TimeUnit.SECONDS) || callbacks.get() != 1 || !contains(events, "PROCESS-START-ERROR")) {
            throw new AssertionError("start failure callback was not exactly once: " + events);
        }
    }

    private static void testStopRetainsProcessSafety() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classPath = System.getProperty("java.class.path");
        RuntimeProcessDiagnostics.exec(new String[]{java, "-cp", classPath, "com.winlator.core.RuntimeProcessDiagnosticsHarness$Sleeper"}, null,
            new File("."), status -> finished.countDown(), line -> {});
        if (!RuntimeProcessDiagnostics.stopAllAndAwait(5000L) || !finished.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("stopped process callback timeout");
        }
    }

    private static void testBoundedDrainTimeout() throws Exception {
        long started = System.nanoTime();
        boolean quiescent = RuntimeProcessDiagnostics.await(new CountDownLatch(1), TimeUnit.MILLISECONDS.toNanos(100L));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (quiescent || elapsedMs >= 2000L) throw new AssertionError("bounded drain was not reported: quiescent=" + quiescent + " ms=" + elapsedMs);
    }

    private static boolean contains(List<String> values, String needle) {
        for (String value : values) if (value.contains(needle)) return true;
        return false;
    }

    private static final class FakeProcess extends Process {
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() {}
    }

    public static final class Child {
        public static void main(String[] args) { System.out.println("child-stdout"); System.err.println("child-stderr"); }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(30000L); }
    }

    public static final class DelayedChild {
        public static void main(String[] args) throws Exception { Thread.sleep(300L); System.out.println("old-run-late"); }
    }

    public static final class PartialChild {
        public static void main(String[] args) { System.out.print("partial-stdout"); System.err.print("partial-stderr"); }
    }

}
