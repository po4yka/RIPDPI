package com.poyka.ripdpi.ui.screens.diagnostics

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.toLocalNetworkGroups
import com.poyka.ripdpi.diagnostics.LocalDeviceConstraints
import com.poyka.ripdpi.diagnostics.LocalNetworkContextModel
import com.poyka.ripdpi.diagnostics.LocalObservationState
import com.poyka.ripdpi.diagnostics.LocalSimConstraints
import com.poyka.ripdpi.diagnostics.LocalSimScope
import com.poyka.ripdpi.diagnostics.LocalTransport
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
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
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class DiagnosticsLocalNetworkComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun localEvidenceAndLimitationsRemainReadable() = checkCard(LayoutDirection.Ltr, 1f, "local-network")

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun localEvidenceFits360DpWithLargeRtlText() =
        checkCard(LayoutDirection.Rtl, 1.5f, "local-network-rtl", history = true)

    private fun checkCard(
        direction: LayoutDirection,
        fontScale: Float,
        name: String,
        history: Boolean = false,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val groups =
            DiagnosticsUiFactorySupport(app).toLocalNetworkGroups(
                LocalNetworkContextModel(
                    transport = LocalTransport.VPN,
                    device =
                        LocalDeviceConstraints(
                            airplaneMode = LocalObservationState.PERMISSION_DENIED,
                            backgroundRestricted = LocalObservationState.UNSUPPORTED,
                            deviceIdle = LocalObservationState.UNAVAILABLE,
                        ),
                    sim =
                        LocalSimConstraints(
                            scope = LocalSimScope.DEFAULT_DATA,
                            mobileDataEnabled = LocalObservationState.DISABLED,
                        ),
                ),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme(themePreference = "light") {
                    Box(
                        Modifier
                            .requiredSize(
                                360.dp,
                                760.dp,
                            ).background(RipDpiThemeTokens.colors.background)
                            .ripDpiTestTag("local-viewport"),
                    ) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            groups.forEach {
                                if (history) {
                                    com.poyka.ripdpi.ui.screens.history.ContextGroupCard(
                                        it,
                                    )
                                } else {
                                    ContextGroupCard(it)
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf(
            R.string.diagnostics_local_value_vpn,
            R.string.diagnostics_local_value_permission_denied,
            R.string.diagnostics_local_value_unsupported,
            R.string.diagnostics_local_value_unavailable,
            R.string.diagnostics_local_caution,
            R.string.diagnostics_local_sim_caution,
            R.string.diagnostics_local_hint_mobile_data_disabled,
        ).forEach { resource ->
            val node = composeRule.onNodeWithText(app.getString(resource))
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse("Clipped: ${it.layoutInput.text}", it.hasVisualOverflow) }
            if (resource == R.string.diagnostics_local_sim_caution) capture(name)
        }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(
                        "local-viewport",
                    ).captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
