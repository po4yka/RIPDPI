package com.poyka.ripdpi

import android.app.Application
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.poyka.ripdpi.diagnostics.DiagnosticsRetentionWorker
import com.poyka.ripdpi.subscription.SubscriptionAutoUpdateWorker
import dagger.hilt.android.internal.modules.ApplicationContextModule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppWorkerFactoryTest {
    @Test
    fun `production graph registers app workers with the configured factory`() {
        val context = RuntimeEnvironment.getApplication()
        val app = RipDpiApp()
        DaggerRipDpiApp_HiltComponents_SingletonC
            .builder()
            .applicationContextModule(ApplicationContextModule(context))
            .build()
            .injectRipDpiApp(app)
        try {
            val factory = app.workManagerConfiguration.workerFactory
            val parameters = parameters(factory)
            val subscription = factory.createWorker(context, SubscriptionAutoUpdateWorker::class.java.name, parameters)
            assertTrue(
                "Subscription refresh must have a generated Hilt binding",
                subscription is SubscriptionAutoUpdateWorker,
            )
            val retention = factory.createWorker(context, DiagnosticsRetentionWorker::class.java.name, parameters)
            assertTrue(
                "Diagnostics retention must have a generated Hilt binding",
                retention is DiagnosticsRetentionWorker,
            )
        } finally {
            app.applicationCoroutineScopeTerminator.terminate()
        }
    }

    private fun parameters(factory: WorkerFactory): WorkerParameters {
        val executor = Executor(Runnable::run)
        return WorkerParameters(
            UUID.randomUUID(),
            Data.EMPTY,
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            EmptyCoroutineContext,
            WorkManagerTaskExecutor(executor),
            factory,
            ProgressUpdater { _, _, _ -> error("Factory creation must not execute work") },
            ForegroundUpdater { _, _, _ -> error("Factory creation must not execute work") },
        )
    }
}
