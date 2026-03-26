package com.poyka.ripdpi

import android.app.Application
import com.poyka.ripdpi.diagnostics.crash.CrashReportWriter
import dagger.hilt.android.HiltAndroidApp
import logcat.AndroidLogcatLogger
import logcat.LogPriority.VERBOSE
import javax.inject.Inject

@HiltAndroidApp
class RipDpiApp : Application() {
    @Inject
    lateinit var startupInitializer: AppStartupInitializer

    override fun onCreate() {
        CrashReportWriter.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        super.onCreate()
        AndroidLogcatLogger.installOnDebuggableApp(this, minPriority = VERBOSE)
        startupInitializer.initialize()
    }
}
