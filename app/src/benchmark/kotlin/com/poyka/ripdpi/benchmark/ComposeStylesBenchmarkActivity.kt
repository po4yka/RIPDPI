package com.poyka.ripdpi.benchmark

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.poyka.ripdpi.activities.LogEntry
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.activities.LogsUiState
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.screens.logs.LogsScreen
import com.poyka.ripdpi.ui.screens.settings.SettingsScreen
import com.poyka.ripdpi.ui.screens.settings.SettingsScreenActions
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/**
 * Local rendering workloads for the three-build Styles comparison.
 *
 * This Activity has no ViewModels, service actions, storage writes, or network
 * calls. Product Application startup remains unchanged; control device network
 * state during comparisons and report startup separately from frame timing.
 */
class ComposeStylesBenchmarkActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration =
            Configuration(newBase.resources.configuration).apply {
                setLocale(Locale.ENGLISH)
                fontScale = 1f
            }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = intent.getStringExtra(SceneExtra)
        require(scene in setOf("settings", "logs", "controls")) { "Unknown rendering workload: $scene" }
        setContent {
            RipDpiTheme(themePreference = "light") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .ripDpiAutomationTreeRoot()
                        .testTag("style-benchmark-$scene"),
                ) {
                    when (scene) {
                        "settings" -> SettingsWorkload()
                        "logs" -> LogsWorkload()
                        "controls" -> ControlsWorkload()
                    }
                }
            }
        }
    }

    private companion object {
        const val SceneExtra = "com.poyka.ripdpi.benchmark.SCENE"
    }
}

@Composable
private fun SettingsWorkload() {
    val state = remember { SettingsUiState(uiPersona = "advanced", onboardingComplete = true) }
    val actions =
        remember {
            SettingsScreenActions(
                onOpenProfileUtility = ::unexpectedSettingsAction,
                onOpenDnsSettings = ::unexpectedSettingsAction,
                onOpenAdvancedSettings = ::unexpectedSettingsAction,
                onOpenCustomization = ::unexpectedSettingsAction,
                onOpenAbout = ::unexpectedSettingsAction,
                onOpenDataTransparency = ::unexpectedSettingsAction,
                onOpenDetectionCheck = ::unexpectedSettingsAction,
                onOpenSubscriptionFailover = ::unexpectedSettingsAction,
                onOpenSubscriptionStatus = ::unexpectedSettingsAction,
                onOpenDomainBypass = ::unexpectedSettingsAction,
                onOpenRoutingRules = ::unexpectedSettingsAction,
                onOpenSplitTunnel = ::unexpectedSettingsAction,
                onOpenRootModeStrategies = ::unexpectedSettingsAction,
                onOpenBackupRestore = ::unexpectedSettingsAction,
                onOpenLogs = ::unexpectedSettingsAction,
                onShareDebugBundle = ::unexpectedSettingsAction,
                onRepairPermission = { unexpectedSettingsAction() },
                onOpenVpnPermissionDialog = ::unexpectedSettingsAction,
                onDismissBackgroundGuidance = ::unexpectedSettingsAction,
                onThemeSelected = { unexpectedSettingsAction() },
                onPersonaSelected = { unexpectedSettingsAction() },
                onWebRtcProtectionChanged = { unexpectedSettingsAction() },
                onExcludeRussianAppsChanged = { unexpectedSettingsAction() },
                onFullTunnelModeChanged = { unexpectedSettingsAction() },
                onStartOnBootChanged = { unexpectedSettingsAction() },
                onBiometricChanged = { unexpectedSettingsAction() },
                onSaveBackupPin = { unexpectedSettingsAction() },
                onResetSettings = ::unexpectedSettingsAction,
                onCommunityApiUrlChanged = { unexpectedSettingsAction() },
                onClearCommunityCache = ::unexpectedSettingsAction,
            )
        }
    SettingsScreen(
        uiState = state,
        actions = actions,
        permissionSummary = PermissionSummaryUiState(),
    )
}

private fun unexpectedSettingsAction(): Nothing =
    error("The Settings workload measures scrolling only; actions must not run")

@Composable
private fun LogsWorkload() {
    val state = remember { LogsUiState(logs = fixedLogEntries(), isAutoScroll = false) }
    LogsScreen(
        uiState = state,
        onToggleSubsystemFilter = { unexpectedLogsAction() },
        onToggleSeverityFilter = { unexpectedLogsAction() },
        onAutoScrollChanged = { unexpectedLogsAction() },
        onActiveSessionOnlyChanged = { unexpectedLogsAction() },
        onClearLogs = ::unexpectedLogsAction,
        onSaveLogs = ::unexpectedLogsAction,
        onShareSupportBundle = ::unexpectedLogsAction,
    )
}

private fun unexpectedLogsAction(): Nothing = error("The Logs workload measures scrolling only; actions must not run")

private fun fixedLogEntries() =
    List(500) { index ->
        LogEntry(
            id = "render-workload-$index",
            createdAtMs = 1_700_000_000_000L + index,
            timestamp = "12:00:00",
            subsystem = LogSubsystem.entries[index % LogSubsystem.entries.size],
            severity = LogSeverity.entries[index % LogSeverity.entries.size],
            message = "Rendering workload entry ${index.toString().padStart(3, '0')}: local path state is unchanged.",
            source = "render-workload",
        )
    }.toImmutableList()

@Composable
private fun ControlsWorkload() {
    var actions by remember { mutableIntStateOf(0) }
    var selections by remember { mutableIntStateOf(0) }
    var toggles by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        RipDpiButton(
            text = "Run local action",
            onClick = { actions++ },
            modifier = Modifier.testTag("style-benchmark-button"),
            hapticFeedback = RipDpiHapticFeedback.None,
        )
        RipDpiChip(
            text = "Local selection",
            onClick = {
                selected = !selected
                selections++
            },
            modifier = Modifier.testTag("style-benchmark-chip"),
            selected = selected,
            hapticFeedback = RipDpiHapticFeedback.None,
        )
        RipDpiSwitch(
            checked = checked,
            onCheckedChange = {
                checked = it
                toggles++
            },
            label = "Local toggle",
            testTag = "style-benchmark-switch",
        )
        Text(
            text = "Actions: $actions/$selections/$toggles",
            modifier = Modifier.testTag("style-benchmark-result"),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
    }
}
