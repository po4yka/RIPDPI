package com.poyka.ripdpi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.poyka.ripdpi.diagnostics.BreadcrumbLogWriter
import com.poyka.ripdpi.diagnostics.FileLogWriter
import com.poyka.ripdpi.diagnostics.crash.CrashReportWriter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RipDpiApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var fileLogWriter: FileLogWriter

    @Inject
    lateinit var startupInitializer: AppStartupInitializer

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Hilt-WorkManager integration: WorkManager queries this provider when
    // it needs to instantiate a `@HiltWorker`-annotated worker so the
    // worker's constructor dependencies resolve through Hilt instead of
    // through WorkManager's default reflective factory. Required for any
    // `CoroutineWorker` that uses `@AssistedInject`.
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.VERBOSE else android.util.Log.INFO)
                .build()

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
