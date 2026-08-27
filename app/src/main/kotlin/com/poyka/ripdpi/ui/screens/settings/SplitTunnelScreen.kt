package com.poyka.ripdpi.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.routes.AppPickerSheet
import com.poyka.ripdpi.ui.screens.routes.InstalledAppItem
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

/** Immutable render state for [SplitTunnelScreen]. */
internal data class SplitTunnelScreenState(
    val mode: String,
    val selectedCount: Int,
    val fullTunnelMode: Boolean,
    /** Android 17+: per-app exclusion is delegated to the system screen instead of the in-app editor. */
    val usesSystemExclusionScreen: Boolean = false,
)

private val SplitTunnelModeOrder = listOf(SplitTunnelMode.Off, SplitTunnelMode.Exclude, SplitTunnelMode.Include)

@Composable
fun SplitTunnelRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SplitTunnelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SplitTunnelScreen(
        state =
            SplitTunnelScreenState(
                mode = uiState.mode,
                selectedCount = uiState.selectedPackages.size,
                fullTunnelMode = uiState.fullTunnelMode,
                usesSystemExclusionScreen = SplitTunnelSystemUiGate.systemScreenAvailable(context),
            ),
        installedApps = installedApps,
        selectedPackages = uiState.selectedPackages,
        onBack = onBack,
        onModeSelected = viewModel::setMode,
        onSelectionConfirmed = viewModel::setSelectedPackages,
        onOpenSystemSettings = { context.startActivity(SplitTunnelSystemUiGate.createExclusionSettingsIntent()) },
        modifier = modifier,
    )
}

@Composable
internal fun SplitTunnelScreen(
    state: SplitTunnelScreenState,
    installedApps: List<InstalledAppItem>,
    selectedPackages: Set<String>,
    onBack: () -> Unit,
    onModeSelected: (String) -> Unit,
    onSelectionConfirmed: (Set<String>) -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAppPicker by remember { mutableStateOf(false) }
    var systemSettingsLaunchFailed by remember(state.usesSystemExclusionScreen) { mutableStateOf(false) }

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.SplitTunnel))
                .fillMaxSize(),
        title = stringResource(R.string.title_split_tunnel),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        if (state.fullTunnelMode) {
            item(key = "split_tunnel_full_tunnel_notice") {
                WarningBanner(
                    title = stringResource(R.string.split_tunnel_full_tunnel_title),
                    message = stringResource(R.string.split_tunnel_full_tunnel_body),
                    tone = WarningBannerTone.Info,
                )
            }
        } else if (state.usesSystemExclusionScreen && !systemSettingsLaunchFailed) {
            item(key = "split_tunnel_system_managed") {
                SplitTunnelSystemManagedCard(
                    onOpenSystemSettings = {
                        try {
                            onOpenSystemSettings()
                        } catch (_: ActivityNotFoundException) {
                            systemSettingsLaunchFailed = true
                        } catch (_: SecurityException) {
                            systemSettingsLaunchFailed = true
                        }
                    },
                )
            }
        } else {
            item(key = "split_tunnel_editor") {
                SplitTunnelEditorCard(
                    state = state,
                    onModeSelected = onModeSelected,
                    onEditApps = { showAppPicker = true },
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerSheet(
            apps = installedApps,
            initialSelection = selectedPackages,
            onConfirm = { selection ->
                onSelectionConfirmed(selection)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }
}

@Composable
private fun SplitTunnelSystemManagedCard(onOpenSystemSettings: () -> Unit) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.split_tunnel_system_managed_body),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            RipDpiButton(
                text = stringResource(R.string.split_tunnel_open_system_settings),
                onClick = onOpenSystemSettings,
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Outline,
                density = RipDpiControlDensity.Compact,
                leadingIcon = RipDpiIcons.Config,
            )
        }
    }
}

@Composable
private fun SplitTunnelEditorCard(
    state: SplitTunnelScreenState,
    onModeSelected: (String) -> Unit,
    onEditApps: () -> Unit,
) {
    val selectedIndex = SplitTunnelModeOrder.indexOf(state.mode).coerceAtLeast(0)
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.split_tunnel_mode_label),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            RipDpiSegmentedButton(
                options =
                    persistentListOf(
                        stringResource(R.string.split_tunnel_mode_off),
                        stringResource(R.string.split_tunnel_mode_exclude),
                        stringResource(R.string.split_tunnel_mode_include),
                    ),
                selectedIndex = selectedIndex,
                onSelect = { index -> onModeSelected(SplitTunnelModeOrder[index]) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.SplitTunnelModeSelector),
            )
            Text(
                text = stringResource(splitTunnelModeHelperRes(state.mode)),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            if (state.mode != SplitTunnelMode.Off) {
                RipDpiButton(
                    text = stringResource(R.string.split_tunnel_edit_apps, state.selectedCount),
                    onClick = onEditApps,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.SplitTunnelEditApps),
                    variant = RipDpiButtonVariant.Outline,
                    density = RipDpiControlDensity.Compact,
                    leadingIcon = RipDpiIcons.Config,
                )
            }
        }
    }
}

private fun splitTunnelModeHelperRes(mode: String): Int =
    when (mode) {
        SplitTunnelMode.Include -> R.string.split_tunnel_mode_include_body
        SplitTunnelMode.Exclude -> R.string.split_tunnel_mode_exclude_body
        else -> R.string.split_tunnel_mode_off_body
    }

@Preview(showBackground = true)
@Composable
private fun previewSplitTunnelScreen() {
    RipDpiTheme(themePreference = "light") {
        SplitTunnelScreen(
            state =
                SplitTunnelScreenState(
                    mode = SplitTunnelMode.Exclude,
                    selectedCount = 3,
                    fullTunnelMode = false,
                ),
            installedApps = emptyList(),
            selectedPackages = emptySet(),
            onBack = {},
            onModeSelected = {},
            onSelectionConfirmed = {},
            onOpenSystemSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewSplitTunnelScreenDark() {
    RipDpiTheme(themePreference = "dark") {
        SplitTunnelScreen(
            state =
                SplitTunnelScreenState(
                    mode = SplitTunnelMode.Include,
                    selectedCount = 0,
                    fullTunnelMode = false,
                ),
            installedApps = emptyList(),
            selectedPackages = emptySet(),
            onBack = {},
            onModeSelected = {},
            onSelectionConfirmed = {},
            onOpenSystemSettings = {},
        )
    }
}
