package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
class VpnProfileSearchFontScaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `Arabic font two large catalog keeps footer and exact selection reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        val selected = mutableListOf<String>()
        val edited = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val profiles = (0 until 500).map { RelayProfileUiState("import-$it", "ssh", "SSH", "", "") }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(411.dp, 839.dp)) {
                        var open by remember { mutableStateOf(true) }
                        if (open) {
                            VpnProfileSearchSheet(
                                profiles = profiles,
                                selectedProfileId = "import-0",
                                search = remember { ProfileSearchState() },
                                onDismiss = { open = false },
                                actions =
                                    ConfigProfileActions(
                                        select = { selected += it },
                                        share = { shared += it },
                                        edit = { edited += it },
                                    ),
                            )
                        }
                    }
                }
            }
        }
        val sheet = composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).fetchSemanticsNode().boundsInRoot
        assertTrue("Actual sheet $sheet exceeds 411x839 viewport", sheet.width <= 411f && sheet.height <= 839f)
        label(text(R.string.config_cancel)).assertIsDisplayed()
        assertComplete(label(text(R.string.config_cancel)))
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("profile:import-499")
        val title = label(profiles.last().selectorLabel).performScrollTo()
        assertComplete(title)
        val select = composeRule.onNodeWithTag(RipDpiTestTags.configVpnProfileSelect("import-499")).performScrollTo()
        select.assertIsDisplayed()
        assertComplete(
            label(text(R.string.profile_variants_cta_select), RipDpiTestTags.configVpnProfileSelect("import-499")),
        )
        label(text(R.string.config_cancel)).assertIsDisplayed()
        select.performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("import-499"), selected)
            assertTrue(edited.isEmpty() && shared.isEmpty())
        }
        composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).assertDoesNotExist()
    }

    private fun label(
        text: String,
        ancestorTag: String = RipDpiTestTags.RelayProfileSearchSheet,
    ) = composeRule.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(ancestorTag)),
        useUnmergedTree = true,
    )

    private fun assertComplete(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing actual text layout", layouts.isNotEmpty())
        layouts.forEach {
            assertEquals("Actual dialog text font scale", 2f, it.layoutInput.density.fontScale)
            assertFalse("Overflow: ${it.size}, text=${it.layoutInput.text}", it.hasVisualOverflow)
        }
        val sheet = composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Label $bounds outside sheet $sheet",
            bounds.left >= sheet.left && bounds.right <= sheet.right && bounds.top >= sheet.top &&
                bounds.bottom <= sheet.bottom,
        )
    }

    private fun text(key: Int): String = RuntimeEnvironment.getApplication().getString(key)
}
