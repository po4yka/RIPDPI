package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun SettingsCategoryHeader(
    title: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            text = title.uppercase(),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.mutedForeground,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun SettingsCategoryHeaderPreview() {
    RipDpiComponentPreview {
        SettingsCategoryHeader(title = "DNS", showDivider = true)
    }
}
