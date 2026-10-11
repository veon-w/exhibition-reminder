package com.exporeminder

import android.app.Application
import com.exporeminder.reminder.Notifier
import com.exporeminder.sync.AlarmScheduler
import com.exporeminder.sync.SyncWorker

class ExpoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        SyncWorker.schedule(this)      // 備援:24h 週期任務
        AlarmScheduler.schedule(this)  // 主力:每日 09:00 精確鬧鐘
    }
}
