package com.wyrix.guard

import android.app.Application
import com.wyrix.guard.system.Notifs
import com.wyrix.guard.work.Scheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WyrixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        Scheduler.schedulePolling(this)
    }
}
