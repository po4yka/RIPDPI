package com.poyka.ripdpi.ui.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.data.HostPackCatalog
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.ui.screens.config.RelayFinalmaskActions
import com.poyka.ripdpi.ui.screens.config.RelayFinalmaskFields
import com.poyka.ripdpi.ui.screens.dns.DnsModeSection
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthScreen
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthUiState
import com.poyka.ripdpi.ui.screens.health.DnsCountersUiState
import com.poyka.ripdpi.ui.screens.home.HomePauseControls
import com.poyka.ripdpi.ui.screens.settings.FakeOrderingProfileCard
import com.poyka.ripdpi.ui.screens.settings.hostsSection
import com.poyka.ripdpi.ui.screens.settings.rememberAdvancedSettingsContentState
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Match the existing native-render harness: pinned Espresso input reflection fails on API 37.
@Config(sdk = [35], qualifiers = "pt-rBR-w420dp-h1100dp-mdpi")
class PortugueseLocalizationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `cache misses and failures have distinct readable labels`() = checkHealth(420, 1f, "ptbr-health")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `cache miss label fits large text`() = checkHealth(360, 1.5f, "ptbr-health-large")

    @Test
    fun `system DNS explanation and warning are readable`() = checkDns(420, 1f, "ptbr-dns")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `system DNS explanation and warning fit large text`() = checkDns(360, 1.5f, "ptbr-dns-large")

    @Test
    fun `clock recovery names the visible resume action`() = checkPause(420, 1f, "ptbr-clock-recovery")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `clock recovery fits large text`() = checkPause(360, 1.5f, "ptbr-clock-recovery-large")

    @Test
    fun `host pack semantics preserve the full entry type`() = checkHosts(420, 1f, "ptbr-hosts")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `host pack semantics fit large text`() = checkHosts(360, 1.5f, "ptbr-hosts-large")

    @Test
    fun `Noise prelude explanation is readable`() = checkNoise(420, 1f, "ptbr-noise")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `Noise prelude explanation fits large text`() = checkNoise(360, 1.5f, "ptbr-noise-large")

    @Test
    fun `fake sequence help names the visible options`() = checkOrdering(420, 1f, "ptbr-ordering")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h1100dp-mdpi")
    fun `fake sequence help fits large text`() = checkOrdering(360, 1.5f, "ptbr-ordering-large")

    private fun checkHealth(
        width: Int,
        scale: Float,
        name: String,
    ) {
        content(width, scale) {
            ConnectionHealthScreen(
                uiState =
                    ConnectionHealthUiState(
                        rows = persistentListOf(),
                        dnsCounters =
                            DnsCountersUiState(
                                queriesTotal = 12,
                                cacheHits = 8,
                                cacheMisses = 4,
                                failuresTotal = 0,
                            ),
                    ),
                onBack = {},
            )
        }
        readable(R.string.connection_health_dns_cache_misses, uppercase = true)
        readable(R.string.connection_health_dns_failures, uppercase = true)
        capture(name)
    }

    private fun checkDns(
        width: Int,
        scale: Float,
        name: String,
    ) {
        content(width, scale) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DnsModeSection(
                    uiState =
                        SettingsUiState(
                            dns = DnsUiState(systemPrivateDnsStatus = SystemPrivateDnsStatus.PRESENT.wireValue),
                        ),
                    onModeSelected = {},
                )
            }
        }
        readable(R.string.dns_private_dns_info_body)
        capture("$name-help")
        readable(R.string.dns_private_dns_warning_title)
        readable(R.string.dns_private_dns_warning_body)
        capture("$name-warning")
    }

    private fun checkPause(
        width: Int,
        scale: Float,
        name: String,
    ) {
        var resumed = false
        content(width, scale) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomePauseControls(
                    state = HomePauseUiState(phase = PausePhase.Deferred, failure = PauseFailure.ClockChanged),
                    onPause = {},
                    onResume = { resumed = true },
                    onStop = {},
                )
            }
        }
        val action = portuguese(R.string.pause_resume_now)
        assertTrue(portuguese(R.string.pause_clock_failed).contains(action))
        readable(R.string.pause_clock_failed)
        capture(name)
        composeRule
            .onNodeWithText(action)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(resumed)
    }

    private fun checkHosts(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val preset = HostPackPreset("youtube", "YouTube", "Video hosts", 1, listOf("youtube.com"))
        val state = SettingsUiState()
        content(width, scale) {
            val options = rememberAdvancedSettingsContentState(state)
            LazyColumn {
                hostsSection(
                    uiState = state,
                    hostPackCatalog =
                        HostPackCatalogUiState(
                            snapshot = HostPackCatalogSnapshot(catalog = HostPackCatalog(packs = listOf(preset))),
                        ),
                    visualEditorEnabled = true,
                    hostPackApplyControlsEnabled = true,
                    hostsOptions = options.hostsOptions,
                    pendingHostPack = null,
                    onPresetSelected = {},
                    onRefreshHostPackCatalog = {},
                    onOptionSelected = { _, _ -> },
                    onTextConfirmed = { _, _ -> },
                )
            }
        }
        assertTrue(portuguese(R.string.host_pack_semantics_note).contains("full:"))
        readable(R.string.host_pack_semantics_note)
        capture(name)
    }

    private fun checkNoise(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val draft = ConfigDraft(relayFinalmaskType = RelayFinalmaskTypeNoise, relayFinalmaskRandRange = "4-4")
        content(width, scale) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RelayFinalmaskFields(draft, ConfigUiState(draft = draft), RelayFinalmaskActions())
            }
        }
        readable(R.string.config_relay_finalmask_noise_help)
        capture(name)
    }

    private fun checkOrdering(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val state =
            SettingsUiState(
                desync =
                    DesyncCoreUiState(
                        tcpChainSteps = persistentListOf(TcpChainStepModel(TcpChainStepKind.Fake, "1")),
                    ),
            )
        content(width, scale) {
            val options = rememberAdvancedSettingsContentState(state)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FakeOrderingProfileCard(state, true, options.fakeOrderOptions, options.fakeSeqModeOptions, { _, _ -> })
            }
        }
        readable(R.string.fake_order_card_seqmode_body)
        capture(name)
    }

    private fun content(
        width: Int,
        scale: Float,
        screen: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 1100.dp).ripDpiTestTag(ViewportTag)) { screen() }
                }
            }
        }
    }

    private fun readable(
        resource: Int,
        uppercase: Boolean = false,
    ) {
        val value = portuguese(resource).let { if (uppercase) it.uppercase(Locale.forLanguageTag("pt-BR")) else it }
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(value, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed().performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        assertTrue("No layout result for resource $resource", layouts.isNotEmpty())
        val viewport = composeRule.onNodeWithTag(ViewportTag).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Portuguese text is outside the viewport: $value",
            bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top &&
                bounds.bottom <= viewport.bottom,
        )
        layouts.forEach { layout ->
            val diagnostic = "Clipped Portuguese text: ${layout.layoutInput.text}; size=${layout.size}; bounds=$bounds"
            assertFalse(diagnostic, layout.didOverflowHeight)
            assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
            // A wrap-content Text can retain unused paragraph width. Check rendered line bounds.
            if (layout.multiParagraph.width <= layout.size.width + 1f) assertFalse(diagnostic, layout.hasVisualOverflow)
            repeat(layout.lineCount) { line ->
                assertFalse(diagnostic, layout.isLineEllipsized(line))
                assertTrue(diagnostic, layout.getLineLeft(line) >= -1f)
                assertTrue(diagnostic, layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue(diagnostic, layout.getLineTop(line) >= -1f)
                assertTrue(diagnostic, layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertTrue(diagnostic, layout.size.width <= bounds.width + 1f && layout.size.height <= bounds.height + 1f)
        }
    }

    private fun portuguese(resource: Int): String {
        val app = RuntimeEnvironment.getApplication()
        val english =
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                },
            )
        return app.getString(resource).also { assertNotEquals(english.getString(resource), it) }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(
                        ViewportTag,
                    ).captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}

private const val ViewportTag = "portuguese-localization-viewport"
