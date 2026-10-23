package app.gamenative

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Exact, one-shot admission for the authenticated Wine61 prefix prelaunch failure. */
internal object ForeverPrelaunchContinuation {
    private const val ASSET = "forever/wine-prefix-prelaunch-continuation-contract.json"
    private const val CONTRACT_SHA256 = "0008db0586756da7b8e20a88725788ce64dc4c290f2d04d360068ff65a64da27"
    private const val SCHEMA = "wowforever.wine-prefix-prelaunch-continuation/v1"
    private const val PACKAGE = "com.wowforever.arm64probe"
    private const val VERSION_CODE = 62
    private const val VERSION_NAME = "0.2.38-automated"
    private const val CERTIFICATE = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"

    fun read(context: Context): JSONObject {
        val raw = context.assets.open(ASSET).use { it.readBytes() }
        require(sha256(raw) == CONTRACT_SHA256) { "continuation contract hash mismatch" }
        return JSONObject(raw.toString(Charsets.UTF_8)).also {
            require(it.getString("schema") == SCHEMA && it.getString("kind") == "PRELAUNCH_CONTINUATION")
            require(it.getString("authority") == "SERVER_ISOLATION")
        }
    }

    fun fixed(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA).put("kind", "PRELAUNCH_CONTINUATION").put("authority", "SERVER_ISOLATION")
        put("candidate", candidateAuthority())
        put("knownFailure", JSONObject()
            .put("runId", "RUN-1791738410167-533692127338")
            .put("wsRunId", "WS-51c1b011c946c45309229bd9d6fff681")
            .put("nonce", "f9f4da016c99beb427b44274e9ab4ef6")
            .put("bootId", "bc331988-3879-4cd4-b4d2-c1b616c5a2a1")
            .put("current", JSONObject().put("status", "FAILED").put("stage", "EXITED")
                .put("bytes", 695).put("sha256", "8625ecf47bf3347107e1475995e85fe3454dcf4f7fed7bad9d8f2b3bba9dd3d2"))
            .put("binding", JSONObject().put("bytes", 208)
                .put("sha256", "627c4a598c224966e4626c8c6fae24dd314fe4d41ce1d7987292995d345a93f7"))
            .put("wineResult", JSONObject().put("terminalStatus", "UNKNOWN").put("nativeStatus", "UNKNOWN")
                .put("bytes", 538).put("sha256", "d25aec706b9cf564ae58ff78c43d8567f0a87e2d3f55736b3e01297bb7a04f04")))
        put("fixedStatusCount", 58)
    }

    fun candidateAuthority(): JSONObject = JSONObject()
        .put("package", PACKAGE).put("versionCode", VERSION_CODE).put("versionName", VERSION_NAME)
        .put("abi", "arm64-v8a").put("api", 34).put("uid", 10330)
        .put("certificateSha256", CERTIFICATE).put("debuggable", true).put("minSdk", 29).put("targetSdk", 36)

    fun verifyCandidateAuthority(contract: JSONObject, authority: JSONObject) {
        val expected = contract.optJSONObject("candidate") ?: candidateAuthority()
        val fields = listOf("package", "versionCode", "versionName", "certificateSha256", "abi", "api", "uid", "debuggable", "minSdk", "targetSdk")
        require(fields.all { authority.opt(it) == expected.opt(it) }) { "candidate62 app identity is not exact" }
        require(expected.optInt("versionCode") == VERSION_CODE && expected.optString("versionName") == VERSION_NAME) {
            "continuation candidate version is not exact"
        }
    }

    fun verifyKnownFailure(
        contract: JSONObject,
        currentBytes: ByteArray,
        runBytes: ByteArray,
        bindingBytes: ByteArray,
        wineResultBytes: ByteArray,
        newBootId: String,
    ) {
        val failure = contract.getJSONObject("knownFailure")
        val current = JSONObject(currentBytes.toString(Charsets.UTF_8))
        val result = JSONObject(wineResultBytes.toString(Charsets.UTF_8))
        require(current.getString("runId") == failure.getString("runId") &&
            current.getString("stage") == "EXITED" && current.getString("status") == "FAILED" &&
            current.getBoolean("terminationUnknown") && !current.getBoolean("quiescenceConfirmed") &&
            current.getString("message") == "filesDir is not the canonical private app root") {
            "known Wine61 failure status is not exact"
        }
        require(currentBytes.contentEquals(runBytes)) { "known Wine61 RUN differs from current" }
        require(failure.getJSONObject("current").getInt("bytes") == currentBytes.size &&
            failure.getJSONObject("current").getString("sha256") == sha256(currentBytes)) { "known Wine61 current seal mismatch" }
        require(failure.getJSONObject("binding").getInt("bytes") == bindingBytes.size &&
            failure.getJSONObject("binding").getString("sha256") == sha256(bindingBytes)) { "known Wine61 binding seal mismatch" }
        require(result.getString("runId") == failure.getString("runId") &&
            result.getString("wsRunId") == failure.getString("wsRunId") &&
            result.getString("nonce") == failure.getString("nonce") &&
            result.getString("terminalStatus") == "UNKNOWN" && result.getString("nativeStatus") == "UNKNOWN" &&
            result.isNull("supervisorExit") && !result.getBoolean("closureObserved") &&
            !result.getBoolean("qualification") && !result.getBoolean("echild") &&
            result.getString("error") == "filesDir is not the canonical private app root") {
            "known Wine61 result is not exact"
        }
        require(failure.getJSONObject("wineResult").getInt("bytes") == wineResultBytes.size &&
            failure.getJSONObject("wineResult").getString("sha256") == sha256(wineResultBytes)) { "known Wine61 result seal mismatch" }
        require(ForeverProcIdentityPolicy.validBootId(newBootId) && newBootId != failure.getString("bootId")) {
            "continuation requires a fresh boot"
        }
    }

    fun receipt(oldRunId: String, oldWs: String, oldNonce: String, newRunId: String,
                newWs: String, newNonce: String, newBoot: String): JSONObject = JSONObject()
        .put("schema", 1).put("kind", "PRELAUNCH_ABORT")
        .put("runId", oldRunId).put("wsRunId", oldWs).put("nonce", oldNonce)
        .put("newRunId", newRunId).put("newWsRunId", newWs).put("newNonce", newNonce)
        .put("newBootId", newBoot).put("terminalStatus", "PRELAUNCH_ABORT")
        .put("nativeStatus", "UNKNOWN").put("closureObserved", false)
        .put("qualification", false).put("echild", false)
        .put("reason", "known Wine61 prefix prelaunch failure")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
