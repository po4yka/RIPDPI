package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * A single recent-import row shown below the source picker.
 *
 * @param name  File/URL/QR identifier as imported.
 * @param when_ Human-readable relative timestamp ("14:08", "Yesterday", …).
 */
data class RecentImportEntry(
    val name: String,
    val `when`: String,
)

/**
 * UI state for one import-source tile in the 2×2 picker grid.
 *
 * @param icon        Icon representing the source.
 * @param titleRes    String resource for the tile title.
 * @param subtitleRes String resource for the tile subtitle.
 * @param onSelect    Callback when the tile is tapped.
 */
data class ImportSourceOption(
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
    val onSelect: () -> Unit,
)

/**
 * UI state for [StrategyImportScreen].
 *
 * @param sources       Four import-source tiles (file, QR, URL, clipboard).
 * @param recentImports Recent imports for fast re-import; may be empty.
 */
data class StrategyImportState(
    val sources: List<ImportSourceOption>,
    val recentImports: List<RecentImportEntry>,
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Presentation-only strategy-import screen matching
 * `docs/design/rds/preview/vpn-strategy-import.html`.
 *
 * Renders a 2×2 grid of import-source tiles (file / QR / URL / clipboard)
 * followed by a "Recent imports" section. All tokens come from
 * [RipDpiThemeTokens]; no literal colours, dp values outside `ui/theme/`,
 * or animation-spec constants appear in this file.
 */
@Composable
fun StrategyImportScreen(
    state: StrategyImportState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.strategy_import_title),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.StaticStrategyImportScreen),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(RipDpiThemeTokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            SourceGrid(sources = state.sources)

            if (state.recentImports.isNotEmpty()) {
                Spacer(modifier = Modifier.height(RipDpiThemeTokens.spacing.sm))
                RecentImportsSection(entries = state.recentImports)
            }

            Spacer(modifier = Modifier.height(RipDpiThemeTokens.spacing.md))
            Text(
                text = stringResource(R.string.strategy_import_caption),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2×2 source picker grid
// ---------------------------------------------------------------------------

@Composable
private fun SourceGrid(
    sources: List<ImportSourceOption>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        sources.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                row.forEach { option ->
                    SourceTile(
                        option = option,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad to two columns if the last row has only one item.
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SourceTile(
    option: ImportSourceOption,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Surface(
        modifier = modifier.ripDpiClickable(onClick = option.onSelect),
        shape = shapes.lg,
        color = colors.card,
        contentColor = colors.foreground,
        border = BorderStroke(RipDpiStroke.Thin, colors.cardBorder),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = shapes.full,
                color = colors.muted,
                contentColor = colors.mutedForeground,
                modifier = Modifier.size(RipDpiIconSizes.Large),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(RipDpiIconSizes.Default),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = stringResource(option.titleRes),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(option.subtitleRes),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Recent imports section
// ---------------------------------------------------------------------------

@Composable
private fun RecentImportsSection(
    entries: List<RecentImportEntry>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = RipDpiStroke.Hairline,
            color = colors.divider,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.strategy_import_recent_title),
            style = type.sectionTitle,
            color = colors.mutedForeground,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = RipDpiStroke.Hairline,
                    color = colors.divider,
                )
            }
            RecentImportRow(entry = entry)
        }
    }
}

@Composable
private fun RecentImportRow(
    entry: RecentImportEntry,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            style = type.monoValue,
            color = colors.foreground,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = entry.`when`,
            style = type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun StrategyImportScreenLightPreview() {
    RipDpiComponentPreview {
        StrategyImportScreen(
            state = sampleStrategyImportState(),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StrategyImportScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        StrategyImportScreen(
            state = sampleStrategyImportState(),
            onBack = {},
        )
    }
}
