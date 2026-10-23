package com.wowforever.launcher

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Cola de una sola ejecución. El hilo que llama a JNI puede superar el timeout;
 * por eso se informa TIMEOUT pero se espera a que termine antes de iniciar otra
 * sonda. No se intenta matar ni se finge cancelar el hilo nativo. */
class DiagnosticCoordinator private constructor() {
    private val monitor = Any()
    private var store: DiagnosticStore? = null
    private var current: DiagnosticRun? = null
    private var running = false
    private var listener: ((DiagnosticRun) -> Unit)? = null

    fun attach(newStore: DiagnosticStore, newListener: (DiagnosticRun) -> Unit) {
        val publication: Pair<((DiagnosticRun) -> Unit)?, DiagnosticRun?> = synchronized(monitor) {
            store = newStore
            listener = newListener
            val loaded = current ?: newStore.latest()
            current = loaded
            if (loaded != null && !running) newStore.recoverInterrupted(loaded)?.also { current = it }
            listener to current
        }
        publish(publication.first, publication.second)
    }

    fun detach(detached: (DiagnosticRun) -> Unit) {
        synchronized(monitor) {
            if (listener === detached) listener = null
        }
    }

    fun start(
        runId: String,
        tests: List<DiagnosticTest>,
        appVersion: String,
        abi: String,
        targetSdk: Int,
        executor: (String) -> DiagnosticExecution,
    ): DiagnosticStart {
        val publication: Pair<((DiagnosticRun) -> Unit)?, DiagnosticRun?>
        val activeStore: DiagnosticStore
        val run: DiagnosticRun
        synchronized(monitor) {
            if (running) return DiagnosticStart(false, "otra corrida sigue en curso")
            if (current?.runId == runId && current?.status != DiagnosticStatus.PENDING) {
                return DiagnosticStart(false, "run_id ya usado; se rechaza una segunda corrida: $runId")
            }
            activeStore = store ?: return DiagnosticStart(false, "persistencia aún no está lista")
            run = try {
                activeStore.createRun(runId, appVersion, abi, targetSdk = targetSdk, tests = tests)
            } catch (error: DiagnosticStoreException) {
                return DiagnosticStart(false, error.message ?: "run_id rechazado")
            }
            current = run
            running = true
            publication = listener to run
        }
        publish(publication.first, publication.second)
        Thread({
            val completed = execute(activeStore, run, tests, executor)
            val completion: Pair<((DiagnosticRun) -> Unit)?, DiagnosticRun?>
            synchronized(monitor) {
                current = completed
                running = false
                completion = listener to current
            }
            publish(completion.first, completion.second)
        }, "diagnostic-queue").start()
        return DiagnosticStart(true)
    }

    fun current(): DiagnosticRun? = synchronized(monitor) { current }

    private fun execute(
        activeStore: DiagnosticStore,
        initial: DiagnosticRun,
        tests: List<DiagnosticTest>,
        executor: (String) -> DiagnosticExecution,
    ): DiagnosticRun {
        var run = initial
        for (test in tests) {
            val started = System.currentTimeMillis()
            run = updateResult(activeStore, run, test.id) {
                it.copy(status = DiagnosticStatus.RUNNING, startedAtMs = started)
            }
            log(activeStore, run, "phase=${phase(test.id)} status=START")
            val finished = AtomicBoolean(false)
            var execution = DiagnosticExecution(DiagnosticStatus.FAIL, "sin resultado")
            val serialGate = DiagnosticSerialGate()
            check(serialGate.tryStart())
            val nativeThread = Thread({
                execution = try {
                    executor(test.id)
                } catch (error: UnsatisfiedLinkError) {
                    DiagnosticExecution(DiagnosticStatus.UNSUPPORTED, "biblioteca nativa no disponible: $error")
                } catch (error: Throwable) {
                    DiagnosticExecution(DiagnosticStatus.FAIL, "${error::class.java.simpleName}: $error")
                } finally {
                    finished.set(true)
                }
            }, "diagnostic-${test.id}")
            nativeThread.start()

            var timedOut = false
            val deadline = System.nanoTime() + test.timeoutMs * 1_000_000
            while (!finished.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                nativeThread.join(minOf(100L, remaining / 1_000_000L + 1))
            }
            val ended = System.currentTimeMillis()
            if (timedOut) {
                serialGate.markTimedOut()
                run = updateResult(activeStore, run, test.id) {
                    it.copy(
                        status = DiagnosticStatus.TIMEOUT,
                        finishedAtMs = ended,
                        detail = "límite ${test.timeoutMs} ms; la llamada nativa sigue viva y no se cancela",
                    )
                }
                log(activeStore, run, "phase=${phase(test.id)} result=TIMEOUT detail=native_call_still_alive")
                emitNativeLog(run, test, DiagnosticStatus.TIMEOUT, "native_call_still_alive")
                // Crucial: no se inicia el siguiente test hasta que esta sonda
                // haya salido por sí misma.
                nativeThread.join()
                serialGate.markFinished()
                val completion = execution.detail.replace('\n', ' ')
                log(activeStore, run, "phase=${phase(test.id)} completed_after_timeout=$completion")
            } else {
                nativeThread.join()
                serialGate.markFinished()
                run = updateResult(activeStore, run, test.id) {
                    it.copy(
                        status = execution.status,
                        finishedAtMs = ended,
                        detail = execution.detail,
                    )
                }
                log(activeStore, run, "phase=${phase(test.id)} result=${execution.status} detail=${execution.detail}")
                emitNativeLog(run, test, execution.status, execution.detail)
            }
        }
        run = run.copy(
            finishedAtMs = System.currentTimeMillis(),
            status = aggregate(run.results),
        )
        synchronized(monitor) { activeStore.save(run) }
        log(activeStore, run, "status=${run.status}")
        return run
    }

    private fun updateResult(
        activeStore: DiagnosticStore,
        run: DiagnosticRun,
        testId: String,
        transform: (DiagnosticResult) -> DiagnosticResult,
    ): DiagnosticRun {
        val updated: DiagnosticRun
        val callback: ((DiagnosticRun) -> Unit)?
        synchronized(monitor) {
            updated = run.copy(
                results = run.results.map { if (it.testId == testId) transform(it) else it },
                status = DiagnosticStatus.RUNNING,
            )
            activeStore.save(updated)
            current = updated
            callback = listener
        }
        publish(callback, updated)
        return updated
    }

    private fun log(activeStore: DiagnosticStore, run: DiagnosticRun, line: String) {
        activeStore.appendLog(run.runId, "run_id=${run.runId} $line")
    }

    private fun emitNativeLog(
        run: DiagnosticRun,
        test: DiagnosticTest,
        status: DiagnosticStatus,
        detail: String,
    ) {
        val safeDetail = detail.replace('\n', ' ').replace('\r', ' ')
        Log.i(TAG, "run_id=${run.runId} phase=${phase(test.id)} result=$status detail=$safeDetail")
    }

    private fun publish(callback: ((DiagnosticRun) -> Unit)?, run: DiagnosticRun?) {
        if (callback != null && run != null) callback.invoke(run)
    }

    private fun aggregate(results: List<DiagnosticResult>): DiagnosticStatus = when {
        results.any { it.status == DiagnosticStatus.FAIL } -> DiagnosticStatus.FAIL
        results.any { it.status == DiagnosticStatus.TIMEOUT } -> DiagnosticStatus.TIMEOUT
        results.any { it.status == DiagnosticStatus.UNSUPPORTED } -> DiagnosticStatus.UNSUPPORTED
        results.all { it.status == DiagnosticStatus.PASS } -> DiagnosticStatus.PASS
        else -> DiagnosticStatus.FAIL
    }

    companion object {
        private const val TAG = "ForeverJNI"
        private val singleton = DiagnosticCoordinator()

        fun shared(): DiagnosticCoordinator = singleton

        fun phase(testId: String): String = when (testId) {
            DiagnosticCatalog.CONTRACT -> "contract"
            DiagnosticCatalog.EXEC_PROBE -> "execprobe"
            DiagnosticCatalog.SELF_TEST -> "selftest"
            else -> testId
        }
    }
}
