package com.poyka.ripdpi.activities

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportPreviewContentGateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `relock and composition disposal invalidate retained export eligibility`() {
        val present = mutableStateOf(true)
        val unlocked = mutableStateOf(true)
        var eligible = false
        var contentVisible = false
        val publish: (Boolean) -> Unit = { eligible = it }
        composeRule.setContent {
            if (present.value) {
                ExportPreviewContentGate(unlocked.value, publish) { contentVisible = true }
            }
        }
        composeRule.waitForIdle()
        assertTrue(eligible)
        assertTrue(contentVisible)
        composeRule.runOnIdle { unlocked.value = false }
        composeRule.waitForIdle()
        assertFalse(eligible)
        composeRule.runOnIdle { unlocked.value = true }
        composeRule.waitForIdle()
        assertTrue(eligible)
        composeRule.runOnIdle { present.value = false }
        composeRule.waitForIdle()
        assertFalse(eligible)
    }
}
