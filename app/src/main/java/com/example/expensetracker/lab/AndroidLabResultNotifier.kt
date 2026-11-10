package com.example.expensetracker.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.expensetracker.R

/**
 * Shows the lab result as an ordinary notification belonging to DailySpend itself.
 *
 * It uses no special-access service: a listener that reads *other* applications' notifications would
 * cross the lab's own rules. Android 13 and later require POST_NOTIFICATIONS, which the user grants
 * from the "Allow lab result notifications" button on Settings - never at startup. While that is
 * outstanding, [post] reports false and the archived lab event says so instead of claiming a tray
 * entry that was never shown.
 */
class AndroidLabResultNotifier(context: Context) : LabResultNotifier {

    private val appContext = context.applicationContext

    override fun post(title: String, message: String): Boolean {
        val manager = manager() ?: return false
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(manager)
        return runCatching { manager.notify(SecurityTestNotification.NOTIFICATION_ID, build(title, message)) }.isSuccess
    }

    private fun manager(): NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            SecurityTestNotification.CHANNEL_ID,
            SecurityTestNotification.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Results of the opt-in university security-lab tests"
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun build(title: String, message: String) = NotificationCompat.Builder(
        appContext,
        SecurityTestNotification.CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .build()
}
