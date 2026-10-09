package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRankedRow
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

@Preview(name = "UiQualityDiagnostics light", widthDp = 320, heightDp = 1000, showBackground = true)
@Preview(
    name = "UiQualityDiagnostics dark",
    widthDp = 320,
    heightDp = 1000,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UiQualityDiagnosticsPreview() {
    DiagnosticsQualityScene()
}

@Preview(name = "UiQualityDiagnostics RTL font 2", widthDp = 320, heightDp = 1000, fontScale = 2f, locale = "ru")
@Composable
private fun UiQualityDiagnosticsRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        DiagnosticsQualityScene()
    }
}

@Composable
private fun DiagnosticsQualityScene() {
    CompositionLocalProvider(LocalScanClockMs provides 3_600_000L) {
        RipDpiTheme {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(RipDpiThemeTokens.colors.background)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CompactProfileRow(
                    profile = DiagnosticsProfileOptionUiModel("web", "Проверка веб-соединения", "bundled"),
                    onChangeProfile = {},
                )
                StrategyTunerRankedRow(
                    result =
                        RankedStrategyProbeResult(
                            "tls",
                            "Разделение записей TLS для выбранных сайтов",
                            3,
                            3,
                            1.0,
                            120,
                            0,
                        ),
                    isBest = true,
                    isApplied = false,
                    applyEnabled = true,
                    onApply = {},
                )
                ScanProgressCard(
                    progress =
                        DiagnosticsProgressUiModel(
                            "tcp",
                            "Проверка сети",
                            3,
                            12,
                            0.25f,
                            ScanKind.STRATEGY_PROBE,
                            false,
                            0L,
                            persistentListOf(),
                            "Проверка соединения с выбранным сайтом",
                        ),
                    strategyProbeSelected = true,
                )
            }
        }
    }
}
