package ch.asds.mobile

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

    @JavascriptInterface fun setOfflineConfig(value: String) = repository.setOfflineConfig(value)
    @JavascriptInterface fun getOfflineConfig(): String = repository.offlineConfig()
    @JavascriptInterface fun getOfflineConfigAt(): Long = repository.offlineConfigAt()
    @JavascriptInterface fun openOfflineCapture() = activity.runOnUiThread { activity.openOfflineCapture() }

    @JavascriptInterface
    fun labelForTripType(code: String): String {
        val key = code.trim().lowercase()
        try {
            val config = JSONObject(repository.offlineConfig())
            val types = config.optJSONArray("trip_types")
            if (types != null) {
                for (index in 0 until types.length()) {
                    val item = types.optJSONObject(index) ?: continue
                    val itemCode = item.optString("code", item.optString("id", "")).trim().lowercase()
                    if (itemCode == key) {
                        val label = item.optString("label", "").trim()
                        if (label.isNotBlank()) return label
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to generic built-in labels when no cached config exists yet.
        }
        return when (key) {
            "asds", "asds_internal", "asds-internal" -> "ASDS intern"
            "commute_asds", "asds_commute" -> "Arbeitsweg ASDS"
            "commute_other", "other_employer", "employer_other" -> "Arbeitsweg anderer Arbeitgeber"
            "private", "privat" -> "Privat"
            "business", "business_other", "dienstlich" -> "Geschäftlich"
            "other", "sonstiges", "misc" -> "Andere Fahrt"
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
            .put("offlineConfigAt", repository.offlineConfigAt())
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
        if (BuildConfig.VERSION_CODE >= 103) result.put("feedback").put("announcements").put("offline_capture_v1").put("more_v2").put("v2_final").put("configured_trip_labels").put("suite_first_trip_view")
        if (BuildConfig.VERSION_CODE >= 200) result.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")
        return result
    }
}
