package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.RipDpiAboutPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiAdvancedSettingsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiBiometricPinPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiBiometricPromptPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiConfigPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiCustomizationPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDataTransparencyPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsScanPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsSharePreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDnsSettingsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHistoryPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeCompactPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeConnectingPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDisconnectedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeErrorPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeExpandedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiIntroLargeFontPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiLogsEmptyDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiLogsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsMediumPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiVpnPermissionDialogPreviewScene
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiScreenCatalogScreenshotTest {
    @Test
    fun homeExpandedScreen() {
        captureRipDpiScreenshot(widthDp = 1040, heightDp = 920) {
            RipDpiHomeExpandedPreviewScene()
        }
    }

    @Test
    fun settingsMediumScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 1100) {
            RipDpiSettingsMediumPreviewScene()
        }
    }

    @Test
    fun introLargeFontScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900, fontScale = 1.3f) {
            RipDpiIntroLargeFontPreviewScene()
        }
    }

    @Test
    fun homeCompactScreen() {
        captureRipDpiScreenshot(widthDp = 390, heightDp = 800) {
            RipDpiHomeCompactPreviewScene()
        }
    }

    @Test
    fun homeDarkScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 920) {
            RipDpiHomeDarkPreviewScene()
        }
    }

    @Test
    fun settingsDarkScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 1100) {
            RipDpiSettingsDarkPreviewScene()
        }
    }

    // TODO(po4yka) Re-enable when Roborazzi adds layoutDirection API
    // @Test
    // fun homeRtlScreen() {
    //     captureRipDpiScreenshotRtl(widthDp = 720, heightDp = 920) {
    //         RipDpiHomeExpandedPreviewScene()
    //     }
    // }

    // @Test
    // fun settingsRtlScreen() {
    //     captureRipDpiScreenshotRtl(widthDp = 720, heightDp = 1100) {
    //         RipDpiSettingsMediumPreviewScene()
    //     }
    // }

    @Test
    fun diagnosticsScanScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDiagnosticsScanPreviewScene()
        }
    }

    @Test
    fun diagnosticsShareScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDiagnosticsSharePreviewScene()
        }
    }

    @Test
    fun homeDisconnectedScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeDisconnectedPreviewScene()
        }
    }

    @Test
    fun homeConnectingScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeConnectingPreviewScene()
        }
    }

    @Test
    fun homeErrorScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeErrorPreviewScene()
        }
    }

    @Test
    fun configScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiConfigPreviewScene()
        }
    }

    @Test
    fun advancedSettingsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 2400) {
            RipDpiAdvancedSettingsPreviewScene()
        }
    }

    @Test
    fun logsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiLogsPreviewScene()
        }
    }

    @Test
    fun logsEmptyDarkScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiLogsEmptyDarkPreviewScene()
        }
    }

    @Test
    fun dnsSettingsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 1200) {
            RipDpiDnsSettingsPreviewScene()
        }
    }

    @Test
    fun aboutScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiAboutPreviewScene()
        }
    }

    @Test
    fun dataTransparencyScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiDataTransparencyPreviewScene()
        }
    }

    @Test
    fun customizationScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiCustomizationPreviewScene()
        }
    }

    @Test
    fun historyScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiHistoryPreviewScene()
        }
    }

    @Test
    fun biometricPromptScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiBiometricPromptPreviewScene()
        }
    }

    @Test
    fun biometricPinScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiBiometricPinPreviewScene()
        }
    }

    @Test
    fun vpnPermissionDialog() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 600) {
            RipDpiVpnPermissionDialogPreviewScene()
        }
    }
}
