package ch.asds.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.min

class OfflineRepository(context: Context) {
    private val secureStore = SecureStore(context.applicationContext)
    private val settings = context.getSharedPreferences("asds_offline_state", Context.MODE_PRIVATE)

    companion object {
        private const val DRAFTS_KEY = "drafts_v1"
        private const val QUEUE_KEY = "queue_v1"
        private const val OFFLINE_CONFIG_KEY = "offline_config_v1"
    }

    @Synchronized
    fun saveDraft(key: String, label: String, payload: String) {
        if (key.isBlank()) return
        val drafts = readObject(DRAFTS_KEY)
        drafts.put(key, JSONObject().put("key", key).put("label", label.ifBlank { key }).put("payload", payload).put("updatedAt", System.currentTimeMillis()))
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized fun loadDraft(key: String): String = readObject(DRAFTS_KEY).optJSONObject(key)?.optString("payload", "") ?: ""

    @Synchronized
    fun deleteDraft(key: String) {
        val drafts = readObject(DRAFTS_KEY)
        drafts.remove(key)
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized
    fun clearDraftPrefix(prefix: String) {
        val drafts = readObject(DRAFTS_KEY)
        drafts.keys().asSequence().toList().filter { it.startsWith(prefix) }.forEach(drafts::remove)
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized
    fun listDrafts(): JSONArray {
        val drafts = readObject(DRAFTS_KEY)
        val result = JSONArray()
        drafts.keys().asSequence().mapNotNull { drafts.optJSONObject(it) }.sortedByDescending { it.optLong("updatedAt") }.forEach { item ->
            val copy = JSONObject(item.toString())
            copy.put("updatedAtLabel", dateLabel(copy.optLong("updatedAt")))
            copy.remove("payload")
            result.put(copy)
        }
        return result
    }

    @Synchronized
    fun enqueue(url: String, method: String, body: String, contentType: String, label: String, draftKey: String): String {
        val queue = listQueueRaw()
        val normalizedMethod = method.ifBlank { "POST" }.uppercase()
        val dedupeKey = sha256("$normalizedMethod\n$url\n$body\n$draftKey")
        if (BuildConfig.VERSION_CODE >= 92) {
            queue.firstOrNull { it.optString("dedupeKey") == dedupeKey }?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val id = UUID.randomUUID().toString()
        queue += JSONObject().put("id", id).put("url", url).put("method", normalizedMethod).put("body", body)
            .put("contentType", contentType.ifBlank { "application/x-www-form-urlencoded; charset=UTF-8" })
            .put("label", label.ifBlank { "Offline-Eintrag" }).put("draftKey", draftKey).put("dedupeKey", dedupeKey)
            .put("state", "pending").put("createdAt", System.currentTimeMillis()).put("attempts", 0).put("nextAttemptAt", 0L).put("lastError", "")
        writeQueue(queue)
        return id
    }

    @Synchronized
    fun listQueueRaw(): MutableList<JSONObject> {
        val queue = readArray(QUEUE_KEY)
        return MutableList(queue.length()) { index -> JSONObject(queue.getJSONObject(index).toString()) }
    }

    @Synchronized
    fun readyQueueRaw(now: Long = System.currentTimeMillis()): MutableList<JSONObject> =
        listQueueRaw().filter { BuildConfig.VERSION_CODE < 101 || it.optLong("nextAttemptAt", 0L) <= now }.toMutableList()

    @Synchronized
    fun listQueueForUi(): JSONArray {
        val result = JSONArray()
        listQueueRaw().forEach { item ->
            val copy = JSONObject(item.toString())
            copy.put("createdAtLabel", dateLabel(copy.optLong("createdAt")))
            copy.put("nextAttemptAtLabel", dateLabel(copy.optLong("nextAttemptAt")))
            copy.remove("body")
            result.put(copy)
        }
        return result
    }

    @Synchronized
    fun markQueueFailure(id: String, error: String) {
        val queue = listQueueRaw()
        queue.firstOrNull { it.optString("id") == id }?.apply {
            val attempts = optInt("attempts") + 1
            put("attempts", attempts).put("state", "failed").put("lastError", error.take(500))
            if (BuildConfig.VERSION_CODE >= 101) {
                val seconds = min(21600L, 30L * (1L shl min(10, attempts - 1)))
                put("nextAttemptAt", System.currentTimeMillis() + seconds * 1000L)
            }
        }
        writeQueue(queue)
    }

    /**
     * A business rejection must never drop a persisted offline record. Only
     * an explicit manual retry can reactivate HOLD. While a server upgrade is
     * pending, a compatible config refresh or manual retry may reactivate it.
     */
    @Synchronized
    fun holdQueueItem(id: String, message: String, waitingForServer: Boolean = false) {
        val queue = listQueueRaw()
        queue.firstOrNull { it.optString("id") == id }?.apply {
            put("state", if (waitingForServer) "waiting_server" else "blocked")
            put("lastError", message.take(500))
            put("nextAttemptAt", if (waitingForServer) System.currentTimeMillis() + 300_000L else Long.MAX_VALUE)
        }
        writeQueue(queue)
    }

    @Synchronized
    fun removeQueueItem(id: String, clearDraft: Boolean) {
        val queue = listQueueRaw()
        val item = queue.firstOrNull { it.optString("id") == id }
        writeQueue(queue.filterNot { it.optString("id") == id })
        if (clearDraft && item != null) item.optString("draftKey").takeIf { it.isNotBlank() }?.let(::deleteDraft)
    }

    @Synchronized
    fun resetQueueRetries() {
        val queue = listQueueRaw()
        queue.forEach { it.put("nextAttemptAt", 0L).put("state", "pending") }
        writeQueue(queue)
    }

    @Synchronized fun clearQueue() = secureStore.put(QUEUE_KEY, JSONArray().toString())
    @Synchronized fun setOfflineConfig(value: String) {
        secureStore.put(OFFLINE_CONFIG_KEY, value.take(300000))
        settings.edit().putLong("offline_config_at", System.currentTimeMillis()).apply()
    }
    @Synchronized fun offlineConfig(): String = secureStore.get(OFFLINE_CONFIG_KEY, "{}")
    fun offlineConfigAt(): Long = settings.getLong("offline_config_at", 0L)
    @Synchronized fun clearAll() { secureStore.clear(); settings.edit().clear().apply() }
    fun draftCount(): Int = readObject(DRAFTS_KEY).length()
    fun queueCount(): Int = readArray(QUEUE_KEY).length()
    fun failedQueueCount(): Int = listQueueRaw().count { it.optString("state") == "failed" || it.optInt("attempts") > 0 }
    fun setLastSync(timestamp: Long = System.currentTimeMillis()) = settings.edit().putLong("last_sync", timestamp).apply()
    fun lastSync(): Long = settings.getLong("last_sync", 0L)
    fun setLastSuccessfulSync(timestamp: Long = System.currentTimeMillis()) = settings.edit().putLong("last_successful_sync", timestamp).apply()
    fun lastSuccessfulSync(): Long = settings.getLong("last_successful_sync", 0L)
    private fun readObject(key: String): JSONObject = try { JSONObject(secureStore.get(key, "{}")) } catch (_: Exception) { JSONObject() }
    private fun readArray(key: String): JSONArray = try { JSONArray(secureStore.get(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun writeQueue(items: Collection<JSONObject>) { val array = JSONArray(); items.forEach(array::put); secureStore.put(QUEUE_KEY, array.toString()) }
    private fun dateLabel(timestamp: Long): String = if (timestamp <= 0L) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
