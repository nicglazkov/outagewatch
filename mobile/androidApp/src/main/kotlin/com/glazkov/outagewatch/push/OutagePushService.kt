package com.glazkov.outagewatch.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glazkov.outagewatch.MainActivity
import com.glazkov.outagewatch.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class OutagePushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Subscriptions are re-registered against the new token next app open;
        // the backend prunes dead tokens automatically.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "OutageWatch"
        val body = message.notification?.body ?: return
        val outageId = message.data["outage_id"]

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Outage alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OUTAGE_ID, outageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, outageId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(outageId.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "outage_alerts"
    }
}
