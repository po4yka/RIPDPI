package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsScanPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsSharePreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeCompactPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeExpandedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiIntroLargeFontPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsMediumPreviewScene
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

    @Test
    fun homeRtlScreen() {
        captureRipDpiScreenshotRtl(widthDp = 720, heightDp = 920) {
            RipDpiHomeExpandedPreviewScene()
        }
    }

    @Test
    fun settingsRtlScreen() {
        captureRipDpiScreenshotRtl(widthDp = 720, heightDp = 1100) {
            RipDpiSettingsMediumPreviewScene()
        }
    }

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
}
