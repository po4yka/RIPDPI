package com.poyka.ripdpi.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w360dp-h800dp")
class BottomNavBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `selected indicator follows selected destination in rtl`() {
        setBottomBar(fontScale = 1f, layoutDirection = LayoutDirection.Rtl, selectedRoute = Route.Home)

        val selectedBounds =
            composeRule
                .onNodeWithTag(RipDpiTestTags.bottomNav(Route.Home))
                .fetchSemanticsNode()
                .boundsInRoot
        val indicatorBounds =
            composeRule
                .onNodeWithTag(RipDpiTestTags.BottomNavIndicator)
                .fetchSemanticsNode()
                .boundsInRoot

        assertEquals(selectedBounds.center.x, indicatorBounds.center.x, 1f)
    }

    @Test
    fun `destination labels do not clip at 150 percent font scale`() {
        assertDestinationLabelsDoNotClip(fontScale = 1.5f)
    }

    @Test
    fun `destination labels do not clip at 200 percent font scale`() {
        assertDestinationLabelsDoNotClip(fontScale = 2f)
    }

    private fun assertDestinationLabelsDoNotClip(fontScale: Float) {
        setBottomBar(fontScale = fontScale, layoutDirection = LayoutDirection.Ltr, selectedRoute = Route.Home)

        val barBounds =
            composeRule
                .onNodeWithTag(RipDpiTestTags.BottomNavBar)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("The bottom bar should grow beyond its compact 64dp baseline", barBounds.height > 64f)

        destinationLabels().forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(layouts)
                }

            val layout = layouts.single()
            assertFalse("$label must not overflow at ${fontScale}x font scale", layout.didOverflowHeight)
            assertFalse(
                "$label must not be ellipsized at ${fontScale}x font scale",
                (0 until layout.lineCount).any(layout::isLineEllipsized),
            )
        }
    }

    private fun setBottomBar(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        selectedRoute: Route,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                RipDpiTheme {
                    Box(modifier = Modifier.width(360.dp).height(300.dp)) {
                        BottomNavBar(
                            selectedRoute = selectedRoute,
                            onNavigate = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun destinationLabels(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Route.topLevel.map { route -> context.getString(route.titleRes) }
    }
}
