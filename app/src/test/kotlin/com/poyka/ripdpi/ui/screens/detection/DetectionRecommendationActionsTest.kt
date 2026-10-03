package com.poyka.ripdpi.ui.screens.detection

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.RecommendationDestination
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DetectionRecommendationActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `actionable recommendation only dispatches its typed navigation destination`() {
        val opened = mutableListOf<RecommendationDestination>()
        composeRule.setContent {
            RipDpiTheme {
                DetectionRecommendations(
                    recommendations =
                        listOf(
                            Recommendation(
                                "DNS points to localhost",
                                "Review the DNS configuration",
                                RecommendationDestination.DNS_SETTINGS,
                            ),
                        ),
                    onOpenRecommendation = { opened += it },
                )
            }
        }

        assertTrue(opened.isEmpty())
        composeRule
            .onNodeWithTag(
                "${RipDpiTestTags.DetectionRecommendationActionPrefix}${RecommendationDestination.DNS_SETTINGS.name}",
            ).performClick()

        assertEquals(listOf(RecommendationDestination.DNS_SETTINGS), opened)
        composeRule.onNodeWithTag("detection_apply_fixes").assertDoesNotExist()
    }

    @Test
    fun `recommendation actions render at compact width`() {
        renderCompactRecommendations(LayoutDirection.Ltr)
        capturePreview("detection-recommendations-en")
    }

    @Test
    @Config(qualifiers = "de")
    fun `long localized action labels render at compact width`() {
        renderCompactRecommendations(LayoutDirection.Ltr)
        capturePreview("detection-recommendations-de")
        assertActionLabelFits(R.string.detection_recommendation_open_dns)
        assertActionLabelFits(R.string.detection_recommendation_open_advanced)
    }

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl")
    fun `recommendation actions render in RTL`() {
        renderCompactRecommendations(LayoutDirection.Rtl)
        capturePreview("detection-recommendations-ar")
    }

    @Test
    fun `informational advice exposes no settings action`() {
        composeRule.setContent {
            RipDpiTheme {
                DetectionRecommendations(
                    recommendations = listOf(Recommendation("External client API exposed", "Review that client")),
                    onOpenRecommendation = { error("Informational advice cannot navigate") },
                )
            }
        }

        RecommendationDestination.entries.forEach { destination ->
            composeRule
                .onNodeWithTag(
                    "${RipDpiTestTags.DetectionRecommendationActionPrefix}${destination.name}",
                ).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("detection_apply_fixes").assertDoesNotExist()
    }

    @Test
    fun `toolbar settings keeps its own callback`() {
        var toolbarOpened = 0
        composeRule.setContent {
            RipDpiTheme {
                DetectionCheckScreen(
                    uiState = DetectionCheckUiState(),
                    onStart = {},
                    onStop = {},
                    onBack = {},
                    onOpenSettings = { toolbarOpened++ },
                    onOpenRecommendation = { error("Toolbar cannot open a recommendation") },
                    onDismissOnboarding = {},
                    onPrivacyModeChange = {},
                    onReloadCommunityStats = {},
                    onRequestPermissions = {},
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.title_detection_settings)).performClick()

        assertEquals(1, toolbarOpened)
    }

    private fun renderCompactRecommendations(layoutDirection: LayoutDirection) {
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Box(
                        Modifier
                            .width(320.dp)
                            .background(RipDpiThemeTokens.colors.background)
                            .padding(16.dp)
                            .ripDpiTestTag("recommendation_preview"),
                    ) {
                        DetectionRecommendations(
                            recommendations =
                                listOf(
                                    Recommendation(
                                        "DNS points to localhost",
                                        "Review the configured resolver before making a change.",
                                        RecommendationDestination.DNS_SETTINGS,
                                    ),
                                    Recommendation(
                                        "App routing needs review",
                                        "Review the configured routing protection before making a change.",
                                        RecommendationDestination.ADVANCED_SETTINGS,
                                    ),
                                ),
                            onOpenRecommendation = {},
                        )
                    }
                }
            }
        }
    }

    private fun capturePreview(name: String) {
        val previewFile = File("build/compose-previews/renders/$name.png")
        checkNotNull(previewFile.parentFile).mkdirs()
        previewFile.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onNodeWithTag("recommendation_preview")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
    }

    private fun assertActionLabelFits(labelRes: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layoutResults = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(context.getString(labelRes), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layoutResults) }
        assertTrue(layoutResults.isNotEmpty())
        layoutResults.forEach {
            val label = context.getString(labelRes)
            assertFalse("Action label $label must not be ellipsized", it.isLineEllipsized(0))
            assertFalse("Action label $label must fit its height", it.didOverflowHeight)
            // Text shaping uses fractional pixels; the raster width is an integer.
            assertTrue(
                "Action label $label must fit its rendered width",
                it.getLineRight(0) - it.getLineLeft(0) <= it.size.width + 1f,
            )
        }
    }
}
