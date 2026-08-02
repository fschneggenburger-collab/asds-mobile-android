package ch.asds.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NotificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val CHANNEL_ID = "asds_reminders"
    }

    override suspend fun doWork(): Result {
        if (!AppSettings.notificationsEnabled(applicationContext)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()

        val repository = OfflineRepository(applicationContext)
        val status = try { ServerClient.deviceStatus(applicationContext, repository) } catch (_: Exception) { null } ?: return Result.retry()
        if (!status.optBoolean("ok")) return Result.success()
        createChannel()
        val seen = applicationContext.getSharedPreferences("asds_notification_seen", Context.MODE_PRIVATE)
        val notifications = status.optJSONArray("notifications") ?: return Result.success()
        for (index in 0 until notifications.length()) {
            val item = notifications.optJSONObject(index) ?: continue
            val key = item.optString("key")
            if (key.isBlank() || seen.getBoolean(key, false)) continue
            showNotification(key, item.optString("title", "ASDS Mobile"), item.optString("body"), item.optString("url"))
            seen.edit().putBoolean(key, true).apply()
        }
        return Result.success()
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ASDS Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Termine und Aufgaben aus ASDS Mobile"
        })
    }

    private fun showNotification(key: String, title: String, body: String, url: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra("asds_url", url)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(key.hashCode(), notification)
    }
}
