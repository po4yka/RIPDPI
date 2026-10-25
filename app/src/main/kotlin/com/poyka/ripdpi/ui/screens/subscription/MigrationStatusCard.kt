package com.poyka.ripdpi.ui.screens.subscription

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.subscription.MigrationCheckResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun MigrationStatusCard(
    state: MigrationStatusUi,
    onCheck: (String, String) -> Unit,
    onExport: () -> Unit,
) {
    var url by rememberSaveable(state.deliveryId) { mutableStateOf(state.probeUrl) }
    SettingsCategoryHeader(title = stringResource(R.string.migration_title))
    Text(
        stringResource(if (state.tested) R.string.migration_tested else R.string.migration_received),
        style = RipDpiThemeTokens.type.body,
        modifier = Modifier.ripDpiTestTag("migration-status"),
    )
    Text(stringResource(R.string.migration_privacy), style = RipDpiThemeTokens.type.caption)
    RipDpiTextField(
        value = url,
        onValueChange = { url = it },
        modifier = Modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = stringResource(R.string.migration_url),
                testTag = "migration-probe-url",
            ),
    )
    state.targets.forEachIndexed { index, target ->
        if (target.awg) Text(stringResource(R.string.migration_awg_help), style = RipDpiThemeTokens.type.caption)
        RipDpiButton(
            text =
                stringResource(
                    if (target.awg) R.string.migration_check_awg else R.string.migration_check_relay,
                    index + 1,
                ),
            onClick = { onCheck(target.tag, url) },
            enabled = state.actionsEnabled && url.isNotBlank(),
            loading = state.busy,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag("migration-check-$index"),
        )
    }
    state.result?.takeUnless { it == MigrationCheckResult.Tested }?.let {
        Text(
            stringResource(
                when (it) {
                    MigrationCheckResult.ConnectAwgFirst -> R.string.migration_connect_awg
                    MigrationCheckResult.Changed -> R.string.migration_changed
                    MigrationCheckResult.InvalidTarget -> R.string.migration_invalid
                    else -> R.string.migration_failed
                },
            ),
            color = RipDpiThemeTokens.colors.destructive,
            style = RipDpiThemeTokens.type.body,
        )
    }
    RipDpiButton(
        text = stringResource(R.string.migration_export),
        onClick = onExport,
        enabled = state.actionsEnabled,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag("migration-export"),
    )
}
