package ch.asds.mobile

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-party, same-origin R1 transport. Existing WebView cookies are reused.
 * Never place tokens in URLs or log request payloads.
 */
object R1Client {
    private const val ROOT = "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/api/v1/"
    data class Response(val code: Int, val json: JSONObject, val retryAfter: Long)

    fun get(path: String): Response = request(path, null)
    fun post(path: String, value: JSONObject): Response = request(path, value)

    private fun request(path: String, payload: JSONObject?): Response {
        require(path.matches(Regex("[a-zA-Z0-9_/?=&%:.+\\-]+"))) { "Invalid R1 path" }
        val address = ROOT + path
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = false
            conn.requestMethod = if (payload == null) "GET" else "POST"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-ASDS-Contract", "1.0")
            conn.setRequestProperty("X-ASDS-Client-Version", BuildConfig.VERSION_NAME)
            val cookie = CookieManager.getInstance().getCookie(address)
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val body = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("raw_error", "HTTP " + code) }
            val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 3600) ?: 0L
            return Response(code, body, retryAfter)
        } finally {
            conn.disconnect()
        }
    }

    /** One immutable envelope per network call prevents ambiguous partial batches. */
    fun upload(row: JSONObject): Response {
        val env = row.getJSONObject("item")
        val body = JSONObject().put("contract_version", "1.0")
            .put("device_id", "android")
            .put("items", JSONArray().put(env))
        return post("sync/batch.php", body)
    }

    fun refreshLookups(store: R1Store) {
        // The authenticated R1 endpoints enforce entity and user scope.
        try {
            val c = get("customers.php")
            if (c.code == 200 && c.json.optJSONArray("items") != null) {
                store.cacheCustomers(c.json.getJSONArray("items"))
            }
        } catch (_: Exception) { }
        try {
            val a = get("appointments.php")
            if (a.code == 200 && a.json.optJSONArray("items") != null) {
                store.cacheAppointments(a.json.getJSONArray("items"))
            }
        } catch (_: Exception) { }
    }

    fun sync(context: Context): Boolean {
        val store = R1Store(context)
        var retryNeeded = false
        for (row in store.pending()) {
            val key = row.optString("source_key")
            try {
                val res = upload(row)
                if (res.code == 401 || res.code == 403 || res.code == 426) {
                    store.updateStatus(key, "failed", "HTTP " + res.code + " – Anmeldung/Vertrag prüfen", false)
                    continue
                }
                val one = res.json.optJSONArray("results")?.optJSONObject(0)
                if (one != null) {
                    val state = one.optString("status", "failed")
                    if (state == "success") {
                        store.updateStatus(key, "success", serverId = one.optInt("server_id"),
                            revision = one.optInt("revision"))
                    } else {
                        val error = one.optJSONObject("error")
                        val transient = error?.optBoolean("retryable", false) == true &&
                            (res.code >= 500 || res.code == 429 || error.optString("code") == "SERVER_ERROR")
                        val status = if (state == "conflict") "conflict" else "failed"
                        val message = error?.optString("code", "UNKNOWN") + ": " + error?.optString("message", "")
                        store.updateStatus(key, status, message, transient, retryAfterSeconds = res.retryAfter)
                        retryNeeded = retryNeeded || transient
                    }
                } else {
                    val transient = res.code == 429 || res.code >= 500
                    val error = res.json.optJSONObject("error")
                    store.updateStatus(key, "failed", error?.optString("code", "HTTP " + res.code) ?: "HTTP " + res.code,
                        transient, retryAfterSeconds = res.retryAfter)
                    retryNeeded = retryNeeded || transient
                }
            } catch (error: IOException) {
                store.updateStatus(key, "failed", "Netzwerkfehler", true)
                retryNeeded = true
            } catch (_: Exception) {
                store.updateStatus(key, "failed", "Lokaler Übertragungsfehler", false)
            }
        }
        refreshLookups(store)
        return retryNeeded
    }
}
