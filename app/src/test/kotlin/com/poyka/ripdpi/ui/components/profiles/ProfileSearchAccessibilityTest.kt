package com.poyka.ripdpi.ui.components.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
@Config(sdk = [35])
class ProfileSearchAccessibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `maximum font LTR keeps search filter empty and reset labels complete`() =
        assertLabels(
            LayoutDirection.Ltr,
        )

    @Test
    @Config(qualifiers = "ar-rEG")
    fun `maximum font Arabic RTL keeps complete accessible labels`() = assertLabels(LayoutDirection.Rtl)

    @Test fun `search input preserves accessible label typing and clear semantics`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    ProfileSearchControls(rememberProfileSearchState(), emptyList(), "field-semantics")
                }
            }
        }
        composeRule.onNodeWithContentDescription(text(R.string.profile_search_label)).performTextInput("typed query")
        composeRule
            .onNodeWithTag(
                com.poyka.ripdpi.ui.testing.RipDpiTestTags
                    .profileSearchQuery("field-semantics"),
            ).assertTextEquals("typed query")
        composeRule
            .onNodeWithContentDescription(
                text(R.string.profile_search_clear),
            ).performSemanticsAction(SemanticsActions.OnClick) {
                it()
            }
        composeRule
            .onNodeWithTag(
                com.poyka.ripdpi.ui.testing.RipDpiTestTags
                    .profileSearchQuery("field-semantics"),
            ).assertTextEquals("")
    }

    private fun assertLabels(direction: LayoutDirection) {
        val keys =
            listOf(
                R.string.profile_search_label,
                R.string.profile_search_all,
                R.string.diagnostics_family_web_connectivity,
                R.string.diagnostics_family_automatic_audit,
                R.string.profile_search_no_results,
                R.string.profile_search_reset,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                RipDpiTheme {
                    Column(
                        Modifier
                            .requiredSize(
                                411.dp,
                                839.dp,
                            ).ripDpiTestTag("search-font-panel")
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ProfileSearchControls(
                            state = ProfileSearchState("missing"),
                            filters =
                                listOf(
                                    ProfileSearchFilter(
                                        "WEB_CONNECTIVITY",
                                        text(R.string.diagnostics_family_web_connectivity),
                                    ),
                                    ProfileSearchFilter(
                                        "AUTOMATIC_AUDIT",
                                        text(R.string.diagnostics_family_automatic_audit),
                                    ),
                                ),
                            tag = "search-font",
                        )
                        ProfileSearchEmpty(ProfileSearchState("missing"), "search-font")
                    }
                }
            }
        }
        keys.forEach { key ->
            val node = composeRule.onNodeWithText(text(key), useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing layout for ${text(key)}", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse(
                    "${text(
                        key,
                    )}: size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}",
                    layout.hasVisualOverflow,
                )
            }
            val panel = composeRule.onNodeWithTag("search-font-panel").fetchSemanticsNode().boundsInRoot
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "${text(key)} bounds=$bounds panel=$panel",
                bounds.left >= panel.left && bounds.right <= panel.right && bounds.top >= panel.top &&
                    bounds.bottom <= panel.bottom,
            )
        }
    }

    private fun text(key: Int): String = RuntimeEnvironment.getApplication().getString(key)
}
