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
        repository.readyQueueRaw().forEach { item ->
            val id = item.optString("id")
            // Until the Mobile Server 2.0.1 capability is actually cached,
            // never hand extended km/appointment time payloads to a legacy
            // endpoint that might return HTTP 200 while silently dropping them.
            if (LegacyOfflineTimeSync.requiresV201Server(item) &&
                !LegacyOfflineTimeSync.supportsV201Server(repository.offlineConfig())) {
                repository.holdQueueItem(id,
                    "Server 2.0.1 für abrechenbare Kilometer / Termin noch nicht bestätigt", true)
                hadTemporaryFailure = true
                return@forEach
            }
            try {
                val response = ServerClient.postForm(item)
                when (LegacyOfflineTimeSync.decision(item, response.code, response.body)) {
                    LegacyOfflineTimeSync.Decision.CONFIRMED -> repository.removeQueueItem(id, true)
                    LegacyOfflineTimeSync.Decision.TRANSIENT -> {
                        repository.markQueueFailure(id,
                            LegacyOfflineTimeSync.failureReason(response.code, response.body))
                        hadTemporaryFailure = true
                    }
                    LegacyOfflineTimeSync.Decision.HOLD -> {
                        repository.holdQueueItem(id,
                            LegacyOfflineTimeSync.failureReason(response.code, response.body))
                        hadTemporaryFailure = true
                    }
                }
            } catch (_: Exception) {
                // Exact client_id and POST body stay unchanged; the server's
                // unique client-id/payload-hash mapping makes retry idempotent.
                repository.markQueueFailure(id, "Netzwerk-/Übertragungsfehler")
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
        val r1Transient = R1Client.sync(applicationContext)
        return if (hadTemporaryFailure || r1Transient) Result.retry() else Result.success()
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
            // A remote wipe must also remove the additive encrypted Room R1 store.
            R1Store(applicationContext).clearForExplicitWipe()
            repository.clearAll(); CookieManager.getInstance().removeAllCookies(null)
        } else if (remote.optBoolean("logout")) {
            try { ServerClient.deviceStatus(applicationContext, repository, acknowledgements = JSONObject().put("ack_remote_logout", true)) } catch (_: Exception) { }
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
