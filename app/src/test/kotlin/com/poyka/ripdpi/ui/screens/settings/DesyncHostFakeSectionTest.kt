package com.poyka.ripdpi.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesyncHostFakeSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `configured hostfake states the raw requirement without root`() {
        composeRule.setContent {
            RipDpiTheme {
                HostFakeProfileCard(
                    SettingsUiState(
                        hasHostFake = true,
                        rootModeEnabled = false,
                        desync =
                            DesyncCoreUiState(
                                tcpChainSteps =
                                    persistentListOf(
                                        TcpChainStepModel(kind = TcpChainStepKind.HostFake, marker = "endhost"),
                                    ),
                            ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Hostfake configured").assertExists()
        composeRule
            .onNodeWithText("Hostfake needs root and raw packet support. Otherwise, RIPDPI uses a plain split.")
            .assertExists()
        composeRule.onNodeWithText("Hostname targeting ready").assertDoesNotExist()

        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/reports/hostfake/configured.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
