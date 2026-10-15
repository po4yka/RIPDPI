package com.poyka.ripdpi.ui.localization

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.support.RestartPolicyRequired
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsSensitivity
import com.poyka.ripdpi.ui.components.scaffold.RipDpiDashboardScaffold
import com.poyka.ripdpi.ui.screens.home.HomePauseControls
import com.poyka.ripdpi.ui.screens.home.HomeTopBar
import com.poyka.ripdpi.ui.screens.support.SupportSettingsScreen
import com.poyka.ripdpi.ui.screens.support.SupportSettingsUiState
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fa-rIR-w420dp-h1100dp-mdpi")
class PersianLocalizationScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private var expectedFontScale = 1f

    @Test
    fun `sensitive support warning is readable before apply`() = checkSupport(420, 1f, "fa-support")

    @Test
    @Config(qualifiers = "fa-rIR-w360dp-h1100dp-mdpi")
    fun `sensitive support warning fits large Persian text`() = checkSupport(360, 1.5f, "fa-support-large")

    @Test
    fun `clock recovery names and opens the resume action`() = checkPause(420, 1f, "fa-clock")

    @Test
    @Config(qualifiers = "fa-rIR-w360dp-h1100dp-mdpi")
    fun `clock recovery fits large Persian text`() = checkPause(360, 1.5f, "fa-clock-large")

    private fun checkSupport(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val warning = persian(R.string.support_settings_sensitive_body)
        assertTrue(warning.contains("اعتبارنامه") && warning.contains("اعتماد"))
        val change = SupportSettingsFieldChange("settings.debug", "false", "true", SupportSettingsSensitivity.Sensitive)
        var applied = false
        content(width, scale) {
            SupportSettingsScreen(
                uiState =
                    SupportSettingsUiState(
                        preview = SupportSettingsPreview("", "", listOf(change), RestartPolicyRequired),
                        changes = persistentListOf(change),
                    ),
                onBack = {},
                onApply = { applied = true },
                onRetry = {},
            )
        }
        readable(R.string.support_settings_sensitive_title)
        readable(R.string.support_settings_sensitive_body)
        capture(name)
        composeRule
            .onNodeWithText(persian(R.string.support_settings_apply_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(applied)
    }

    private fun checkPause(
        width: Int,
        scale: Float,
        name: String,
    ) {
        var resumed = false
        var stopped = false
        val action = persian(R.string.pause_resume_now)
        assertTrue(persian(R.string.pause_clock_failed).contains(action))
        content(width, scale) {
            RipDpiDashboardScaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = { HomeTopBar(title = stringResource(R.string.app_name)) },
            ) {
                HomePauseControls(
                    state = HomePauseUiState(phase = PausePhase.Deferred, failure = PauseFailure.ClockChanged),
                    onPause = {},
                    onResume = { resumed = true },
                    onStop = { stopped = true },
                )
            }
        }
        readable(R.string.pause_deferred)
        readable(R.string.pause_saved_settings)
        readable(R.string.pause_clock_failed)
        capture(name)
        readable(R.string.pause_resume_now)
        composeRule
            .onNodeWithText(action)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(resumed)
        readable(R.string.pause_stop)
        composeRule
            .onNodeWithText(persian(R.string.pause_stop))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(stopped)
    }

    private fun content(
        width: Int,
        scale: Float,
        screen: @Composable () -> Unit,
    ) {
        expectedFontScale = scale
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
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
        val value = persian(resource).let { if (uppercase) it.uppercase(Locale.forLanguageTag("fa")) else it }
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(value, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue("No text layout for $resource", layouts.isNotEmpty())
        val viewport = composeRule.onNodeWithTag(ViewportTag).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        layouts.forEach { layout ->
            val diagnostic =
                "Clipped Persian text: ${layout.layoutInput.text}; size=${layout.size}; bounds=$bounds; " +
                    "viewport=$viewport; constraints=${layout.layoutInput.constraints}; " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                    "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; " +
                    "direction=${layout.layoutInput.layoutDirection}; " +
                    "textAlign=${layout.layoutInput.style.textAlign}; " +
                    "lines=" +
                    (0 until layout.lineCount).joinToString { line ->
                        "$line:left=${layout.getLineLeft(line)},right=${layout.getLineRight(line)}," +
                            "top=${layout.getLineTop(line)},bottom=${layout.getLineBottom(line)}"
                    }
            assertEquals(diagnostic, expectedFontScale, layout.layoutInput.density.fontScale, 0.001f)
            assertArabicTextLayout(layout, bounds, viewport, diagnostic)
        }
    }

    private fun persian(resource: Int): String =
        RuntimeEnvironment.getApplication().getString(resource).also {
            assertTrue("Resource $resource is not Persian: $it", it.any { char -> char in '\u0600'..'\u06ff' })
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

private const val ViewportTag = "persian-localization-viewport"
