package ch.asds.mobile

import org.json.JSONObject
import java.net.URLDecoder

/**
 * 2.0.2 legacy offline-sync safety rules.
 *
 * The old queue_v1 is retained byte-for-byte in SecureStore. A submission is
 * removed only after affirmative application-level acknowledgement. Neither
 * HTTP 302 login redirects nor HTTP 200 with an error JSON may delete entries.
 */
internal object LegacyOfflineTimeSync {
    enum class Decision { CONFIRMED, TRANSIENT, HOLD }

    private fun formField(body: String, name: String): String {
        val encoded = body.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "") ?: return ""
        return try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Exception) { "" }
    }

    fun requiresV201Server(queueItem: JSONObject): Boolean {
        if (!queueItem.optString("url").contains("/offline_sync.php")) return false
        if (formField(queueItem.optString("body"), "kind") != "time") return false
        val payload = try {
            JSONObject(formField(queueItem.optString("body"), "payload"))
        } catch (_: Exception) { return true } // Fail closed on malformed time payload
        val km = payload.optString("billable_km", payload.optString("km", "0"))
            .replace(',', '.').toDoubleOrNull() ?: return true
        val appointment = payload.optString("appointment_id", "0").toIntOrNull() ?: 0
        return km > 0.0 || appointment > 0
    }

    fun supportsV201Server(offlineConfig: String): Boolean = try {
        val cfg = JSONObject(offlineConfig)
        cfg.optBoolean("ok") && cfg.optString("contract_version") == "1.0" &&
            cfg.optJSONObject("capabilities")?.optJSONObject("features")
                ?.optBoolean("billable_km_on_time") == true
    } catch (_: Exception) { false }

    fun decision(item: JSONObject, code: Int, responseBody: String): Decision {
        if (code < 200 || code >= 300) {
            return if (code == 408 || code == 429 || code >= 500) Decision.TRANSIENT else Decision.HOLD
        }
        if (!item.optString("url").contains("/offline_sync.php")) return Decision.CONFIRMED
        val json = try { JSONObject(responseBody) } catch (_: Exception) { return Decision.TRANSIENT }
        if (!json.optBoolean("ok", false)) {
            return if (json.optBoolean("retryable", false)) Decision.TRANSIENT else Decision.HOLD
        }
        val status = json.optString("status", "")
        // Old server versions return ok=true + target_id, 2.0.1 has success status.
        return if (status == "success" || status == "done" || json.optInt("target_id", 0) > 0) {
            Decision.CONFIRMED
        } else Decision.TRANSIENT
    }

    fun failureReason(code: Int, body: String): String {
        val remote = try { JSONObject(body) } catch (_: Exception) { null }
        val error = remote?.optString("code")?.takeIf { it.isNotBlank() }
            ?: remote?.optString("error")?.takeIf { it.isNotBlank() }
        return (error ?: "HTTP $code").take(140)
    }
}
