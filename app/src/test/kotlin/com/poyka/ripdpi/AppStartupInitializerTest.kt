package com.poyka.ripdpi

import android.app.Application
import com.poyka.ripdpi.core.detection.DetectionObservationStarter
import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import com.poyka.ripdpi.strategy.StrategyPackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class AppStartupInitializerTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `startup report marks all subsystems succeeded when initialization succeeds`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(
                "App startup report: compatibility_reset=succeeded, " +
                    "strategy_pack_initialization=succeeded, diagnostics_bootstrap=succeeded",
                report.toLogMessage(),
            )
        }

    @Test
    fun `compatibility reset failure does not prevent strategy pack or diagnostics startup`() =
        runTest {
            val compatibilityResetter =
                RecordingAppCompatibilityResetter(
                    failure = IllegalStateException("compat-boom"),
                )
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Failed, report.compatibilityReset.status)
            assertEquals("compat-boom", report.compatibilityReset.errorMessage)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertTrue(report.toLogMessage().contains("compatibility_reset=failed(error=compat-boom)"))
        }

    @Test
    fun `strategy pack initialization failure does not prevent diagnostics startup`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService =
                RecordingStrategyPackService(
                    initializeFailure = IllegalStateException("strategy-boom"),
                )
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.strategyPackInitialization.status)
            assertEquals("strategy-boom", report.strategyPackInitialization.errorMessage)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.diagnosticsBootstrap.status)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `diagnostics bootstrap failure does not prevent earlier startup steps`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper =
                RecordingDiagnosticsBootstrapper(
                    failure = IllegalStateException("diagnostics-boom"),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    scope = backgroundScope,
                )

            val report = initializer.initializeSubsystems()

            assertEquals(AppStartupSubsystemStatus.Succeeded, report.compatibilityReset.status)
            assertEquals(AppStartupSubsystemStatus.Succeeded, report.strategyPackInitialization.status)
            assertEquals(AppStartupSubsystemStatus.Failed, report.diagnosticsBootstrap.status)
            assertEquals("diagnostics-boom", report.diagnosticsBootstrap.errorMessage)
            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
        }

    @Test
    fun `public initialize still starts detection observation when a subsystem fails`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService =
                RecordingStrategyPackService(
                    initializeFailure = IllegalStateException("strategy-boom"),
                )
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val detectionObservationStarter = RecordingDetectionObservationStarter()
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    scope = backgroundScope,
                )

            initializer.initialize()
            testScheduler.advanceUntilIdle()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
            assertEquals(application, detectionObservationStarter.contexts.single())
        }

    @Test
    fun `detection observation failure is swallowed after successful subsystem initialization`() =
        runTest {
            val compatibilityResetter = RecordingAppCompatibilityResetter()
            val strategyPackService = RecordingStrategyPackService()
            val diagnosticsBootstrapper = RecordingDiagnosticsBootstrapper()
            val detectionObservationStarter =
                RecordingDetectionObservationStarter(
                    failure = IllegalStateException("detect-boom"),
                )
            val initializer =
                createInitializer(
                    compatibilityResetter = compatibilityResetter,
                    strategyPackService = strategyPackService,
                    diagnosticsBootstrapper = diagnosticsBootstrapper,
                    detectionObservationStarter = detectionObservationStarter,
                    scope = backgroundScope,
                )

            initializer.initialize()
            testScheduler.advanceUntilIdle()

            assertEquals(1, compatibilityResetter.calls)
            assertEquals(1, strategyPackService.initializeCalls)
            assertEquals(1, diagnosticsBootstrapper.calls)
            assertEquals(1, detectionObservationStarter.startCalls)
        }

    @Test
    fun `initializeSubsystems preserves subsystem ordering`() =
        runTest {
            val callOrder = mutableListOf<String>()
            val initializer =
                createInitializer(
                    compatibilityResetter =
                        RecordingAppCompatibilityResetter(onReset = { callOrder += "compatibility" }),
                    strategyPackService =
                        RecordingStrategyPackService(onInitialize = { callOrder += "strategy" }),
                    diagnosticsBootstrapper =
                        RecordingDiagnosticsBootstrapper(onInitialize = { callOrder += "diagnostics" }),
                    scope = backgroundScope,
                )

            initializer.initializeSubsystems()

            assertEquals(listOf("compatibility", "strategy", "diagnostics"), callOrder)
        }

    private fun createInitializer(
        compatibilityResetter: AppCompatibilityResetter,
        strategyPackService: StrategyPackService,
        diagnosticsBootstrapper: DiagnosticsBootstrapper,
        detectionObservationStarter: RecordingDetectionObservationStarter = RecordingDetectionObservationStarter(),
        scope: CoroutineScope,
    ): AppStartupInitializer =
        AppStartupInitializer(
            context = application,
            appCompatibilityResetter = compatibilityResetter,
            diagnosticsBootstrapperProvider = constantProvider(diagnosticsBootstrapper),
            detectionObservationStarter = detectionObservationStarter,
            strategyPackService = strategyPackService,
            applicationScope = scope,
        )
}

private class RecordingAppCompatibilityResetter(
    private val failure: Throwable? = null,
    private val onReset: (() -> Unit)? = null,
) : AppCompatibilityResetter {
    var calls: Int = 0
        private set

    override fun resetIfNeeded() {
        calls += 1
        onReset?.invoke()
        failure?.let { throw it }
    }
}

private class RecordingStrategyPackService(
    private val initializeFailure: Throwable? = null,
    private val onInitialize: (() -> Unit)? = null,
) : StrategyPackService {
    var initializeCalls: Int = 0
        private set

    override fun initialize() {
        initializeCalls += 1
        onInitialize?.invoke()
        initializeFailure?.let { throw it }
    }

    override suspend fun refreshNow() {
        error("unused")
    }
}

private class RecordingDiagnosticsBootstrapper(
    private val failure: Throwable? = null,
    private val onInitialize: (() -> Unit)? = null,
) : DiagnosticsBootstrapper {
    var calls: Int = 0
        private set

    override suspend fun initialize() {
        calls += 1
        onInitialize?.invoke()
        failure?.let { throw it }
    }
}

private class RecordingDetectionObservationStarter(
    private val failure: Throwable? = null,
) : DetectionObservationStarter {
    var startCalls: Int = 0
        private set
    val contexts = mutableListOf<Application>()

    override fun startObserving(
        context: android.content.Context,
        scope: CoroutineScope,
    ) {
        startCalls += 1
        contexts += context as Application
        failure?.let { throw it }
    }
}

private fun constantProvider(bootstrapper: DiagnosticsBootstrapper): Provider<DiagnosticsBootstrapper> =
    object : Provider<DiagnosticsBootstrapper> {
        override fun get(): DiagnosticsBootstrapper = bootstrapper
    }
