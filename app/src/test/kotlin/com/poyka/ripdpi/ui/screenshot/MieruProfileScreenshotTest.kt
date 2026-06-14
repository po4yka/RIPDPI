package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.ui.screens.mieru.MieruEditorField
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileEditorState
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileScreen
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MieruProfileScreenshotTest {
    @Test
    fun mieruProfileEmpty() {
        capture(
            "mieruProfileEmpty",
            MieruProfileUiState(editor = MieruProfileEditorState.initial()),
        )
    }

    @Test
    fun mieruProfilePopulated() {
        capture(
            "mieruProfilePopulated",
            MieruProfileUiState(
                editor =
                    MieruProfileEditorState(
                        rawTextByField =
                            mapOf(
                                MieruEditorField.DISPLAY_NAME to "Home Mieru",
                                MieruEditorField.SERVER to "mieru.example.com",
                                MieruEditorField.SERVER_PORT to "8443",
                                MieruEditorField.USERNAME to "alice",
                                MieruEditorField.PASSWORD to "correct horse battery",
                                MieruEditorField.MTU to "1400",
                            ),
                        protocol = RelayMieruProtocolUdp,
                        multiplexing = RelayMieruMultiplexingHigh,
                    ),
            ),
        )
    }

    @Test
    fun mieruProfileInvalid() {
        capture(
            "mieruProfileInvalid",
            MieruProfileUiState(
                editor =
                    MieruProfileEditorState(
                        rawTextByField =
                            mapOf(
                                MieruEditorField.DISPLAY_NAME to "Broken Mieru",
                                MieruEditorField.SERVER to "mieru.example.com",
                                MieruEditorField.SERVER_PORT to "99999",
                                MieruEditorField.USERNAME to "bob",
                                MieruEditorField.PASSWORD to "hunter2",
                                MieruEditorField.MTU to "9000",
                            ),
                    ),
            ),
        )
    }

    private fun capture(
        name: String,
        state: MieruProfileUiState,
    ) {
        captureScreenBothThemes(
            name = name,
            widthDp = 420,
            heightDp = 1400,
            testClassFqn = FQN,
        ) {
            MieruProfileScreen(
                uiState = state,
                onBack = {},
                onFieldChanged = { _, _ -> },
                onProtocolSelected = {},
                onMultiplexingSelected = {},
                onSave = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.MieruProfileScreenshotTest"
    }
}
