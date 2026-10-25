package com.poyka.ripdpi.ui.screens.home

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
class HomeAdvisoryPrimaryActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `highest priority recovery is actionable before opening more advisories`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeAdvisorySlot(
                    advisories =
                        listOf(
                            HomeAdvisory(
                                title = "VPN permission needed",
                                message = "Allow a local VPN before connecting.",
                                severity = HomeAdvisorySeverity.Error,
                                presentation = HomeAdvisoryPresentation.Row,
                                tone = WarningBannerTone.Error,
                                actionLabel = "Allow VPN",
                                onClick = { repairs++ },
                                testTag = "permission-advisory",
                            ),
                            HomeAdvisory(
                                title = "Battery optimization",
                                message = "Review the background service settings.",
                                severity = HomeAdvisorySeverity.Info,
                                presentation = HomeAdvisoryPresentation.CompactRow,
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
            .onNodeWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, repairs)
        composeRule.onNodeWithTag("battery-advisory").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).performClick()
        composeRule.onNodeWithTag("battery-advisory").assertIsDisplayed()
        composeRule.onAllNodesWithText("VPN permission needed").assertCountEquals(1)
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).performClick()
        assertEquals(2, repairs)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).performClick()
        composeRule.onNodeWithTag("battery-advisory").assertDoesNotExist()
    }

    @Test
    fun `actionless priority warning stays visible beside the first available recovery`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeAdvisorySlot(
                    advisories =
                        listOf(
                            testAdvisory("Provider failed", HomeAdvisorySeverity.Error),
                            testAdvisory("Review VPN settings", HomeAdvisorySeverity.Warning) { repairs++ },
                            testAdvisory("Background activity", HomeAdvisorySeverity.Info) {},
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Provider failed").assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        composeRule.onNodeWithText("1 more").assertIsDisplayed()
        composeRule.onNodeWithText("Background activity").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).performClick()
        composeRule.onNodeWithText("Background activity").assertIsDisplayed()
        composeRule.onAllNodesWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertCountEquals(1)
    }

    @Test
    fun `single recovery row has no disclosure`() {
        var repairs = 0
        composeRule.setContent {
            RipDpiTheme {
                HomeAdvisorySlot(listOf(testAdvisory("Allow VPN", HomeAdvisorySeverity.Error) { repairs++ }))
            }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryPrimaryAction).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).assertDoesNotExist()
    }

    @Test
    fun `a status label without an action stays visible and is not clickable`() {
        composeRule.setContent {
            RipDpiTheme {
                HomeAdvisorySlot(
                    listOf(
                        testAdvisory("System lockdown enabled", HomeAdvisorySeverity.Info)
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
                HomeAdvisorySlot(
                    listOf(
                        testAdvisory("Sign-in needed", HomeAdvisorySeverity.Info) { signIns++ }
                            .copy(presentation = HomeAdvisoryPresentation.Banner),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("Sign-in needed").assertIsDisplayed().performClick()
        assertEquals(1, signIns)
        composeRule.onNodeWithTag(RipDpiTestTags.HomeSetupHealthRow).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.HomeAdvisoryMore).assertDoesNotExist()
    }
}

private fun testAdvisory(
    title: String,
    severity: HomeAdvisorySeverity,
    onClick: (() -> Unit)? = null,
): HomeAdvisory =
    HomeAdvisory(
        title = title,
        message = "Review this condition.",
        severity = severity,
        presentation = HomeAdvisoryPresentation.Row,
        tone = WarningBannerTone.Warning,
        actionLabel = if (onClick != null) "Review" else null,
        onClick = onClick,
        testTag = title,
    )
