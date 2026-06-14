package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.ui.screens.anytls.AnyTlsEditorField
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileEditorState
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileScreen
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnyTlsProfileScreenshotTest {
    @Test
    fun emptyState() {
        capture(
            "emptyState",
            AnyTlsProfileUiState(editor = AnyTlsProfileEditorState.initial()),
        )
    }

    @Test
    fun populatedValidState() {
        capture(
            "populatedValidState",
            AnyTlsProfileUiState(
                editor =
                    AnyTlsProfileEditorState(
                        rawTextByField =
                            mapOf(
                                AnyTlsEditorField.DISPLAY_NAME to "Frankfurt AnyTLS",
                                AnyTlsEditorField.SERVER to "anytls.example.net",
                                AnyTlsEditorField.SERVER_PORT to "8443",
                                AnyTlsEditorField.PASSWORD to "s3cret-passphrase",
                                AnyTlsEditorField.SNI to "cdn.example.net",
                            ),
                    ),
            ),
        )
    }

    @Test
    fun invalidState() {
        capture(
            "invalidState",
            AnyTlsProfileUiState(
                editor =
                    AnyTlsProfileEditorState(
                        rawTextByField =
                            mapOf(
                                AnyTlsEditorField.DISPLAY_NAME to "Broken endpoint",
                                AnyTlsEditorField.SERVER to "anytls.example.net",
                                AnyTlsEditorField.SERVER_PORT to "99999",
                                AnyTlsEditorField.PASSWORD to "ok-password",
                                AnyTlsEditorField.SNI to "cdn.example.net",
                            ),
                    ),
            ),
        )
    }

    private fun capture(
        name: String,
        state: AnyTlsProfileUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1000, testClassFqn = FQN) {
            AnyTlsProfileScreen(
                uiState = state,
                onBack = {},
                onFieldChanged = { _, _ -> },
                onSave = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.AnyTlsProfileScreenshotTest"
    }
}
