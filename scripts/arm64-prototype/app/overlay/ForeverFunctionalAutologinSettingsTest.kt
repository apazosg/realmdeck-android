package app.gamenative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ForeverFunctionalAutologinSettingsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun preferenceDefaultsDisabledAndPersistsOnlyABoolean() {
        val preferences = context.getSharedPreferences(
            "forever_functional_autologin_preferences",
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()

        assertFalse(ForeverFunctionalAutologinPreferences.load(context).enabled)
        ForeverFunctionalAutologinPreferences.setEnabled(context, true)

        assertTrue(ForeverFunctionalAutologinPreferences.load(context).enabled)
        assertEquals(setOf("enabled"), preferences.all.keys)
        assertTrue(preferences.all.values.all { it is Boolean })

        ForeverFunctionalAutologinPreferences.setEnabled(context, false)
        assertFalse(ForeverFunctionalAutologinPreferences.load(context).enabled)
        preferences.edit().clear().commit()
    }

    @Test
    fun falseCommitFailureStillAttemptsVaultCleanupAndNeverEnables() {
        val prefs = FakePreferences(enabled = true, failFalseCommits = 1)
        val store = FakeStore()
        val result = service(prefs, store).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = false,
            checkActive = {},
        )

        assertFalse(result.success)
        assertEquals(1, store.cleanupCalls)
        assertFalse(prefs.enabled)
        assertTrue(prefs.falseCommitCalls >= 2)
    }

    @Test
    fun trueCommitFailureCompensatesVaultAndForcesFalse() {
        val prefs = FakePreferences(failTrueCommits = 1)
        val store = FakeStore()
        val result = service(prefs, store).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = true,
            checkActive = {},
        )

        assertFalse(result.success)
        assertEquals(1, store.saveCalls)
        assertEquals(1, store.cleanupCalls)
        assertFalse(prefs.enabled)
        assertEquals("AUTLOGIN_SAVE_FAILED", result.errorCode)
    }

    @Test
    fun vaultSaveFailureIsCompensated() {
        val prefs = FakePreferences()
        val store = FakeStore(failSave = true)
        val result = service(prefs, store).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = true,
            checkActive = {},
        )

        assertFalse(result.success)
        assertEquals(1, store.cleanupCalls)
        assertFalse(prefs.enabled)
    }

    @Test
    fun cancellationBeforeVaultMutationDoesNotPublishSuccess() {
        val prefs = FakePreferences()
        val store = FakeStore()
        val result = service(prefs, store).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = false,
            checkActive = { throw CancellationException() },
        )

        assertFalse(result.success)
        assertTrue(result.cancelled)
        assertEquals(0, store.saveCalls)
        assertEquals(1, store.cleanupCalls)
        assertFalse(prefs.enabled)
    }

    @Test
    fun cancellationAfterVaultMutationCompensatesAndDoesNotEnable() {
        val prefs = FakePreferences()
        val store = FakeStore()
        var checks = 0
        val result = service(prefs, store).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = false,
            checkActive = {
                checks++
                if (checks == 3) throw CancellationException()
            },
        )

        assertFalse(result.success)
        assertTrue(result.cancelled)
        assertEquals(1, store.saveCalls)
        assertEquals(1, store.cleanupCalls)
        assertFalse(prefs.enabled)
    }

    @Test
    fun cleanupFailureNeverClaimsDeletedOrSuccessful() {
        val result = service(FakePreferences(), FakeStore(failCleanup = true)).cleanup()

        assertFalse(result.success)
        assertEquals("AUTLOGIN_CLEANUP_FAILED", result.errorCode)
    }

    @Test
    fun saveCompensationFailuresUseOpaqueAggregateCode() {
        val result = service(
            FakePreferences(failFalseCommits = 2),
            FakeStore(failCleanup = true),
        ).save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = false,
            checkActive = {},
        )

        assertFalse(result.success)
        assertEquals("AUTLOGIN_SAVE_COMPENSATION_FAILED", result.errorCode)
    }

    @Test
    fun reentryRequiredDisablesStalePreferenceDuringLoad() {
        val prefs = FakePreferences(enabled = true)
        val loaded = service(prefs, FakeStore(requiresReentry = true)).load()

        assertFalse(loaded.desiredEnabled)
        assertFalse(loaded.effectiveEnabled)
        assertFalse(prefs.enabled)
    }

    @Test
    fun rememberedFileWithoutAcquiredSessionIsPendingNotActive() {
        val loaded = service(
            FakePreferences(enabled = true),
            FakeStore(remembered = true),
        ).load()

        assertTrue(loaded.desiredEnabled)
        assertFalse(loaded.effectiveEnabled)
        assertTrue(loaded.pendingVerification)
    }

    @Test
    fun passwordToolbarNeverExecutesCopyCutPasteOrSelectAllCallbacks() {
        var callbackCalls = 0
        val callback: () -> Unit = { callbackCalls++ }

        assertEquals(TextToolbarStatus.Hidden, ForeverFunctionalAutologinNoopTextToolbar.status)
        ForeverFunctionalAutologinNoopTextToolbar.showMenu(
            Rect(0f, 0f, 1f, 1f),
            callback,
            callback,
            callback,
            callback,
        )

        assertEquals(0, callbackCalls)
        // The real field-gesture/device gate remains pending; this seam proves no toolbar action
        // can reach clipboard callbacks before that gate is independently validated.
    }

    @Test
    fun oldOwnerCannotCommitAfterNewOwnerAndCannotClearNewState() {
        val coordinator = ForeverFunctionalAutologinSettingsCoordinator()
        val prefs = FakePreferences()
        val store = FakeStore()
        val old = service(prefs, store, coordinator)
        var newer: ForeverFunctionalAutologinSettingsService? = null
        store.onSave = { newer = service(prefs, store, coordinator) }

        val oldResult = old.save(
            requestedEnabled = true,
            username = "old-user".toCharArray(),
            password = "old-password".toCharArray(),
            remember = false,
            checkActive = {},
        )
        assertFalse(oldResult.success)
        val newService = requireNotNull(newer)
        store.onSave = null
        val newResult = newService.save(
            requestedEnabled = true,
            username = "new-user".toCharArray(),
            password = "new-password".toCharArray(),
            remember = false,
            checkActive = {},
        )

        assertTrue(newResult.success)
        assertEquals("new-user", store.activeUser)
        assertTrue(prefs.enabled)
    }

    @Test
    fun oldPostCommitCancellationCannotWipeNewOwnerState() {
        val coordinator = ForeverFunctionalAutologinSettingsCoordinator()
        val prefs = FakePreferences()
        val store = FakeStore()
        val old = service(prefs, store, coordinator)
        val oldResult = old.save(
            requestedEnabled = true,
            username = "old-user".toCharArray(),
            password = "old-password".toCharArray(),
            remember = false,
            checkActive = {},
        )
        assertTrue(oldResult.success)

        val newer = service(prefs, store, coordinator)
        val newResult = newer.save(
            requestedEnabled = true,
            username = "new-user".toCharArray(),
            password = "new-password".toCharArray(),
            remember = false,
            checkActive = {},
        )
        assertTrue(newResult.success)
        assertEquals(0, store.cleanupCalls)
        val cleanup = old.cleanup()

        assertFalse(cleanup.success)
        assertEquals(0, store.cleanupCalls)
        assertEquals("new-user", store.activeUser)
    }

    @Test
    fun loadResultFromOlderGenerationIsNotPublishable() {
        val coordinator = ForeverFunctionalAutologinSettingsCoordinator()
        val prefs = FakePreferences(enabled = true)
        val store = FakeStore()
        var newer: ForeverFunctionalAutologinSettingsService? = null
        prefs.onLoad = { newer = service(prefs, store, coordinator) }
        val old = service(prefs, store, coordinator)

        val loaded = old.load()

        assertFalse(loaded.publishable)
        assertTrue(newer != null)
    }

    @Test
    fun invalidationDuringCleanupDoesNotSplitPreferenceAndVaultDeletion() {
        val coordinator = ForeverFunctionalAutologinSettingsCoordinator()
        val prefs = FakePreferences(enabled = true)
        val store = FakeStore()
        val active = service(prefs, store, coordinator)
        prefs.onSetFalse = { active.invalidateOwner() }

        val result = active.cleanup()

        assertTrue(result.success)
        assertFalse(prefs.enabled)
        assertEquals(1, store.cleanupCalls)
    }

    @Test
    fun loadAndSaveUseOneOrderedTransactionBoundary() {
        val events = mutableListOf<String>()
        val prefs = FakePreferences(events = events)
        val store = FakeStore(events = events)
        val service = service(prefs, store)

        service.load()
        service.save(
            requestedEnabled = true,
            username = "synthetic-user".toCharArray(),
            password = "synthetic-password".toCharArray(),
            remember = false,
            checkActive = {},
        )

        assertEquals(
            listOf("load", "summary", "false", "save", "true", "summary"),
            events,
        )
    }

    private fun service(
        preferences: FakePreferences,
        store: FakeStore,
        coordinator: ForeverFunctionalAutologinSettingsCoordinator =
            ForeverFunctionalAutologinSettingsCoordinator(),
    ) = ForeverFunctionalAutologinSettingsService(preferences, store, coordinator)

    private class FakePreferences(
        enabled: Boolean = false,
        var failFalseCommits: Int = 0,
        var failTrueCommits: Int = 0,
        private val events: MutableList<String> = mutableListOf(),
    ) : ForeverFunctionalAutologinSettingsPreferencesPort {
        private var enabledValue = enabled
        val enabled: Boolean get() = enabledValue
        var falseCommitCalls = 0
        var onLoad: (() -> Unit)? = null
        var onSetFalse: (() -> Unit)? = null

        override fun load(): ForeverFunctionalAutologinPreferences.Preferences {
            events += "load"
            onLoad?.invoke()
            return ForeverFunctionalAutologinPreferences.Preferences(enabledValue)
        }

        override fun setEnabled(enabled: Boolean) {
            if (enabled) {
                if (failTrueCommits > 0) {
                    failTrueCommits--
                    throw IllegalStateException()
                }
            } else {
                falseCommitCalls++
                events += "false"
                if (failFalseCommits > 0) {
                    failFalseCommits--
                    throw IllegalStateException()
                }
            }
            if (enabled) events += "true"
            enabledValue = enabled
            if (!enabled) onSetFalse?.invoke()
        }
    }

    private class FakeStore(
        private val failSave: Boolean = false,
        private val failCleanup: Boolean = false,
        private val requiresReentry: Boolean = false,
        private val remembered: Boolean = false,
        private val events: MutableList<String> = mutableListOf(),
    ) : ForeverFunctionalAutologinSettingsStorePort {
        var saveCalls = 0
        var cleanupCalls = 0
        var activeUser: String? = null
        var onSave: (() -> Unit)? = null

        override fun save(credentials: ForeverFunctionalAutologinCredentials, remember: Boolean) {
            saveCalls++
            events += "save"
            credentials.useValues { user, _ -> activeUser = String(user) }
            onSave?.invoke()
            credentials.close()
            if (failSave) throw IllegalStateException()
        }

        override fun acquire(): ForeverFunctionalAutologinCredentials? = null

        override fun settingsSummary(): ForeverFunctionalAutologinSettingsSummary {
            events += "summary"
            return ForeverFunctionalAutologinSettingsSummary(
                persistenceAvailable = true,
                remembered = remembered,
                sessionAvailable = saveCalls > 0,
                requiresReentry = requiresReentry,
                status = if (saveCalls > 0) {
                    ForeverFunctionalAutologinStatus.MEMORY_ONLY
                } else {
                    ForeverFunctionalAutologinStatus.EMPTY
                },
            )
        }

        override fun disableAndClear() {
            cleanupCalls++
            events += "clear"
            activeUser = null
            if (failCleanup) throw IllegalStateException()
        }
    }
}
