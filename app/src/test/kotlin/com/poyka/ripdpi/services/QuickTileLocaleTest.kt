package com.poyka.ripdpi.services

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.LocaleTestActivity
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** A legacy Service context stays English while the tile follows the app locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class QuickTileLocaleTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @After fun cleanup() {
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }

    @Test fun tileRendererFollowsAppLocaleOnAnEnglishService() {
        cleanup()
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.ENGLISH)) }
        val englishServiceContext = app.createConfigurationContext(configuration)
        // Do not create the Hilt graph: renderTileState reads only Android context and qsTile.
        val service = Robolectric.buildService(QuickTileService::class.java).get()
        // Robolectric shares application updates. Keep the service context English, as on
        // legacy Android where AppCompat's activity override does not configure a TileService.
        ContextWrapper::class.java
            .getDeclaredField("mBase")
            .apply { isAccessible = true }
            .set(service, englishServiceContext)
        for (tag in listOf("ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR")) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            Robolectric.buildActivity(LocaleTestActivity::class.java).create().destroy()
            assertEquals(tag, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(
                "en",
                service.resources.configuration.locales[0]
                    .language,
            )
            val expected =
                app.createConfigurationContext(
                    Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
                )
            for (state in listOf(QuickTileVisualState.Active, QuickTileVisualState.Inactive)) {
                val id =
                    if (state == QuickTileVisualState.Active) {
                        R.string.qs_tile_label_connected
                    } else {
                        R.string.qs_tile_label_disconnected
                    }
                service.renderTileState(state)
                if (state == QuickTileVisualState.Active) {
                    org.junit.Assert.assertNotEquals(
                        englishServiceContext.getString(id),
                        service.qsTile.label.toString(),
                    )
                }
                assertEquals("tile label $tag/$state", expected.getString(id), service.qsTile.label.toString())
                val subtitleId =
                    if (state == QuickTileVisualState.Active) {
                        R.string.qs_tile_subtitle_tap_disconnect
                    } else {
                        R.string.qs_tile_subtitle_tap_connect
                    }
                assertEquals(
                    "tile subtitle $tag/$state",
                    expected.getString(subtitleId),
                    service.qsTile.subtitle.toString(),
                )
                org.junit.Assert.assertNotEquals(
                    englishServiceContext.getString(subtitleId),
                    service.qsTile.subtitle.toString(),
                )
            }
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Robolectric.buildActivity(LocaleTestActivity::class.java).create().destroy()
        service.renderTileState(QuickTileVisualState.Active)
        assertEquals(englishServiceContext.getString(R.string.qs_tile_label_connected), service.qsTile.label.toString())
        assertEquals(
            englishServiceContext.getString(R.string.qs_tile_subtitle_tap_disconnect),
            service.qsTile.subtitle.toString(),
        )
        service.renderTileState(QuickTileVisualState.Unavailable)
        org.junit.Assert.assertNull(service.qsTile.subtitle)
    }
}
