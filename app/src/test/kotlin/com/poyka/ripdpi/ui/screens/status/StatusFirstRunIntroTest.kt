package com.poyka.ripdpi.ui.screens.status

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatusFirstRunIntroTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a user who never had the flag set does not see the explanation`() {
        composeRule.setContent { RipDpiTheme { StatusFirstRunIntro() } }

        composeRule.onAllNodesWithTag(RipDpiTestTags.StatusFirstRunIntro).assertCountEquals(0)
    }

    @Test
    fun `the explanation shows after onboarding and never again after dismissal`() {
        StatusFirstRunIntroStore.setPending(context, pending = true)
        composeRule.setContent { RipDpiTheme { StatusFirstRunIntro() } }

        composeRule.onNodeWithTag(RipDpiTestTags.StatusFirstRunIntro).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.StatusFirstRunIntroDismiss).performClick()

        composeRule.onAllNodesWithTag(RipDpiTestTags.StatusFirstRunIntro).assertCountEquals(0)
        assertFalse(StatusFirstRunIntroStore.isPending(context))
    }
}
