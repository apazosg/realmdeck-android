package com.wowforever.runtimeprobe;

/** Pure gating rules for the private Box64 asset repair path. */
public final class Box64InstallPolicy {
    private Box64InstallPolicy() {}

    public static boolean needsExtraction(String requestedVersion, String marker, boolean executableValid) {
        return !executableValid || requestedVersion == null || !requestedVersion.equals(marker);
    }

    public static boolean mayCommitMarker(boolean extractionSucceeded, boolean executableValid) {
        return extractionSucceeded && executableValid;
    }
}
