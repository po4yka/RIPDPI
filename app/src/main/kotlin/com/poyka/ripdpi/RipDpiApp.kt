package com.poyka.ripdpi

import android.app.Application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.poyka.ripdpi.diagnostics.crash.CrashReportWriter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RipDpiApp : Application() {
    @Inject
    lateinit var startupInitializer: AppStartupInitializer

    override fun onCreate() {
        CrashReportWriter.install(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        super.onCreate()
        Logger.setLogWriters(platformLogWriter())
        Logger.setMinSeverity(if (BuildConfig.DEBUG) Severity.Verbose else Severity.Warn)
        startupInitializer.initialize()
    }
}
