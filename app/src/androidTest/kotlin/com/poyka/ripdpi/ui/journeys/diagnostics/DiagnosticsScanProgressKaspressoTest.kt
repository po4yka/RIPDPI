package com.poyka.ripdpi.ui.journeys.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactoryModule
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import org.junit.Test
import javax.inject.Singleton

/**
 * Native scan stand-in: [startScan] succeeds and the scan reports progress without ever finishing until
 * [cancelScan] is called, which makes a partial terminal report available. The real Diagnostics UI,
 * ViewModel, scan controller and conflict-dialog logic run unchanged on top of it.
 */
private class HeldScanBridge : NetworkDiagnosticsBridge {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var request: EngineScanRequestWire? = null

    @Volatile private var sessionId: String? = null

    @Volatile private var cancelled = false

    override suspend fun startScan(
        requestJson: String,
        sessionId: String,
    ) {
        request = json.decodeFromString(EngineScanRequestWire.serializer(), requestJson)
        this.sessionId = sessionId
        cancelled = false
    }

    override suspend fun cancelScan() {
        cancelled = true
    }

    override suspend fun pollProgressJson(): String? {
        val id = sessionId ?: return null
        return json.encodeToString(
            EngineProgressWire.serializer(),
            EngineProgressWire(
                sessionId = id,
                phase = "probing",
                completedSteps = 1,
                totalSteps = 10,
                message = "Held scan",
                isFinished = cancelled,
            ),
        )
    }

    override suspend fun takeReportJson(): String? {
        val id = sessionId ?: return null
        val scan = request ?: return null
        if (!cancelled) return null
        return json.encodeToString(
            EngineScanReportWire.serializer(),
            EngineScanReportWire(
                sessionId = id,
                profileId = scan.profileId,
                pathMode = scan.pathMode,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "Cancelled held scan",
            ),
        )
    }

    override suspend fun pollPassiveEventsJson(): String? = "[]"

    override suspend fun destroy() {
        sessionId = null
        request = null
    }
}

/** Scan-in-progress states of the Diagnostics scan section, driven by a native scan that never finishes on its own. */
@HiltAndroidTest
@UninstallModules(NetworkDiagnosticsBridgeFactoryModule::class)
class DiagnosticsScanProgressKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Diagnostics().stableRoute,
            dataPreset = AutomationDataPreset.DiagnosticsDemo,
        ),
    ) {
    // Nested so Hilt installs it only for this test class.
    @Module
    @InstallIn(SingletonComponent::class)
    object HeldScanBridgeFactoryModule {
        @Provides
        @Singleton
        fun provideNetworkDiagnosticsBridgeFactory(): NetworkDiagnosticsBridgeFactory =
            object : NetworkDiagnosticsBridgeFactory {
                override fun create(): NetworkDiagnosticsBridge = HeldScanBridge()
            }
    }

    private fun DiagnosticsPage.startRawScan() {
        openSection(DiagnosticsSection.Scan)
        waitFor(scanStateIdle)
        click(scanRunRawAction)
        waitFor(scanStateProgress)
    }

    @Test
    fun rawScanMovesTheScanSectionIntoProgress() =
        run {
            step("Starting a raw scan replaces the idle state with the progress state and its cancel action") {
                with(DiagnosticsPage(composeRule)) {
                    startRawScan()
                    awaitDisplayed(scanCancelAction)
                    waitGone(scanStateIdle)
                }
            }
        }

    @Test
    fun cancellingAScanLeavesTheProgressState() =
        run {
            step("Cancelling the running scan leaves the progress state") {
                with(DiagnosticsPage(composeRule)) {
                    startRawScan()
                    // An injected touch on this just-inserted list item did not reach its handler on the emulator.
                    activate(scanCancelAction)
                    // Cancellation waits up to two 4 s native grace periods before it settles.
                    waitGone(scanStateProgress, timeoutMillis = 30_000)
                }
            }
        }
}
