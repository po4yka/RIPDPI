package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.data.xray.XrayProviderDiagnosticsFixtures
import com.poyka.ripdpi.ui.screens.home.HomeXrayProviderBanner
import com.poyka.ripdpi.ui.screens.xray.XrayProviderSettingsStatusRow
import com.poyka.ripdpi.ui.screens.xray.XrayProviderStatusCard
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi coverage for the embedded-Xray provider rendering across the five
 * canonical [XrayProviderDiagnosticsFixtures] states (healthy, configInvalid,
 * protectFailure, dnsLoopSuspected, outboundUnreachable). Each fixture renders
 * the Diagnostics card (with probes), the Home provider banner, and the Settings
 * status row together so the provider-distinct treatment (decision 6) is locked
 * across all three surfaces.
 *
 * These are NEW screenshots, not a re-bless of any existing golden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class XrayProviderStatusScreenshotTest {
    @Test
    fun healthy() = capture("healthy")

    @Test
    fun configInvalid() = capture("configInvalid")

    @Test
    fun protectFailure() = capture("protectFailure")

    @Test
    fun dnsLoopSuspected() = capture("dnsLoopSuspected")

    @Test
    fun outboundUnreachable() = capture("outboundUnreachable")

    private fun capture(fixtureName: String) {
        val report =
            XrayProviderDiagnosticsFixtures.all
                .first { it.first == fixtureName }
                .second
        captureScreenBothThemes(name = fixtureName, widthDp = 420, heightDp = 1100, testClassFqn = FQN) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(RipDpiThemeTokens.layout.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                HomeXrayProviderBanner(snapshot = report.snapshot)
                XrayProviderStatusCard(
                    report = report,
                    onRunProbe = {},
                    probeRunning = false,
                )
                XrayProviderSettingsStatusRow(snapshot = report.snapshot)
            }
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.XrayProviderStatusScreenshotTest"
    }
}
