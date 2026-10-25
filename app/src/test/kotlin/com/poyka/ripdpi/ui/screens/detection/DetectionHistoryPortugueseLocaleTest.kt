package com.poyka.ripdpi.ui.screens.detection

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "en-w420dp-h900dp-mdpi", application = Application::class)
class DetectionHistoryPortugueseLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `stored generated unknown network follows Portuguese English Portuguese without mutation`() {
        val entry = DetectionHistoryEntry("unknown", "Unknown", 1L, "NOT_DETECTED", 80, 0)
        val context = mutableStateOf(languageContext("pt-BR"))
        composeRule.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme { HistoryCard(listOf(entry)) }
            }
        }
        composeRule.onNodeWithText("Rede desconhecida").assertIsDisplayed()
        capture("ptbr-detection-history-manual")
        composeRule.runOnIdle { context.value = languageContext("en") }
        composeRule.onNodeWithText("Unknown network").assertIsDisplayed()
        capture("en-detection-history-manual")
        composeRule.runOnIdle { context.value = languageContext("pt-BR") }
        composeRule.onNodeWithText("Rede desconhecida").assertIsDisplayed()
        assertEquals("Unknown", entry.networkSummary)
        assertEquals("unknown", entry.networkFingerprint)
    }

    @Test
    fun `scheduler fallback is translated while actual fingerprints custom ISP and transport names stay intact`() {
        val entries =
            listOf(
                DetectionHistoryEntry("unknown", "Unknown network", 1L, "NOT_DETECTED", 80, 0),
                DetectionHistoryEntry("captured-network-1", "Unknown", 2L, "NOT_DETECTED", 80, 0),
                DetectionHistoryEntry("captured-network-2", "Unknown network", 3L, "NOT_DETECTED", 80, 0),
                DetectionHistoryEntry("unknown", "Unknown ISP", 4L, "NOT_DETECTED", 80, 0),
                DetectionHistoryEntry("captured-network-3", "wifi/wifi", 5L, "NOT_DETECTED", 80, 0),
            )
        val original = entries.map { it.networkFingerprint to it.networkSummary }
        val context = languageContext("pt-BR")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                RipDpiTheme { HistoryCard(entries) }
            }
        }
        listOf("Rede desconhecida", "Unknown", "Unknown network", "Unknown ISP", "wifi/wifi").forEach { text ->
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
        capture("ptbr-detection-history-preserved-names")
        assertEquals(original, entries.map { it.networkFingerprint to it.networkSummary })
    }

    @Test
    fun `generated history summary is readable in Portuguese with large text on narrow screen`() {
        val context = languageContext("pt-BR")
        val entry = DetectionHistoryEntry("unknown", "Unknown network", 1L, "NOT_DETECTED", 80, 0)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(1f, 1.5f),
            ) {
                RipDpiTheme { Box(Modifier.width(360.dp)) { HistoryCard(listOf(entry)) } }
            }
        }
        composeRule.onNodeWithText("Rede desconhecida").assertIsDisplayed()
        capture("ptbr-detection-history-large")
        assertEquals("Unknown network", entry.networkSummary)
    }

    private fun capture(name: String) {
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    output,
                ),
            )
        }
    }

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            },
        )
    }
}
