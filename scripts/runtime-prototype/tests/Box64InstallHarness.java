import com.wowforever.runtimeprobe.Box64Diagnostics;
import com.wowforever.runtimeprobe.Box64InstallPolicy;
import com.wowforever.runtimeprobe.Box64LaunchPlan;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Box64InstallHarness {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected extracted Box64 path and rootfs");
        File asset = new File(args[0]);
        File rootfs = new File(args[1]);
        if (!Box64Diagnostics.isExecutableFile(asset)) throw new AssertionError("extracted asset is not executable");
        String evidence = Box64Diagnostics.describe("BOX64-HARNESS", asset);
        if (!evidence.contains("machine=AArch64") || !evidence.contains("ptInterp=/data/data/it.wowmobile/files/rootfs/lib/ld-linux-aarch64.so.1")) {
            throw new AssertionError("ELF evidence did not preserve machine/interpreter: " + evidence);
        }
        if (!Box64Diagnostics.isLaunchable(rootfs, asset)) throw new AssertionError("bundled loader was not resolved");
        File loader = Box64Diagnostics.resolveInterpreter(rootfs, asset);
        String command = Box64LaunchPlan.command(rootfs, asset, "wine cmd /c probe.bat");
        if (!command.startsWith(loader.getAbsolutePath() + " --library-path " + new File(rootfs, "lib").getAbsolutePath())) {
            throw new AssertionError("launch command did not use explicit loader: " + command);
        }
        if (!command.contains(" " + asset.getAbsolutePath() + " wine cmd /c probe.bat")) {
            throw new AssertionError("launch command lost Box64/guest argv: " + command);
        }

        Path root = Files.createTempDirectory("box64-install-policy");
        File valid = root.resolve("box64").toFile();
        Files.write(valid.toPath(), new byte[]{1});
        valid.setExecutable(true, false);
        if (!Box64InstallPolicy.needsExtraction("0.4.0", "0.4.0", false)) throw new AssertionError("missing executable was accepted");
        if (!Box64InstallPolicy.needsExtraction("0.4.0", "0.3.0", true)) throw new AssertionError("stale marker was accepted");
        if (Box64InstallPolicy.mayCommitMarker(false, true)) throw new AssertionError("failed extraction committed marker");
        if (Box64InstallPolicy.mayCommitMarker(true, false)) throw new AssertionError("invalid executable committed marker");
        if (!Box64InstallPolicy.mayCommitMarker(true, true)) throw new AssertionError("successful extraction was rejected");
        if (Box64InstallPolicy.needsExtraction("0.4.0", "0.4.0", true) != false) throw new AssertionError("valid marker was not reusable");
        System.out.println("BOX64-ASSET-POLICY-PASS explicit-loader-stale-marker-missing-executable-extraction-failure-success-gating");
    }
}
