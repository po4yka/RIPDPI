package com.poyka.ripdpi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import logcat.AndroidLogcatLogger
import logcat.LogPriority.VERBOSE

@HiltAndroidApp
class RipDpiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = VERBOSE)
    }
}
