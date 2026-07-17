package com.poyka.ripdpi.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w700dp-h400dp")
class RipDpiNavRailTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default font scale keeps destination labels on one line`() {
        setRailContent(fontScale = 1f)

        destinationLabels().forEach { label ->
            val textLayouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(textLayouts)
                }

            assertEquals("$label should use one line", 1, textLayouts.single().lineCount)
        }
    }

    @Test
    fun `all destinations stay visible and named at 1_3 font scale`() {
        assertDestinationsVisibleAndNamed(fontScale = 1.3f)
    }

    @Test
    fun `all destinations stay visible and named at 2_0 font scale`() {
        assertDestinationsVisibleAndNamed(fontScale = 2f)
    }

    private fun assertDestinationsVisibleAndNamed(fontScale: Float) {
        setRailContent(fontScale)

        destinationLabels().forEach { label ->
            composeRule
                .onNode(hasContentDescription(label) and hasClickAction())
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    private fun setRailContent(fontScale: Float) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(modifier = Modifier.width(700.dp).height(400.dp)) {
                        RipDpiNavRail(
                            selectedRoute = Route.Home,
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
