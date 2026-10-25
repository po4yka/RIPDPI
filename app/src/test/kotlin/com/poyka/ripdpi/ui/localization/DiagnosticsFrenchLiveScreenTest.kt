package com.poyka.ripdpi.ui.localization

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.buildCurrentServiceTelemetry
import com.poyka.ripdpi.activities.buildLiveUiModel
import com.poyka.ripdpi.activities.historyTelemetry
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.screens.diagnostics.LiveHeroCard
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-w420dp-h1100dp-mdpi", application = Application::class)
class DiagnosticsFrenchLiveScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `French stopped Live labels and RTT fit normal text`() = checkLive(420, 1f, true, "fr-live-stopped")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `French stopped Live labels and RTT fit large text`() = checkLive(360, 1.5f, true, "fr-live-stopped-large")

    @Test
    fun `French unknown network and RTT fit normal text`() = checkLive(420, 1f, false, "fr-live-unknown")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `French unknown network and RTT fit large text`() = checkLive(360, 1.5f, false, "fr-live-unknown-large")

    private fun checkLive(
        width: Int,
        scale: Float,
        stopped: Boolean,
        name: String,
    ) {
        val sample =
            if (stopped) {
                historyTelemetry().copy(
                    connectionState = "Stopped",
                    createdAt = 0L,
                    failureClass = null,
                    lastFailureClass = null,
                    lastFallbackAction = null,
                    winningTcpStrategyFamily = null,
                    winningQuicStrategyFamily = null,
                    proxyRttBand = "lt50",
                    resolverRttBand = "unknown",
                )
            } else {
                checkNotNull(
                    buildCurrentServiceTelemetry(
                        status = AppStatus.Running,
                        mode = Mode.VPN,
                        telemetry = ServiceTelemetrySnapshot(mode = Mode.VPN, status = AppStatus.Running),
                        activeConnectionSession = null,
                    ),
                ).copy(proxyRttBand = "lt50")
            }
        val model =
            DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication()).buildLiveUiModel(
                activeConnectionSession = null,
                telemetry = listOf(sample),
                currentTelemetry = sample,
                nativeEvents = emptyList(),
                latestSnapshot = null,
                latestContext = null,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 1100.dp).ripDpiTestTag(ViewportTag)) {
                        LiveHeroCard(model)
                    }
                }
            }
        }
        if (stopped) {
            val labels = composeRule.onAllNodesWithText("Arrêté")
            assertEquals(2, labels.fetchSemanticsNodes().size)
            labels[0].assertIsDisplayed()
            labels[1].assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("Le trafic passe par Inconnu").assertIsDisplayed()
            composeRule.onNodeWithText("Inconnu").assertIsDisplayed()
        }
        composeRule.onNodeWithText("< 50 ms").assertIsDisplayed()
        capture(name)
        assertAllTextReadable()
        assertEquals(if (stopped) "Stopped" else "Running", sample.connectionState)
        assertEquals("lt50", sample.proxyRttBand)
    }

    private fun assertAllTextReadable() {
        val viewport = composeRule.onNodeWithTag(ViewportTag).fetchSemanticsNode().boundsInRoot
        val nodes =
            composeRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            )
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("The Live card must have readable text nodes", count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index]
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = nodes[index].fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                val diagnostic =
                    "Clipped French Live text: ${layout.layoutInput.text}; " +
                        "visualOverflow=${layout.hasVisualOverflow}; " +
                        "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; " +
                        "size=${layout.size}; bounds=$bounds; constraints=${layout.layoutInput.constraints}; " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                        "density=${layout.layoutInput.density}; " +
                        "font=${layout.layoutInput.style.fontSize}/${layout.layoutInput.style.lineHeight}; " +
                        "softWrap=${layout.layoutInput.softWrap}; maxLines=${layout.layoutInput.maxLines}; " +
                        "lines=" +
                        (0 until layout.lineCount).joinToString { line ->
                            "$line:left=${layout.getLineLeft(line)},right=${layout.getLineRight(line)}," +
                                "bottom=${layout.getLineBottom(line)}"
                        }
                // Wrap-content Text can keep a paragraph measured at its parent's larger width.
                // Check rendered lines so unused paragraph width is not treated as clipping.
                assertFalse(diagnostic, layout.didOverflowHeight)
                assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
                if (layout.multiParagraph.width <= layout.size.width + 1f) {
                    assertFalse(diagnostic, layout.hasVisualOverflow)
                }
                repeat(layout.lineCount) { line ->
                    assertFalse(diagnostic, layout.isLineEllipsized(line))
                    assertTrue(diagnostic, layout.getLineLeft(line) >= -1f)
                    assertTrue(diagnostic, layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue(diagnostic, layout.getLineTop(line) >= -1f)
                    assertTrue(diagnostic, layout.getLineBottom(line) <= layout.size.height + 1f)
                }
                assertTrue("French Live text width is clipped", layout.size.width <= bounds.width + 1f)
                assertTrue("French Live text height is clipped", layout.size.height <= bounds.height + 1f)
            }
        }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(ViewportTag)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}

private const val ViewportTag = "french-live-localization-viewport"
