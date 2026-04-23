package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.RipDpiDesignSystemScreenshotCatalog
import com.poyka.ripdpi.ui.theme.RipDpiContrastLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiDesignSystemScreenshotTest {
    @Test
    fun designSystemCatalogLightCompact() {
        captureRipDpiScreenshot(widthDp = 390, heightDp = 2800) {
            RipDpiDesignSystemScreenshotCatalog(themePreference = "light")
        }
    }

    @Test
    fun designSystemCatalogDarkMedium() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 2800) {
            RipDpiDesignSystemScreenshotCatalog(themePreference = "dark")
        }
    }

    @Test
    fun designSystemCatalogLargeFont() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 2800, fontScale = 1.3f) {
            RipDpiDesignSystemScreenshotCatalog(themePreference = "light")
        }
    }

    @Test
    fun designSystemCatalogHighContrast() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 2800) {
            RipDpiDesignSystemScreenshotCatalog(
                themePreference = "light",
                contrastLevel = RipDpiContrastLevel.High,
            )
        }
    }
}
