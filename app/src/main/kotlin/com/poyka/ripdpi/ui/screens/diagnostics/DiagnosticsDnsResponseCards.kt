package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsDnsResponseCards(
    groups: List<DiagnosticsContextGroupUiModel>,
    initiallyExpanded: Boolean = false,
) {
    if (groups.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        RipDpiButton(
            text = stringResource(if (expanded) R.string.diagnostics_dns_hide else R.string.diagnostics_dns_show),
            onClick = { expanded = !expanded },
            variant = RipDpiButtonVariant.Secondary,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) groups.forEach { ContextGroupCard(it) }
    }
}
