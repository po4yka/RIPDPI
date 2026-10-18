package com.poyka.ripdpi.activities

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.platform.LocaleTestActivity
import org.junit.Assert.assertEquals
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class RetainedLocaleTestSupport {
    private val activities = mutableListOf<ActivityController<LocaleTestActivity>>()

    fun select(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        activities += Robolectric.buildActivity(LocaleTestActivity::class.java).create()
        awaitLocaleStorage()
        assertEquals(
            "AppCompat must persist the selected locale before resolver assertions",
            tag,
            LocaleManagerCompat.getApplicationLocales(RuntimeEnvironment.getApplication()).toLanguageTags(),
        )
    }

    fun englishContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration =
            Configuration(application.resources.configuration).apply {
                setLocales(LocaleList(Locale.ENGLISH))
            }
        return application.createConfigurationContext(configuration)
    }

    // AppCompat 1.8 writes locales on this serial executor, outside Robolectric's schedulers.
    private fun awaitLocaleStorage() {
        val executor =
            AppCompatDelegate::class.java
                .getDeclaredField("sSerialExecutorForLocalesStorage")
                .apply { isAccessible = true }
                .get(null) as Executor
        val completed = CountDownLatch(1)
        executor.execute { completed.countDown() }
        check(completed.await(5, TimeUnit.SECONDS)) { "AppCompat locale storage did not become idle" }
    }

    fun close() {
        activities.forEach { it.destroy() }
        awaitLocaleStorage()
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }
}
