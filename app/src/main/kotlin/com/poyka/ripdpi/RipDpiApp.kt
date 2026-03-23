package com.poyka.ripdpi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import logcat.AndroidLogcatLogger
import logcat.LogPriority.VERBOSE
import javax.inject.Inject

@HiltAndroidApp
class RipDpiApp : Application() {
    @Inject
    lateinit var startupInitializer: AppStartupInitializer

    override fun onCreate() {
        super.onCreate()
        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = VERBOSE)
        startupInitializer.initialize()
    }
}
