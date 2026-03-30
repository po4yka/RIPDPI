package com.poyka.ripdpi

import android.app.Application
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.poyka.ripdpi.diagnostics.BreadcrumbLogWriter
import com.poyka.ripdpi.diagnostics.FileLogWriter
import com.poyka.ripdpi.diagnostics.crash.CrashReportWriter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RipDpiApp : Application() {
    @Inject
    lateinit var fileLogWriter: FileLogWriter

    @Inject
    lateinit var startupInitializer: AppStartupInitializer

    override fun onCreate() {
        val breadcrumbWriter = BreadcrumbLogWriter()
        CrashReportWriter.install(
            this,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong(),
            breadcrumbProvider = breadcrumbWriter::snapshot,
        )
        super.onCreate()
        Logger.setLogWriters(platformLogWriter(), fileLogWriter, breadcrumbWriter)
        Logger.setMinSeverity(if (BuildConfig.DEBUG) Severity.Verbose else Severity.Warn)
        startupInitializer.initialize()
    }
}
