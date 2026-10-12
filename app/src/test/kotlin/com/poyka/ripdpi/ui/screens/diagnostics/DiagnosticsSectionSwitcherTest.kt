package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsSection
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
@Config(sdk = [35])
class DiagnosticsSectionSwitcherTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [35], qualifiers = "ru")
    fun maximumFontKeepsTabsInOneRowAndScrollsToEverySection() {
        var selectedSection by mutableStateOf(DiagnosticsSection.Dashboard)
        val selections = mutableListOf<DiagnosticsSection>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(320.dp)
                                .testTag("switcher-root"),
                    ) {
                        DiagnosticsSectionSwitcher(
                            selectedSection = selectedSection,
                            onSelectSection = {
                                selectedSection = it
                                selections += it
                            },
                        )
                    }
                }
            }
        }

        val root = composeRule.onNodeWithTag("switcher-root").fetchSemanticsNode()
        assertTrue("Tab strip must retain a 48dp touch target", root.size.height >= 48)
        assertTrue("Tab strip must not consume three rows", root.size.height <= 96)
        val lastTab =
            composeRule
                .onNodeWithTag(RipDpiTestTags.diagnosticsSection(DiagnosticsSection.Tools))
                .fetchSemanticsNode()
        assertTrue(
            "Tools must start beyond the narrow viewport to exercise horizontal scrolling",
            lastTab.positionInRoot.x + lastTab.size.width > root.boundsInRoot.right,
        )
        DiagnosticsSection.entries.forEach { section ->
            val tab = composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsSection(section))
            val node = tab.performScrollTo().assertIsDisplayed().fetchSemanticsNode()
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
            assertEquals(root.boundsInRoot.top, node.boundsInRoot.top, 1f)
            val textLayouts = mutableListOf<TextLayoutResult>()
            tab.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            assertTrue("Missing text layout for $section", textLayouts.isNotEmpty())
            textLayouts.forEach {
                assertFalse(
                    "Clipped label for $section: size=${it.size} constraints=${it.layoutInput.constraints} " +
                        "height=${it.didOverflowHeight} width=${it.didOverflowWidth} " +
                        "paragraphWidth=${it.multiParagraph.width} " +
                        "intrinsicWidth=${it.multiParagraph.intrinsics.maxIntrinsicWidth} " +
                        "density=${it.layoutInput.density} style=${it.layoutInput.style}",
                    it.hasVisualOverflow,
                )
            }
            tab.performClick().assertIsSelected()
            DiagnosticsSection.entries.filter { it != section }.forEach { unselected ->
                composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsSection(unselected)).assertIsNotSelected()
            }
        }
        assertEquals(DiagnosticsSection.entries, selections)
    }

    @Test
    fun sectionSwitcherExposesOneTabGroupAtDefaultAndMaximumFont() {
        var fontScale by mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                RipDpiTheme {
                    DiagnosticsSectionSwitcher(
                        selectedSection = DiagnosticsSection.Dashboard,
                        onSelectSection = {},
                    )
                }
            }
        }

        listOf(1f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()

            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
                .assertCountEquals(1)
            DiagnosticsSection.entries.forEach { section ->
                composeRule
                    .onNodeWithTag(RipDpiTestTags.diagnosticsSection(section))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.Selected,
                            section == DiagnosticsSection.Dashboard,
                        ),
                    )
            }
        }
    }
}
