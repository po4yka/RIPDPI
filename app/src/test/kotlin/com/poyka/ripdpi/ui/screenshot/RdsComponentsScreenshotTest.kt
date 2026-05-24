package com.poyka.ripdpi.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.chrome.RipDpiSectionHeader
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffLine
import com.poyka.ripdpi.ui.components.feedback.RipDpiDiffViewer
import com.poyka.ripdpi.ui.components.feedback.RipDpiJsonNode
import com.poyka.ripdpi.ui.components.feedback.RipDpiJsonTree
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogEntry
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogLevel
import com.poyka.ripdpi.ui.components.feedback.RipDpiLogStream
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltip
import com.poyka.ripdpi.ui.components.feedback.RipDpiTooltipRich
import com.poyka.ripdpi.ui.components.indicators.RipDpiActuatorStatesGallery
import com.poyka.ripdpi.ui.components.indicators.RipDpiBrandBadge
import com.poyka.ripdpi.ui.components.indicators.RipDpiBrandBadgeSize
import com.poyka.ripdpi.ui.components.indicators.RipDpiHeartbeatIndicator
import com.poyka.ripdpi.ui.components.indicators.RipDpiHeartbeatState
import com.poyka.ripdpi.ui.components.indicators.RipDpiKbdShortcut
import com.poyka.ripdpi.ui.components.indicators.RipDpiLiveCounter
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.indicators.RipDpiSkeletonBox
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleDataBadge
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleTier
import com.poyka.ripdpi.ui.components.inputs.RipDpiCidrInput
import com.poyka.ripdpi.ui.components.inputs.RipDpiCidrValue
import com.poyka.ripdpi.ui.components.inputs.RipDpiCombobox
import com.poyka.ripdpi.ui.components.inputs.RipDpiFilter
import com.poyka.ripdpi.ui.components.inputs.RipDpiFilterBar
import com.poyka.ripdpi.ui.components.inputs.RipDpiSegmentedButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiSlider
import com.poyka.ripdpi.ui.components.inputs.RipDpiStepper
import com.poyka.ripdpi.ui.components.inputs.RipDpiTab
import com.poyka.ripdpi.ui.components.inputs.RipDpiTabs
import com.poyka.ripdpi.ui.components.inputs.RipDpiToggleAlternatives
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val BlessPending =
    "Pending RIPDPI_BLESS_GOLDENS=1 by user per .claude/rules/golden-bless-discipline.md"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RdsComponentsScreenshotTest {
    private fun captureBothThemes(
        @Suppress("UNUSED_PARAMETER") name: String,
        widthDp: Int = 360,
        heightDp: Int = 200,
        content: @Composable () -> Unit,
    ) {
        listOf("light", "dark").forEach { theme ->
            captureRipDpiScreenshot(widthDp = widthDp, heightDp = heightDp) {
                RipDpiTheme(themePreference = theme) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(RipDpiThemeTokens.colors.background)
                                .padding(16.dp),
                    ) { content() }
                }
            }
            // The Roborazzi runner derives the golden file name from the test method
            // name (NAME_$theme captures both passes inside one @Test).
        }
    }

    // === 25 tests follow ===

    @Test
    @Ignore(BlessPending)
    fun brandBadgeAllSizes() {
        captureBothThemes("brandBadgeAllSizes", widthDp = 360, heightDp = 120) {
            RipDpiBrandBadge(size = RipDpiBrandBadgeSize.AppBarCompact)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun kbdShortcut() {
        captureBothThemes("kbdShortcut", widthDp = 360, heightDp = 120) {
            RipDpiKbdShortcut(keys = listOf("⌘", "K"))
        }
    }

    @Test
    @Ignore(BlessPending)
    fun sectionHeader() {
        captureBothThemes("sectionHeader", widthDp = 360, heightDp = 120) {
            RipDpiSectionHeader(title = "Connection")
        }
    }

    @Test
    @Ignore(BlessPending)
    fun staleDataBadge() {
        captureBothThemes("staleDataBadge", widthDp = 360, heightDp = 120) {
            RipDpiStaleDataBadge(label = "14 s ago", tier = RipDpiStaleTier.Recent)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun liveCounter() {
        captureBothThemes("liveCounter", widthDp = 360, heightDp = 120) {
            RipDpiLiveCounter(value = 1234, suffix = " ms")
        }
    }

    @Test
    @Ignore(BlessPending)
    fun spinner() {
        captureBothThemes("spinner", widthDp = 360, heightDp = 120) {
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun progressBar() {
        captureBothThemes("progressBar", widthDp = 360, heightDp = 120) {
            RipDpiProgressBar(progress = 0.6f)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun skeletonBox() {
        captureBothThemes("skeletonBox", widthDp = 360, heightDp = 120) {
            RipDpiSkeletonBox(height = 14.dp)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun tabs() {
        captureBothThemes("tabs", widthDp = 360, heightDp = 120) {
            RipDpiTabs(
                tabs = listOf(RipDpiTab("a", "Home"), RipDpiTab("b", "Logs")),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun segmentedButton() {
        captureBothThemes("segmentedButton", widthDp = 360, heightDp = 120) {
            RipDpiSegmentedButton(
                options = listOf("Auto", "Manual"),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun slider() {
        captureBothThemes("slider", widthDp = 360, heightDp = 120) {
            RipDpiSlider(value = 0.5f, onValueChange = {})
        }
    }

    @Test
    @Ignore(BlessPending)
    fun stepper() {
        captureBothThemes("stepper", widthDp = 360, heightDp = 120) {
            RipDpiStepper(value = 3, onValueChange = {}, valueRange = 0..10)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun toggleAlternatives() {
        captureBothThemes("toggleAlternatives", widthDp = 360, heightDp = 120) {
            RipDpiToggleAlternatives(selectedIndex = 0, onSelect = {})
        }
    }

    @Test
    @Ignore(BlessPending)
    fun tooltip() {
        captureBothThemes("tooltip", widthDp = 360, heightDp = 120) {
            RipDpiTooltip(text = "Reconnect tunnel") { Text("Reconnect") }
        }
    }

    @Test
    @Ignore(BlessPending)
    fun accordion() {
        captureBothThemes("accordion", widthDp = 360, heightDp = 200) {
            RipDpiAccordion(
                title = "Advanced",
                expanded = true,
                onExpandedChange = {},
            ) { Text("Inside content") }
        }
    }

    @Test
    @Ignore(BlessPending)
    fun filterBar() {
        captureBothThemes("filterBar", widthDp = 360, heightDp = 120) {
            RipDpiFilterBar(
                filters = listOf(RipDpiFilter("a", "All"), RipDpiFilter("b", "Errors")),
                selectedKeys = setOf("b"),
                onToggle = {},
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun heartbeatIndicator() {
        captureBothThemes("heartbeatIndicator", widthDp = 360, heightDp = 120) {
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Healthy)
        }
    }

    @Test
    @Ignore(BlessPending)
    fun actuatorStatesGallery() {
        captureBothThemes("actuatorStatesGallery", widthDp = 360, heightDp = 1000) {
            RipDpiActuatorStatesGallery()
        }
    }

    @Test
    @Ignore(BlessPending)
    fun cidrInput() {
        captureBothThemes("cidrInput", widthDp = 360, heightDp = 120) {
            RipDpiCidrInput(value = RipDpiCidrValue("10.0.0.0", 8), onValueChange = {})
        }
    }

    @Test
    @Ignore(BlessPending)
    fun commandPalettePlaceholder() {
        captureBothThemes("commandPalettePlaceholder", widthDp = 360, heightDp = 120) {
            Text(
                "Command palette is a modal; capture inside Dialog requires runtime context.",
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun combobox() {
        captureBothThemes("combobox", widthDp = 360, heightDp = 120) {
            RipDpiCombobox(
                value = "rel",
                onValueChange = {},
                suggestions = listOf("relay.example.com"),
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun diffViewer() {
        captureBothThemes("diffViewer", widthDp = 360, heightDp = 200) {
            RipDpiDiffViewer(
                lines =
                    listOf(
                        RipDpiDiffLine(RipDpiDiffKind.Added, "x", null, 1),
                        RipDpiDiffLine(RipDpiDiffKind.Removed, "y", 1, null),
                    ),
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun jsonTree() {
        captureBothThemes("jsonTree", widthDp = 360, heightDp = 200) {
            RipDpiJsonTree(
                root =
                    RipDpiJsonNode.Branch(
                        null,
                        listOf(RipDpiJsonNode.Leaf("k", "v", RipDpiJsonNode.Leaf.Kind.String)),
                        isArray = false,
                    ),
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun logStream() {
        captureBothThemes("logStream", widthDp = 360, heightDp = 200) {
            RipDpiLogStream(
                entries = listOf(RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:01", "core", "tunnel up")),
            )
        }
    }

    @Test
    @Ignore(BlessPending)
    fun tooltipRich() {
        captureBothThemes("tooltipRich", widthDp = 360, heightDp = 120) {
            RipDpiTooltipRich(title = "Stale data", body = "Last probe 18m ago") {
                Text("18m ago")
            }
        }
    }
}
