package com.exporeminder.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.exporeminder.data.SyncRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 每日 09:00(台北)精確鬧鐘,喚醒後跑同步+提醒評估,再排下一天。
 * WorkManager 的週期任務在三星/小米的激進省電下可能整天不跑,
 * 精確鬧鐘(setExactAndAllowWhileIdle)是 reminder 類 app 的業界標準做法;
 * 兩者並存:鬧鐘為主,WorkManager 週期任務當備援。
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 9001

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now(SyncRepository.TAIPEI)
        var next = now.with(LocalTime.of(9, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val pi = pendingIntent(context)
        val at = next.toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 鬧鐘響 → 丟給 SyncWorker(它離線也會用快取評估提醒)→ 排明天。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncWorker.syncNow(context)
        AlarmScheduler.schedule(context)
    }
}

/** 重開機後鬧鐘會消失,開機時重排。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.schedule(context)
        }
    }
}