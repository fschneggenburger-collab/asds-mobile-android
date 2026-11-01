package ch.asds.mobile

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
        if (BuildConfig.VERSION_CODE >= 103) capabilities.put("feedback").put("announcements").put("offline_capture_v1").put("more_v2").put("v2_final").put("configured_trip_labels").put("suite_first_trip_view")
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
