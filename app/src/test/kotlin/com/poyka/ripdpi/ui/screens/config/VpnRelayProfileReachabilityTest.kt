package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
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
@Config(sdk = [35])
class VpnRelayProfileReachabilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `every saved vpn relay profile has an edit action`() {
        val edited = mutableListOf<String>()
        val kinds = listOf("vless", "trojan", "shadowsocks", "google_apps_script", "mieru", "ssh")
        setProfiles(kinds, edited)
        expandProfiles()
        composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("relay-ssh")
        assertTrue(kinds.take(4).all(::isModeEditorRelayKindSupported))
        for ((index, kind) in kinds.withIndex()) {
            composeRule.onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList).performScrollToKey("relay-$kind")
            composeRule
                .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("relay-$kind"))
                .performScrollTo()
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.runOnIdle { assertEquals(kinds.take(index + 1).map { "relay-$it" }, edited) }
        }
    }

    @Test
    fun `VLESS Trojan and Shadowsocks preview rows open by tap`() {
        assertPreviewProfilesOpenByTap(listOf("vless", "trojan", "shadowsocks"))
    }

    @Test
    fun `Apps Script Mieru and SSH preview rows open by tap`() {
        assertPreviewProfilesOpenByTap(listOf("google_apps_script", "mieru", "ssh"))
    }

    @Test
    fun `profile beyond preview opens by tap`() {
        val edited = mutableListOf<String>()
        setProfiles(listOf("vless", "trojan", "shadowsocks", "google_apps_script"), edited)
        expandProfiles()
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnProfileList)
            .performScrollToKey("relay-google_apps_script")
        composeRule
            .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("relay-google_apps_script"))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf("relay-google_apps_script"), edited) }
    }

    private fun assertPreviewProfilesOpenByTap(kinds: List<String>) {
        val edited = mutableListOf<String>()
        setProfiles(kinds, edited)
        for ((index, kind) in kinds.withIndex()) {
            composeRule
                .onNodeWithTag(RipDpiTestTags.configVpnProfileRow("relay-$kind"))
                .performScrollTo()
                .performClick()
            composeRule.runOnIdle { assertEquals(kinds.take(index + 1).map { "relay-$it" }, edited) }
        }
    }

    private fun expandProfiles() {
        composeRule
            .onNodeWithTag(RipDpiTestTags.ConfigVpnProfilesMore)
            .performScrollTo()
            .performClick()
    }

    private fun setProfiles(
        kinds: List<String>,
        edited: MutableList<String>,
    ) {
        val profiles = kinds.map { kind -> RelayProfileUiState("relay-$kind", kind, kind, "", "") }
        composeRule.setContent {
            RipDpiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VpnConfigScreen(
                        uiState = ConfigUiState(vpnProfiles = profiles.toImmutableList()),
                        onRuntimeModeToggle = { _, _ -> },
                        onOpenRelaySettings = {},
                        onOpenDnsSettings = {},
                        onPasteServerLink = {},
                        onScanServer = {},
                        onProfileEdit = { edited += it },
                    )
                }
            }
        }
    }
}
