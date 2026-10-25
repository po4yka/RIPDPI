package com.poyka.ripdpi.ui.screens.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.FakeSeqModeDuplicate
import com.poyka.ripdpi.data.FakeSeqModeSequential
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownLabels
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w420dp-h1100dp-mdpi")
class SettingsHindiLocalizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `built in descriptions localize and custom descriptions remain exact`() {
        val app = RuntimeEnvironment.getApplication()
        val english =
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                },
            )
        val presets = previewHostPackPresets()
        presets.forEach { preset ->
            val resource = checkNotNull(hostPackDescriptionResId(preset))
            assertEquals(preset.description, english.getString(resource))
            assertNotEquals(preset.description, app.getString(resource))
            assertTrue(app.getString(resource).any { it in '\u0900'..'\u097F' })
            assertNull(hostPackDescriptionResId(preset.copy(description = "My custom description")))
            assertNull(hostPackDescriptionResId(preset.copy(id = "custom")))
        }
        assertNull(hostPackDescriptionResId(HostPackPreset("youtube", "YouTube", "", 0, emptyList())))
    }

    @Test
    fun `catalog cards update dates from English to Hindi and back with system English`() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
        try {
            val app = RuntimeEnvironment.getApplication()
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
            val configuration =
                mutableStateOf(
                    Configuration(app.resources.configuration).apply {
                        setLocale(Locale.ENGLISH)
                    },
                )
            composeRule.setContent {
                val context = app.createConfigurationContext(configuration.value)
                CompositionLocalProvider(
                    LocalConfiguration provides configuration.value,
                    LocalContext provides context,
                ) {
                    RipDpiTheme {
                        Column {
                            HostPackCatalogStatusCard(
                                hostPackCatalog = previewHostPackCatalog("bundled", 1_773_306_000_000),
                                onRefreshCatalog = {},
                            )
                            StrategyPackCatalogStatusCard(
                                strategyPackCatalog = previewStrategyPackCatalogForLocaleTest(),
                                onRefreshCatalog = {},
                            )
                        }
                    }
                }
            }
            listOf("en" to "Mar 12, 13:00", "hi" to "12 मार्च, 13:00", "en" to "Mar 12, 13:00")
                .forEach { (tag, expected) ->
                    composeRule.runOnIdle {
                        configuration.value =
                            Configuration(configuration.value).apply {
                                setLocale(Locale.forLanguageTag(tag))
                            }
                    }
                    assertEquals(3, composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().size)
                    assertEquals(Locale.ENGLISH, Locale.getDefault())
                }
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `Hindi descriptions and sequence options render at normal font scale`() = checkLabels(1f, "hi-settings")

    @Test
    fun `Hindi descriptions and sequence options fit large font scale`() = checkLabels(1.5f, "hi-settings-large")

    private fun checkLabels(
        fontScale: Float,
        name: String,
    ) {
        val app = RuntimeEnvironment.getApplication()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                RipDpiTheme {
                    Column(Modifier.requiredWidth(360.dp)) {
                        previewHostPackPresets().forEach { preset ->
                            HostPackPresetCard(preset, enabled = true, selected = false, onClick = {})
                        }
                        val options = rememberFakeSeqModeOptions()
                        assertEquals(listOf(FakeSeqModeDuplicate, FakeSeqModeSequential), options.map { it.value })
                        options.forEach { option ->
                            RipDpiDropdown(
                                options = options,
                                selectedValue = option.value,
                                onValueSelected = {},
                                labels = RipDpiDropdownLabels(),
                            )
                        }
                    }
                }
            }
        }
        val file = File("build/compose-previews/renders/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            assertTrue(
                composeRule
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        val resources =
            listOf(
                R.string.host_pack_youtube_description,
                R.string.host_pack_telegram_description,
                R.string.host_pack_discord_description,
                R.string.ripdpi_fake_seq_mode_duplicate,
                R.string.ripdpi_fake_seq_mode_sequential,
            )
        resources.forEach { resource ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(app.getString(resource), useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse(
                    "Clipped: ${layout.layoutInput.text}; size=${layout.size}; lines=${layout.lineCount}; " +
                        "widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; " +
                        "constraints=${layout.layoutInput.constraints}; style=${layout.layoutInput.style}",
                    layout.hasVisualOverflow,
                )
            }
        }
    }
}

private fun previewStrategyPackCatalogForLocaleTest() =
    com.poyka.ripdpi.activities.StrategyPackCatalogUiState(
        runtimeState =
            com.poyka.ripdpi.data.StrategyPackRuntimeState(
                snapshot =
                    com.poyka.ripdpi.data
                        .StrategyPackSnapshot(lastFetchedAtEpochMillis = 1_773_306_000_000),
            ),
    )
