package app.gamenative

import org.junit.Assert.*
import org.junit.Test

class ForeverFunctionalWowStartupTest {
    @Test fun windowIdentityGeometryAndContentMustAgree() {
        var signals = 0
        val startup = ForeverFunctionalWowStartup(1180, 540, "WowB-ARM64.exe", { true }, { signals++ })
        // Wine can use the full desktop too. A transient WoW window is not enough.
        startup.observe(1, "explorer.exe", 1180, 540, true, true)
        startup.observe(2, "WowB-ARM64.exe", 1180, 30, true, true)
        startup.observe(3, "WowB-ARM64.exe", 1180, 540, true, false)
        assertEquals(0, signals)
        // Content for another window cannot supply this window's evidence.
        startup.observe(3, "WowB-ARM64.exe", 1180, 540, true, true)
        startup.observe(3, "WowB-ARM64.exe", 1180, 540, true, true)
        assertEquals(1, signals)
    }

    @Test fun contentBeforeMappingAndLateClassStillSignalOnce() {
        var signals = 0
        val startup = ForeverFunctionalWowStartup(1180, 540, "WowB-ARM64.exe", { true }, { signals++ })
        startup.observe(1, "", 1, 1, false, true)
        startup.observe(1, "", 1180, 540, true, false)
        assertEquals(0, signals)
        startup.observe(1, "wowb-arm64.exe", 1180, 540, true, false)
        assertEquals(1, signals)
    }

    @Test fun unmappedContentAndSupersededOrClosedAttemptsCannotStart() {
        var current = true
        var signals = 0
        val startup = ForeverFunctionalWowStartup(1180, 540, "WowB-ARM64.exe", { current }, { signals++ })
        startup.observe(1, "", 1180, 540, false, true)
        startup.forget(1)
        startup.observe(1, "WowB-ARM64.exe", 1180, 540, true, false)
        assertEquals(0, signals)
        current = false
        startup.observe(1, "WowB-ARM64.exe", 1180, 540, true, true)
        assertEquals(0, signals)
        current = true
        startup.close()
        startup.observe(1, "WowB-ARM64.exe", 1180, 540, true, true)
        assertEquals(0, signals)
    }
}
