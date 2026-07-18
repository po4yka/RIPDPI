package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.updates.AvailableAppUpdate
import com.poyka.ripdpi.updates.DistributionChannel
import com.poyka.ripdpi.updates.UpdateEffect
import com.poyka.ripdpi.updates.UpdateFailureReason
import com.poyka.ripdpi.updates.UpdateUiState
import com.poyka.ripdpi.updates.UpdateUiStatus
import com.poyka.ripdpi.updates.UpdateViewModel

private const val BytesPerMib = 1024 * 1024

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val updateUiState = updateViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(updateViewModel.effects) { effect ->
        when (effect) {
            is UpdateEffect.OpenIntent -> context.startActivity(effect.intent)
        }
    }
    AboutScreen(
        onBack = onBack,
        updateUiState = updateUiState.value,
        onCheckForUpdates = updateViewModel::checkForUpdates,
        onInstallUpdate = updateViewModel::installAvailableUpdate,
        modifier = modifier,
    )
}

@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateUiState: UpdateUiState = previewUpdateUiState(),
    onCheckForUpdates: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.About))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.about_category),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item {
            AboutAppSummaryCard()
        }

        item {
            AboutBuildSection()
        }

        item {
            AboutUpdatesSection(
                updateUiState = updateUiState,
                onCheckForUpdates = onCheckForUpdates,
                onInstallUpdate = onInstallUpdate,
            )
        }

        item {
            AboutProjectSection()
        }

        item {
            AboutLinksSection()
        }
    }
}

@Composable
private fun AboutAppSummaryCard() {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiCard {
        Text(
            text = stringResource(R.string.app_name),
            style = type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.splash_subtitle),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.about_description),
            style = type.body,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun AboutBuildSection() {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.about_build_section))
        RipDpiCard(paddingValues = PaddingValues(horizontal = layout.cardPadding, vertical = 0.dp)) {
            SettingsRow(
                title = stringResource(R.string.version),
                value = BuildConfig.VERSION_NAME,
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.about_version_code),
                value = BuildConfig.VERSION_CODE.toString(),
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.about_build_type),
                value = BuildConfig.BUILD_TYPE,
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.about_package_name),
                value = BuildConfig.APPLICATION_ID,
                monospaceValue = true,
                valueWeight = 1.6f,
            )
        }
    }
}

@Composable
private fun AboutProjectSection() {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.about_project_section))
        RipDpiCard {
            Text(
                text = stringResource(R.string.about_credits_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.about_credits_body),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun AboutLinksSection() {
    val spacing = RipDpiThemeTokens.spacing
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.ripdpi_source)
    val docsUrl = stringResource(R.string.ripdpi_docs)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.about_links_section))
        RipDpiCard {
            RipDpiButton(
                text = stringResource(R.string.source_code_link),
                onClick = { uriHandler.openUri(sourceUrl) },
                variant = RipDpiButtonVariant.Outline,
                leadingIcon = RipDpiIcons.Share,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.AboutSourceCode),
            )
            RipDpiButton(
                text = stringResource(R.string.ripdpi_readme_link),
                onClick = { uriHandler.openUri(docsUrl) },
                variant = RipDpiButtonVariant.Ghost,
                leadingIcon = RipDpiIcons.Info,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.AboutReadme),
            )
        }
    }
}

@Composable
private fun AboutUpdatesSection(
    updateUiState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val update = updateUiState.availableUpdate

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.updates_section_title))
        RipDpiCard(paddingValues = PaddingValues(horizontal = layout.cardPadding, vertical = 0.dp)) {
            SettingsRow(
                title = stringResource(R.string.update_channel_title),
                subtitle = stringResource(updateUiState.channelBodyRes()),
                value = stringResource(updateUiState.channelLabelRes()),
                showDivider = true,
            )
            Text(
                text = updateUiState.statusText(),
                style = type.body,
                color = colors.mutedForeground,
            )
            update?.let {
                AvailableUpdateDetails(update = it)
            }
            RipDpiButton(
                text = stringResource(R.string.update_check_action),
                onClick = onCheckForUpdates,
                enabled = updateUiState.channelInfo.canCheckInApp && updateUiState.status != UpdateUiStatus.Checking,
                modifier = Modifier.fillMaxWidth(),
                variant = RipDpiButtonVariant.Outline,
            )
            if (update != null && updateUiState.channelInfo.canInstallInApp) {
                RipDpiButton(
                    text = stringResource(R.string.update_install_action),
                    onClick = onInstallUpdate,
                    enabled = updateUiState.status != UpdateUiStatus.Downloading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AvailableUpdateDetails(update: AvailableAppUpdate) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        Text(
            text =
                stringResource(
                    R.string.update_available_title_format,
                    update.versionName,
                    update.versionCode,
                ),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        update.sizeBytes?.let { sizeBytes ->
            Text(
                text = stringResource(R.string.update_size_format, sizeBytes / BytesPerMib),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
        if (update.changelog.isNotBlank()) {
            Text(
                text = stringResource(R.string.update_changelog_title),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = update.changelog,
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun UpdateUiState.statusText(): String =
    when (val currentStatus = status) {
        UpdateUiStatus.Checking -> stringResource(R.string.update_checking)
        UpdateUiStatus.Downloading -> stringResource(R.string.update_downloading_action)
        UpdateUiStatus.ExternalAuthority -> stringResource(channelBodyRes())
        is UpdateUiStatus.Failed -> failedStatusText(currentStatus.reason)
        UpdateUiStatus.Idle -> stringResource(R.string.update_idle)
        UpdateUiStatus.InstallerStarted -> stringResource(R.string.update_install_started)
        UpdateUiStatus.NoUpdate -> stringResource(R.string.update_no_update)
        UpdateUiStatus.PermissionRequired -> stringResource(R.string.update_permission_required)
    }

@Composable
private fun failedStatusText(reason: UpdateFailureReason): String =
    stringResource(
        R.string.update_error_format,
        stringResource(reason.labelRes()),
    )

private fun UpdateUiState.channelLabelRes(): Int =
    when (channelInfo.channel) {
        DistributionChannel.Play -> R.string.update_channel_play
        DistributionChannel.Fdroid -> R.string.update_channel_fdroid
        DistributionChannel.Github -> R.string.update_channel_github
    }

private fun UpdateUiState.channelBodyRes(): Int =
    when (channelInfo.channel) {
        DistributionChannel.Play -> R.string.update_channel_play_body
        DistributionChannel.Fdroid -> R.string.update_channel_fdroid_body
        DistributionChannel.Github -> R.string.update_channel_github_body
    }

private fun UpdateFailureReason.labelRes(): Int =
    when (this) {
        UpdateFailureReason.ChecksumMismatch -> R.string.update_error_checksum
        UpdateFailureReason.InstallerUnavailable -> R.string.update_error_installer_unavailable
        UpdateFailureReason.InvalidMetadata -> R.string.update_error_invalid_metadata
        UpdateFailureReason.InvalidPackageName -> R.string.update_error_invalid_package_name
        UpdateFailureReason.InvalidRepositoryAsset -> R.string.update_error_invalid_repository_asset
        UpdateFailureReason.InvalidVersionCode -> R.string.update_error_invalid_version
        UpdateFailureReason.Network -> R.string.update_error_network
        UpdateFailureReason.NotAvailable -> R.string.update_error_not_available
        UpdateFailureReason.PackageReadFailed -> R.string.update_error_package_read
        UpdateFailureReason.UnsupportedSdk -> R.string.update_error_unsupported_sdk
        UpdateFailureReason.Unknown -> R.string.update_error_unknown
    }

private fun previewUpdateUiState(): UpdateUiState =
    UpdateUiState(
        channelInfo =
            com.poyka.ripdpi.updates.UpdateChannelInfo(
                channel = DistributionChannel.Github,
                canCheckInApp = true,
                canInstallInApp = true,
            ),
    )

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        AboutScreen(onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        AboutScreen(onBack = {})
    }
}
