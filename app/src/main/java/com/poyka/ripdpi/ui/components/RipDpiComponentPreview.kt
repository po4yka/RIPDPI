package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun RipDpiComponentPreview(
    themePreference: String = "light",
    content: @Composable ColumnScope.() -> Unit,
) {
    RipDpiTheme(themePreference = themePreference) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(RipDpiThemeTokens.layout.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg),
                content = content,
            )
        }
    }
}
