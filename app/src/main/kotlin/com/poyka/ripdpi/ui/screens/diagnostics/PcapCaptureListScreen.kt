package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.pcap.PcapCaptureMetadata
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val BytesPerKilobyte = 1024L

/**
 * Lists previously-captured .pcap files for the user to view or export.
 * Capture-start happens elsewhere via the Settings -> Developer toggle.
 *
 * Empty-state shows the toggle-enablement hint. Each row surfaces
 * file size + packet count + drops warning when capture was lossy.
 */
@Composable
fun PcapCaptureListScreen(
    captures: ImmutableList<PcapCaptureMetadata>,
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun PcapCaptureListContent(
    captures: ImmutableList<PcapCaptureMetadata>,
    onCaptureSelected: (PcapCaptureMetadata) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    if (captures.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.vpn_pcap_capture_list_empty),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
        return
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        captures.forEach { capture ->
            RipDpiCard(onClick = { onCaptureSelected(capture) }) {
                Text(
                    text = capture.path.substringAfterLast('/'),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text =
                        stringResource(
                            R.string.vpn_pcap_capture_meta_format,
                            capture.byteSize / BytesPerKilobyte,
                            capture.packetCount,
                        ),
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                )
                if (capture.drops > 0) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text =
                            stringResource(
                                R.string.vpn_pcap_capture_drops_format,
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
