package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiBottomSheetSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `drag handle is labeled, header icon is inert and dismiss stays available`() {
        composeRule.setContent {
            RipDpiTheme {
                RipDpiBottomSheet(
                    onDismissRequest = {},
                    title = "Session",
                    icon = RipDpiIcons.Search,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        composeRule.waitForIdle()
        val clickable = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val unlabeled = clickable.filter { SemanticsProperties.ContentDescription !in it.config }
        assertEquals("Unlabeled clickable nodes: ${unlabeled.map { it.config }}", 0, unlabeled.size)
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .assertCountEquals(1)
    }
}
