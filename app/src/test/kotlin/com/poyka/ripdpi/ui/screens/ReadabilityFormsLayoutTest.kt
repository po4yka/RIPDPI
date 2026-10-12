package com.poyka.ripdpi.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.ui.screens.config.CloudflareTunnelRelayFields
import com.poyka.ripdpi.ui.screens.config.MasqueAuthModeRow
import com.poyka.ripdpi.ui.screens.config.MasqueCloudflareMtlsFields
import com.poyka.ripdpi.ui.screens.config.ModeEditorRelaySection
import com.poyka.ripdpi.ui.screens.config.NoOpModeEditorActions
import com.poyka.ripdpi.ui.screens.config.RelayMasqueActions
import com.poyka.ripdpi.ui.screens.config.RelayTuicActions
import com.poyka.ripdpi.ui.screens.config.RelayVlessActions
import com.poyka.ripdpi.ui.screens.config.TuicRelayFields
import com.poyka.ripdpi.ui.screens.config.VlessTransportFields
import com.poyka.ripdpi.ui.screens.customization.AboutPackageRow
import com.poyka.ripdpi.ui.screens.dns.DnsOptionCard
import com.poyka.ripdpi.ui.screens.dns.DnsResolverCard
import com.poyka.ripdpi.ui.screens.dns.DnsResolverOption
import com.poyka.ripdpi.ui.screens.routes.RuleEditorPersistenceActions
import com.poyka.ripdpi.ui.screens.routes.RuleEditorScreen
import com.poyka.ripdpi.ui.screens.routes.RuleEditorUiState
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworkCard
import com.poyka.ripdpi.ui.screens.settings.rememberedNetworksPreviewEntries
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerRow
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerStatus
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionServerUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h1800dp")
class ReadabilityFormsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun masqueAuthenticationLabelsStayCompleteAndSelectionCallsOnce() {
        var changes = 0
        assertMatrix(
            labels =
                listOf(
                    R.string.config_relay_masque_auth_bearer,
                    R.string.config_relay_masque_auth_preshared,
                    R.string.config_relay_masque_auth_cloudflare_direct,
                    R.string.config_relay_masque_auth_privacy_pass,
                ),
        ) {
            MasqueAuthModeRow(
                ConfigDraft(relayMasqueAuthMode = RelayMasqueAuthModeCloudflareMtls),
                ConfigUiState(supportsMasquePrivacyPass = true),
                RelayMasqueActions(onRelayMasqueAuthModeChanged = { changes++ }),
            )
        }
        composeRule
            .onNodeWithContentDescription(text(R.string.config_relay_masque_auth_cloudflare_direct))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, changes) }
    }

    @Test
    fun masqueImportActionLabelsStayCompleteAndKeepDistinctCallbacks() {
        var certificateClicks = 0
        var keyClicks = 0
        var bundleClicks = 0
        val labels =
            listOf(
                R.string.config_relay_masque_import_certificate_chain,
                R.string.config_relay_masque_import_private_key,
                R.string.config_relay_masque_import_pkcs12,
            )
        assertMatrix(labels = labels) {
            MasqueCloudflareMtlsFields(
                ConfigDraft(),
                RelayMasqueActions(
                    onRelayMasqueImportCertificateChainClicked = { certificateClicks++ },
                    onRelayMasqueImportPrivateKeyClicked = { keyClicks++ },
                    onRelayMasqueImportPkcs12Clicked = { bundleClicks++ },
                ),
            )
        }
        labels.forEach { composeRule.onNodeWithText(text(it)).performScrollTo().performClick() }
        composeRule.runOnIdle {
            assertEquals(1, certificateClicks)
            assertEquals(1, keyClicks)
            assertEquals(1, bundleClicks)
        }
    }

    @Test
    fun vlessAndXhttpOptionsWrapByContentAtNarrowWidths() {
        assertMatrix(
            labels =
                listOf(
                    R.string.config_relay_vless_transport_reality_tcp,
                    R.string.config_relay_vless_transport_xhttp,
                    R.string.config_relay_xhttp_mode_auto,
                    R.string.config_relay_xhttp_mode_stream_up,
                    R.string.config_relay_xhttp_mode_stream_one,
                ),
        ) {
            VlessTransportFields(ConfigDraft(relayVlessTransport = RelayVlessTransportXhttp), RelayVlessActions())
        }
    }

    @Test
    fun cloudflareTunnelChoicesStayComplete() {
        assertMatrix(
            labels =
                listOf(
                    R.string.relay_cloudflare_mode_consume_existing,
                    R.string.relay_cloudflare_mode_publish_local,
                ),
        ) {
            CloudflareTunnelRelayFields(
                ConfigDraft(relayKind = RelayKindCloudflareTunnel),
                ConfigUiState(),
                RelayVlessActions(),
            )
        }
    }

    @Test
    fun tuicCongestionChoicesStayCompleteInRtl() {
        assertMatrix(
            labels = listOf(R.string.config_relay_tuic_congestion_bbr, R.string.config_relay_tuic_congestion_cubic),
            direction = LayoutDirection.Rtl,
        ) {
            TuicRelayFields(ConfigDraft(), RelayTuicActions())
        }
    }

    @Test
    fun relayProtocolOptionsKeepFullLabelsAndEditingLock() {
        assertMatrix(labels = listOf(R.string.config_relay_kind_masque)) {
            ModeEditorRelaySection(
                ConfigDraft(relayEnabled = true, relayKind = RelayKindMasque, editingRelayProfileId = "saved"),
                ConfigUiState(),
                NoOpModeEditorActions,
            )
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.modeEditorRelayChip(RelayKindMasque))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun selectedDnsCardDoesNotSqueezeTitleAndBadges() {
        var clicks = 0
        val title = "Настройки зашифрованного DNS"
        val body = "Разрешение адресов и резервный сервер без передачи истории"
        val badges = listOf("Зашифрованный DNS", "HTTPS", "Резервный сервер")
        assertMatrix(strings = listOf(title, body) + badges + text(R.string.dns_selected_badge)) {
            DnsOptionCard(RipDpiIcons.Lock, title, body, true, badges, { clicks++ }, "dns-option")
        }
        composeRule
            .onNodeWithTag(
                "dns-option",
            ).performScrollTo()
            .assertIsSelected()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun selectedResolverRetainsFullTextInRtl() {
        val title = "خادم أسماء النطاقات المشفر"
        val description = "وضع تحليل العناوين والخادم الاحتياطي"
        assertMatrix(
            strings = listOf(title, description, "1.1.1.1", "DoH", text(R.string.dns_selected_badge)),
            direction = LayoutDirection.Rtl,
        ) {
            DnsResolverCard(
                DnsResolverOption(
                    "fixture",
                    EncryptedDnsProtocolDoh,
                    "1.1.1.1",
                    "dns.example",
                    443,
                    "dns.example",
                    "https://dns.example/dns-query",
                    persistentListOf("1.1.1.1"),
                    title = title,
                    description = description,
                ),
                true,
                {},
            )
        }
    }

    @Test
    fun subscriptionStatusDoesNotStarveServerName() {
        val name = "Резервный сервер для зашифрованного соединения"
        assertMatrix(strings = listOf(name, text(R.string.subscription_failover_status_checking))) {
            SubscriptionServerRow(
                SubscriptionServerUiState(
                    "fixture",
                    name,
                    "relay.example:443",
                    SubscriptionServerStatus.Checking,
                    "Сервер 2 из 4",
                    "Ожидание проверки соединения",
                ),
            )
        }
        val nameBounds = composeRule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val statusBounds =
            composeRule
                .onNodeWithText(text(R.string.subscription_failover_status_checking), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(statusBounds.top >= nameBounds.bottom)
    }

    @Test
    fun rememberedNetworkOutcomesAndDateRemainComplete() {
        val entry = rememberedNetworksPreviewEntries().first().copy(updatedLabel = "12 октября 2026 года, 13:06")
        assertMatrix(
            strings =
                listOf(
                    text(R.string.remembered_networks_outcomes, entry.successCount, entry.failureCount),
                    text(R.string.remembered_networks_updated, entry.updatedLabel),
                ),
        ) {
            RememberedNetworkCard(entry, {})
        }
    }

    @Test
    fun packageIdentifierHasNoLineCapAtLargeFont() {
        assertMatrix(strings = listOf(BuildConfig.APPLICATION_ID.replace(".", ".\u200B"))) {
            AboutPackageRow()
        }
    }

    @Test
    fun ruleEnabledSwitchRemainsVisibleAndCallsOnceInRtl() {
        var width by mutableStateOf(320.dp)
        var scale by mutableStateOf(1f)
        var changes = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width).height(1200.dp)) {
                        RuleEditorScreen(
                            state = RuleEditorUiState(loaded = true),
                            onBack = {},
                            onNameChange = {},
                            onEnabledChange = { changes++ },
                            onDomainsChange = {},
                            onIpCidrsChange = {},
                            onPortsChange = {},
                            onSourcePortsChange = {},
                            onNetworkChange = {},
                            onProcessNameChange = {},
                            onPackagesChange = {},
                            onOutboundChange = {},
                            persistenceActions = RuleEditorPersistenceActions(save = {}),
                        )
                    }
                }
            }
        }
        listOf(320.dp, 420.dp).forEach { testWidth ->
            listOf(1f, 2f).forEach { testScale ->
                composeRule.runOnIdle {
                    width = testWidth
                    scale = testScale
                }
                composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("rule_editor_identity")
                val label = composeRule.onNodeWithText(text(R.string.rule_editor_enabled), useUnmergedTree = true)
                label.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(layouts.single().hasVisualOverflow)
                composeRule
                    .onNodeWithContentDescription(
                        text(R.string.rule_editor_enabled),
                    ).assertIsDisplayed()
                    .performClick()
                composeRule.onNode(hasScrollToIndexAction()).performScrollToKey("rule_editor_actions")
                listOf(R.string.rule_editor_cancel, R.string.rule_editor_save).forEach { labelRes ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    composeRule
                        .onNodeWithText(text(labelRes), useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    assertFalse(layout.hasVisualOverflow)
                    assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
                    assertTrue(layout.size.width + 1f >= layout.multiParagraph.intrinsics.minIntrinsicWidth)
                }
            }
        }
        composeRule.runOnIdle { assertEquals(4, changes) }
    }

    private fun assertMatrix(
        labels: List<Int> = emptyList(),
        strings: List<String> = labels.map { text(it) },
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        var width by mutableStateOf(320.dp)
        var scale by mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        }
        listOf(320.dp, 420.dp).forEach { testWidth ->
            listOf(1f, 2f).forEach { testScale ->
                composeRule.runOnIdle {
                    width = testWidth
                    scale = testScale
                }
                strings.forEach { label ->
                    val nodes = composeRule.onAllNodesWithText(label, useUnmergedTree = true)
                    val count = nodes.fetchSemanticsNodes().size
                    assertTrue("'$label' is missing", count > 0)
                    repeat(count) { index ->
                        val node = nodes[index].performScrollTo()
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        val layout = layouts.single()
                        assertFalse("'$label' overflows at $testWidth, font $testScale", layout.hasVisualOverflow)
                        assertTrue("'$label' is truncated", (0 until layout.lineCount).none(layout::isLineEllipsized))
                        assertTrue(
                            "'$label' is squeezed below its longest word at $testWidth, font $testScale",
                            layout.size.width + 1f >= layout.multiParagraph.intrinsics.minIntrinsicWidth,
                        )
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        assertTrue("'$label' has no text width", bounds.width >= 24f)
                    }
                }
            }
        }
    }

    private fun text(
        id: Int,
        vararg arguments: Any,
    ): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *arguments)
}
