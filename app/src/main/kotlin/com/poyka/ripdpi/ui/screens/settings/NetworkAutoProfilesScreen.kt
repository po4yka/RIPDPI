package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

@Composable
fun NetworkAutoProfilesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworkAutoProfilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NetworkAutoProfilesScreen(
        uiState = uiState,
        onBack = onBack,
        onRefreshNetwork = viewModel::refreshCurrentNetwork,
        onBindCurrentProfile = viewModel::bindCurrentProfileToCurrentNetwork,
        onDeleteBinding = viewModel::deleteBinding,
        modifier = modifier,
    )
}

@Composable
internal fun NetworkAutoProfilesScreen(
    uiState: NetworkAutoProfilesUiState,
    onBack: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onBindCurrentProfile: () -> Unit,
    onDeleteBinding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.NetworkAutoProfiles))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.title_network_auto_profiles),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            CurrentNetworkBindingCard(
                uiState = uiState,
                onRefreshNetwork = onRefreshNetwork,
                onBindCurrentProfile = onBindCurrentProfile,
            )
        }
        item {
            BindingList(
                bindings = uiState.bindings,
                onDeleteBinding = onDeleteBinding,
            )
        }
    }
}

@Composable
private fun CurrentNetworkBindingCard(
    uiState: NetworkAutoProfilesUiState,
    onRefreshNetwork: () -> Unit,
    onBindCurrentProfile: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val currentNetwork = uiState.currentNetwork
    val canBind = currentNetwork is CurrentNetworkBindingUiState.Available
    RipDpiCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.NetworkAutoProfilesCurrent),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.network_auto_profiles_current_network),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text =
                    when (currentNetwork) {
                        is CurrentNetworkBindingUiState.Available -> {
                            currentNetwork.label
                        }

                        CurrentNetworkBindingUiState.Unavailable -> {
                            stringResource(R.string.network_auto_profiles_network_unavailable)
                        }
                    },
                style = type.body,
                color = colors.mutedForeground,
            )
            Text(
                text = stringResource(R.string.network_auto_profiles_current_profile, uiState.currentProfileName),
                style = type.body,
                color = colors.mutedForeground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                RipDpiButton(
                    text = stringResource(R.string.network_auto_profiles_refresh),
                    onClick = onRefreshNetwork,
                    variant = RipDpiButtonVariant.Secondary,
                )
                RipDpiButton(
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.NetworkAutoProfilesBindCurrent),
                    text = stringResource(R.string.network_auto_profiles_bind_current),
                    onClick = onBindCurrentProfile,
                    enabled = canBind,
                )
            }
        }
    }
}

@Composable
private fun BindingList(
    bindings: List<NetworkAutoProfileBindingUiState>,
    onDeleteBinding: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.NetworkAutoProfilesBindingList),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.network_auto_profiles_saved_bindings))
        if (bindings.isEmpty()) {
            EmptyBindingsCard()
        } else {
            bindings.forEach { binding ->
                BindingCard(binding = binding, onDeleteBinding = onDeleteBinding)
            }
        }
    }
}

@Composable
private fun EmptyBindingsCard() {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.network_auto_profiles_empty),
            style = type.body,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun BindingCard(
    binding: NetworkAutoProfileBindingUiState,
    onDeleteBinding: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = binding.networkLabel,
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.network_auto_profiles_bound_profile, binding.profileName),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiButton(
                text = stringResource(R.string.network_auto_profiles_remove),
                onClick = { onDeleteBinding(binding.id) },
                variant = RipDpiButtonVariant.Secondary,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun previewNetworkAutoProfilesScreen() {
    RipDpiTheme {
        NetworkAutoProfilesScreen(
            uiState =
                NetworkAutoProfilesUiState(
                    currentNetwork =
                        CurrentNetworkBindingUiState.Available(
                            fingerprintHash = "abc",
                            label = "Wifi · Validated · DNS 2 · Private DNS",
                        ),
                    currentProfileName = "VPN · split+sni · Disabled",
                    bindings =
                        persistentListOf(
                            NetworkAutoProfileBindingUiState(
                                id = "1",
                                fingerprintHash = "abc",
                                networkLabel = "Wifi · Validated · DNS 2",
                                profileName = "VPN · split+sni · Disabled",
                                updatedAt = 1_000L,
                            ),
                        ),
                ),
            onBack = {},
            onRefreshNetwork = {},
            onBindCurrentProfile = {},
            onDeleteBinding = {},
        )
    }
}
