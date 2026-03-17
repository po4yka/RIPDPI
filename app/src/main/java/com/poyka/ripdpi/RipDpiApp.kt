package com.poyka.ripdpi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import logcat.AndroidLogcatLogger
import logcat.LogPriority.VERBOSE

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
