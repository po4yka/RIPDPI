package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdvancedSettingsComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    // -- AdvancedTextSetting --

    @Test
    fun `text setting save button disabled when value unchanged`() {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedTextSetting(
                    title = "TTL",
                    value = "128",
                    setting = AdvancedTextSetting.FakeTtl,
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeRule.onNode(hasText("Save", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun `text setting save button enabled when value changed`() {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedTextSetting(
                    title = "TTL",
                    value = "128",
                    setting = AdvancedTextSetting.FakeTtl,
                    onConfirm = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("128").performTextReplacement("64")
        composeRule.onNode(hasText("Save", substring = true)).assertIsEnabled()
    }

    @Test
    fun `text setting save button disabled when validation fails`() {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedTextSetting(
                    title = "TTL",
                    value = "128",
                    setting = AdvancedTextSetting.FakeTtl,
                    onConfirm = { _, _ -> },
                    validator = { it.toLongOrNull() != null && it.toLong() in 1..255 },
                    invalidMessage = "Out of range",
                )
            }
        }

        composeRule.onNodeWithText("128").performTextReplacement("999")
        composeRule.onNode(hasText("Save", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun `text setting save button disabled when control is disabled`() {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedTextSetting(
                    title = "TTL",
                    value = "128",
                    setting = AdvancedTextSetting.FakeTtl,
                    onConfirm = { _, _ -> },
                    enabled = false,
                )
            }
        }

        composeRule.onNode(hasText("Save", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun `text setting fires callback with trimmed value on save`() {
        var captured: Pair<AdvancedTextSetting, String>? = null

        composeRule.setContent {
            RipDpiTheme {
                AdvancedTextSetting(
                    title = "TTL",
                    value = "128",
                    setting = AdvancedTextSetting.FakeTtl,
                    onConfirm = { s, v -> captured = s to v },
                )
            }
        }

        composeRule.onNodeWithText("128").performTextReplacement("  64  ")
        composeRule.onNode(hasText("Save", substring = true)).performClick()

        assertEquals(AdvancedTextSetting.FakeTtl to "64", captured)
    }

    // -- AdvancedDropdownSetting --

    @Test
    fun `dropdown setting renders title and description`() {
        composeRule.setContent {
            RipDpiTheme {
                AdvancedDropdownSetting(
                    title = "Desync method",
                    value = "fake",
                    options =
                        listOf(
                            RipDpiDropdownOption(value = "fake", label = "Fake"),
                            RipDpiDropdownOption(value = "split", label = "Split"),
                        ),
                    setting = AdvancedOptionSetting.DesyncMethod,
                    onSelected = { _, _ -> },
                    description = "Choose method",
                )
            }
        }

        composeRule.onNodeWithText("Desync method").assertExists()
        composeRule.onNodeWithText("Choose method").assertExists()
    }

    // -- ProfileSummaryLine --

    @Test
    fun `profile summary line renders label and value`() {
        composeRule.setContent {
            RipDpiTheme {
                ProfileSummaryLine(
                    label = "Current range",
                    value = "100 - 500",
                )
            }
        }

        composeRule.onNodeWithText("Current range").assertExists()
        composeRule.onNodeWithText("100 - 500").assertExists()
    }

    // -- ActivationRangeEditorCard --

    @Test
    fun `activation range editor save disabled with empty inputs`() {
        composeRule.setContent {
            RipDpiTheme {
                ActivationRangeEditorCard(
                    title = "Round range",
                    description = "Activation round range",
                    currentRange = NumericRangeModel(start = null, end = null),
                    emptySummary = "No range",
                    effectSummary = "All rounds",
                    enabled = true,
                    minValue = 0,
                    onSave = { _, _ -> },
                )
            }
        }

        composeRule.onNode(hasText("Save", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun `activation range editor save enabled after changing from values`() {
        var saved = false

        composeRule.setContent {
            RipDpiTheme {
                ActivationRangeEditorCard(
                    title = "Round range",
                    description = "Activation round range",
                    currentRange = NumericRangeModel(start = 10, end = 100),
                    emptySummary = "No range",
                    effectSummary = "Rounds 10-100",
                    enabled = true,
                    minValue = 0,
                    onSave = { _, _ -> saved = true },
                )
            }
        }

        composeRule.onNodeWithText("10").performTextReplacement("20")
        composeRule.onNode(hasText("Save", substring = true)).assertIsEnabled()
        composeRule.onNode(hasText("Save", substring = true)).performClick()
        assertTrue(saved)
    }

    // -- SummaryCapsuleFlow --

    @Test
    fun `capsule flow renders all items`() {
        composeRule.setContent {
            RipDpiTheme {
                SummaryCapsuleFlow(
                    items =
                        listOf(
                            "Desync" to SummaryCapsuleTone.Active,
                            "HTTP" to SummaryCapsuleTone.Neutral,
                            "Warning" to SummaryCapsuleTone.Warning,
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Desync").assertExists()
        composeRule.onNodeWithText("HTTP").assertExists()
        composeRule.onNodeWithText("Warning").assertExists()
    }
}
