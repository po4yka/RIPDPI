package com.poyka.ripdpi.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU")
class RipDpiThemeLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tokenAndMaterialTextFollowTheAppLanguageWhenItChanges() {
        val configuration = mutableStateOf(Configuration().apply { setLocale(Locale.forLanguageTag("ru")) })
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                RipDpiTheme {
                    Column {
                        Text("Приложение", Modifier.testTag("token"), style = RipDpiThemeTokens.type.body)
                        Text("Приложение", Modifier.testTag("material"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Приложение",
                            Modifier.testTag("material-headline"),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                    }
                }
            }
        }
        listOf("ru", "en", "ar").forEach { language ->
            composeRule.runOnIdle {
                configuration.value =
                    Configuration(configuration.value).apply { setLocale(Locale.forLanguageTag(language)) }
            }
            listOf("token", "material", "material-headline").forEach { tag ->
                assertEquals(
                    language,
                    layout(tag)
                        .layoutInput.style.localeList
                        ?.get(0)
                        ?.language,
                )
            }
        }
    }

    @Test
    fun oversizedRussianWordUsesDifferentBreaksWithSystemHyphenation() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Column {
                        val style = RipDpiThemeTokens.type.body
                        Text("Переподключиться", Modifier.requiredWidth(200.dp).testTag("auto"), style = style)
                        Text(
                            "Переподключиться",
                            Modifier.requiredWidth(200.dp).testTag("none"),
                            style = style.copy(hyphens = Hyphens.None),
                        )
                    }
                }
            }
        }
        val automatic = layout("auto")
        val plain = layout("none")
        assertFalse(automatic.hasVisualOverflow)
        assertEquals("Переподключиться".length, automatic.getLineEnd(automatic.lineCount - 1))
        assertNotEquals(
            "The language dictionary must change the word break",
            plain.getLineEnd(0),
            automatic.getLineEnd(0),
        )
    }

    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
}
