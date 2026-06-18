package com.poyka.ripdpi.ui.screenshot

import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgEditorState
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileScreen
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileUiState
import com.poyka.ripdpi.ui.screens.awg.AwgCohortOption
import com.poyka.ripdpi.ui.screens.awg.AwgEditorField
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AmneziaWgProfileScreenshotTest {
    @Test
    fun emptyNew() {
        capture(
            "emptyNew",
            AmneziaWgProfileUiState(
                editor = AmneziaWgEditorState.initial(),
                cohortOptions = listOf(customOption()),
            ),
        )
    }

    @Test
    fun populatedLockedCohort() {
        val form =
            AwgProfileForm(
                server = "vpn.example.org",
                serverPort = 51820,
                interfacePrivateKey = "CJ8kY0sample+privateKeyMaterialForPreview=",
                peerPublicKey = "9mWk0sample+peerPublicKeyForPreview1234abcd=",
                presharedKey = "presharedKeySampleForPreview+0000000000ab=",
                jc = 4,
                jmin = 8,
                jmax = 80,
                s1 = 15,
                s2 = 36,
                h1 = 1_148_874_316L,
                h2 = 2_137_415_592L,
                h3 = 1_293_416_021L,
                h4 = 2_011_528_944L,
                cohortId = COHORT_ID,
            )
        val rawText =
            mapOf(
                AwgEditorField.SERVER to form.server,
                AwgEditorField.SERVER_PORT to form.serverPort.toString(),
                AwgEditorField.INTERFACE_PRIVATE_KEY to form.interfacePrivateKey,
                AwgEditorField.ADDRESS to "10.8.0.2/32",
                AwgEditorField.DNS to "1.1.1.1, 1.0.0.1",
                AwgEditorField.MTU to "1280",
                AwgEditorField.PEER_PUBLIC_KEY to form.peerPublicKey,
                AwgEditorField.PEER_ENDPOINT to "vpn.example.org:51820",
                AwgEditorField.ALLOWED_IPS to "0.0.0.0/0, ::/0",
                AwgEditorField.PRESHARED_KEY to form.presharedKey,
                AwgEditorField.PERSISTENT_KEEPALIVE to "25",
                AwgEditorField.JC to form.jc.toString(),
                AwgEditorField.JMIN to form.jmin.toString(),
                AwgEditorField.JMAX to form.jmax.toString(),
                AwgEditorField.S1 to form.s1.toString(),
                AwgEditorField.S2 to form.s2.toString(),
                AwgEditorField.H1 to form.h1.toString(),
                AwgEditorField.H2 to form.h2.toString(),
                AwgEditorField.H3 to form.h3.toString(),
                AwgEditorField.H4 to form.h4.toString(),
            )
        val editor =
            AmneziaWgEditorState(
                form = form,
                rawTextByField = rawText,
                obfuscationLocked = true,
            )
        capture(
            "populatedLockedCohort",
            AmneziaWgProfileUiState(
                editor = editor,
                cohortOptions = listOf(AwgCohortOption(id = COHORT_ID, displayNameKey = ""), customOption()),
                privateKeyRevealed = true,
                canActivate = editor.isActivatable(),
            ),
        )
    }

    @Test
    fun customWithInvalidField() {
        val form =
            AwgProfileForm(
                server = "203.0.113.7",
                serverPort = 443,
                interfacePrivateKey = "CJ8kY0sample+privateKeyMaterialForPreview=",
                peerPublicKey = "9mWk0sample+peerPublicKeyForPreview1234abcd=",
                cohortId = AwgProfileForm.CUSTOM_COHORT_ID,
            )
        val rawText =
            mapOf(
                AwgEditorField.SERVER to form.server,
                AwgEditorField.SERVER_PORT to form.serverPort.toString(),
                AwgEditorField.INTERFACE_PRIVATE_KEY to form.interfacePrivateKey,
                AwgEditorField.MTU to "not-a-number",
                AwgEditorField.PEER_PUBLIC_KEY to form.peerPublicKey,
                AwgEditorField.PEER_ENDPOINT to "203.0.113.7:443",
                AwgEditorField.JC to "120",
                AwgEditorField.JMIN to "8",
                AwgEditorField.JMAX to "80",
                AwgEditorField.S1 to "-3",
                AwgEditorField.I1 to "zzzz",
            )
        capture(
            "customWithInvalidField",
            AmneziaWgProfileUiState(
                editor =
                    AmneziaWgEditorState(
                        form = form,
                        rawTextByField = rawText,
                        obfuscationLocked = false,
                    ),
                cohortOptions = listOf(AwgCohortOption(id = COHORT_ID, displayNameKey = ""), customOption()),
            ),
        )
    }

    private fun capture(
        name: String,
        state: AmneziaWgProfileUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1600, testClassFqn = FQN) {
            AmneziaWgProfileScreen(
                uiState = state,
                onBack = {},
                onFieldChanged = { _, _ -> },
                onCohortSelected = {},
                onPasteConf = {},
                onRevealPrivateKey = {},
                onRevealPresharedKey = {},
                onConnect = {},
            )
        }
    }

    private fun customOption(): AwgCohortOption =
        AwgCohortOption(id = AwgProfileForm.CUSTOM_COHORT_ID, displayNameKey = "")

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.AmneziaWgProfileScreenshotTest"
        const val COHORT_ID = "rtk_south"
    }
}
