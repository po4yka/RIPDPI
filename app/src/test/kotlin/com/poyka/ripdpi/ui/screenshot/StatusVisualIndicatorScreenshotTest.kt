package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.ui.screens.detection.StatusVisualPreviewRow
import com.poyka.ripdpi.ui.screens.detection.VerdictScoreCard
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatusVisualIndicatorScreenshotTest {
    @Test
    fun standardVerdictHero() {
        capturePaletteHero(DetectionColorVisionMode.OFF)
    }

    @Test
    fun redGreenSafeVerdictHero() {
        capturePaletteHero(DetectionColorVisionMode.RED_GREEN)
    }

    @Test
    fun tritanSafeVerdictHero() {
        capturePaletteHero(DetectionColorVisionMode.BLUE_YELLOW)
    }

    @Test
    fun achromatopsiaVerdictHero() {
        capturePaletteHero(DetectionColorVisionMode.ACHROMATOPSIA)
    }

    @Test
    fun previewRowsShowEveryStateForEveryPalette() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 320) {
            RipDpiTheme(themePreference = "light") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(24.dp),
                ) {
                    DetectionColorVisionMode.entries.forEach { mode ->
                        StatusVisualPreviewRow(mode = mode)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    private fun capturePaletteHero(mode: DetectionColorVisionMode) {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 180) {
            RipDpiTheme(themePreference = "light") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(24.dp),
                ) {
                    VerdictScoreCard(
                        result = detectedResult(),
                        score = 32,
                        label = "Exposed",
                        colorVisionMode = mode,
                    )
                }
            }
        }
    }

    private fun detectedResult(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = detectedCategory("GeoIP"),
            directSigns = detectedCategory("Direct"),
            indirectSigns = detectedCategory("Indirect"),
            locationSignals = detectedCategory("Location"),
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = true,
                ),
            verdict = Verdict.DETECTED,
        )

    private fun detectedCategory(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = true,
            findings = emptyList(),
        )
}
