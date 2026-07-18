package com.poyka.ripdpi.ui.screens.diagnostics.rkn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
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
            text = stringResource(R.string.diagnostics_rkn_self_info_network_context),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        SettingsRow(
            title = stringResource(R.string.rkn_self_info_ip),
            value = info.maskedIp,
            monospaceValueWeight = 1f,
        )
        SettingsRow(
            title = stringResource(R.string.rkn_self_info_isp),
            value = info.provider,
        )
        info.location?.let { location ->
            SettingsRow(
                title = stringResource(R.string.rkn_self_info_location),
                value = location,
            )
        }
        SettingsRow(
            title = stringResource(R.string.rkn_self_info_source),
            value = info.source,
        )
    }
}
