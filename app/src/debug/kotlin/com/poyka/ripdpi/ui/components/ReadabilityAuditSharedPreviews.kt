package com.poyka.ripdpi.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbar
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.navigation.TopLevelNavigationScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Preview(name = "Shared light", widthDp = 420, heightDp = 940, locale = "ru")
@Preview(name = "Shared dark", widthDp = 420, heightDp = 940, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Shared large font", widthDp = 320, heightDp = 2400, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditSharedPreview() {
    RipDpiComponentPreview(themePreference = if (isSystemInDarkTheme()) "dark" else "light") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WarningBanner(
                title = "Проверьте настройки соединения",
                message = "Разрешите VPN, чтобы использовать сохранённую конфигурацию.",
                onDismiss = {},
            )
            RipDpiButton("Подключиться с сохранёнными настройками", {
            }, leadingIcon = RipDpiIcons.Connected, density = RipDpiControlDensity.Compact)
            RipDpiTextAction("Завершить проверку с текущими настройками", {})
            RipDpiIconButton(RipDpiIcons.Close, "Закрыть", {}, density = RipDpiControlDensity.Compact)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RipDpiChip("DNS", {}, selected = true, wrapLabel = true)
                RipDpiChip("TLS", {}, wrapLabel = true)
                RipDpiChip("Использовать защищённый DNS", {}, wrapLabel = true)
            }
            RipDpiAccordion("Дополнительные настройки соединения", false, {}) {}
            RipDpiSnackbar(
                "Соединение остановлено до запуска туннеля.",
                actionLabel = "Открыть настройки соединения",
                onAction = {},
                tone = RipDpiSnackbarTone.Warning,
            )
        }
    }
}

@Preview(name = "Navigation light", widthDp = 420, heightDp = 210, locale = "ru")
@Preview(
    name = "Navigation dark",
    widthDp = 420,
    heightDp = 210,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Navigation large font", widthDp = 320, heightDp = 440, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditNavigationPreview() {
    RipDpiTheme(themePreference = if (isSystemInDarkTheme()) "dark" else "light") {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            TopLevelNavigationScaffold(Route.Settings, {}) {}
        }
    }
}
