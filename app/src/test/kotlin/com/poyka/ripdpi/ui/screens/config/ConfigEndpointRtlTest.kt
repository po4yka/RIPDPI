package com.poyka.ripdpi.ui.screens.config

import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rEG-w420dp-h2600dp-mdpi")
class ConfigEndpointRtlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `local bypass endpoints retain logical and visual address order under RTL titles`() {
        val state = mutableStateOf(ConfigUiState(uiPersona = "advanced", draft = ConfigDraft(mode = Mode.Proxy)))
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RipDpiTheme(themePreference = "light") {
                    LocalBypassConfigScreen(
                        uiState = state.value,
                        desyncSummary = "",
                        onRuntimeModeToggle = { _, _ -> },
                        onOpenDesyncSettings = {},
                        onOpenDnsSettings = {},
                        onRetestStrategies = {},
                        modifier = Modifier.requiredSize(420.dp, 2400.dp),
                    )
                }
            }
        }
        addresses.forEach { address ->
            composeRule.runOnIdle {
                state.value =
                    state.value.copy(draft = ConfigDraft(mode = Mode.Proxy, proxyIp = address, proxyPort = "443"))
            }
            assertEndpoint(address)
            if (address ==
                addresses.first()
            ) {
                captureEndpoint(RipDpiTestTags.ConfigLocalBypassListenAddress, "local-bypass-ipv6")
            }
        }
        assertDirection(resource(R.string.bye_dpi_proxy_ip_setting), ResolvedTextDirection.Rtl)
        assertDirection(resource(R.string.config_listen_address_summary), ResolvedTextDirection.Rtl)
    }

    @Test
    fun `VPN relay endpoints remain LTR while disabled and missing server captions remain Arabic RTL`() {
        val state = mutableStateOf(ConfigUiState(uiPersona = "advanced", draft = ConfigDraft()))
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RipDpiTheme(themePreference = "light") {
                    VpnConfigScreen(
                        uiState = state.value,
                        onRuntimeModeToggle = { _, _ -> },
                        onOpenRelaySettings = {},
                        onOpenDnsSettings = {},
                        onPasteServerLink = {},
                        onScanServer = {},
                        modifier = Modifier.requiredSize(420.dp, 2400.dp),
                    )
                }
            }
        }
        addresses.forEach { address ->
            composeRule.runOnIdle {
                state.value =
                    state.value.copy(
                        draft =
                            ConfigDraft(
                                relayEnabled = true,
                                relayKind = "vless_reality",
                                relayServer = address,
                                relayServerPort = "443",
                            ),
                    )
            }
            assertEndpoint(address)
            if (address == addresses.first()) captureEndpoint(RipDpiTestTags.ConfigVpnRelay, "vpn-relay-ipv6")
        }
        assertDirection(resource(R.string.config_relay_server), ResolvedTextDirection.Rtl)
        assertDirection(resource(R.string.config_vpn_relay_server_summary), ResolvedTextDirection.Rtl)
        composeRule.runOnIdle { state.value = state.value.copy(draft = ConfigDraft(relayEnabled = false)) }
        assertRelayFallbackDirection(resource(R.string.home_mode_card_remote_relay_disabled))
        composeRule.runOnIdle {
            state.value =
                state.value.copy(draft = ConfigDraft(relayEnabled = true, relayKind = "vless_reality"))
        }
        assertRelayFallbackDirection(resource(R.string.home_mode_card_remote_server_unknown))
    }

    private fun assertEndpoint(address: String) {
        val raw = RuntimeEnvironment.getApplication().getString(R.string.proxy_address, address, "443")
        composeRule.onNodeWithText(raw, useUnmergedTree = true).assertTextEquals(raw)
        val result = layout(raw)
        assertEquals(raw, result.layoutInput.text.text)
        assertEquals("Paragraph direction for $raw", ResolvedTextDirection.Ltr, result.getParagraphDirection(0))
        val firstDigit = raw.indexOfFirst(Char::isDigit)
        val lastDigit = raw.indexOfLast(Char::isDigit)
        assertTrue(
            "Numeric address visual order for $raw",
            result.getBoundingBox(firstDigit).left < result.getBoundingBox(lastDigit).left,
        )
    }

    private fun assertDirection(
        text: String,
        direction: ResolvedTextDirection,
    ) {
        assertEquals("Paragraph direction for $text", direction, layout(text).getParagraphDirection(0))
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertRelayFallbackDirection(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNode(
                hasText(text) and hasAnyAncestor(hasTestTag(RipDpiTestTags.ConfigVpnRelay)),
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(
            text,
            results
                .single()
                .layoutInput.text.text,
        )
        assertEquals(ResolvedTextDirection.Rtl, results.single().getParagraphDirection(0))
    }

    private fun captureEndpoint(
        tag: String,
        name: String,
    ) {
        val output = File("build/reports/localization-ar/$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use {
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun resource(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val addresses = listOf("2001:4860:4860::8888", "2001:db8::1", "::1", "192.0.2.1", "[2001:4860:4860::8888]")
}
