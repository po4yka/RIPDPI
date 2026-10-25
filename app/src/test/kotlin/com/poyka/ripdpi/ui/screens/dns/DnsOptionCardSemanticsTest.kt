package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
class DnsOptionCardSemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `DNS cards announce selected state`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    DnsOptionCard(RipDpiIcons.Dns, "Selected DNS", "", true, emptyList(), {})
                    DnsOptionCard(RipDpiIcons.Dns, "Other DNS", "", false, emptyList(), {})
                }
            }
        }
        composeRule
            .onNodeWithContentDescription("Selected DNS")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithContentDescription("Other DNS").assertIsNotSelected()
    }

    @Test
    fun `badges stay aligned with the text column at large font`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        DnsOptionCard(RipDpiIcons.Dns, "Encrypted DNS", "Body", true, listOf("DoH"), {})
                    }
                }
            }
        }
        val titleLeft = composeRule.onNodeWithText("Encrypted DNS", useUnmergedTree = true).getBoundsInRoot().left
        val badgeLeft = composeRule.onNodeWithText("DoH", useUnmergedTree = true).getBoundsInRoot().left

        assertTrue("badge starts at $badgeLeft, before the text column at $titleLeft", badgeLeft >= titleLeft)
    }
}
