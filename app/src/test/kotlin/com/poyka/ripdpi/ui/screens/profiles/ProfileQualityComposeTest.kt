package com.poyka.ripdpi.ui.screens.profiles

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
import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecision
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityKey
import com.poyka.ripdpi.data.ProfileQualityObservation
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h839dp-mdpi")
class ProfileQualityComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun verifiedQualityRemainsReadable() = checkCard(LayoutDirection.Ltr, 1f, "quality")

    @Test
    @Config(qualifiers = "ar-rEG-w411dp-h839dp-mdpi")
    fun qualityFits360DpWithLargeRtlText() = checkCard(LayoutDirection.Rtl, 1.5f, "quality-rtl")

    private fun checkCard(
        direction: LayoutDirection,
        fontScale: Float,
        name: String,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val state =
            ProfileQualityUiState(
                ProfileQualityObservation(
                    ProfileQualityKey("group", "member"),
                    ProfileQualityStatus.Verified,
                    262144,
                    1_000_000_000,
                    262144,
                    25,
                    40,
                    true,
                    ProfileQualityReason.Verified,
                    1000,
                ),
                ProfileQualityBudget("group", 1048580, 15728640, 16777216, null, ProfileQualityReason.Verified),
                ProfileQualityDecision("group", "member", "candidate", ProfileQualityDecisionReason.Cooldown, 1000),
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
                            .ripDpiTestTag("quality-viewport"),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RipDpiCard {
                                ProfileQualityDetails(
                                    state,
                                    if (direction ==
                                        LayoutDirection.Rtl
                                    ) {
                                        Locale.forLanguageTag("ar")
                                    } else {
                                        Locale.US
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        capture("$name-top")
        listOf(
            R.string.profile_quality_title,
            R.string.profile_quality_verified,
            R.string.profile_quality_cooldown,
            R.string.profile_quality_limits,
        ).forEach { resource ->
            val node = composeRule.onNodeWithText(app.getString(resource))
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse("Clipped: ${it.layoutInput.text}", it.hasVisualOverflow) }
            if (resource == R.string.profile_quality_limits) capture(name)
        }
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag(
                        "quality-viewport",
                    ).captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }
}
