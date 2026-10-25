package com.poyka.ripdpi.platform

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsUiFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class AppLanguageTransitionRegressionTest {
    private val tags = listOf("en", "ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR")

    @Before fun reset() = clearCache()

    @After fun cleanup() = clearCache()

    private fun clearCache() {
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }

    private fun select(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        Robolectric.buildActivity(LocaleTestActivity::class.java).create().destroy()
    }

    private fun contextFor(tag: String): Context {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return app.createConfigurationContext(configuration)
    }

    @Test fun `all 90 language transitions and 10 system resets use current resources`() {
        val frozenEnglish = contextFor("en")
        val resolver = AndroidStringResolver(frozenEnglish)
        val formatter = DiagnosticsUiFormatter(frozenEnglish)
        var transitions = 0
        for (from in tags) {
            select(from)
            for (to in tags.filter { it != from }) {
                select(from)
                select(to)
                val expected = contextFor(to)
                assertEquals("$from->$to selected", to, AppCompatDelegate.getApplicationLocales().toLanguageTags())
                assertEquals(
                    "$from->$to scalar",
                    expected.getString(R.string.language_picker_system_language),
                    resolver.getString(R.string.language_picker_system_language),
                )
                for (quantity in listOf(0, 1, 2, 3, 5, 11, 21, 101)) {
                    assertEquals(
                        "$from->$to plural=$quantity",
                        expected.resources.getQuantityString(
                            R.plurals.diagnostics_live_errors_format,
                            quantity,
                            quantity,
                        ),
                        resolver.getQuantityString(R.plurals.diagnostics_live_errors_format, quantity, quantity),
                    )
                }
                assertEquals("$from->$to numeric locale", Locale.forLanguageTag(to).language, formatter.locale.language)
                assertEquals(
                    "$from->$to numbers",
                    Formatter.formatShortFileSize(expected, 1_500),
                    formatter.formatBytes(1_500),
                )
                transitions++
            }
            select(from)
            assertEquals("reset origin", from, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            select("")
            assertTrue("$from -> system selection", AppCompatDelegate.getApplicationLocales().isEmpty)
            assertEquals(
                "$from -> system text",
                frozenEnglish.getString(R.string.language_picker_system_language),
                resolver.getString(R.string.language_picker_system_language),
            )
        }
        assertEquals(90, transitions)
        println("AUDIT: 90 directed transitions and 10 English-system resets passed")
    }

    @Test fun `all 10 persisted language selections restore after AndroidX cache reset`() {
        for (tag in tags) {
            select(tag)
            assertEquals(
                tag,
                LocaleManagerCompat.getApplicationLocales(RuntimeEnvironment.getApplication()).toLanguageTags(),
            )
            clearCache()
            val activity = Robolectric.buildActivity(LocaleTestActivity::class.java).create()
            assertEquals("persisted $tag", tag, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(
                "restored $tag",
                Locale.forLanguageTag(tag).language,
                activity
                    .get()
                    .resources.configuration.locales[0]
                    .language,
            )
            activity.destroy()
        }
        println("AUDIT: 10 persisted selections restored")
    }
}
