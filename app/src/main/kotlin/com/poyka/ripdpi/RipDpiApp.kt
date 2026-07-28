package com.poyka.ripdpi

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.poyka.ripdpi.diagnostics.BreadcrumbLogWriter
import com.poyka.ripdpi.diagnostics.FileLogWriter
import com.poyka.ripdpi.diagnostics.crash.CrashReportWriter
import com.poyka.ripdpi.platform.MemoryTrimCoordinator
import com.poyka.ripdpi.services.AndroidRuntimeEvidenceReporter
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

    @Inject
    lateinit var memoryTrimCoordinator: MemoryTrimCoordinator

    @Inject
    lateinit var runtimeEvidenceReporter: AndroidRuntimeEvidenceReporter

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
                .setMinimumLoggingLevel(workManagerMinimumLoggingLevel(BuildConfig.DEBUG))
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

    // Android reclaims memory from backgrounded processes and, on Android 17,
    // hard-caps a single app's footprint. Voluntarily shed regenerable caches
    // when the UI is hidden so the OS does not have to (and so RIPDPI stays
    // under the cap). Only TRIM_MEMORY_UI_HIDDEN and TRIM_MEMORY_BACKGROUND are
    // still delivered by the platform as of Android 14/15; the other levels are
    // deprecated no-ops.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        handleTrimMemoryCallback(
            level = level,
            recordEvidence = runtimeEvidenceReporter::recordMemoryTrim,
            trimBackgroundCaches = memoryTrimCoordinator::onAppBackgrounded,
        )
    }
}

internal fun handleTrimMemoryCallback(
    level: Int,
    recordEvidence: (Int) -> Unit,
    trimBackgroundCaches: () -> Unit,
) {
    recordEvidence(level)
    if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
        level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    ) {
        trimBackgroundCaches()
    }
}

internal fun workManagerMinimumLoggingLevel(isDebugBuild: Boolean): Int = if (isDebugBuild) Log.VERBOSE else Log.WARN
