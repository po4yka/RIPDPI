package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.buildOverviewMetrics
import com.poyka.ripdpi.activities.buildTelemetryLiveMetrics
import com.poyka.ripdpi.activities.historyTelemetry
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.screens.diagnostics.LiveHighlightsGrid
import com.poyka.ripdpi.ui.screens.diagnostics.OverviewStatGrid
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthScreen
import com.poyka.ripdpi.ui.screens.health.previewConnectionHealthUiState
import com.poyka.ripdpi.ui.screens.history.MetricList
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsMeasurementsScreenshotTest {
    @Test
    fun healthLight() = captureHealth("health_light")

    @Test
    fun healthDark() = captureHealth("health_dark", darkMode = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun healthRtl() = captureHealth("health_rtl", direction = LayoutDirection.Rtl)

    @Test
    fun healthMaximumFont() = captureHealth("health_max_font", fontScale = 2f)

    @Test
    fun metricMeaningsLight() = captureMeanings("metric_meanings_light")

    @Test
    fun metricMeaningsDark() = captureMeanings("metric_meanings_dark", darkMode = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun metricMeaningsRtl() = captureMeanings("metric_meanings_rtl", direction = LayoutDirection.Rtl)

    @Test
    fun metricMeaningsMaximumFont() = captureMeanings("metric_meanings_max_font", fontScale = 2f)

    private fun captureHealth(
        name: String,
        darkMode: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
    ) = withUtcScreenshotTime {
        val state = previewConnectionHealthUiState()
        captureSingle(
            name,
            widthDp = 420,
            heightDp = if (fontScale > 1f) 3000 else 1600,
            darkMode = darkMode,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
        ) {
            ConnectionHealthScreen(
                uiState =
                    state.copy(
                        rows = persistentListOf(),
                        latencyDistributions = persistentListOf(state.latencyDistributions.first()),
                    ),
                onBack = {},
            )
        }
    }

    private fun captureMeanings(
        name: String,
        darkMode: Boolean = false,
        direction: LayoutDirection? = null,
        fontScale: Float = 1f,
    ) = withUtcScreenshotTime {
        val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
        val sample = historyTelemetry(createdAt = 1_700_000_300_000L)
        val labels =
            listOf(
                R.string.diagnostics_metric_rtt_band,
                R.string.diagnostics_metric_dns_latency,
                R.string.diagnostics_metric_retries,
            ).map { support.context.getString(it) }
        val metrics = support.buildTelemetryLiveMetrics(sample).filter { it.label in labels }.toImmutableList()
        val overview =
            support
                .buildOverviewMetrics(
                    DiagnosticsHealth.Healthy,
                    emptyList(),
                    emptyList(),
                    sample,
                ).toImmutableList()
        captureSingle(
            name,
            widthDp = 420,
            heightDp = if (fontScale > 1f) 2600 else 1500,
            darkMode = darkMode,
            fontScale = fontScale,
            layoutDirection = direction,
            testClassFqn = javaClass.name,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
                OverviewStatGrid(overview)
                LiveHighlightsGrid(metrics)
                RipDpiCard { MetricList(metrics) }
            }
        }
    }
}
