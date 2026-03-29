package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiContextSupportTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `missing doze exemption alone does not create samsung background warning`() {
        val warnings =
            support.buildContextWarnings(
                context = diagnosticContext(manufacturer = "Samsung", batteryOptimizationState = "disabled"),
            )

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `power restrictions use generic warning even on samsung`() {
        val warnings =
            support.buildContextWarnings(
                context =
                    diagnosticContext(
                        manufacturer = "Samsung",
                        batteryOptimizationState = "disabled",
                        dataSaverState = "enabled",
                    ),
            )

        assertEquals(1, warnings.size)
        assertEquals("context-power-restriction", warnings.single().id)
        assertEquals(
            support.context.getString(R.string.diagnostics_warn_power_restriction),
            warnings.single().message,
        )
    }

    @Test
    fun `overview summary includes blocked host counts`() {
        val group =
            support.toOverviewContextGroup(
                diagnosticContext(
                    manufacturer = "Google",
                    batteryOptimizationState = "disabled",
                    blockedHostCount = 2,
                ),
            )

        val hostLearning =
            group.fields.firstOrNull {
                it.label == support.context.getString(R.string.diagnostics_field_host_learning)
            }
        assertNotNull(hostLearning)
        assertEquals("Active · 3 learned · 1 penalized · 2 blocked", hostLearning?.value)
    }

    @Test
    fun `live context groups humanize blocked host signal and provider`() {
        val groups =
            support.toLiveContextGroups(
                diagnosticContext(
                    manufacturer = "Google",
                    batteryOptimizationState = "disabled",
                    blockedHostCount = 1,
                    lastBlockSignal = "tcp_reset",
                    lastBlockProvider = "rkn",
                    lastAutolearnAction = "host_blocked",
                ),
            )

        val hostLearning =
            groups.first {
                it.title == support.context.getString(R.string.diagnostics_field_host_learning)
            }

        assertEquals(
            "1",
            hostLearning.valueFor(support.context.getString(R.string.diagnostics_field_blocked_hosts)),
        )
        assertEquals(
            support.context.getString(R.string.block_signal_tcp_reset),
            hostLearning.valueFor(support.context.getString(R.string.diagnostics_field_last_block_signal)),
        )
        assertEquals(
            "RKN",
            hostLearning.valueFor(support.context.getString(R.string.diagnostics_field_last_block_provider)),
        )
        assertEquals(
            support.context.getString(R.string.diagnostics_autolearn_host_blocked),
            hostLearning.valueFor(support.context.getString(R.string.diagnostics_field_last_action)),
        )
    }

    private fun diagnosticContext(
        manufacturer: String,
        batteryOptimizationState: String,
        dataSaverState: String = "disabled",
        powerSaveModeState: String = "disabled",
        blockedHostCount: Int = 0,
        lastBlockSignal: String = "none",
        lastBlockProvider: String = "none",
        lastAutolearnAction: String = "host_promoted",
    ): DiagnosticContextModel =
        DiagnosticContextModel(
            service =
                ServiceContextModel(
                    serviceStatus = "Running",
                    configuredMode = "VPN",
                    activeMode = "VPN",
                    selectedProfileId = "default",
                    selectedProfileName = "Default",
                    configSource = "ui",
                    proxyEndpoint = "127.0.0.1:1080",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(1)",
                    routeGroup = "3",
                    sessionUptimeMs = 20_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 2,
                    hostAutolearnEnabled = "enabled",
                    learnedHostCount = 3,
                    penalizedHostCount = 1,
                    blockedHostCount = blockedHostCount,
                    lastBlockSignal = lastBlockSignal,
                    lastBlockProvider = lastBlockProvider,
                    lastAutolearnHost = "example.org",
                    lastAutolearnGroup = "2",
                    lastAutolearnAction = lastAutolearnAction,
                ),
            permissions =
                PermissionContextModel(
                    vpnPermissionState = "enabled",
                    notificationPermissionState = "enabled",
                    batteryOptimizationState = batteryOptimizationState,
                    dataSaverState = dataSaverState,
                ),
            device =
                DeviceContextModel(
                    appVersionName = "0.0.1",
                    appVersionCode = 1L,
                    buildType = "debug",
                    androidVersion = "16",
                    apiLevel = 36,
                    manufacturer = manufacturer,
                    model = "Pixel",
                    primaryAbi = "arm64-v8a",
                    locale = "en-US",
                    timezone = "UTC",
                ),
            environment =
                EnvironmentContextModel(
                    batterySaverState = "disabled",
                    powerSaveModeState = powerSaveModeState,
                    networkMeteredState = "disabled",
                    roamingState = "disabled",
                ),
        )

    private fun DiagnosticsContextGroupUiModel.valueFor(label: String): String =
        fields.first { it.label == label }.value
}
