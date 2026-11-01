package ch.asds.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Additive R1 encrypted store. Does NOT migrate, modify or clear legacy
 * drafts_v1 / queue_v1 used by the installed 2.0.0 client.
 */
class R1Store(context: Context) {
    private val secure = SecureStore(context.applicationContext)
    companion object {
        private const val QUEUE = "r1_queue_v1"
        private const val DRAFT = "r1_time_draft_v1"
        private const val TEMPLATES = "r1_time_templates_v1"
        private const val CUSTOMERS = "r1_customers_cache_v1"
        private const val APPOINTMENTS = "r1_appointments_cache_v1"
    }

    @Synchronized fun draft(): JSONObject = objectValue(DRAFT)
    @Synchronized fun saveDraft(value: JSONObject) = secure.put(DRAFT, value.toString())
    @Synchronized fun clearDraft() = secure.remove(DRAFT)
    @Synchronized fun templates(): JSONArray = arrayValue(TEMPLATES)
    @Synchronized fun customers(): JSONArray = arrayValue(CUSTOMERS)
    @Synchronized fun appointments(): JSONArray = arrayValue(APPOINTMENTS)
    @Synchronized fun cacheCustomers(value: JSONArray) = secure.put(CUSTOMERS, value.toString())
    @Synchronized fun cacheAppointments(value: JSONArray) = secure.put(APPOINTMENTS, value.toString())

    @Synchronized fun saveTemplate(label: String, value: JSONObject) {
        require(label.isNotBlank() && label.length <= 80)
        val items = arrayValue(TEMPLATES)
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            if (row.optString("label") != label) out.put(row)
        }
        out.put(JSONObject().put("label", label).put("fields", JSONObject(value.toString())))
        secure.put(TEMPLATES, out.toString())
    }

    @Synchronized fun enqueueTime(payload: JSONObject, objectId: Int? = null, baseRevision: Int? = null): String {
        val sourceKey = UUID.randomUUID().toString()
        val body = JSONObject(payload.toString())
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(body.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val envelope = JSONObject()
            .put("source", "mobile")
            .put("source_key", sourceKey)
            .put("external_id", sourceKey)
            .put("object_type", "time")
            .put("payload_hash", hash)
            .put("client_updated_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("payload", body)
        if (objectId != null && objectId > 0) envelope.put("object_id", objectId)
        if (baseRevision != null) envelope.put("base_revision", baseRevision)
        val row = JSONObject().put("source_key", sourceKey).put("item", envelope)
            .put("status", "pending").put("attempts", 0).put("retryable", true)
            .put("next_retry_at", 0L).put("last_error", "")
            .put("created_at", System.currentTimeMillis())
        val rows = queue().apply { put(row) }
        secure.put(QUEUE, rows.toString())
        return sourceKey
    }

    @Synchronized fun queue(): JSONArray = arrayValue(QUEUE)

    @Synchronized fun pending(): List<JSONObject> {
        val now = System.currentTimeMillis()
        val result = ArrayList<JSONObject>()
        val rows = queue()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if ((row.optString("status") == "pending" || row.optString("status") == "failed") &&
                row.optBoolean("retryable", false) && row.optLong("next_retry_at") <= now) {
                result.add(JSONObject(row.toString()))
            }
        }
        return result
    }

    @Synchronized fun updateStatus(key: String, status: String, message: String = "",
                                   retryable: Boolean = false, serverId: Int = 0,
                                   revision: Int = 0, retryAfterSeconds: Long = 0L) {
        require(status in listOf("pending", "processing", "success", "failed", "conflict"))
        val rows = queue()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("source_key") != key) continue
            val attempts = row.optInt("attempts") + if (status == "processing" || status == "failed" || status == "conflict") 1 else 0
            row.put("status", status).put("attempts", attempts)
                .put("last_error", message.take(350)).put("retryable", retryable && attempts < 7)
                .put("last_attempt", System.currentTimeMillis())
            if (serverId > 0) row.put("server_id", serverId)
            if (revision > 0) row.put("revision", revision)
            if (retryable && attempts < 7) {
                val backoff = (30L * (1L shl attempts.coerceAtMost(6))).coerceAtMost(3600L)
                row.put("next_retry_at", System.currentTimeMillis() + maxOf(backoff, retryAfterSeconds) * 1000L)
            }
            secure.put(QUEUE, rows.toString())
            return
        }
    }

    private fun objectValue(key: String): JSONObject =
        try { JSONObject(secure.get(key, "{}")) } catch (_: Exception) { JSONObject() }
    private fun arrayValue(key: String): JSONArray =
        try { JSONArray(secure.get(key, "[]")) } catch (_: Exception) { JSONArray() }
}

object R1Amounts {
    /** Contract 1.0: money and distances are decimal strings, never JSON floats. */
    fun decimal(value: String, scale: Int): String {
        val normal = value.trim().replace(',', '.')
        val amount = BigDecimal(normal.ifBlank { "0" })
        require(amount.signum() >= 0 && amount <= BigDecimal("1000000")) { "Ungültiger Wert" }
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }
    fun kmAmount(km: String, rate: String): String =
        BigDecimal(decimal(km, 2)).multiply(BigDecimal(decimal(rate, 2)))
            .setScale(2, RoundingMode.HALF_UP).toPlainString()
}
