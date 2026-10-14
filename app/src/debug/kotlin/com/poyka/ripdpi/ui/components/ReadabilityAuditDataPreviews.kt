package com.poyka.ripdpi.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.screens.diagnostics.CollapsibleSection
import com.poyka.ripdpi.ui.screens.diagnostics.HandshakeTimelineScreen
import com.poyka.ripdpi.ui.screens.diagnostics.OomRecoveryScreen
import com.poyka.ripdpi.ui.screens.diagnostics.OomRecoveryState
import com.poyka.ripdpi.ui.screens.diagnostics.StateMachineRoute
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyImportScreen
import com.poyka.ripdpi.ui.screens.diagnostics.ThroughputGraphScreen
import com.poyka.ripdpi.ui.screens.diagnostics.sampleHandshakeTimelineState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleStrategyImportState
import com.poyka.ripdpi.ui.screens.diagnostics.sampleThroughputGraphState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Preview(name = "Recovery light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(name = "Recovery dark", widthDp = 420, heightDp = 900, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Recovery large font", widthDp = 320, heightDp = 1200, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditRecoveryPreview() {
    DataReadabilityPreview {
        OomRecoveryScreen(OomRecoveryState("12:42 UTC", "4 m 18 s"), {}, {}, {}, {})
    }
}

@Preview(name = "Import light", widthDp = 420, heightDp = 900, locale = "ru")
@Preview(name = "Import dark", widthDp = 420, heightDp = 900, locale = "ru", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Import large font", widthDp = 320, heightDp = 2300, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditImportPreview() {
    DataReadabilityPreview {
        StrategyImportScreen(sampleStrategyImportState(), {})
    }
}

@Preview(name = "Throughput light", widthDp = 420, heightDp = 650, locale = "ru")
@Preview(
    name = "Throughput dark",
    widthDp = 420,
    heightDp = 650,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Throughput large font", widthDp = 320, heightDp = 1100, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditThroughputPreview() {
    DataReadabilityPreview {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RipDpiThemeTokens.spacing.lg)) {
            ThroughputGraphScreen(
                sampleThroughputGraphState("Пропускная способность", "1.42 MiB/s", "312 KiB/s", "128 MiB"),
            )
        }
    }
}

@Preview(name = "Data headers light", widthDp = 420, heightDp = 1000, locale = "ru")
@Preview(
    name = "Data headers dark",
    widthDp = 420,
    heightDp = 1000,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Data headers large font", widthDp = 320, heightDp = 2300, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditDataHeadersPreview() {
    DataReadabilityPreview {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RipDpiThemeTokens.spacing.lg)) {
            CollapsibleSection(
                "Результаты проверки зашифрованных соединений",
                badgeCount = 1234,
                defaultExpanded = true,
            ) {
                HandshakeTimelineScreen(
                    sampleHandshakeTimelineState(
                        "Этапы подключения",
                        "Текущее соединение",
                        "Всего",
                        "Медленный этап: TLS",
                        "В пределах бюджета",
                    ),
                )
            }
        }
    }
}

@Preview(name = "State diagram light", widthDp = 420, heightDp = 750, locale = "ru")
@Preview(
    name = "State diagram dark",
    widthDp = 420,
    heightDp = 750,
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "State diagram large font", widthDp = 320, heightDp = 1300, locale = "ru", fontScale = 2f)
@Composable
private fun ReadabilityAuditStateDiagramPreview() {
    DataReadabilityPreview {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RipDpiThemeTokens.spacing.lg)) {
            StateMachineRoute()
        }
    }
}

@Composable
private fun DataReadabilityPreview(content: @Composable () -> Unit) {
    RipDpiTheme(themePreference = if (isSystemInDarkTheme()) "dark" else "light") {
        Surface(modifier = Modifier.fillMaxSize(), color = RipDpiThemeTokens.colors.background, content = content)
    }
}
