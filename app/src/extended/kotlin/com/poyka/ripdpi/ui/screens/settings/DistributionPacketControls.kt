package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.rawPacketOperationsEnabled
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun HostFakeControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (!model.visibility.showHostFakeSection) return
    HostFakeProfileCard(
        uiState = model.uiState,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
internal fun SeqOverlapControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (!model.visibility.showSeqOverlapSection) return
    SeqOverlapProfileCard(
        uiState = model.uiState,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
internal fun PacketSignatureControl(model: DesyncSectionModel) {
    if (model.uiState.settings.rawPacketOperationsEnabled()) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_md5sig_setting),
            checked = model.uiState.fake.md5sig,
            onCheckedChange = { model.actions.onToggleChanged(AdvancedToggleSetting.Md5Sig, it) },
            enabled = model.visualEditorEnabled,
            showDivider = true,
            testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.Md5Sig),
        )
    }
}
