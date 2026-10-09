package com.poyka.ripdpi.ui.screens.anytls

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import com.poyka.ripdpi.ui.screens.mieru.MieruEditorField
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileEditorState
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileScreen
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileUiState
import com.poyka.ripdpi.ui.screens.ssh.SshEditorField
import com.poyka.ripdpi.ui.screens.ssh.SshProfileEditorState
import com.poyka.ripdpi.ui.screens.ssh.SshProfileScreen
import com.poyka.ripdpi.ui.screens.ssh.SshProfileUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnyTlsPasswordPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `password is masked while edits retain the original credential`() {
        var edited: Pair<AnyTlsEditorField, String>? = null
        composeRule.setContent {
            RipDpiTheme {
                AnyTlsProfileScreen(
                    uiState =
                        AnyTlsProfileUiState(
                            editor =
                                AnyTlsProfileEditorState.initial().updateField(
                                    AnyTlsEditorField.PASSWORD,
                                    "secret",
                                ),
                        ),
                    onBack = {},
                    onFieldChanged = { field, raw -> edited = field to raw },
                    onSave = {},
                )
            }
        }
        val field =
            composeRule
                .onNode(hasSetTextAction() and hasText("secret"))
                .performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        assertRenderedPasswordMasked(field)
        field.performTextReplacement("new-secret")
        assertEquals(AnyTlsEditorField.PASSWORD to "new-secret", edited)
    }

    @Test
    fun `Mieru password is masked`() {
        var edited: String? = null
        composeRule.setContent {
            RipDpiTheme {
                MieruProfileScreen(
                    uiState =
                        MieruProfileUiState(
                            editor = MieruProfileEditorState.initial().updateField(MieruEditorField.PASSWORD, "secret"),
                        ),
                    onBack = {},
                    onFieldChanged = { _, raw -> edited = raw },
                    onProtocolSelected = {},
                    onMultiplexingSelected = {},
                    onSave = {},
                )
            }
        }
        val field =
            composeRule
                .onNode(hasSetTextAction() and hasText("secret"))
                .performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedPasswordMasked(field)
        field.performTextReplacement("new-secret")
        assertEquals("new-secret", edited)
    }

    @Test
    fun `SSH password is masked`() {
        var edited: String? = null
        composeRule.setContent {
            RipDpiTheme {
                SshProfileScreen(
                    uiState =
                        SshProfileUiState(
                            editor = SshProfileEditorState.initial().updateField(SshEditorField.PASSWORD, "secret"),
                        ),
                    onBack = {},
                    onFieldChanged = { _, raw -> edited = raw },
                    onAuthTypeSelected = {},
                    onStrictHostKeyChanged = {},
                    onSave = {},
                    onRevealPrivateKey = {},
                    onRevealPassphrase = {},
                )
            }
        }
        val field =
            composeRule
                .onNode(hasSetTextAction() and hasText("secret"))
                .performScrollTo()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedPasswordMasked(field)
        field.performTextReplacement("new-secret")
        assertEquals("new-secret", edited)
    }

    private fun assertRenderedPasswordMasked(field: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            "••••••",
            layouts
                .single()
                .layoutInput.text.text,
        )
        assertFalse(
            layouts
                .single()
                .layoutInput.text.text
                .contains("secret"),
        )
    }
}
