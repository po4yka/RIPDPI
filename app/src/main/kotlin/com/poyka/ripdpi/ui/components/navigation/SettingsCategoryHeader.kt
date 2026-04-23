package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.chrome.RipDpiScreenSectionHeader

@Composable
fun SettingsCategoryHeader(
    title: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    RipDpiScreenSectionHeader(
        title = title,
        modifier = modifier,
        showDivider = showDivider,
    )
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun SettingsCategoryHeaderPreview() {
    RipDpiComponentPreview {
        SettingsCategoryHeader(title = "DNS", showDivider = true)
    }
}
