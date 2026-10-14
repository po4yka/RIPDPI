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
import com.poyka.ripdpi.data.support.RestartPolicyAsk
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsSensitivity
import com.poyka.ripdpi.ui.screens.config.ModeEditorChainBlockEditor
import com.poyka.ripdpi.ui.screens.config.NoOpModeEditorActions
import com.poyka.ripdpi.ui.screens.customization.AboutScreen
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
@Config(sdk = [35], qualifiers = "de-w420dp-h1100dp-mdpi")
class GermanLocalizationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun sensitiveSupportWarningAndApplyActionAreReadable() = checkSupport(420, 1f, "de-support")

    @Test
    @Config(qualifiers = "de-w360dp-h1100dp-mdpi")
    fun sensitiveSupportWarningFitsLargeText() = checkSupport(360, 1.5f, "de-support-large")

    @Test
    fun privacyScopeAndRedactionAreReadable() = checkPrivacy(420, 1f, "de-privacy")

    @Test
    @Config(qualifiers = "de-w360dp-h1100dp-mdpi")
    fun privacyScopeAndRedactionFitLargeText() = checkPrivacy(360, 1.5f, "de-privacy-large")

    @Test
    fun updatePermissionAndInstallActionAreReadable() = checkUpdate(420, 1f, "de-update")

    @Test
    @Config(qualifiers = "de-w360dp-h1100dp-mdpi")
    fun updatePermissionFitsLargeText() = checkUpdate(360, 1.5f, "de-update-large")

    @Test
    fun invalidBlockRecoveryIsReadable() = checkBlocks(420, 1f, "de-blocks")

    @Test
    @Config(qualifiers = "de-w360dp-h1100dp-mdpi")
    fun invalidBlockRecoveryFitsLargeText() = checkBlocks(360, 1.5f, "de-blocks-large")

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
            .onNodeWithText(german(R.string.support_settings_apply_action))
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
            .onNodeWithText(german(R.string.update_install_action))
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
        composeRule.onNodeWithText(german(R.string.config_chain_editor_label)).performScrollTo().assertIsDisplayed()
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
        val text = german(resource)
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
        layouts.forEach { assertFalse("Clipped German text: ${it.layoutInput.text}", it.hasVisualOverflow) }
    }

    private fun german(resource: Int): String {
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

private const val ViewportTag = "german-localization-viewport"
