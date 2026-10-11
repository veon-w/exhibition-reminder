package com.exporeminder.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exporeminder.ui.MainActivity

class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_REMINDERS = "reminders"   // R1/R2/R3
        const val CHANNEL_DIGEST = "digest"         // R4 週摘要

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDERS, "展覽提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "開展、閉展倒數、開賣提醒" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DIGEST, "每週摘要",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    fun show(c: ReminderEngine.Candidate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val channel = if (c.ruleKey == "R4") CHANNEL_DIGEST else CHANNEL_REMINDERS
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("exhibitionId", c.exhibitionId)
        }
        val pi = PendingIntent.getActivity(
            context, (c.ruleKey + c.refKey).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(c.title)
            .setContentText(c.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify((c.ruleKey + c.refKey).hashCode(), notif)
    }
}
