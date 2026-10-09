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
import com.poyka.ripdpi.activities.http3Group
import com.poyka.ripdpi.diagnostics.Http3ProbeEvidence
import com.poyka.ripdpi.diagnostics.Http3ProbeStage
import com.poyka.ripdpi.diagnostics.Http3ProbeStatus
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
class DiagnosticsHttp3ComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun protocolEvidenceAndLimitationsRemainReadable() = checkCard(LayoutDirection.Ltr, 1f, "http3")

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun protocolEvidenceFits360DpWithLargeRtlText() = checkCard(LayoutDirection.Rtl, 1.5f, "http3-rtl")

    private fun checkCard(
        direction: LayoutDirection,
        fontScale: Float,
        name: String,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val group =
            http3Group(
                AndroidStringResolver(app),
                Http3ProbeEvidence(
                    stage = Http3ProbeStage.HTTP_BODY,
                    status = Http3ProbeStatus.BODY_LIMIT,
                    reason = "body_limit",
                    alpn = "h3",
                    tlsValidated = true,
                    http3Validated = true,
                    requestSent = true,
                    httpStatus = 403,
                    responseBytes = 65536,
                    durationMs = 1500,
                    attemptCount = 1,
                    handshakeElapsedMs = 100,
                    headersElapsedMs = 300,
                    firstByteElapsedMs = 400,
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
                            .ripDpiTestTag("http3-viewport"),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ContextGroupCard(group)
                        }
                    }
                }
            }
        }
        capture("$name-top")
        listOf(
            R.string.diagnostics_http3_validated,
            R.string.diagnostics_http3_caution,
        ).forEach { resource ->
            val node = composeRule.onNodeWithText(app.getString(resource))
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse("Clipped: ${it.layoutInput.text}", it.hasVisualOverflow) }
            if (resource == R.string.diagnostics_http3_caution) capture(name)
        }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(
                        "http3-viewport",
                    ).captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
