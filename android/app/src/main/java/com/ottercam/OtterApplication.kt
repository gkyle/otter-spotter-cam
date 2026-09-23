package com.kylescholz.otterspotter

import android.app.Application
import android.util.Log
import com.kylescholz.otterspotter.sync.SyncScheduler

class OtterApplication : Application() {

    companion object {
        private const val TAG = "OtterApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OtterApplication initialized. Scheduling periodic background sync.")
        SyncScheduler.schedulePeriodicSync(this)
    }
}
