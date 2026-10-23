package com.wowforever.runtimeprobe;

import java.io.File;
import java.io.IOException;

/** Small, side-effect-free helpers for the prototype's concrete Wine prefix. */
public final class ProbePrefix {
    private ProbePrefix() {}

    public static File concreteWinePrefix(File containerRoot) {
        if (containerRoot == null) throw new IllegalArgumentException("container root is null");
        return new File(containerRoot, ".wine");
    }

    public static boolean isActivated(File activatedPath, File containerRoot) {
        if (activatedPath == null || containerRoot == null || !activatedPath.isDirectory()) return false;
        try {
            return activatedPath.getCanonicalFile().equals(containerRoot.getCanonicalFile());
        }
        catch (IOException e) {
            return false;
        }
    }
}
