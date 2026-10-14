package com.poyka.ripdpi.ui.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.data.PauseFailure
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.support.RestartPolicyAsk
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsSensitivity
import com.poyka.ripdpi.ui.screens.config.ModeEditorChainBlockEditor
import com.poyka.ripdpi.ui.screens.config.NoOpModeEditorActions
import com.poyka.ripdpi.ui.screens.customization.AboutScreen
import com.poyka.ripdpi.ui.screens.home.HomePauseControls
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyScreen
import com.poyka.ripdpi.ui.screens.support.SupportSettingsScreen
import com.poyka.ripdpi.ui.screens.support.SupportSettingsUiState
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.updates.AvailableAppUpdate
import com.poyka.ripdpi.updates.DistributionChannel
import com.poyka.ripdpi.updates.UpdateChannelInfo
import com.poyka.ripdpi.updates.UpdateUiState
import com.poyka.ripdpi.updates.UpdateUiStatus
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
@Config(sdk = [35], qualifiers = "fr-w420dp-h1100dp-mdpi")
class FrenchLocalizationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `sensitive support warning and apply action are readable`() = checkSupport(420, 1f, "fr-support")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `sensitive support warning fits large text`() = checkSupport(360, 1.5f, "fr-support-large")

    @Test
    fun `privacy scope and redaction are readable`() = checkPrivacy(420, 1f, "fr-privacy")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `privacy scope and redaction fit large text`() = checkPrivacy(360, 1.5f, "fr-privacy-large")

    @Test
    fun `update permission and install action are readable`() = checkUpdate(420, 1f, "fr-update")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `update permission fits large text`() = checkUpdate(360, 1.5f, "fr-update-large")

    @Test
    fun `invalid block recovery is readable`() = checkBlocks(420, 1f, "fr-blocks")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `invalid block recovery fits large text`() = checkBlocks(360, 1.5f, "fr-blocks-large")

    @Test
    fun `clock recovery names the visible resume action`() = checkPause(420, 1f, "fr-clock-recovery")

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun `clock recovery fits large text`() = checkPause(360, 1.5f, "fr-clock-recovery-large")

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
        val action = french(R.string.pause_resume_now)
        assertTrue(french(R.string.pause_clock_failed).contains(action))
        readable(R.string.pause_clock_failed)
        capture(name)
        composeRule
            .onNodeWithText(action)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(resumed)
    }

    private fun checkSupport(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val change =
            SupportSettingsFieldChange("diagnostics.enabled", "false", "true", SupportSettingsSensitivity.Sensitive)
        var applied = false
        content(width, scale) {
            SupportSettingsScreen(
                uiState =
                    SupportSettingsUiState(
                        preview = SupportSettingsPreview("", "", listOf(change), RestartPolicyAsk),
                        changes = persistentListOf(change),
                    ),
                onBack = {},
                onApply = { applied = true },
                onRetry = {},
            )
        }
        readable(R.string.support_settings_sensitive_body)
        capture(name)
        composeRule
            .onNodeWithText(french(R.string.support_settings_apply_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(applied)
    }

    private fun checkPrivacy(
        width: Int,
        scale: Float,
        name: String,
    ) {
        content(width, scale) { DataTransparencyScreen(onBack = {}) }
        readable(R.string.data_transparency_no_personal_data, lazy = true)
        capture("$name-pcap")
        readable(R.string.data_transparency_export_redaction, lazy = true)
        readable(R.string.data_transparency_export_control, lazy = true)
        capture("$name-export")
    }

    private fun checkUpdate(
        width: Int,
        scale: Float,
        name: String,
    ) {
        var installed = false
        content(width, scale) {
            AboutScreen(
                onBack = {},
                updateUiState =
                    UpdateUiState(
                        channelInfo = UpdateChannelInfo(DistributionChannel.Github, true, true),
                        status = UpdateUiStatus.PermissionRequired,
                        availableUpdate = AvailableAppUpdate(99, "99.0", "", 1_048_576),
                    ),
                onInstallUpdate = { installed = true },
            )
        }
        readable(R.string.update_permission_required)
        capture(name)
        composeRule
            .onNodeWithText(french(R.string.update_install_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(installed)
    }

    private fun checkBlocks(
        width: Int,
        scale: Float,
        name: String,
    ) {
        val draft = ConfigDraft(chainDsl = "not-a-chain(")
        content(width, scale) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModeEditorChainBlockEditor(draft, ConfigUiState(draft = draft), NoOpModeEditorActions)
            }
        }
        readable(R.string.config_chain_block_invalid_body)
        capture(name)
        composeRule.onNodeWithText(french(R.string.config_chain_editor_label)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(draft.chainDsl).assertIsDisplayed()
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
        lazy: Boolean = false,
    ) {
        val text = french(resource)
        if (lazy) {
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No layout result for resource $resource", layouts.isNotEmpty())
        val viewport = composeRule.onNodeWithTag(ViewportTag).fetchSemanticsNode().boundsInRoot
        val bounds = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Text is outside the viewport: $text",
            bounds.left >= viewport.left && bounds.right <= viewport.right,
        )
        assertTrue(
            "Text is outside the viewport: $text",
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
        )
        layouts.forEach { assertFalse("Clipped French text: ${it.layoutInput.text}", it.hasVisualOverflow) }
    }

    private fun french(resource: Int): String {
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
                    .onNodeWithTag(ViewportTag)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}

private const val ViewportTag = "french-localization-viewport"
