package ch.asds.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class OfflineRepository(context: Context) {
    private val secureStore = SecureStore(context.applicationContext)
    private val settings = context.getSharedPreferences("asds_offline_state", Context.MODE_PRIVATE)

    companion object {
        private const val DRAFTS_KEY = "drafts_v1"
        private const val QUEUE_KEY = "queue_v1"
    }

    @Synchronized
    fun saveDraft(key: String, label: String, payload: String) {
        if (key.isBlank()) return
        val drafts = readObject(DRAFTS_KEY)
        drafts.put(
            key,
            JSONObject()
                .put("key", key)
                .put("label", label.ifBlank { key })
                .put("payload", payload)
                .put("updatedAt", System.currentTimeMillis())
        )
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized
    fun loadDraft(key: String): String {
        return readObject(DRAFTS_KEY).optJSONObject(key)?.optString("payload", "") ?: ""
    }

    @Synchronized
    fun deleteDraft(key: String) {
        val drafts = readObject(DRAFTS_KEY)
        drafts.remove(key)
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized
    fun clearDraftPrefix(prefix: String) {
        val drafts = readObject(DRAFTS_KEY)
        val names = drafts.keys().asSequence().toList()
        names.filter { it.startsWith(prefix) }.forEach(drafts::remove)
        secureStore.put(DRAFTS_KEY, drafts.toString())
    }

    @Synchronized
    fun listDrafts(): JSONArray {
        val drafts = readObject(DRAFTS_KEY)
        val result = JSONArray()
        drafts.keys().asSequence()
            .mapNotNull { drafts.optJSONObject(it) }
            .sortedByDescending { it.optLong("updatedAt") }
            .forEach { item ->
                val copy = JSONObject(item.toString())
                copy.put("updatedAtLabel", dateLabel(copy.optLong("updatedAt")))
                copy.remove("payload")
                result.put(copy)
            }
        return result
    }

    @Synchronized
    fun enqueue(url: String, method: String, body: String, contentType: String, label: String, draftKey: String): String {
        val id = UUID.randomUUID().toString()
        val queue = readArray(QUEUE_KEY)
        queue.put(
            JSONObject()
                .put("id", id)
                .put("url", url)
                .put("method", method.ifBlank { "POST" }.uppercase())
                .put("body", body)
                .put("contentType", contentType.ifBlank { "application/x-www-form-urlencoded; charset=UTF-8" })
                .put("label", label.ifBlank { "Offline-Eintrag" })
                .put("draftKey", draftKey)
                .put("createdAt", System.currentTimeMillis())
                .put("attempts", 0)
                .put("lastError", "")
        )
        secureStore.put(QUEUE_KEY, queue.toString())
        return id
    }

    @Synchronized
    fun listQueueRaw(): MutableList<JSONObject> {
        val queue = readArray(QUEUE_KEY)
        return MutableList(queue.length()) { index -> JSONObject(queue.getJSONObject(index).toString()) }
    }

    @Synchronized
    fun listQueueForUi(): JSONArray {
        val result = JSONArray()
        listQueueRaw().forEach { item ->
            val copy = JSONObject(item.toString())
            copy.put("createdAtLabel", dateLabel(copy.optLong("createdAt")))
            copy.remove("body")
            result.put(copy)
        }
        return result
    }

    @Synchronized
    fun markQueueFailure(id: String, error: String) {
        val queue = listQueueRaw()
        queue.firstOrNull { it.optString("id") == id }?.apply {
            put("attempts", optInt("attempts") + 1)
            put("lastError", error.take(500))
        }
        writeQueue(queue)
    }

    @Synchronized
    fun removeQueueItem(id: String, clearDraft: Boolean) {
        val queue = listQueueRaw()
        val item = queue.firstOrNull { it.optString("id") == id }
        val filtered = queue.filterNot { it.optString("id") == id }
        writeQueue(filtered)
        if (clearDraft && item != null) {
            val draftKey = item.optString("draftKey")
            if (draftKey.isNotBlank()) deleteDraft(draftKey)
        }
    }

    @Synchronized
    fun clearQueue() {
        secureStore.put(QUEUE_KEY, JSONArray().toString())
    }

    @Synchronized
    fun clearAll() {
        secureStore.clear()
        settings.edit().clear().apply()
    }

    fun draftCount(): Int = readObject(DRAFTS_KEY).length()
    fun queueCount(): Int = readArray(QUEUE_KEY).length()

    fun setLastSync(timestamp: Long = System.currentTimeMillis()) {
        settings.edit().putLong("last_sync", timestamp).apply()
    }

    fun lastSync(): Long = settings.getLong("last_sync", 0L)

    private fun readObject(key: String): JSONObject {
        val raw = secureStore.get(key, "{}")
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun readArray(key: String): JSONArray {
        val raw = secureStore.get(key, "[]")
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun writeQueue(items: Collection<JSONObject>) {
        val array = JSONArray()
        items.forEach(array::put)
        secureStore.put(QUEUE_KEY, array.toString())
    }

    private fun dateLabel(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    }
}
