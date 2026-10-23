package app.gamenative

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Parser for the one externally sealed Wine60 abandonment authorization. */
internal object ForeverInterruptedRunDisposition {
    private const val ASSET = "forever/wine-interrupted-run-disposition-contract.json"
    private const val CONTRACT_SHA256 = "b65e2a66668e7c137259b68e832f587edc41e9f03ed4d5ee8976d19f8d4e92ad"
    private const val SCHEMA = "wowforever.wine-interrupted-run-disposition/v1"
    private const val CANDIDATE_PACKAGE = "com.wowforever.arm64probe"
    private const val CANDIDATE_VERSION_CODE = 61
    private const val CANDIDATE_VERSION_NAME = "0.2.37-automated"
    private const val CANDIDATE_CERTIFICATE_SHA256 = "2b082e8200e066e665f73e71f547f827d6674634298024cb0b823be22adb242b"

    fun read(context: Context?): JSONObject {
        val raw = if (context != null) context.assets.open(ASSET).use { it.readBytes() } else error("disposition contract asset unavailable")
        require(sha256(raw) == CONTRACT_SHA256) { "disposition contract hash mismatch" }
        val value = JSONObject(raw.toString(Charsets.UTF_8))
        require(value.getString("schema") == SCHEMA && value.getString("kind") == "ABANDONED_ON_NEW_BOOT")
        require(value.getString("authority") == "SERVER_ISOLATION")
        return value
    }

    fun fixed(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("kind", "ABANDONED_ON_NEW_BOOT")
        put("authority", "SERVER_ISOLATION")
        put("candidate", candidateAuthority())
        put("prior", JSONObject().put("runId", "RUN-1791718684192-65783495695")
            .put("wsRunId", "WS-024cc10c5e18649fad0c6567191aa18b")
            .put("nonce", "44fa3305f5c3db9fa0d8cd6541978ba8")
            .put("lastObservedBootId", "06254205-1db4-4aaa-8f4e-7123db10f851")
            .put("current", JSONObject().put("status", "ACTIVE").put("stage", "PREFLIGHT")
                .put("bytes", 575).put("sha256", "745eff1e97de89de1de4c5665ce064564022686cf8409b613cce71aebc022cf2"))
            .put("binding", JSONObject().put("status", "BOUND").put("bytes", 207)
                .put("sha256", "1c7d568d4ec506bb7a0a2233fa12ec1b453bf64143d5835f9e2bdf083b28bd22")))
        put("receipt", JSONObject().put("reason", "host-capture-interruption")
            .put("terminalStatus", "ABANDONED_ON_NEW_BOOT").put("nativeStatus", "UNKNOWN")
            .put("closureObserved", false).put("qualification", false).put("echild", false))
        put("transactionPreconditions", org.json.JSONArray()
            .put(JSONObject().put("path", "files/forever/runs/RUN-1791718684192-65783495695-current-before-abandonment.json").put("status", "MISSING").put("prospective", true))
            .put(JSONObject().put("path", "files/forever/runs/RUN-1791718684192-65783495695-abandonment-reservation.json").put("status", "MISSING").put("prospective", true))
            .put(JSONObject().put("path", "files/forever/runs/RUN-1791718684192-65783495695/abandoned-on-new-boot.json").put("status", "MISSING").put("prospective", true))
            .put(JSONObject().put("path", "files/forever/runs/RUN-1791718684192-65783495695-abandonment-consumption.json").put("status", "MISSING").put("prospective", true)))
    }

    fun candidateAuthority(): JSONObject = JSONObject()
        .put("package", CANDIDATE_PACKAGE)
        .put("versionCode", CANDIDATE_VERSION_CODE)
        .put("versionName", CANDIDATE_VERSION_NAME)
        .put("abi", "arm64-v8a")
        .put("api", 34)
        .put("uid", 10330)
        .put("certificateSha256", CANDIDATE_CERTIFICATE_SHA256)
        .put("debuggable", true).put("minSdk", 29).put("targetSdk", 36)

    fun verifyCandidateAuthority(contract: JSONObject, authority: JSONObject) {
        val expected = contract.optJSONObject("candidate") ?: candidateAuthority()
        val fields = listOf("package", "versionCode", "versionName", "abi", "api", "uid", "certificateSha256", "debuggable", "minSdk", "targetSdk")
        require(fields.all { authority.opt(it) == expected.opt(it) }) { "candidate app identity is not exact" }
    }

    fun verifyAdmission(value: JSONObject, currentBytes: ByteArray, runBytes: ByteArray,
                        bindingBytes: ByteArray, newBootId: String) {
        val prior = value.getJSONObject("prior")
        require(prior.getString("runId").matches(Regex("RUN-[A-Za-z0-9_.-]+")))
        require(prior.getString("wsRunId").matches(Regex("WS-[0-9a-f]{32}")))
        require(prior.getString("nonce").matches(Regex("[0-9a-f]{32}")))
        require(ForeverProcIdentityPolicy.validBootId(newBootId) &&
            newBootId != prior.getString("lastObservedBootId") &&
            newBootId != prior.optString("bootBeforeReboot")) { "new boot is not fresh" }
        require(prior.getJSONObject("current").getInt("bytes") == currentBytes.size &&
            prior.getJSONObject("current").getString("sha256") == sha256(currentBytes)) { "old current is not exact" }
        require(runBytes.contentEquals(currentBytes)) { "old RUN state differs from current" }
        require(bindingBytes.size == prior.getJSONObject("binding").getInt("bytes") &&
            prior.getJSONObject("binding").getString("sha256") == sha256(bindingBytes)) { "old binding is not exact" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
