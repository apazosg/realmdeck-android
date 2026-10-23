package app.gamenative

import org.json.JSONArray
import org.json.JSONObject

enum class ForeverStage {
    PREFLIGHT,
    RUNTIME,
    CLIENT,
    CONFIGURATION,
    LAUNCHING,
    RUNNING,
    STOPPING,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    EXITED,
}

enum class ForeverRunStatus {
    ACTIVE,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    EXITED,
}

data class ForeverRunState(
    val runId: String,
    val stage: ForeverStage,
    val status: ForeverRunStatus,
    val patchState: String = "pending",
    val renderState: String = "unverified",
    val loginState: String = "unverified",
    val message: String? = null,
    val events: List<String> = emptyList(),
    val argv: List<String> = emptyList(),
    val cwd: String? = null,
    val pid: Int? = null,
    val startTicks: Long? = null,
    val spawnAt: Long? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val terminationUnknown: Boolean = false,
    val rootPid: Int? = null,
    val rootStartTicks: Long? = null,
    val gamePid: Int? = null,
    val gameStartTicks: Long? = null,
    /** PID of this Android host process, distinct from the Wine wrapper PID. */
    val hostPid: Int? = null,
    val launchAttempted: Boolean = false,
    val bootId: String? = null,
    val quiescenceConfirmed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("stage", stage.name)
        .put("status", status.name)
        .put("patchState", patchState)
        .put("renderState", renderState)
        .put("loginState", loginState)
        .put("message", message ?: JSONObject.NULL)
        .put("events", JSONArray(events))
        .put("argv", JSONArray(argv))
        .put("cwd", cwd ?: JSONObject.NULL)
        .put("pid", pid ?: JSONObject.NULL)
        .put("startTicks", startTicks ?: JSONObject.NULL)
        .put("spawnAt", spawnAt ?: JSONObject.NULL)
        .put("exitCode", exitCode ?: JSONObject.NULL)
        .put("durationMs", durationMs ?: JSONObject.NULL)
        .put("terminationUnknown", terminationUnknown)
        .put("rootPid", rootPid ?: JSONObject.NULL)
        .put("rootStartTicks", rootStartTicks ?: JSONObject.NULL)
        .put("gamePid", gamePid ?: JSONObject.NULL)
        .put("gameStartTicks", gameStartTicks ?: JSONObject.NULL)
        .put("hostPid", hostPid ?: JSONObject.NULL)
        .put("launchAttempted", launchAttempted)
        .put("bootId", bootId ?: JSONObject.NULL)
        .put("quiescenceConfirmed", quiescenceConfirmed)

    companion object {
        fun fromJson(value: JSONObject): ForeverRunState = ForeverRunState(
            runId = value.getString("runId"),
            stage = ForeverStage.valueOf(value.getString("stage")),
            status = ForeverRunStatus.valueOf(value.getString("status")),
            patchState = value.optString("patchState", "pending"),
            renderState = value.optString("renderState", "unverified"),
            loginState = value.optString("loginState", "unverified"),
            message = value.optString("message").takeUnless { it.isEmpty() || it == "null" },
            events = value.optJSONArray("events")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index, null) }
            } ?: emptyList(),
            argv = value.optJSONArray("argv")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index, null) }
            } ?: emptyList(),
            cwd = value.optString("cwd").takeUnless { it.isEmpty() || it == "null" },
            pid = value.optInt("pid", 0).takeIf { it > 0 },
            startTicks = value.optLong("startTicks", 0L).takeIf { it > 0L },
            spawnAt = value.optLong("spawnAt", 0L).takeIf { it > 0L },
            exitCode = if (value.has("exitCode") && !value.isNull("exitCode")) value.optInt("exitCode") else null,
            durationMs = value.optLong("durationMs", 0L).takeIf { it > 0L },
            terminationUnknown = value.optBoolean("terminationUnknown", false),
            rootPid = value.optInt("rootPid", 0).takeIf { it > 0 },
            rootStartTicks = value.optLong("rootStartTicks", 0L).takeIf { it > 0L },
            gamePid = value.optInt("gamePid", 0).takeIf { it > 0 },
            gameStartTicks = value.optLong("gameStartTicks", 0L).takeIf { it > 0L },
            hostPid = value.optInt("hostPid", 0).takeIf { it > 0 },
            launchAttempted = value.optBoolean("launchAttempted", false),
            bootId = value.optString("bootId").takeUnless { it.isEmpty() || it == "null" },
            quiescenceConfirmed = value.optBoolean("quiescenceConfirmed", false),
        )
    }
}

/** Pure transition guard used by the Android store and JVM tests. */
object ForeverRunMachine {
    private val terminal = setOf(
        ForeverRunStatus.FAILED,
        ForeverRunStatus.CANCELLED,
        ForeverRunStatus.INTERRUPTED,
        ForeverRunStatus.EXITED,
    )

    fun begin(runId: String, existing: ForeverRunState?): ForeverRunState {
        require(existing == null || existing.status in terminal) { "another Forever run is active" }
        return ForeverRunState(runId, ForeverStage.PREFLIGHT, ForeverRunStatus.ACTIVE)
    }

    fun move(state: ForeverRunState, stage: ForeverStage, event: String): ForeverRunState {
        require(state.status == ForeverRunStatus.ACTIVE) { "run ${state.runId} is terminal" }
        return state.copy(stage = stage, events = state.events + event)
    }

    fun finish(state: ForeverRunState, status: ForeverRunStatus, message: String? = null): ForeverRunState {
        require(status in terminal) { "finish requires a terminal status" }
        require(state.status == ForeverRunStatus.ACTIVE) { "run ${state.runId} is already terminal" }
        return state.copy(status = status, message = message)
    }

    fun cancel(state: ForeverRunState, reason: String): ForeverRunState =
        if (state.status == ForeverRunStatus.ACTIVE) finish(state, ForeverRunStatus.CANCELLED, reason)
        else state
}
