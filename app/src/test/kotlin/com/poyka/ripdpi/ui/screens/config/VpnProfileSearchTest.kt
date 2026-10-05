package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VpnProfileSearchTest {
    @get:Rule val composeRule = createComposeRule()
    private val selected = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private var runtimeActions = 0
    private val profiles =
        listOf(
            RelayProfileUiState("alpha", "vless", "VLESS", "Germany", "Local operator"),
            RelayProfileUiState("alpha-other", "trojan", "Trojan", "France", "Other operator"),
            RelayProfileUiState("third", "shadowsocks", "Shadowsocks", "", ""),
            RelayProfileUiState("fourth-ssh", "ssh", "SSH", "France", "Local operator"),
            RelayProfileUiState("unknown-id", "future_kind", "Future kind", "", ""),
        )

    @Test fun `search is reachable with a single saved profile and cancel has no effects`() {
        composeRule.setContent { Content(profiles.take(1)) }
        open()
        query("missing")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchEmpty(scope)).assertExists()
        cancel()
        assertNoActions()
    }

    @Test fun `query and filter restore while open and survive dismissal`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { Content(profiles) }
        open()
        query("alpha")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "vless")).performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextContains("alpha")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "vless")).assertIsSelected()
        cancel()
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextContains("alpha")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "vless")).assertIsSelected()
        assertNoActions()
    }

    @Test fun `filter hides selected profile without changing it and clear preserves filter`() {
        composeRule.setContent { Content(profiles) }
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "ssh")).performClick()
        query("not-found")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchClear(scope)).performClick()
        sheetRow("alpha").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "ssh")).assertIsSelected()
        assertNoActions()
        cancel()
        composeRule.onNodeWithTag(RipDpiTestTags.configVpnProfileSelect("alpha")).assertExists()
    }

    @Test fun `empty state resets query and filter without selecting`() {
        composeRule.setContent { Content(profiles) }
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "trojan")).performClick()
        query("not-found")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchReset(scope)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, null)).assertIsSelected()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextEquals("")
        sheetRow("alpha").assertExists()
        assertNoActions()
    }

    @Test fun `searched edit beyond preview receives exact id once and closes sheet`() {
        composeRule.setContent { Content(profiles) }
        open()
        query("fourth-ssh")
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("profile:fourth-ssh")
        sheetRow("fourth-ssh").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("fourth-ssh"), edited)
        assertEquals(emptyList<String>(), selected)
        assertEquals(emptyList<String>(), shared)
        composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).assertDoesNotExist()
    }

    @Test fun `searched select is exact once with overlapping ids`() {
        composeRule.setContent { Content(profiles) }
        open()
        query("alpha-other")
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("profile:alpha-other")
        sheetNode(RipDpiTestTags.configVpnProfileSelect("alpha-other")).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("alpha-other"), selected)
        assertEquals(emptyList<String>(), edited)
        assertEquals(0, runtimeActions)
    }

    @Test fun `unknown kind has no invented edit but share uses exact id once`() {
        composeRule.setContent { Content(profiles) }
        open()
        query("future")
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("profile:unknown-id")
        sheetRow("unknown-id").assertHasNoClickAction()
        sheetNode(RipDpiTestTags.configVpnProfileShare("unknown-id")).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("unknown-id"), shared)
        assertEquals(emptyList<String>(), selected)
        assertEquals(emptyList<String>(), edited)
    }

    @Test fun `large imported catalog composes a bounded viewport and last profile edit is reachable`() {
        val large = (0 until 500).map { RelayProfileUiState("import-$it", "ssh", "SSH", "", "") }
        composeRule.setContent { Content(large) }
        open()
        sheetRow("import-499").assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("profile:import-499")
        sheetRow("import-499").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("import-499"), edited) }
        assertEquals(emptyList<String>(), selected)
        assertEquals(0, runtimeActions)
    }

    @Test fun `relay Back dismisses without selecting or changing runtime`() {
        composeRule.setContent { Content(profiles) }
        open()
        query("ssh")
        composeRule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).assertDoesNotExist()
        assertNoActions()
    }

    @Test fun `relay drag dismisses without selecting or changing runtime`() {
        composeRule.setContent { Content(profiles) }
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).performTouchInput { swipeDown() }
        composeRule.onNodeWithTag(RipDpiTestTags.RelayProfileSearchSheet).assertDoesNotExist()
        assertNoActions()
    }

    @Composable private fun Content(items: List<RelayProfileUiState>) {
        var activeId by remember { mutableStateOf("alpha") }
        RipDpiTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VpnConfigScreen(
                    uiState =
                        ConfigUiState(
                            vpnProfiles = items.toImmutableList(),
                            activeRelayProfileId = activeId,
                            activeRelayEnabled = true,
                        ),
                    onRuntimeModeToggle = { _, _ -> runtimeActions++ },
                    onOpenRelaySettings = {},
                    onOpenDnsSettings = {},
                    onPasteServerLink = {},
                    onScanServer = {},
                    onProfileSelect = {
                        selected += it
                        activeId = it
                    },
                    onProfileShare = { shared += it },
                    onProfileEdit = { edited += it },
                )
            }
        }
    }

    private fun open() =
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfilesMore).performScrollTo().performClick()

    private fun query(value: String) =
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).performTextReplacement(value)

    private fun cancel() = composeRule.onNodeWithTag(RipDpiTestTags.profileSearchCancel(scope)).performClick()

    private fun sheetRow(id: String) = sheetNode(RipDpiTestTags.configVpnProfileRow(id))

    private fun sheetNode(tag: String) =
        composeRule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(RipDpiTestTags.RelayProfileSearchSheet)))

    private fun assertNoActions() {
        assertEquals(emptyList<String>(), selected)
        assertEquals(emptyList<String>(), shared)
        assertEquals(emptyList<String>(), edited)
        assertEquals(0, runtimeActions)
    }

    private val scope = RipDpiTestTags.RelayProfileSearch
}
