package com.poyka.ripdpi.platform

import android.content.res.Configuration
import com.poyka.ripdpi.R
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeLabelsLocaleTest {
    @Test
    fun themeLabelsAreLocalizedAndSelectionKeysStayStable() {
        val labels =
            mapOf(
                "en" to arrayOf("System", "Light", "Dark"),
                "ru" to arrayOf("Системная", "Светлая", "Тёмная"),
                "es" to arrayOf("Sistema", "Claro", "Oscuro"),
                "de" to arrayOf("System", "Hell", "Dunkel"),
                "fr" to arrayOf("Système", "Clair", "Sombre"),
                "fa" to arrayOf("سیستم", "روشن", "تیره"),
                "ar" to arrayOf("النظام", "فاتح", "داكن"),
                "zh-CN" to arrayOf("跟随系统", "浅色", "深色"),
                "hi" to arrayOf("सिस्टम", "हल्की", "गहरी"),
                "pt-BR" to arrayOf("Sistema", "Claro", "Escuro"),
            )
        val application = RuntimeEnvironment.getApplication()
        labels.forEach { (locale, expected) ->
            val configuration =
                Configuration(application.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(locale))
                }
            val resources = application.createConfigurationContext(configuration).resources
            assertArrayEquals(locale, expected, resources.getStringArray(R.array.themes))
            assertArrayEquals(
                locale,
                arrayOf("system", "light", "dark"),
                resources.getStringArray(R.array.themes_entries),
            )
        }
    }
}
