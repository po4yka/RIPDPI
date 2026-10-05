package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppliedRuntimeSummaryTest {
    @Test fun `halted failed replacement retains pending and history without reconnect confirmation`() {
        val selection = RuntimeConfigurationSelection("native")
        val applied =
            AppliedRuntimeConfiguration(
                "old-runtime",
                1,
                1,
                Mode.VPN,
                selection,
                selection,
                RuntimeConfigurationDns("plain", "system"),
                RuntimeConfigurationStrategy(false),
                RuntimeConfigurationApplyReason.InitialStart,
            )
        val attempt =
            com.poyka.ripdpi.data.RuntimeConfigurationAttempt(
                "failed-runtime",
                1,
                Mode.VPN,
                selection,
                RuntimeConfigurationApplyReason.UserReconnect,
            )
        val base =
            MainUiInputs(
                com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
                com.poyka.ripdpi.data.AppStatus.Running to Mode.VPN,
                ConnectionRuntimeState(),
                com.poyka.ripdpi.data
                    .ServiceTelemetrySnapshot(),
                PermissionRuntimeState(),
                com.poyka.ripdpi.services
                    .AndroidHardKillSwitchSnapshot(),
                emptyList(),
                HostPackCatalogUiState(),
                com.poyka.ripdpi.data
                    .StrategyPackRuntimeState(),
                null,
                applications =
                    mapOf(
                        Mode.VPN to
                            com.poyka.ripdpi.data.RuntimeConfigurationApplication
                                .Applied(applied),
                    ),
                pendingConfigurations =
                    mapOf(Mode.VPN to com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus.SavedChangesPending),
            )
        val strings = AndroidStringResolver(RuntimeEnvironment.getApplication())
        assertTrue(buildAppliedConfigurationUiState(base, strings).confirmation != null)
        val failed =
            base.copy(
                statusAndMode = com.poyka.ripdpi.data.AppStatus.Halted to Mode.VPN,
                applications =
                    mapOf(
                        Mode.VPN to
                            com.poyka.ripdpi.data.RuntimeConfigurationApplication.Failed(
                                attempt,
                                applied,
                                com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure.RuntimeRejected,
                            ),
                    ),
            )
        val view = buildAppliedConfigurationUiState(failed, strings)
        assertTrue(view.visible)
        assertTrue(view.pending)
        assertTrue(view.previous)
        org.junit.Assert.assertNull(view.confirmation)
        val applying =
            base.copy(
                applications =
                    mapOf(
                        Mode.VPN to
                            com.poyka.ripdpi.data.RuntimeConfigurationApplication
                                .Applying(attempt, applied),
                    ),
            )
        org.junit.Assert.assertNull(buildAppliedConfigurationUiState(applying, strings).confirmation)
    }

    @Test
    fun `DoT and custom strategy are factual and unrecognized transport never leaks raw input`() {
        val selection =
            RuntimeConfigurationSelection(
                "native",
                transport = "private-endpoint-or-token",
                relayKind = "warp",
                profileId = "private-profile-id",
            )
        val applied =
            AppliedRuntimeConfiguration(
                "runtime",
                1,
                1,
                Mode.VPN,
                selection,
                selection,
                RuntimeConfigurationDns(DnsModeEncrypted, "custom", "dot"),
                RuntimeConfigurationStrategy(true, custom = true),
                RuntimeConfigurationApplyReason.InitialStart,
            )
        val summary = confirmedRuntimeSummary(applied, AndroidStringResolver(RuntimeEnvironment.getApplication()))
        assertTrue(summary.contains("WARP"))
        assertTrue(summary.contains("DoT"))
        assertFalse(summary.contains("DoH"))
        assertTrue(summary.contains("Custom"))
        assertFalse(summary.contains("private-endpoint-or-token"))
        assertFalse(summary.contains("private-profile-id"))
    }
}
