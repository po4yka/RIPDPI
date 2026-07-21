package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsSectionSwitcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maximumFontWrapsEverySectionIntoVisibleBounds() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(411.dp)
                                .testTag("switcher-root"),
                    ) {
                        DiagnosticsSectionSwitcher(
                            selectedSection = DiagnosticsSection.Dashboard,
                            onSelectSection = {},
                        )
                    }
                }
            }
        }

        val root = composeRule.onNodeWithTag("switcher-root").fetchSemanticsNode()
        val nodes =
            DiagnosticsSection.entries.map { section ->
                composeRule
                    .onNodeWithTag(RipDpiTestTags.diagnosticsSection(section))
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            }
        nodes.forEachIndexed { index, node ->
            val section = DiagnosticsSection.entries[index]
            assertTrue(
                "section=$section node=${node.boundsInRoot} root=${root.boundsInRoot}",
                node.boundsInRoot.left >= root.boundsInRoot.left - 1f,
            )
            assertTrue(
                "section=$section node=${node.boundsInRoot} root=${root.boundsInRoot}",
                node.boundsInRoot.right <= root.boundsInRoot.right + 1f,
            )
            assertTrue(
                "section=$section node=${node.boundsInRoot} root=${root.boundsInRoot}",
                node.boundsInRoot.bottom <= root.boundsInRoot.bottom + 1f,
            )
        }
        nodes.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.boundsInRoot.bottom <= lower.boundsInRoot.top)
        }
    }
}
