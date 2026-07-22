package com.poyka.ripdpi.ui.screens.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class ConnectionHealthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maximumFontKeepsMetricLabelsOnWholeLines() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredSize(width = 411.dp, height = 891.dp)) {
                        ConnectionHealthScreen(
                            uiState =
                                ConnectionHealthUiState(
                                    rows =
                                        persistentListOf(
                                            ConnectionHealthRowUiState(
                                                destinationClass = ConnectionHealthDestinationClass.VK,
                                                successCount = 18,
                                                failureCount = 2,
                                            ),
                                        ),
                                ),
                            onBack = {},
                        )
                    }
                }
            }
        }

        listOf("SUCCESS", "FAILURE", "SAMPLES").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            assertFalse(layout.getLineRight(0) > layout.size.width + 1f)
            assertFalse(layout.isLineEllipsized(0))
        }
    }
}
