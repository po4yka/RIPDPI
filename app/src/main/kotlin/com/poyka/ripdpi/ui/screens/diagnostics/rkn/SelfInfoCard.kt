package com.poyka.ripdpi.ui.screens.diagnostics.rkn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.DiagnosticsRknSelfInfoUiModel
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun SelfInfoCard(info: DiagnosticsRknSelfInfoUiModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = "Network context",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        SettingsRow(
            title = "IP",
            value = info.maskedIp,
            monospaceValue = true,
        )
        SettingsRow(
            title = "ISP",
            value = info.provider,
        )
        info.location?.let { location ->
            SettingsRow(
                title = "Location",
                value = location,
            )
        }
        SettingsRow(
            title = "Source",
            value = info.source,
        )
    }
}
