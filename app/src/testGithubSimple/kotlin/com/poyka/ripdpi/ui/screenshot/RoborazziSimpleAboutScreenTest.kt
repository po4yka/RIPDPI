package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.components.RipDpiAboutPreviewScene
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class RoborazziSimpleAboutScreenTest {
    @Test
    fun simpleAboutScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiAboutPreviewScene()
        }
    }
}
