package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiMotion
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class SharedFeedbackQualityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `short large-font dialog keeps both actions reachable`() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp).requiredHeight(240.dp)) {
                        RipDpiDialogCard(
                            title = "Export captured network traffic",
                            dismissAction = RipDpiDialogAction("Cancel export", { dismissed++ }, "dismiss"),
                            confirmAction =
                                RipDpiDialogAction(
                                    "Export captured network traffic",
                                    { confirmed++ },
                                    "confirm",
                                ),
                            visuals = RipDpiDialogVisuals(icon = null),
                        ) {
                            Text("Packet headers and endpoint addresses can identify your network. ".repeat(8))
                        }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("confirm")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag("dismiss")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h640dp-mdpi")
    fun `large-font adaptive dialog stacks actions in a medium window`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        RipDpiDialogCard(
                            title = "Export traffic",
                            dismissAction = RipDpiDialogAction("Cancel traffic export", {}, "medium-dismiss"),
                            confirmAction = RipDpiDialogAction("Export captured network traffic", {}, "medium-confirm"),
                            visuals = RipDpiDialogVisuals(icon = null),
                        )
                    }
                }
            }
        }
        val confirmAction = composeRule.onNodeWithTag("medium-confirm")
        confirmAction.performScrollTo().assertIsDisplayed()
        val confirm = confirmAction.getUnclippedBoundsInRoot()
        val dismiss = composeRule.onNodeWithTag("medium-dismiss").getUnclippedBoundsInRoot()
        assertTrue("Actions overlap: $confirm and $dismiss", confirm.bottom <= dismiss.top)
        assertTrue("Confirm target is too narrow: $confirm", confirm.right - confirm.left >= 48.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h640dp-mdpi")
    fun `medium dialog keeps adaptive actions inline at default font`() =
        assertActionPolicy(RipDpiActionLayout.Adaptive, 1f, stacked = false)

    @Test
    @Config(qualifiers = "w800dp-h640dp-mdpi")
    fun `explicit inline dialog remains inline at large font`() =
        assertActionPolicy(RipDpiActionLayout.Inline, 2f, stacked = false)

    @Test
    @Config(qualifiers = "w800dp-h640dp-mdpi")
    fun `explicit stacked dialog remains stacked at default font`() =
        assertActionPolicy(RipDpiActionLayout.Stacked, 1f, stacked = true)

    private fun assertActionPolicy(
        layout: RipDpiActionLayout,
        fontScale: Float,
        stacked: Boolean,
    ) {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(400.dp)) {
                        RipDpiDialogCard(
                            title = "Export",
                            dismissAction = RipDpiDialogAction("No", { dismissed++ }, "policy-dismiss"),
                            confirmAction = RipDpiDialogAction("Yes", { confirmed++ }, "policy-confirm"),
                            visuals = RipDpiDialogVisuals(icon = null, actionLayout = layout),
                        )
                    }
                }
            }
        }
        val confirm = composeRule.onNodeWithTag("policy-confirm").assertIsDisplayed()
        val dismiss = composeRule.onNodeWithTag("policy-dismiss").assertIsDisplayed()
        val confirmBounds = confirm.getUnclippedBoundsInRoot()
        val dismissBounds = dismiss.getUnclippedBoundsInRoot()
        if (stacked) {
            assertTrue("Actions must stack", confirmBounds.bottom <= dismissBounds.top)
        } else {
            assertEquals(dismissBounds.top, confirmBounds.top)
            assertTrue("Actions must remain distinct", dismissBounds.right <= confirmBounds.left)
        }
        confirm.performClick()
        dismiss.performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun `dialog card remains usable inside a scrolling parent`() {
        var dismissed = 0
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RipDpiDialogCard(
                        title = "Export traffic",
                        dismissAction = RipDpiDialogAction("Dismiss", { dismissed++ }, "outer-dismiss"),
                        visuals = RipDpiDialogVisuals(icon = null),
                    ) { Text("Packet headers") }
                }
            }
        }
        composeRule.onNodeWithTag("outer-dismiss").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun `consent redaction label is a named toggle and changes export value`() {
        var exported: Boolean? = null
        composeRule.setContent {
            RipDpiTheme {
                RipDpiExportConsentDialog(
                    fileName = "traffic.pcap",
                    fileSize = "1 KiB",
                    packetCount = 1,
                    contents = persistentListOf(),
                    onDismiss = {},
                    onExport = { exported = it },
                )
            }
        }
        val toggle = composeRule.onNodeWithText("Redact endpoint IPs to 0.0.0.0")
        toggle.assertIsOn().performClick().assertIsOff()
        composeRule.onNodeWithText("Export").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, exported) }
    }

    @Test
    fun `warning dismiss reserves a separate 48dp target`() {
        var opened = 0
        var dismissed = 0
        composeRule.setContent {
            RipDpiTheme {
                WarningBanner(
                    "Permission needed",
                    "Open Android settings.",
                    onClick = { opened++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        val action = composeRule.onNodeWithTag(RipDpiTestTags.WarningBannerDismiss)
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("Dismiss target $bounds", bounds.width >= 48f && bounds.height >= 48f)
        action.performClick()
        composeRule.runOnIdle {
            assertEquals(0, opened)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun `static motion reveals accordion content without a timed transition`() {
        var expanded by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalRipDpiMotion provides DefaultRipDpiMotion.copy(animationsEnabled = false),
                ) {
                    RipDpiAccordion("Advanced options", expanded, { expanded = it }, headerTestTag = "header") {
                        Box(Modifier.requiredHeight(80.dp).ripDpiTestTag("body")) { Text("Options") }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("header").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        val body = composeRule.onNodeWithTag("body").assertIsDisplayed()
        assertEquals(80f, body.fetchSemanticsNode().boundsInRoot.height, 0.01f)
    }

    @Test
    fun `large-font accordion keeps the expansion cue inside the header`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        RipDpiAccordion("Network modes and diagnostics", false, {}, headerTestTag = "header") {}
                    }
                }
            }
        }
        val header = composeRule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val cue =
            composeRule
                .onNodeWithContentDescription(
                    "Expand",
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Cue $cue outside $header", cue.width > 0 && cue.left >= header.left && cue.right <= header.right)
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h640dp-mdpi")
    fun `decorative feedback icons do not add English severity names`() {
        composeRule.setContent {
            RipDpiTheme {
                Column {
                    WarningBanner("Ошибка подключения", "Повторите попытку", tone = WarningBannerTone.Error)
                    RipDpiSnackbar("Не удалось сохранить", tone = RipDpiSnackbarTone.Error)
                }
            }
        }
        composeRule.onNodeWithContentDescription("Error", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Ошибка подключения").assertIsDisplayed()
    }
}
