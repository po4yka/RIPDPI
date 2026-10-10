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
import com.poyka.ripdpi.activities.ipFamilyGroup
import com.poyka.ripdpi.diagnostics.IpFamilyProbeEvidence
import com.poyka.ripdpi.diagnostics.IpProbeFamily
import com.poyka.ripdpi.diagnostics.IpProbeStage
import com.poyka.ripdpi.diagnostics.IpProbeStatus
import com.poyka.ripdpi.diagnostics.Nat64DiscoveryStatus
import com.poyka.ripdpi.platform.AndroidStringResolver
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
class DiagnosticsIpFamilyComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun familyEvidenceAndLimitationsRemainReadable() = checkCard(LayoutDirection.Ltr, 1f, "ip-family")

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun familyEvidenceFits360DpWithLargeRtlText() = checkCard(LayoutDirection.Rtl, 1.5f, "ip-family-rtl")

    private fun checkCard(
        direction: LayoutDirection,
        fontScale: Float,
        name: String,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val group =
            ipFamilyGroup(
                AndroidStringResolver(app),
                IpFamilyProbeEvidence(
                    family = IpProbeFamily.NAT64,
                    stage = IpProbeStage.TCP_CONNECT,
                    status = IpProbeStatus.TIMEOUT,
                    reason = "timeout",
                    durationMs = 1500,
                    discoveryStatus = Nat64DiscoveryStatus.DISCOVERED,
                    prefixLength = 96,
                    attemptCount = 2,
                    resolverSource = "NETWORK_SNAPSHOT",
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
                            .requiredSize(360.dp, 760.dp)
                            .background(RipDpiThemeTokens.colors.background)
                            .ripDpiTestTag("ip-viewport"),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ContextGroupCard(group)
                        }
                    }
                }
            }
        }
        listOf(
            R.string.diagnostics_ip_nat64_caution,
            R.string.diagnostics_ip_caution,
        ).forEach { resource ->
            val node = composeRule.onNodeWithText(app.getString(resource))
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse("Clipped: ${it.layoutInput.text}", it.hasVisualOverflow) }
            if (resource == R.string.diagnostics_ip_caution) capture(name)
        }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(
                        "ip-viewport",
                    ).captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
