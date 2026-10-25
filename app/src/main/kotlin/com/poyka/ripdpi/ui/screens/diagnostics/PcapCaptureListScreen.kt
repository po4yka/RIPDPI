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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
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
    message: String? = null,
) {
    RipDpiScreenScaffold(
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.PcapCaptureList)),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_pcap_capture_list),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
            )
        },
    ) { innerPadding ->
        PcapCaptureListContent(
            captures = captures,
            onCaptureSelected = onCaptureSelected,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            message = message,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun PcapCaptureListContent(
    captures: ImmutableList<PcapCaptureMetadata>,
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    message: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    if (message != null || captures.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = RipDpiThemeTokens.layout.horizontalPadding, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = message ?: stringResource(R.string.vpn_pcap_capture_list_empty),
                style = type.body,
                color = colors.mutedForeground,
            )
            if (message == null) {
                RipDpiButton(
                    text = stringResource(R.string.vpn_pcap_open_advanced_settings),
                    onClick = onOpenAdvancedSettings,
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.ChevronRight,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
