package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.xray.XrayConnectionStage
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Compact, read-only embedded-Xray provider status surface for Settings. Shows
 * the typed [XrayConnectionStage] + failure class DISTINCTLY from the tunnel /
 * connectivity rows (decision 6). No probe action here — the user-triggered probe
 * lives on Diagnostics; Settings only reflects the live provider state.
 */
@Composable
internal fun XrayProviderSettingsStatusRow(
    snapshot: XrayProviderSnapshot,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val stage = XrayConnectionStage.fromSnapshot(snapshot)
    // Same header-above-card structure as the other Settings sections.
    Column(
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.SettingsXrayProviderStatus),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        SettingsCategoryHeader(title = stringResource(R.string.xray_provider_settings_section_title))
        RipDpiCard(variant = RipDpiCardVariant.Outlined) {
            StatusIndicator(
                label = stage.title(),
                tone = stage.tone().statusTone(),
            )
            Text(
                text = stage.subtitle(snapshot),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = if (stage is XrayConnectionStage.ProviderFailed) colors.warning else colors.mutedForeground,
            )
            snapshot.xrayVersion?.let { version ->
                Text(
                    text = stringResource(R.string.xray_provider_engine_version_format, version),
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
