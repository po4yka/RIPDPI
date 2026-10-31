package com.poyka.ripdpi.ui.screens.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentWidth
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

/**
 * Lists previously-captured .pcap files for the user to view or export.
 * Capture-start happens elsewhere via the Advanced settings -> Developer toggle.
 *
 * Empty-state links to that toggle. Each row surfaces
 * file size + packet count + drops warning when capture was lossy.
 */
@Composable
fun PcapCaptureListScreen(
    captures: ImmutableList<PcapCaptureMetadata>,
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    onBack: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    RipDpiScreenScaffold(
        scrollBehavior = topBarScrollBehavior,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.PcapCaptureList)),
        topBar = {
            RipDpiTopAppBar(
                scrollBehavior = topBarScrollBehavior,
                title = stringResource(R.string.title_pcap_capture_list),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
            )
        },
    ) { innerPadding ->
        RipDpiContentWidth(Modifier.padding(innerPadding)) {
            PcapCaptureListContent(
                captures = captures,
                onCaptureSelected = onCaptureSelected,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                loading = loading,
                failed = failed,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun PcapCaptureListContent(
    captures: ImmutableList<PcapCaptureMetadata>,
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    if (failed || loading || captures.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = RipDpiThemeTokens.layout.horizontalPadding, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PcapCaptureListStatus(
                failed = failed,
                loading = loading,
                onRetry = onRetry,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = RipDpiThemeTokens.layout.horizontalPadding, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(
            items = captures,
            key = PcapCaptureMetadata::path,
        ) { capture ->
            RipDpiCard(onClick = { onCaptureSelected(capture) }) {
                Text(
                    text = capture.path.substringAfterLast('/'),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.vpn_pcap_capture_meta_format,
                            capture.packetCount.toInt(),
                            Formatter.formatShortFileSize(LocalContext.current, capture.byteSize),
                            capture.packetCount,
                        ),
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                )
                if (capture.drops > 0) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.vpn_pcap_capture_drops_format,
                                capture.drops.toInt(),
                                capture.drops,
                            ),
                        style = type.caption,
                        color = colors.warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun PcapCaptureListStatus(
    failed: Boolean,
    loading: Boolean,
    onRetry: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    when {
        failed -> {
            WarningBanner(
                title = stringResource(R.string.vpn_pcap_read_failed),
                message = stringResource(R.string.vpn_pcap_capture_list_read_failed_body),
                tone = WarningBannerTone.Error,
            )
            RipDpiButton(
                text = stringResource(R.string.startup_recovery_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                wrapLabel = true,
            )
        }

        loading -> {
            Text(
                text = stringResource(R.string.vpn_pcap_loading),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }

        else -> {
            RipDpiEmptyStateCard(
                title = stringResource(R.string.vpn_pcap_capture_list_empty_title),
                body = stringResource(R.string.vpn_pcap_capture_list_empty),
                actionLabel = stringResource(R.string.vpn_pcap_open_advanced_settings),
                onAction = onOpenAdvancedSettings,
            )
        }
    }
}
