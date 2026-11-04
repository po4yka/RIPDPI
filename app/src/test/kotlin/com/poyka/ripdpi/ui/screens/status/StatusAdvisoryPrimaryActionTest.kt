package com.poyka.ripdpi.ui.screens.status

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
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
@Config(sdk = [35])
class StatusAdvisoryPrimaryActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `highest priority recovery is actionable before opening more advisories`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusAdvisorySlot(
                    advisories =
                        listOf(
                            StatusAdvisory(
                                title = "VPN permission needed",
                                message = "Allow a local VPN before connecting.",
                                severity = StatusAdvisorySeverity.Error,
                                presentation = StatusAdvisoryPresentation.Row,
                                tone = WarningBannerTone.Error,
                                actionLabel = "Allow VPN",
                                onClick = { repairs++ },
                                testTag = "permission-advisory",
                            ),
                            StatusAdvisory(
                                title = "Battery optimization",
                                message = "Review the background service settings.",
                                severity = StatusAdvisorySeverity.Info,
                                presentation = StatusAdvisoryPresentation.CompactRow,
                                tone = WarningBannerTone.Info,
                                actionLabel = "Review",
                                onClick = {},
                                testTag = "battery-advisory",
                            ),
                        ),
                )
            }
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, repairs)
        composeRule.onNodeWithTag("battery-advisory").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryMore).performClick()
        composeRule.onNodeWithTag("battery-advisory").assertIsDisplayed()
        composeRule.onAllNodesWithText("VPN permission needed").assertCountEquals(1)
        composeRule.onAllNodesWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction).assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction).performClick()
        assertEquals(2, repairs)
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryMore).performClick()
        composeRule.onNodeWithTag("battery-advisory").assertDoesNotExist()
    }

    @Test
    fun `actionless priority warning stays visible beside the first available recovery`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusAdvisorySlot(
                    advisories =
                        listOf(
                            testAdvisory("Provider failed", StatusAdvisorySeverity.Error),
                            testAdvisory("Review VPN settings", StatusAdvisorySeverity.Warning) { repairs++ },
                            testAdvisory("Background activity", StatusAdvisorySeverity.Info) {},
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Provider failed").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        composeRule.onNodeWithText("1 more notice").assertIsDisplayed()
        composeRule.onNodeWithText("Background activity").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryMore).performClick()
        composeRule.onNodeWithText("Background activity").assertIsDisplayed()
        composeRule.onAllNodesWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction).assertCountEquals(1)
    }

    @Test
    fun `single recovery row has no disclosure`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusAdvisorySlot(listOf(testAdvisory("Allow VPN", StatusAdvisorySeverity.Error) { repairs++ }))
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryPrimaryAction).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryMore).assertDoesNotExist()
    }

    @Test
    fun `a status label without an action stays visible and is not clickable`() {
        composeRule.setContent {
            RipDpiTheme {
                StatusAdvisorySlot(
                    listOf(
                        testAdvisory("System lockdown enabled", StatusAdvisorySeverity.Info)
                            .copy(actionLabel = "Managed by Android lockdown"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Managed by Android lockdown", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("System lockdown enabled").assertHasNoClickAction()
    }

    @Test
    fun `single banner keeps its original action surface`() {
        var signIns = 0
        composeRule.setContent {
            RipDpiTheme {
                StatusAdvisorySlot(
                    listOf(
                        testAdvisory("Sign-in needed", StatusAdvisorySeverity.Info) { signIns++ }
                            .copy(presentation = StatusAdvisoryPresentation.Banner),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("Sign-in needed").assertIsDisplayed().performClick()
        assertEquals(1, signIns)
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryCard).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.StatusAdvisoryMore).assertDoesNotExist()
    }
}

private fun testAdvisory(
    title: String,
    severity: StatusAdvisorySeverity,
    onClick: (() -> Unit)? = null,
): StatusAdvisory =
    StatusAdvisory(
        title = title,
        message = "Review this condition.",
        severity = severity,
        presentation = StatusAdvisoryPresentation.Row,
        tone = WarningBannerTone.Warning,
        actionLabel = if (onClick != null) "Review" else null,
        onClick = onClick,
        testTag = title,
    )
