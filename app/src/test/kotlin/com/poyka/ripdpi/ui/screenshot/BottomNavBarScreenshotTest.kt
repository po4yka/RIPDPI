package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.ui.navigation.BottomNavBar
import com.poyka.ripdpi.ui.navigation.Route
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BottomNavBarScreenshotTest {
    @Test
    fun maximumAccessibilityFont() {
        captureScreenBothThemes(
            name = "maximumAccessibilityFont",
            widthDp = 411,
            heightDp = 120,
            fontScale = 2f,
            testClassFqn = javaClass.name,
        ) {
            BottomNavBar(
                selectedRoute = Route.Home,
                onNavigate = {},
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-rES")
class SpanishBottomNavBarScreenshotTest {
    @Test
    fun maximumAccessibilityFont() {
        captureMaximumFontBottomBar(javaClass.name)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rSA")
class ArabicBottomNavBarScreenshotTest {
    @Test
    fun maximumAccessibilityFont() {
        captureMaximumFontBottomBar(javaClass.name, LayoutDirection.Rtl)
    }
}

private fun captureMaximumFontBottomBar(
    testClassFqn: String,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    captureScreenBothThemes(
        name = "maximumAccessibilityFont",
        widthDp = 411,
        heightDp = 120,
        fontScale = 2f,
        testClassFqn = testClassFqn,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            BottomNavBar(
                selectedRoute = Route.Home,
                onNavigate = {},
            )
        }
    }
}
