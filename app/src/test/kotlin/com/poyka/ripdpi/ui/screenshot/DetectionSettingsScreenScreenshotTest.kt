package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.ui.screens.detection.DetectionDnsPreset
import com.poyka.ripdpi.ui.screens.detection.DetectionDnsResolverMode
import com.poyka.ripdpi.ui.screens.detection.DetectionPortRangeMode
import com.poyka.ripdpi.ui.screens.detection.DetectionSettingsScreen
import com.poyka.ripdpi.ui.screens.detection.DetectionSettingsUiState
import com.poyka.ripdpi.ui.screens.detection.DetectionTunProbeMode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DetectionSettingsScreenScreenshotTest {
    @Test
    fun defaultDetectionSettingsScreen() {
        captureSettingsScreen(DetectionSettingsUiState())
    }

    @Test
    fun customDnsAndPortDetectionSettingsScreen() {
        captureSettingsScreen(
            DetectionSettingsUiState(
                networkRequestsEnabled = false,
                cdnPullingEnabled = true,
                callTransportProbeEnabled = true,
                rttTriangulationEnabled = true,
                portRangeMode = DetectionPortRangeMode.CUSTOM,
                customPortStart = 1080,
                customPortEnd = 1090,
                dnsResolverMode = DetectionDnsResolverMode.DOH,
                dnsPreset = DetectionDnsPreset.CLOUDFLARE,
                dnsDirectServers = DetectionDnsPreset.CLOUDFLARE.directServers,
                dnsDohUrl = DetectionDnsPreset.CLOUDFLARE.dohUrl,
                dnsDohBootstrapIps = DetectionDnsPreset.CLOUDFLARE.directServers,
                privacyModeEnabled = true,
                debugModeEnabled = true,
                colorVisionMode = DetectionColorVisionMode.BLUE_YELLOW,
            ),
        )
    }

    @Test
    fun networkDisabledDetectionSettingsSection() {
        captureSettingsScreen(
            DetectionSettingsUiState(
                networkRequestsEnabled = false,
                cdnPullingEnabled = true,
                cdnPullingMeduzaEnabled = true,
                callTransportProbeEnabled = true,
                rttTriangulationEnabled = true,
            ),
        )
    }

    @Test
    fun splitTunnelCustomPortsDetectionSettingsSection() {
        captureSettingsScreen(
            DetectionSettingsUiState(
                proxyScanEnabled = true,
                xrayApiScanEnabled = true,
                tunProbeMode = DetectionTunProbeMode.STRICT_SAME_PATH,
                portRangeMode = DetectionPortRangeMode.CUSTOM,
                customPortStart = 1024,
                customPortEnd = 49151,
            ),
        )
    }

    @Test
    fun dnsDirectDetectionSettingsSection() {
        captureSettingsScreen(
            DetectionSettingsUiState(
                dnsResolverMode = DetectionDnsResolverMode.DIRECT,
                dnsPreset = DetectionDnsPreset.CUSTOM,
                dnsDirectServers = "9.9.9.9, 149.112.112.112",
            ),
        )
    }

    @Test
    fun dnsDohPresetDetectionSettingsSection() {
        captureSettingsScreen(
            DetectionSettingsUiState(
                dnsResolverMode = DetectionDnsResolverMode.DOH,
                dnsPreset = DetectionDnsPreset.GOOGLE,
                dnsDirectServers = DetectionDnsPreset.GOOGLE.directServers,
                dnsDohUrl = DetectionDnsPreset.GOOGLE.dohUrl,
                dnsDohBootstrapIps = DetectionDnsPreset.GOOGLE.directServers,
            ),
        )
    }

    private fun captureSettingsScreen(state: DetectionSettingsUiState) {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 1180) {
            RipDpiTheme(themePreference = "light") {
                DetectionSettingsScreen(
                    state = state,
                    onBack = {},
                )
            }
        }
    }
}
