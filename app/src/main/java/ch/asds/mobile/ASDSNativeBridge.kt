package ch.asds.mobile

import android.webkit.JavascriptInterface
import org.json.JSONObject

class ASDSNativeBridge(
    private val activity: MainActivity,
    private val repository: OfflineRepository,
    private val securityController: SecurityController
) {
    @JavascriptInterface
    fun getState(): String = JSONObject()
        .put("draftCount", repository.draftCount())
        .put("queueCount", repository.queueCount())
        .put("biometricEnabled", AppSettings.biometricEnabled(activity))
        .put("notificationsEnabled", AppSettings.notificationsEnabled(activity))
        .put("lastSync", repository.lastSync())
        .toString()

    @JavascriptInterface
    fun saveDraft(key: String, label: String, payload: String) = repository.saveDraft(key, label, payload)

    @JavascriptInterface
    fun loadDraft(key: String): String = repository.loadDraft(key)

    @JavascriptInterface
    fun deleteDraft(key: String) = repository.deleteDraft(key)

    @JavascriptInterface
    fun clearDraftPrefix(prefix: String) = repository.clearDraftPrefix(prefix)

    @JavascriptInterface
    fun listDrafts(): String = repository.listDrafts().toString()

    @JavascriptInterface
    fun enqueueForm(url: String, method: String, body: String, label: String, draftKey: String): String {
        val id = repository.enqueue(url, method, body, "application/x-www-form-urlencoded; charset=UTF-8", label, draftKey)
        WorkerScheduler.retryNow(activity)
        return id
    }

    @JavascriptInterface
    fun listQueue(): String = repository.listQueueForUi().toString()

    @JavascriptInterface
    fun retrySync() = WorkerScheduler.retryNow(activity)

    @JavascriptInterface
    fun clearLocalData() = repository.clearAll()

    @JavascriptInterface
    fun setBiometricEnabled(enabled: Boolean) = activity.runOnUiThread { securityController.setBiometricEnabled(enabled) }

    @JavascriptInterface
    fun lockNow() = activity.runOnUiThread { securityController.lockNow() }

    @JavascriptInterface
    fun setNotificationsEnabled(enabled: Boolean) {
        AppSettings.setNotificationsEnabled(activity, enabled)
        activity.reportDeviceState()
        if (enabled) WorkerScheduler.retryNow(activity)
    }

    @JavascriptInterface
    fun requestNotificationPermission() = activity.runOnUiThread { activity.requestNotificationPermission() }
}
