package com.poyka.ripdpi.activities

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.platform.LocaleTestActivity
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import java.util.Locale

internal class RetainedLocaleTestSupport {
    private val activities = mutableListOf<ActivityController<LocaleTestActivity>>()

    fun select(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        activities += Robolectric.buildActivity(LocaleTestActivity::class.java).create()
    }

    fun englishContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration =
            Configuration(application.resources.configuration).apply {
                setLocales(LocaleList(Locale.ENGLISH))
            }
        return application.createConfigurationContext(configuration)
    }

    fun close() {
        activities.forEach { it.destroy() }
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }
}
