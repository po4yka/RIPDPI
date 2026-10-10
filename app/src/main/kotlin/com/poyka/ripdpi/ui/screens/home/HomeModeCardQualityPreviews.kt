package com.poyka.ripdpi.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Preview(name = "Quality Home actions large font", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ru")
@Composable
private fun QualityHomeActionsLargeFontPreview() {
    QualityHomeModeCard(rtl = false)
}

@Preview(name = "Quality Home RTL dark large font", widthDp = 320, heightDp = 640, fontScale = 2f, locale = "ar")
@Composable
private fun QualityHomeActionsRtlPreview() {
    QualityHomeModeCard(rtl = true)
}

@Composable
private fun QualityHomeModeCard(rtl: Boolean) {
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        RipDpiTheme(themePreference = if (rtl) "dark" else "light") {
            HomeModeCard(
                uiState =
                    HomeModeCardUiState(
                        mode = HomeMode.Diagnostic,
                        title = if (rtl) "فحص تشخيصي" else "Диагностическое сканирование",
                        primaryLabel = if (rtl) "لم يتم إجراء تحليل بعد" else "Анализ ещё не проводился",
                        primaryActionLabel =
                            if (rtl) "بدء الفحص التشخيصي للشبكة" else "Запустить диагностическое сканирование",
                        configureLabel = if (rtl) "فتح الإعدادات" else "Открыть настройки",
                    ),
                onPrimaryAction = {},
                onConfigure = {},
            )
        }
    }
}
