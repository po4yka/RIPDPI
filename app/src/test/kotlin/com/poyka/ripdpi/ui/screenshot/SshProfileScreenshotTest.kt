package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.ui.screens.ssh.SshEditorField
import com.poyka.ripdpi.ui.screens.ssh.SshProfileEditorState
import com.poyka.ripdpi.ui.screens.ssh.SshProfileScreen
import com.poyka.ripdpi.ui.screens.ssh.SshProfileUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SshProfileScreenshotTest {
    @Test
    fun newEmpty() {
        capture(
            "newEmpty",
            SshProfileUiState(editor = SshProfileEditorState.initial()),
        )
    }

    @Test
    fun populatedValid() {
        capture(
            "populatedValid",
            SshProfileUiState(
                editor =
                    SshProfileEditorState(
                        rawTextByField =
                            mapOf(
                                SshEditorField.DISPLAY_NAME to "Home bastion",
                                SshEditorField.SERVER to "bastion.example.com",
                                SshEditorField.SERVER_PORT to "22",
                                SshEditorField.USERNAME to "deploy",
                                SshEditorField.PASSWORD to "correct-horse",
                                SshEditorField.HOST_KEY_FINGERPRINT to
                                    "SHA256:abcdEFGHijklMNOPqrstUVWXyz0123456789ABCD",
                            ),
                        authType = RelaySshAuthTypePassword,
                        strictHostKey = true,
                    ),
            ),
        )
    }

    @Test
    fun invalidPort() {
        capture(
            "invalidPort",
            SshProfileUiState(
                editor =
                    SshProfileEditorState(
                        rawTextByField =
                            mapOf(
                                SshEditorField.SERVER to "bastion.example.com",
                                SshEditorField.SERVER_PORT to "99999",
                                SshEditorField.USERNAME to "deploy",
                            ),
                        authType = RelaySshAuthTypePassword,
                    ),
            ),
        )
    }

    private fun capture(
        name: String,
        state: SshProfileUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1400, testClassFqn = FQN) {
            SshProfileScreen(
                uiState = state,
                onBack = {},
                onFieldChanged = { _, _ -> },
                onAuthTypeSelected = {},
                onStrictHostKeyChanged = {},
                onRevealPrivateKey = {},
                onRevealPassphrase = {},
                onSave = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.SshProfileScreenshotTest"
    }
}
