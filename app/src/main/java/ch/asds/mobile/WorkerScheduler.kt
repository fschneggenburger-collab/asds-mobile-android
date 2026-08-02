package ch.asds.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkerScheduler {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val sync = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build()
        val notifications = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build()
        workManager.enqueueUniquePeriodicWork("asds_sync", ExistingPeriodicWorkPolicy.UPDATE, sync)
        workManager.enqueueUniquePeriodicWork("asds_notifications", ExistingPeriodicWorkPolicy.UPDATE, notifications)
        retryNow(context)
    }

    fun retryNow(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val sync = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build()
        val notifications = OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(connected).build()
        workManager.enqueueUniqueWork("asds_sync_now", ExistingWorkPolicy.REPLACE, sync)
        workManager.enqueueUniqueWork("asds_notifications_now", ExistingWorkPolicy.REPLACE, notifications)
    }
}
