package com.wowforever.runtimeprobe;

import java.io.File;

/** Builds the host-AArch64 command that bypasses Box64's stale PT_INTERP path. */
public final class Box64LaunchPlan {
    private Box64LaunchPlan() {}

    public static String command(File rootDir, File box64, String guestExecutable) {
        if (!Box64Diagnostics.isLaunchable(rootDir, box64)) {
            throw new IllegalArgumentException("Box64 loader is not usable under this rootfs");
        }
        File loader = Box64Diagnostics.resolveInterpreter(rootDir, box64);
        File libraryPath = new File(rootDir, "lib");
        return loader.getAbsolutePath() + " --library-path " + libraryPath.getAbsolutePath() +
            " " + box64.getAbsolutePath() + " " + guestExecutable;
    }
}
