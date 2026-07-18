package com.glazkov.outagewatch.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glazkov.outagewatch.BuildConfig
import com.glazkov.outagewatch.R
import com.glazkov.outagewatch.ui.AppGraph

/**
 * Periodically asks GitHub for the latest release and, the first time a newer
 * version appears, posts a system notification. Tapping it opens the release
 * page to download the new APK. Runs even when the app is closed, so users hear
 * about a new version without opening the app. Dedup (once per version) is
 * shared with the in-app dialog via [AppUpdate]'s settings flag.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = runCatching {
            AppUpdate.check(BuildConfig.VERSION_NAME, AppGraph.api)
        }.getOrNull() ?: return Result.success()
        postNotification(update)
        return Result.success()
    }

    private fun postNotification(update: AppUpdate.Available) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "New OutageWatch versions" }
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.url))
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Update available")
            .setContentText("OutageWatch ${update.version} is out. Tap to update.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            manager.notify(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIF_ID = 424242
        const val WORK_NAME = "update-check"
    }
}
