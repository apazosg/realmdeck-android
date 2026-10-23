import com.wowforever.runtimeprobe.ProbePrefix;
import com.wowforever.runtimeprobe.ProbeTerminalState;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProbeRuntimeInvariants {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("runtime-prototype-invariants");
        File containerRoot = root.resolve("rootfs/home/xuser-7").toFile();
        if (!new File(containerRoot, ".wine").mkdirs()) throw new AssertionError("prefix setup failed");
        File activated = root.resolve("rootfs/home/xuser").toFile();
        Files.createSymbolicLink(activated.toPath(), Path.of("xuser-7"));
        if (!ProbePrefix.isActivated(activated, containerRoot)) throw new AssertionError("activation was not recognized");
        if (!ProbePrefix.concreteWinePrefix(containerRoot).getCanonicalFile().equals(new File(containerRoot, ".wine").getCanonicalFile())) {
            throw new AssertionError("concrete prefix is not under the selected container");
        }
        Files.delete(activated.toPath());
        if (!activated.mkdir() || ProbePrefix.isActivated(activated, containerRoot)) throw new AssertionError("ordinary rootfs home passed activation");

        ProbeTerminalState terminal = new ProbeTerminalState();
        AtomicInteger winners = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Thread thread = new Thread(() -> {
                if (terminal.compareAndSet(false, true)) winners.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        if (winners.get() != 1 || terminal.compareAndSet(false, true)) throw new AssertionError("terminal state had more than one winner");
        System.out.println("PROBE-RUNTIME-INVARIANTS-PASS concrete-prefix-and-single-terminal-winner");
    }
}
