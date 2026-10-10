package com.poyka.ripdpi.ui.screens.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteState
import com.poyka.ripdpi.activities.DiagnosticsDpiSuiteToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScreenUiState
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.SensitiveProfileConsentDialogState
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.ui.screens.detection.HistoryCard
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRankedRow
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRunState
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerScreen
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w320dp-h1000dp-mdpi")
class DiagnosticsQualityRegressionTest {
    @get:Rule val composeRule = createComposeRule()
    private val profile = DiagnosticsProfileOptionUiModel("web", "Проверка веб-соединения", "bundled")

    @Test
    fun `busy scan disables the profile picker`() {
        composeRule.setContent {
            Viewport(scroll = false) { Scan(DiagnosticsScanUiModel(selectedProfile = profile, isBusy = true)) }
        }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).assertIsNotEnabled()
    }

    @Test
    fun `scan start closes an open profile picker without selection`() {
        val scan =
            mutableStateOf(DiagnosticsScanUiModel(selectedProfile = profile, profiles = persistentListOf(profile)))
        val selected = mutableListOf<String>()
        composeRule.setContent { Viewport(scroll = false) { Scan(scan.value, onSelect = { selected += it }) } }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertExists()

        composeRule.runOnIdle { scan.value = scan.value.copy(isBusy = true) }

        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertDoesNotExist()
        assertEquals(emptyList<String>(), selected)
        composeRule.runOnIdle { scan.value = scan.value.copy(isBusy = false) }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertDoesNotExist()
    }

    @Test
    fun `sensitive consent has one dismiss control and separate confirm control`() {
        var dismissed = 0
        var confirmed = 0
        composeRule.setContent {
            RipDpiTheme {
                DiagnosticsScreen(
                    uiState =
                        DiagnosticsScreenUiState(
                            scan =
                                DiagnosticsScanUiModel(
                                    sensitiveProfileConsentDialog =
                                        SensitiveProfileConsentDialogState(
                                            "web",
                                            profile.name,
                                            ScanPathMode.RAW_PATH,
                                            ScanKind.CONNECTIVITY,
                                            false,
                                        ),
                                ),
                        ),
                    pagerState = rememberPagerState { DiagnosticsSection.entries.size },
                    actions =
                        DiagnosticsScreenActions(
                            onDismissSensitiveProfileConsentDialog = { dismissed++ },
                            onConfirmSensitiveProfileRun = { confirmed++ },
                        ),
                )
            }
        }

        composeRule
            .onAllNodesWithTag(
                RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss,
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsSensitiveProfileConsentDismiss).performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsSensitiveProfileConsentConfirm).performClick()
        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    @Test
    fun `different network history does not claim an improvement or degradation`() {
        composeRule.setContent {
            Viewport {
                HistoryCard(
                    listOf(
                        DetectionHistoryEntry("new-network", "Новая сеть", 2000L, "NOT_DETECTED", 70, 2),
                        DetectionHistoryEntry("old-network", "Старая сеть", 1000L, "DETECTED", 40, 2),
                    ),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription(text(R.string.detection_score_improved)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(text(R.string.detection_score_degraded)).assertCountEquals(0)
        composeRule.onNodeWithText("Новая сеть").assertIsDisplayed()
        composeRule.onNodeWithText("Старая сеть").assertIsDisplayed()
        composeRule.onNodeWithText("70").assertIsDisplayed()
        composeRule.onNodeWithText("40").assertIsDisplayed()
    }

    @Test
    fun `tool states use the current locale`() {
        composeRule.setContent {
            Viewport {
                DpiProbeSuiteCard(
                    tool = DiagnosticsDpiSuiteToolUiModel(state = DiagnosticsDpiSuiteState.Cancelled),
                    onProbeEnabledChange = { _, _ -> },
                    onCustomDomainsChange = {},
                    onConcurrencyDelta = {},
                    onRun = {},
                    onCancel = {},
                )
            }
        }

        composeRule
            .onNodeWithText(text(R.string.diagnostics_tool_state_cancelled), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `running tuner disables domain editing`() {
        composeRule.setContent {
            Viewport(scroll = false) {
                StrategyTunerScreen(
                    state = StrategyTunerUiState(runState = StrategyTunerRunState.Running, domainsText = "one.example"),
                    onBack = {},
                    onDomainsChanged = {},
                    onRun = {},
                    onCancel = {},
                    onApply = {},
                )
            }
        }

        composeRule.onNodeWithText("one.example").assertIsNotEnabled()
    }

    @Test
    fun `tuner ranking keeps the strategy name readable at large RTL text`() {
        val label = "Разделение записей TLS для выбранных сайтов"
        composeRule.setContent {
            Viewport(fontScale = 2f, direction = LayoutDirection.Rtl) {
                StrategyTunerRankedRow(
                    result = RankedStrategyProbeResult("tls", label, 3, 3, 1.0, 120, 0),
                    isBest = true,
                    isApplied = false,
                    applyEnabled = true,
                    onApply = {},
                )
            }
        }

        capture("tuner-ranking-rtl-font2")
        assertReadable(label, maxLines = 4)
        assertReadable(text(R.string.strategy_tuner_apply))
        assertReadable(text(R.string.strategy_tuner_best_badge))
    }

    @Test
    fun `scan progress header remains readable at large RTL text`() {
        composeRule.setContent {
            Viewport(fontScale = 2f, direction = LayoutDirection.Rtl) {
                ScanProgressCard(
                    progress =
                        DiagnosticsProgressUiModel(
                            "tcp",
                            "Проверка сети",
                            3,
                            12,
                            0.25f,
                            ScanKind.STRATEGY_PROBE,
                            false,
                            1_000L,
                            persistentListOf(),
                            "Проверка соединения с выбранным сайтом",
                        ),
                    strategyProbeSelected = true,
                )
            }
        }

        capture("scan-progress-rtl-font2")
        assertReadable(text(R.string.diagnostics_probe_progress_title))
        assertReadable("1h 00m", singleLine = true)
    }

    @Test
    fun `profile change control remains readable at large RTL text`() {
        composeRule.setContent {
            Viewport(fontScale = 2f, direction = LayoutDirection.Rtl) {
                CompactProfileRow(profile = profile, onChangeProfile = {})
            }
        }

        capture("scan-profile-rtl-font2")
        assertReadable(profile.name)
        assertReadable(text(R.string.diagnostics_profile_change_action))
    }

    @Test
    fun `diagnostics controls render in dark theme`() {
        composeRule.setContent {
            Viewport(theme = "dark") {
                CompactProfileRow(profile = profile, onChangeProfile = {})
                StrategyTunerRankedRow(
                    result = RankedStrategyProbeResult("tls", "Разделение TLS", 3, 3, 1.0, 120, 0),
                    isBest = true,
                    isApplied = false,
                    applyEnabled = true,
                    onApply = {},
                )
            }
        }

        capture("diagnostics-controls-dark")
        assertReadable(profile.name)
        assertReadable(text(R.string.strategy_tuner_apply))
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h1000dp-mdpi")
    fun `Arabic tuner ranking stays readable at large RTL text`() {
        val label = "تقسيم سجلات TLS للمواقع المحددة"
        composeRule.setContent {
            Viewport(fontScale = 2f, direction = LayoutDirection.Rtl) {
                StrategyTunerRankedRow(
                    result = RankedStrategyProbeResult("tls", label, 3, 3, 1.0, 120, 0),
                    isBest = true,
                    isApplied = false,
                    applyEnabled = true,
                    onApply = {},
                )
            }
        }

        capture("tuner-ranking-arabic-font2")
        assertReadable(label, maxLines = 4)
        assertReadable(text(R.string.strategy_tuner_apply))
        assertReadable(text(R.string.strategy_tuner_best_badge))
    }

    @Composable
    private fun Viewport(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        scroll: Boolean = true,
        theme: String = "light",
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalLayoutDirection provides direction,
            LocalInspectionMode provides true,
            LocalScanClockMs provides 3_601_000L,
        ) {
            RipDpiTheme(themePreference = theme) {
                Box(Modifier.requiredSize(320.dp, 1000.dp).background(RipDpiThemeTokens.colors.background)) {
                    if (scroll) {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
    }

    @Composable
    private fun Scan(
        scan: DiagnosticsScanUiModel,
        onSelect: (String) -> Unit = {},
    ) {
        ScanSection(
            scan = scan,
            onSelectProfile = onSelect,
            onRunRawScan = {},
            onRunInPathScan = {},
            onCancelScan = {},
            onOpenAdvancedSettings = {},
            onOpenDnsSettings = {},
            onRequestVpnPermission = {},
            onSelectStrategyProbeCandidate = {},
            onSelectProbe = {},
            onOpenHistory = {},
            onOpenModeEditor = {},
            onOpenOwnedStackBrowser = {},
        )
    }

    private fun assertReadable(
        label: String,
        singleLine: Boolean = false,
        maxLines: Int? = null,
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
        node
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        if (singleLine) assertEquals("Line count for $label", 1, layout.lineCount)
        maxLines?.let { assertTrue("Too many lines for $label", layout.lineCount <= it) }
        val lines =
            (0 until layout.lineCount).map { line ->
                Rect(
                    layout.getLineLeft(line),
                    layout.getLineTop(line),
                    layout.getLineRight(line),
                    layout.getLineBottom(line),
                )
            }
        // Compose can retain the parent-width paragraph after it measures a smaller Text.
        // Check the line-content region in the measured Text coordinate space.
        val contentLeft = lines.minOf(Rect::left)
        val contentTop = lines.minOf(Rect::top)
        lines.forEach { line ->
            assertTrue(
                "Horizontal content clipped for $label",
                (line.right - contentLeft).roundToInt() <= layout.size.width,
            )
            assertTrue(
                "Vertical content clipped for $label",
                (line.bottom - contentTop).roundToInt() <= layout.size.height,
            )
        }
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Text outside viewport for $label", layout.size.height <= visible.height.roundToInt())
        assertTrue("Text outside viewport for $label", layout.size.width <= visible.width.roundToInt())
        assertFalse("Ellipsis for $label", (0 until layout.lineCount).any(layout::isLineEllipsized))
    }

    private fun text(resource: Int) = RuntimeEnvironment.getApplication().getString(resource)

    private fun capture(name: String) {
        val image = File("build/ui-quality/diagnostics/$name.png")
        requireNotNull(image.parentFile).mkdirs()
        image.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
