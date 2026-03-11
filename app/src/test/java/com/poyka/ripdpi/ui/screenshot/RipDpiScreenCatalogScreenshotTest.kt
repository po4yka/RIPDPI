package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.RipDpiHomeExpandedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiIntroLargeFontPreviewScene
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
}
