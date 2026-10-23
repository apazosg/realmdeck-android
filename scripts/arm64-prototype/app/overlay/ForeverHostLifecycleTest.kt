package app.gamenative

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.ProcessHelper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForeverHostLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realProcessHelperVetoDoesNotConsumeSpawnFailureOrStartAWaiter() {
        val before = AtomicInteger()
        val started = AtomicInteger()
        val failed = AtomicInteger()
        val finished = AtomicInteger()
        val callbacks = AtomicInteger()
        val field = ProcessHelper::class.java.getDeclaredField("execObserver").apply { isAccessible = true }
        val old = field.get(null) as ProcessHelper.ExecObserver?
        ProcessHelper.setExecObserver(object : ProcessHelper.ExecObserver {
            override fun beforeStart(command: String?, env: Array<String>?, cwd: File?): Boolean {
                before.incrementAndGet(); return false
            }
            override fun onStarted(command: String?, env: Array<String>?, cwd: File?, process: Process?): Boolean {
                started.incrementAndGet(); return true
            }
            override fun onFinished(process: Process?, status: Int) { finished.incrementAndGet() }
            override fun onStartFailed(command: String?, env: Array<String>?, error: Throwable?) { failed.incrementAndGet() }
        })
        try {
            val result = ProcessHelper.exec("/definitely-not-a-real-executable", emptyArray(), temporary.root) { status ->
                assertEquals(-1, status); callbacks.incrementAndGet()
            }
            assertEquals(-1, result)
            assertEquals(1, before.get())
            assertEquals(1, callbacks.get())
            assertEquals(0, started.get())
            assertEquals(0, failed.get())
            assertEquals(0, finished.get())
        } finally { ProcessHelper.setExecObserver(old) }
    }

    @Test fun lateXServerCallbacksCannotChangeAConfirmedPreSpawnCancellation() {
        val context = FixtureContext(temporary.newFolder("host"))
        val controller = ForeverLaunchController.get(context)
        val store = ForeverRunStoreRegistry.get(context)
        val run = store.begin()
        store.update(run.runId) { it.copy(launchAttempted = true, terminationUnknown = false, quiescenceConfirmed = true) }
        store.finish(run.runId, ForeverRunStatus.CANCELLED, "confirmed_pre_spawn_failure")
        setField(controller, "activeRunId", run.runId)
        setField(controller, "activeToken", 1L)
        setField(controller, "terminalGate", true)
        val before = store.read(run.runId)
        val uiBefore = controller.state.value
        val journal = File(context.filesDir, "forever/runs/${run.runId}-diagnostics.jsonl")
        val journalBefore = journal.readBytes()

        controller.onGameExit(run.runId)
        controller.onLaunchError(run.runId, "late old XServer error")

        assertEquals(before, store.read(run.runId))
        assertEquals(uiBefore, controller.state.value)
        assertArrayEquals(journalBefore, journal.readBytes())
        assertFalse(controller.state.value.blockedByUnknownProcess)
        assertSame(controller, ForeverLaunchController.get(context))
    }

    private fun setField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private class FixtureContext(private val directory: File) : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.example.forever.host${directory.name.hashCode().toUInt()}"
    }
}
