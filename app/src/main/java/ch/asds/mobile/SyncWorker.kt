package ch.asds.mobile

import android.content.Context
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = OfflineRepository(applicationContext)
        var hadTemporaryFailure = false

        repository.listQueueRaw().forEach { item ->
            try {
                val response = ServerClient.postForm(item)
                if (response.code in 200..399) {
                    repository.removeQueueItem(item.optString("id"), true)
                } else {
                    repository.markQueueFailure(item.optString("id"), "HTTP ${response.code}")
                    if (response.code >= 500) hadTemporaryFailure = true
                }
            } catch (error: Exception) {
                repository.markQueueFailure(item.optString("id"), error.message ?: error.javaClass.simpleName)
                hadTemporaryFailure = true
            }
        }

        val status = try { ServerClient.deviceStatus(applicationContext, repository, true) } catch (_: Exception) { null }
        if (status != null) handleRemote(status, repository)
        repository.setLastSync()
        return if (hadTemporaryFailure) Result.retry() else Result.success()
    }

    private fun handleRemote(status: JSONObject, repository: OfflineRepository) {
        if (status.optBoolean("not_paired")) {
            CookieManager.getInstance().removeAllCookies(null)
            return
        }
        val remote = status.optJSONObject("remote") ?: return
        if (remote.optBoolean("lock")) {
            AppSettings.setForceLock(applicationContext, true)
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_lock", true)) } catch (_: Exception) { }
        }
        if (remote.optBoolean("wipe")) {
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_remote_wipe", true)) } catch (_: Exception) { }
            repository.clearAll()
            CookieManager.getInstance().removeAllCookies(null)
        } else if (remote.optBoolean("logout")) {
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_remote_logout", true)) } catch (_: Exception) { }
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
