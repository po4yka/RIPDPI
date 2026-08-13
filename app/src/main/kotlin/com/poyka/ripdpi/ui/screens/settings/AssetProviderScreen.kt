package com.poyka.ripdpi.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.assets.AssetProvider
import com.poyka.ripdpi.data.assets.BuiltInAssetProviders
import com.poyka.ripdpi.data.assets.CustomAssetProviderId
import com.poyka.ripdpi.data.assets.DefaultAssetProviderId
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/** Immutable render state for [AssetProviderScreen]. */
internal data class AssetProviderScreenState(
    val providerId: String,
    val customBaseUrl: String,
    val geoipTag: String,
    val geositeTag: String,
    val staleness: GeoAssetStaleness,
    val activeOperation: AssetProviderOperation?,
    val resultBanner: AssetProviderBanner?,
    val hasPersistenceError: Boolean = false,
    val isExitPersistenceInProgress: Boolean = false,
)

internal data class AssetProviderBanner(
    val title: String,
    val message: String,
    val tone: WarningBannerTone,
)

@Composable
fun AssetProviderRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AssetProviderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val requestBack: () -> Unit = {
        coroutineScope.launch {
            if (viewModel.persistConfigurationBeforeExit()) onBack()
        }
    }

    BackHandler(onBack = requestBack)

    val geoipImportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importLocalAsset(GeoAssetKind.Geoip, it) }
        }
    val geositeImportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importLocalAsset(GeoAssetKind.Geosite, it) }
        }

    AssetProviderScreen(
        state =
            AssetProviderScreenState(
                providerId = uiState.providerId,
                customBaseUrl = uiState.customBaseUrl,
                geoipTag = uiState.geoipTag,
                geositeTag = uiState.geositeTag,
                staleness = uiState.staleness,
                activeOperation = uiState.activeOperation,
                resultBanner = uiState.lastResult?.let { rememberOutcomeBanner(it) },
                hasPersistenceError = uiState.hasPersistenceError,
                isExitPersistenceInProgress = uiState.isExitPersistenceInProgress,
            ),
        onBack = requestBack,
        onProviderSelected = viewModel::selectProvider,
        onCustomUrlChanged = viewModel::updateCustomBaseUrl,
        onCheckForUpdates = viewModel::checkForUpdates,
        onRetryConfigurationPersistence = viewModel::retryConfigurationPersistence,
        onImportGeoip = { geoipImportLauncher.launch(GeoAssetImportMimeTypes) },
        onImportGeosite = { geositeImportLauncher.launch(GeoAssetImportMimeTypes) },
        modifier = modifier,
    )
}

private val GeoAssetImportMimeTypes = arrayOf("application/octet-stream", "*/*")

@Composable
internal fun rememberOutcomeBanner(outcome: AssetProviderCheckOutcome): AssetProviderBanner =
    when (outcome) {
        is AssetProviderCheckOutcome.Updated -> {
            AssetProviderBanner(
                title = stringResource(R.string.asset_provider_updated_title),
                message =
                    stringResource(
                        R.string.asset_provider_updated_body,
                        outcome.geoipTag ?: "—",
                        outcome.geositeTag ?: "—",
                    ),
                tone = WarningBannerTone.Info,
            )
        }

        AssetProviderCheckOutcome.UpToDate -> {
            AssetProviderBanner(
                title = stringResource(R.string.asset_provider_uptodate_title),
                message = stringResource(R.string.asset_provider_uptodate_body),
                tone = WarningBannerTone.Info,
            )
        }

        AssetProviderCheckOutcome.Imported -> {
            AssetProviderBanner(
                title = stringResource(R.string.asset_provider_imported_title),
                message = stringResource(R.string.asset_provider_imported_body),
                tone = WarningBannerTone.Info,
            )
        }

        is AssetProviderCheckOutcome.Failed -> {
            AssetProviderBanner(
                title = stringResource(R.string.asset_provider_failed_title),
                message =
                    stringResource(
                        when (outcome.reason) {
                            AssetProviderFailureReason.UnableToOpen -> {
                                R.string.asset_provider_failure_unable_to_open
                            }

                            AssetProviderFailureReason.InvalidPayload -> {
                                R.string.asset_provider_failure_invalid_payload
                            }

                            AssetProviderFailureReason.TooLarge -> {
                                R.string.asset_provider_failure_too_large
                            }

                            AssetProviderFailureReason.Storage -> {
                                R.string.asset_provider_failure_storage
                            }

                            AssetProviderFailureReason.MissingConfiguration -> {
                                R.string.asset_provider_failure_missing_configuration
                            }

                            AssetProviderFailureReason.Network -> {
                                R.string.asset_provider_failure_network
                            }

                            AssetProviderFailureReason.Unexpected -> {
                                R.string.asset_provider_failure_unexpected
                            }
                        },
                    ),
                tone = WarningBannerTone.Error,
            )
        }
    }

@Composable
internal fun AssetProviderScreen(
    state: AssetProviderScreenState,
    onBack: () -> Unit,
    onProviderSelected: (String) -> Unit,
    onCustomUrlChanged: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onRetryConfigurationPersistence: () -> Unit = {},
    onImportGeoip: () -> Unit,
    onImportGeosite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.AssetProvider))
                .fillMaxSize(),
        title = stringResource(R.string.title_asset_provider),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationEnabled = !state.isExitPersistenceInProgress,
    ) {
        if (state.isExitPersistenceInProgress) {
            item(key = "asset_provider_exit_pending") {
                WarningBanner(
                    title = stringResource(R.string.asset_provider_exit_pending_title),
                    message = stringResource(R.string.asset_provider_exit_pending_body),
                    tone = WarningBannerTone.Info,
                )
            }
        }
        item(key = "asset_provider_intro") {
            WarningBanner(
                title = stringResource(R.string.asset_provider_intro_title),
                message = stringResource(R.string.asset_provider_intro_body),
                tone = WarningBannerTone.Info,
            )
        }
        if (state.hasPersistenceError) {
            item(key = "asset_provider_persistence_error") {
                WarningBanner(
                    title = stringResource(R.string.asset_provider_persistence_failed_title),
                    message = stringResource(R.string.asset_provider_persistence_failed_body),
                    tone = WarningBannerTone.Error,
                    onClick = onRetryConfigurationPersistence,
                )
            }
        }
        state.resultBanner?.let { banner ->
            item(key = "asset_provider_result") {
                WarningBanner(title = banner.title, message = banner.message, tone = banner.tone)
            }
        }
        item(key = "asset_provider_picker") {
            AssetProviderPickerCard(
                state = state,
                onProviderSelected = onProviderSelected,
                onCustomUrlChanged = onCustomUrlChanged,
            )
        }
        item(key = "asset_provider_actions") {
            AssetProviderActionsCard(
                state = state,
                onCheckForUpdates = onCheckForUpdates,
                onImportGeoip = onImportGeoip,
                onImportGeosite = onImportGeosite,
            )
        }
    }
}

@Composable
private fun AssetProviderPickerCard(
    state: AssetProviderScreenState,
    onProviderSelected: (String) -> Unit,
    onCustomUrlChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val options =
        BuiltInAssetProviders
            .map { provider ->
                RipDpiDropdownOption(
                    value = provider.id,
                    label = context.localizedProviderLabel(provider),
                )
            }.toImmutableList()
    val selected = state.providerId.ifEmpty { DefaultAssetProviderId }
    val activeProvider = BuiltInAssetProviders.firstOrNull { it.id == selected }
    val configurationEnabled = state.activeOperation == null && !state.isExitPersistenceInProgress

    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiDropdown(
                options = options,
                selectedValue = selected,
                onValueSelected = onProviderSelected,
                label = stringResource(R.string.asset_provider_label),
                enabled = configurationEnabled,
                testTag = RipDpiTestTags.AssetProviderDropdown,
            )
            activeProvider?.let { provider ->
                Text(
                    text = context.localizedProviderDescription(provider),
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            if (selected == CustomAssetProviderId) {
                RipDpiTextField(
                    state =
                        rememberRipDpiTextFieldState(
                            value = state.customBaseUrl,
                            onValueChange = onCustomUrlChanged,
                        ),
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.asset_provider_custom_url_label),
                            placeholder = stringResource(R.string.asset_provider_custom_url_placeholder),
                            helperText = stringResource(R.string.asset_provider_custom_url_helper),
                            testTag = RipDpiTestTags.AssetProviderCustomUrl,
                        ),
                    behavior = RipDpiTextFieldBehavior(enabled = configurationEnabled),
                )
            }
            VersionRow(
                label = stringResource(R.string.asset_provider_geoip_version),
                tag = state.geoipTag,
            )
            VersionRow(
                label = stringResource(R.string.asset_provider_geosite_version),
                tag = state.geositeTag,
            )
            UpdatedRow(staleness = state.staleness)
        }
    }
}

@Composable
private fun VersionRow(
    label: String,
    tag: String,
) {
    val version = tag.ifEmpty { stringResource(R.string.asset_provider_version_none) }
    val useStackedLayout = LocalDensity.current.fontScale >= VersionRowStackedFontScale

    if (useStackedLayout) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            VersionLabel(label)
            VersionValue(version)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            VersionLabel(label, Modifier.weight(VersionLabelWeight))
            VersionValue(
                version = version,
                modifier = Modifier.weight(VersionValueWeight),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun VersionLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier,
        style = RipDpiThemeTokens.type.body,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Composable
private fun VersionValue(
    version: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = version,
        modifier = modifier,
        style = RipDpiThemeTokens.type.monoValue,
        color = RipDpiThemeTokens.colors.mutedForeground,
        textAlign = textAlign,
    )
}

private const val VersionRowStackedFontScale = 1.5f
private const val VersionLabelWeight = 0.55f
private const val VersionValueWeight = 0.45f

@Composable
private fun UpdatedRow(staleness: GeoAssetStaleness) {
    val text =
        when (staleness) {
            GeoAssetStaleness.Never -> {
                stringResource(R.string.asset_provider_updated_never)
            }

            GeoAssetStaleness.Today -> {
                stringResource(R.string.asset_provider_updated_today)
            }

            is GeoAssetStaleness.DaysAgo -> {
                stringResource(R.string.asset_provider_updated_days_ago, staleness.days)
            }
        }
    Text(
        text = text,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun AssetProviderActionsCard(
    state: AssetProviderScreenState,
    onCheckForUpdates: () -> Unit,
    onImportGeoip: () -> Unit,
    onImportGeosite: () -> Unit,
) {
    val operationInProgress = state.activeOperation != null || state.isExitPersistenceInProgress
    val customProviderMissingUrl =
        state.providerId == CustomAssetProviderId && state.customBaseUrl.isBlank()
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiButton(
                text = stringResource(R.string.asset_provider_check_updates),
                onClick = onCheckForUpdates,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.AssetProviderCheckUpdates),
                loading = state.activeOperation == AssetProviderOperation.CheckUpdates,
                enabled = !operationInProgress && !customProviderMissingUrl,
                leadingIcon = RipDpiIcons.Refresh,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                RipDpiButton(
                    text = stringResource(R.string.asset_provider_import_geoip),
                    onClick = onImportGeoip,
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.AssetProviderImport),
                    variant = RipDpiButtonVariant.Outline,
                    loading = state.activeOperation == AssetProviderOperation.ImportGeoip,
                    enabled = !operationInProgress,
                    leadingIcon = RipDpiIcons.Advanced,
                )
                RipDpiButton(
                    text = stringResource(R.string.asset_provider_import_geosite),
                    onClick = onImportGeosite,
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.AssetProviderImportGeosite),
                    variant = RipDpiButtonVariant.Outline,
                    loading = state.activeOperation == AssetProviderOperation.ImportGeosite,
                    enabled = !operationInProgress,
                    leadingIcon = RipDpiIcons.Advanced,
                )
            }
        }
    }
}

private fun android.content.Context.localizedProviderLabel(provider: AssetProvider): String =
    resolveResString(provider.labelResKey, provider.id)

private fun android.content.Context.localizedProviderDescription(provider: AssetProvider): String =
    resolveResString(provider.descriptionResKey, "")

private fun android.content.Context.resolveResString(
    resKey: String,
    fallback: String,
): String {
    val resId = resources.getIdentifier(resKey, "string", packageName)
    return if (resId != 0) getString(resId) else fallback
}

/** Sample "days since last update" used only by the preview below. */
private const val PreviewStalenessDays = 3L

@Preview(showBackground = true)
@Composable
private fun previewAssetProviderScreen() {
    RipDpiTheme(themePreference = "light") {
        AssetProviderScreen(
            state =
                AssetProviderScreenState(
                    providerId = "custom",
                    customBaseUrl = "https://github.com/me/repo/releases/latest/download",
                    geoipTag = "202405010000",
                    geositeTag = "",
                    staleness = GeoAssetStaleness.DaysAgo(PreviewStalenessDays),
                    activeOperation = null,
                    resultBanner =
                        AssetProviderBanner(
                            title = "Updated",
                            message = "geoip 202405010000, geosite —",
                            tone = WarningBannerTone.Info,
                        ),
                ),
            onBack = {},
            onProviderSelected = {},
            onCustomUrlChanged = {},
            onCheckForUpdates = {},
            onRetryConfigurationPersistence = {},
            onImportGeoip = {},
            onImportGeosite = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewAssetProviderScreenDark() {
    RipDpiTheme(themePreference = "dark") {
        AssetProviderScreen(
            state =
                AssetProviderScreenState(
                    providerId = "sagernet",
                    customBaseUrl = "",
                    geoipTag = "v1.2.3",
                    geositeTag = "v1.2.3",
                    staleness = GeoAssetStaleness.Never,
                    activeOperation = AssetProviderOperation.CheckUpdates,
                    resultBanner = null,
                ),
            onBack = {},
            onProviderSelected = {},
            onCustomUrlChanged = {},
            onCheckForUpdates = {},
            onImportGeoip = {},
            onImportGeosite = {},
        )
    }
}
