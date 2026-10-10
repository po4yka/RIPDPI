package com.poyka.ripdpi.ui.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileEditorState
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileScreen
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileUiState
import com.poyka.ripdpi.ui.screens.xray.XrayImportRestoreStatus
import com.poyka.ripdpi.ui.screens.xray.XrayImportUiState
import com.poyka.ripdpi.ui.screens.xray.XrayProfileImportScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CredentialWindowPresentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `credential screens protect the real window and release only when all are gone`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var editorVisible by mutableStateOf(true)
        var importVisible by mutableStateOf(true)
        val previousMotion = System.getProperty("ripdpi.staticMotion")
        System.setProperty("ripdpi.staticMotion", "false")
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalActivity provides activity) {
                    RipDpiTheme {
                        if (editorVisible) {
                            AnyTlsProfileScreen(
                                AnyTlsProfileUiState(AnyTlsProfileEditorState.initial()),
                                {},
                                { _, _ -> },
                                {},
                            )
                        }
                        if (importVisible) {
                            XrayProfileImportScreen(
                                XrayImportUiState(restoreStatus = XrayImportRestoreStatus.Ready),
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                            )
                        }
                    }
                }
            }
            composeRule.runOnIdle {
                assertEquals(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
                editorVisible = false
            }
            composeRule.runOnIdle {
                assertEquals(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
                importVisible = false
            }
            composeRule.runOnIdle {
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        } finally {
            if (previousMotion ==
                null
            ) {
                System.clearProperty("ripdpi.staticMotion")
            } else {
                System.setProperty("ripdpi.staticMotion", previousMotion)
            }
            controller.pause().stop().destroy()
        }
    }
}
