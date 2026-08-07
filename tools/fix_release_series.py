#!/usr/bin/env python3
"""Build cumulative ASDS Mobile releases 1.8.1 -> 2.0.0 on top of 1.8.0.

The script is intentionally deterministic and contains no signing material.
"""
from pathlib import Path
import re
import sys

VERSIONS = {
    "1.8.1": 91,
    "1.8.2": 92,
    "1.9.0": 100,
    "1.9.1": 101,
    "1.9.2": 102,
    "2.0.0": 200,
}

if len(sys.argv) != 2 or sys.argv[1] not in VERSIONS:
    raise SystemExit("Usage: fix_release_series.py " + "|".join(VERSIONS))
version = sys.argv[1]
version_code = VERSIONS[version]


def write(path: str, content: str) -> None:
    Path(path).write_text(content.rstrip() + "\n", encoding="utf-8")

# Release identity
build = Path("app/build.gradle.kts")
text = build.read_text(encoding="utf-8")
text, n1 = re.subn(r"versionCode\s*=\s*90", f"versionCode = {version_code}", text, count=1)
text, n2 = re.subn(r'versionName\s*=\s*"1\.8\.0"', f'versionName = "{version}"', text, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit("release identity: 1.8.0 baseline not found")
build.write_text(text, encoding="utf-8")

# Human-readable labels and diagnostics strings.
strings = Path("app/src/main/res/values/strings.xml")
text = strings.read_text(encoding="utf-8")
extra = '''
    <string name="security_diagnostics">Diagnose</string>
    <string name="diagnostics_title">ASDS Mobile Diagnose</string>
    <string name="diagnostics_copy">Diagnose kopieren</string>
    <string name="diagnostics_copied">Diagnose wurde kopiert.</string>
'''
if 'name="security_diagnostics"' not in text:
    text = text.replace("</resources>", extra + "</resources>", 1)
strings.write_text(text, encoding="utf-8")

write("app/src/main/java/ch/asds/mobile/AppSettings.kt", r'''package ch.asds.mobile

import android.content.Context
import java.util.UUID

object AppSettings {
    private const val PREFS = "asds_app_settings"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun biometricEnabled(context: Context): Boolean = prefs(context).getBoolean("biometric_enabled", true)
    fun setBiometricEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("biometric_enabled", enabled).apply()
    fun notificationsEnabled(context: Context): Boolean = prefs(context).getBoolean("notifications_enabled", false)
    fun setNotificationsEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("notifications_enabled", enabled).apply()
    fun forceLock(context: Context): Boolean = prefs(context).getBoolean("force_lock", false)
    fun setForceLock(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("force_lock", enabled).apply()
    fun lastBackgroundAt(context: Context): Long = prefs(context).getLong("last_background_at", 0L)
    fun setLastBackgroundAt(context: Context, value: Long) = prefs(context).edit().putLong("last_background_at", value).apply()
    fun lockTimeoutMillis(context: Context): Long = prefs(context).getLong("lock_timeout_ms", 60_000L)

    fun ensureV171SecurityDefaults(context: Context) {
        val preferences = prefs(context)
        if (!preferences.getBoolean("v171_security_defaults_applied", false)) {
            preferences.edit().putBoolean("biometric_enabled", true).putBoolean("v171_security_defaults_applied", true).apply()
        }
    }

    fun instanceId(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString("instance_id", "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString("instance_id", created).apply()
        return created
    }

    fun setLastServerState(context: Context, value: String) = prefs(context).edit().putString("last_server_state", value.take(16000)).apply()
    fun lastServerState(context: Context): String = prefs(context).getString("last_server_state", "{}").orEmpty()
    fun setCompatibilityState(context: Context, value: String) = prefs(context).edit().putString("compatibility_state", value.take(1000)).apply()
    fun compatibilityState(context: Context): String = prefs(context).getString("compatibility_state", "").orEmpty()
}
''')

write("app/src/main/java/ch/asds/mobile/OfflineRepository.kt", r'''package ch.asds.mobile

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
''')

write("app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt", r'''package ch.asds.mobile

import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class ASDSNativeBridge(
    private val activity: MainActivity,
    private val repository: OfflineRepository,
    private val securityController: SecurityController
) {
    @JavascriptInterface
    fun getState(): String = JSONObject().put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
        .put("draftCount", repository.draftCount()).put("queueCount", repository.queueCount()).put("failedQueueCount", repository.failedQueueCount())
        .put("biometricEnabled", AppSettings.biometricEnabled(activity)).put("notificationsEnabled", AppSettings.notificationsEnabled(activity))
        .put("lastSync", repository.lastSync()).put("lastSuccessfulSync", repository.lastSuccessfulSync()).put("capabilities", capabilities()).toString()

    @JavascriptInterface fun saveDraft(key: String, label: String, payload: String) = repository.saveDraft(key, label, payload)
    @JavascriptInterface fun loadDraft(key: String): String = repository.loadDraft(key)
    @JavascriptInterface fun deleteDraft(key: String) = repository.deleteDraft(key)
    @JavascriptInterface fun clearDraftPrefix(prefix: String) = repository.clearDraftPrefix(prefix)
    @JavascriptInterface fun listDrafts(): String = repository.listDrafts().toString()

    @JavascriptInterface
    fun enqueueForm(url: String, method: String, body: String, label: String, draftKey: String): String {
        val id = repository.enqueue(url, method, body, "application/x-www-form-urlencoded; charset=UTF-8", label, draftKey)
        WorkerScheduler.retryNow(activity)
        return id
    }

    @JavascriptInterface fun listQueue(): String = repository.listQueueForUi().toString()
    @JavascriptInterface fun retrySync() { repository.resetQueueRetries(); WorkerScheduler.retryNow(activity) }
    @JavascriptInterface fun clearLocalData() = repository.clearAll()
    @JavascriptInterface fun setBiometricEnabled(enabled: Boolean) = activity.runOnUiThread { securityController.setBiometricEnabled(enabled) }
    @JavascriptInterface fun lockNow() = activity.runOnUiThread { securityController.lockNow() }

    @JavascriptInterface
    fun setNotificationsEnabled(enabled: Boolean) {
        AppSettings.setNotificationsEnabled(activity, enabled)
        activity.reportDeviceState()
        if (enabled) WorkerScheduler.retryNow(activity)
    }

    @JavascriptInterface fun requestNotificationPermission() = activity.runOnUiThread { activity.requestNotificationPermission() }

    @JavascriptInterface
    fun labelForTripType(code: String): String {
        val key = code.trim().lowercase()
        return when (key) {
            "asds", "asds_internal", "asds-internal" -> "ASDS intern"
            "commute_asds", "asds_commute" -> "Arbeitsweg ASDS"
            "commute_other", "other_employer", "employer_other" -> "Arbeitsweg anderer Arbeitgeber"
            "private", "privat" -> "Privat"
            "business", "business_other", "dienstlich" -> "Geschäftlich"
            "other", "sonstiges", "misc" -> "Sonstiges"
            else -> code.replace('_', ' ').trim().split(Regex("\\s+")).joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.titlecase() } }
        }
    }

    @JavascriptInterface
    fun getDiagnostics(): String {
        if (BuildConfig.VERSION_CODE < 100) return JSONObject().put("available", false).toString()
        return JSONObject().put("available", true).put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("package", activity.packageName).put("androidSdk", Build.VERSION.SDK_INT).put("androidVersion", Build.VERSION.RELEASE)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("instanceId", AppSettings.instanceId(activity))
            .put("draftCount", repository.draftCount()).put("queueCount", repository.queueCount()).put("failedQueueCount", repository.failedQueueCount())
            .put("lastSync", repository.lastSync()).put("lastSuccessfulSync", repository.lastSuccessfulSync())
            .put("biometricEnabled", AppSettings.biometricEnabled(activity)).put("notificationsEnabled", AppSettings.notificationsEnabled(activity))
            .put("compatibility", AppSettings.compatibilityState(activity))
            .put("lastServerState", try { JSONObject(AppSettings.lastServerState(activity)) } catch (_: Exception) { JSONObject() })
            .put("capabilities", capabilities()).toString()
    }

    private fun capabilities(): JSONArray {
        val result = JSONArray().put("trip_edit").put("trip_delete").put("trip_labels")
        if (BuildConfig.VERSION_CODE >= 92) result.put("queue_dedupe")
        if (BuildConfig.VERSION_CODE >= 100) result.put("diagnostics_v1").put("server_compatibility")
        if (BuildConfig.VERSION_CODE >= 101) result.put("queue_backoff").put("last_successful_sync")
        if (BuildConfig.VERSION_CODE >= 102) result.put("device_instance").put("notification_deeplink")
        if (BuildConfig.VERSION_CODE >= 200) result.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")
        return result
    }
}
''')

write("app/src/main/java/ch/asds/mobile/ServerClient.kt", r'''package ch.asds.mobile

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ServerClient {
    private const val STATUS_URL = "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/device_status.php?stage=10"
    data class Response(val code: Int, val body: String)

    fun postForm(item: JSONObject): Response {
        val url = item.optString("url")
        val connection = openConnection(url, item.optString("method", "POST"))
        connection.setRequestProperty("Content-Type", item.optString("contentType", "application/x-www-form-urlencoded; charset=UTF-8"))
        val body = item.optString("body").toByteArray(Charsets.UTF_8)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return read(connection)
    }

    fun deviceStatus(context: Context, repository: OfflineRepository, syncCompleted: Boolean = false, acknowledgements: JSONObject? = null): JSONObject? {
        val connection = openConnection(STATUS_URL, "POST")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.doOutput = true
        val capabilities = JSONArray().put("trip_edit").put("trip_delete").put("trip_labels")
        if (BuildConfig.VERSION_CODE >= 92) capabilities.put("queue_dedupe")
        if (BuildConfig.VERSION_CODE >= 100) capabilities.put("diagnostics_v1").put("server_compatibility")
        if (BuildConfig.VERSION_CODE >= 101) capabilities.put("queue_backoff")
        if (BuildConfig.VERSION_CODE >= 102) capabilities.put("device_instance").put("notification_deeplink")
        if (BuildConfig.VERSION_CODE >= 200) capabilities.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")
        val payload = JSONObject().put("app_version", BuildConfig.VERSION_NAME).put("app_version_code", BuildConfig.VERSION_CODE).put("capabilities", capabilities)
            .put("notifications_enabled", AppSettings.notificationsEnabled(context)).put("biometric_enabled", AppSettings.biometricEnabled(context))
            .put("sync_completed", syncCompleted)
            .put("device_info", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE))
            .put("security", JSONObject().put("biometric", AppSettings.biometricEnabled(context)).put("notifications", AppSettings.notificationsEnabled(context))
                .put("drafts", repository.draftCount()).put("queue", repository.queueCount()).put("failed_queue", repository.failedQueueCount()))
        if (BuildConfig.VERSION_CODE >= 102) payload.put("instance_id", AppSettings.instanceId(context))
        if (acknowledgements != null) acknowledgements.keys().forEach { key -> payload.put(key, acknowledgements.opt(key)) }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val response = read(connection)
        if (response.code == 401 || response.code == 403) return JSONObject().put("ok", false).put("not_paired", true)
        return try { JSONObject(response.body) } catch (_: Exception) { null }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method.uppercase()
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "ASDSMobile/${BuildConfig.VERSION_NAME} Android")
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    private fun read(connection: HttpURLConnection): Response {
        val code = connection.responseCode
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val body = if (stream != null) BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() } else ""
        connection.disconnect()
        return Response(code, body)
    }
}
''')

write("app/src/main/java/ch/asds/mobile/SyncWorker.kt", r'''package ch.asds.mobile

import android.content.Context
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = OfflineRepository(applicationContext)
        var hadTemporaryFailure = false
        repository.readyQueueRaw().forEach { item ->
            try {
                val response = ServerClient.postForm(item)
                if (response.code in 200..399) repository.removeQueueItem(item.optString("id"), true)
                else {
                    repository.markQueueFailure(item.optString("id"), "HTTP ${response.code}")
                    if (response.code >= 500 || response.code == 408 || response.code == 429) hadTemporaryFailure = true
                }
            } catch (error: Exception) {
                repository.markQueueFailure(item.optString("id"), error.message ?: error.javaClass.simpleName)
                hadTemporaryFailure = true
            }
        }

        val status = try { ServerClient.deviceStatus(applicationContext, repository, !hadTemporaryFailure) } catch (_: Exception) { null }
        if (status != null) {
            handleRemote(status, repository)
            if (BuildConfig.VERSION_CODE >= 100) {
                AppSettings.setLastServerState(applicationContext, status.toString())
                val compatibility = status.optJSONObject("compatibility")?.toString() ?: status.optString("compatibility", "")
                AppSettings.setCompatibilityState(applicationContext, compatibility)
            }
        } else hadTemporaryFailure = true
        repository.setLastSync()
        if (!hadTemporaryFailure && status?.optBoolean("ok", true) != false) repository.setLastSuccessfulSync()
        return if (hadTemporaryFailure) Result.retry() else Result.success()
    }

    private fun handleRemote(status: JSONObject, repository: OfflineRepository) {
        if (status.optBoolean("not_paired")) { CookieManager.getInstance().removeAllCookies(null); return }
        val remote = status.optJSONObject("remote") ?: return
        if (remote.optBoolean("lock")) {
            AppSettings.setForceLock(applicationContext, true)
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_lock", true)) } catch (_: Exception) { }
        }
        if (remote.optBoolean("wipe")) {
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_remote_wipe", true)) } catch (_: Exception) { }
            repository.clearAll(); CookieManager.getInstance().removeAllCookies(null)
        } else if (remote.optBoolean("logout")) {
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_remote_logout", true)) } catch (_: Exception) { }
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
''')

main = Path("app/src/main/java/ch/asds/mobile/MainActivity.kt")
text = main.read_text(encoding="utf-8")
text, n = re.subn(r'ASDSMobile/1\.8\.0', f'ASDSMobile/{version}', text, count=1)
if n != 1:
    raise SystemExit("MainActivity user agent 1.8.0 not found")

pattern = re.compile(r'    private fun showNativeSecurityMenu\(\) \{.*?\n    \}\n\n    override fun onSaveInstanceState', re.S)
replacement = r'''    private fun showNativeSecurityMenu() {
        val biometricOn = AppSettings.biometricEnabled(this)
        val notificationsOn = AppSettings.notificationsEnabled(this)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += getString(if (biometricOn) R.string.security_disable_lock else R.string.security_enable_lock)
        actions += { securityController.setBiometricEnabled(!biometricOn) }
        labels += getString(R.string.security_lock_now)
        actions += { securityController.lockNow() }
        labels += getString(if (notificationsOn) R.string.security_disable_notifications else R.string.security_enable_notifications)
        actions += { if (notificationsOn) { AppSettings.setNotificationsEnabled(this, false); reportDeviceState() } else requestNotificationPermission() }
        labels += getString(R.string.security_sync_now)
        actions += { offlineRepository.resetQueueRetries(); WorkerScheduler.retryNow(this); Toast.makeText(this, R.string.security_sync_started, Toast.LENGTH_SHORT).show() }
        labels += getString(R.string.security_open_center)
        actions += { navigateTo("security.php") }
        if (BuildConfig.VERSION_CODE >= 100) { labels += getString(R.string.security_diagnostics); actions += { showNativeDiagnostics() } }
        AlertDialog.Builder(this).setTitle(R.string.security_title)
            .setMessage(getString(R.string.security_status, offlineRepository.draftCount(), offlineRepository.queueCount()))
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showNativeDiagnostics() {
        val diagnostic = ASDSNativeBridge(this, offlineRepository, securityController).getDiagnostics()
        AlertDialog.Builder(this).setTitle(R.string.diagnostics_title).setMessage(diagnostic)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ASDS Mobile Diagnose", diagnostic))
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    override fun onSaveInstanceState'''
text, n = pattern.subn(replacement, text, count=1)
if n != 1:
    raise SystemExit("security menu block not found")

old = '''        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(PORTAL_URL)
    }
'''
new = '''        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else {
            val requested = if (BuildConfig.VERSION_CODE >= 102) intent?.getStringExtra("asds_url") else null
            webView.loadUrl(requested?.takeIf(::isInternalUrl) ?: PORTAL_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.VERSION_CODE >= 102) intent.getStringExtra("asds_url")?.takeIf(::isInternalUrl)?.let { webView.loadUrl(it) }
    }
'''
if old not in text:
    raise SystemExit("onCreate load block not found")
text = text.replace(old, new, 1)
main.write_text(text, encoding="utf-8")

print(f"ASDS Mobile Android {version} release-series enhancements applied (versionCode {version_code})")
