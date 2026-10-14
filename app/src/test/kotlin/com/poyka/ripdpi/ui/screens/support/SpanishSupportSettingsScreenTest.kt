package com.poyka.ripdpi.ui.screens.support

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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
import com.poyka.ripdpi.data.support.RestartPolicyAsk
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.data.support.SupportSettingsPreview
import com.poyka.ripdpi.data.support.SupportSettingsSensitivity
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
@Config(sdk = [35], qualifiers = "es-w420dp-h1100dp-mdpi")
class SpanishSupportSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun sensitiveWarningAndApplyActionAreReadableInSpanish() = checkScreen(420, 1f, "support-es")

    @Test
    @Config(qualifiers = "es-w360dp-h1100dp-mdpi")
    fun sensitiveWarningAndApplyActionFitLargeSpanishText() = checkScreen(360, 1.5f, "support-es-large")

    private fun checkScreen(
        width: Int,
        fontScale: Float,
        name: String,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val warning = app.getString(R.string.support_settings_sensitive_body)
        assertTrue(warning.contains("credenciales"))
        assertTrue(warning.contains("tráfico"))
        val change =
            SupportSettingsFieldChange("diagnostics.enabled", "false", "true", SupportSettingsSensitivity.Sensitive)
        var applied = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 1100.dp).ripDpiTestTag("spanish-support-viewport")) {
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
                }
            }
        }
        composeRule.onNodeWithText("Support settings link").assertDoesNotExist()
        val warningNode = composeRule.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        warningNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertFalse("Clipped warning: ${it.layoutInput.text}", it.hasVisualOverflow) }
        capture(name)
        composeRule
            .onNodeWithText(app.getString(R.string.support_settings_apply_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(applied)
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag("spanish-support-viewport")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
