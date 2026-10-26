package com.poyka.ripdpi.platform

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsUiFormatter
import com.poyka.ripdpi.activities.formatTelegramThroughput
import com.poyka.ripdpi.utility.createConnectionNotification
import com.poyka.ripdpi.utility.createDynamicConnectionNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.text.Bidi
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class AndroidStringResolverLocaleTest {
    private val activities = mutableListOf<ActivityController<LocaleTestActivity>>()

    @Before
    fun resetLocaleState() {
        resetAppCompatLocaleCache()
    }

    @After
    fun destroyActivities() {
        activities.forEach { it.destroy() }
        resetAppCompatLocaleCache()
    }

    @Test
    fun `application resolver uses persisted Russian locale and follows reset to system language`() {
        createActivity()
        selectLanguage("ru")
        // Robolectric shares resource updates across Activity and Application contexts.
        // Keep an explicit English context to exercise the pre-API-33 non-Activity contract.
        val englishContext = englishContext(RuntimeEnvironment.getApplication())
        val english = englishContext.getString(R.string.language_picker_system_language)
        val resolver = AndroidStringResolver(englishContext)

        assertEquals(
            "en",
            englishContext.resources.configuration.locales[0]
                .language,
        )
        assertEquals("Язык системы", resolver.getString(R.string.language_picker_system_language))
        assertEquals(
            "2 ошибки в потоке событий",
            resolver.getQuantityString(R.plurals.diagnostics_live_errors_format, 2, 2),
        )

        selectLanguage("")
        assertEquals(english, resolver.getString(R.string.language_picker_system_language))
    }

    @Test
    fun `count labels agree with their number in English and Russian`() {
        createActivity()
        val resolver = AndroidStringResolver(englishContext(RuntimeEnvironment.getApplication()))

        fun networks(count: Int) = resolver.getQuantityString(R.plurals.remembered_networks_count_summary, count, count)

        selectLanguage("en")
        assertEquals("1 remembered network", networks(1))
        assertEquals("2 remembered networks", networks(2))
        selectLanguage("ru")
        assertEquals("1 запомненная сеть", networks(1))
        assertEquals("3 запомненные сети", networks(3))
        assertEquals("5 запомненных сетей", networks(5))
        assertEquals("21 запомненная сеть", networks(21))
    }

    @Test
    fun `Arabic text arguments keep a following number out of their Latin run`() {
        createActivity()
        val resolver = AndroidStringResolver(englishContext(RuntimeEnvironment.getApplication()))

        fun subtitle() = resolver.getString(R.string.history_connection_subtitle_format, "Wi-Fi", "25 أكتوبر، 7:30 م")

        selectLanguage("ar")
        val arabic = subtitle()
        // In an RTL paragraph the separator must stay in the RTL flow (odd level). If "Wi-Fi"
        // pulls "· 25" into its LTR run, the separator gets the even LTR level instead.
        val bidi = Bidi(arabic, Bidi.DIRECTION_RIGHT_TO_LEFT)
        assertEquals(1, bidi.getLevelAt(arabic.indexOf('·')))

        selectLanguage("en")
        assertEquals("Wi-Fi · 25 أكتوبر، 7:30 م", subtitle())
    }

    @Test
    fun `Arabic text copied or shared carries no bidi isolate controls`() {
        createActivity()
        val resolver = AndroidStringResolver(englishContext(RuntimeEnvironment.getApplication()))
        selectLanguage("ar")

        val shown = resolver.getString(R.string.history_connection_subtitle_format, "Wi-Fi", "25 أكتوبر")
        val copied = shown.withoutBidiIsolates()

        assertTrue("Expected the screen text to carry isolates", shown.any { it.code in 0x2066..0x2069 })
        assertTrue("Expected the copied text to keep the argument", copied.contains("Wi-Fi"))
        assertTrue("Expected no isolates in the copied text", copied.none { it.code in 0x2066..0x2069 })
    }

    @Test
    fun `diagnostics numbers use selected Spanish on English system and follow language switch`() {
        createActivity()
        selectLanguage("es")
        val context = englishContext(RuntimeEnvironment.getApplication())
        val formatter = DiagnosticsUiFormatter(context)
        assertEquals(
            "en",
            context.resources.configuration.locales[0]
                .language,
        )
        assertEquals("1,5 kB", formatter.formatBytes(1_500L))
        assertEquals("1,5 MB", formatter.formatBytes(1_500_000L))
        assertEquals("1,5 GB", formatter.formatBytes(1_500_000_000L))
        assertEquals("1,5 Kbps", formatter.formatBps(1_500L))
        assertEquals("1,5 Mbps", formatter.formatBps(1_500_000L))

        selectLanguage("en")

        assertEquals("1.5 kB", formatter.formatBytes(1_500L))
        assertEquals("1.5 Mbps", formatter.formatBps(1_500_000L))
    }

    @Test
    fun `Telegram numbers use selected German on an English system and return to English`() {
        createActivity()
        val formatter = DiagnosticsUiFormatter(englishContext(RuntimeEnvironment.getApplication()))
        selectLanguage("en")
        assertEquals("1.5 Mbps", formatTelegramThroughput(187_500L, formatter.locale))
        selectLanguage("de")
        assertEquals("1,5 Mbps", formatTelegramThroughput(187_500L, formatter.locale))
        selectLanguage("en")
        assertEquals("1.5 Mbps", formatTelegramThroughput(187_500L, formatter.locale))
    }

    @Test
    fun `diagnostics number unit boundaries remain stable in Spanish`() {
        createActivity()
        selectLanguage("es")
        val formatter = DiagnosticsUiFormatter(englishContext(RuntimeEnvironment.getApplication()))
        assertEquals("0 B", formatter.formatBytes(0L))
        assertEquals("1,00 kB", formatter.formatBytes(999L))
        assertEquals("1,0 kB", formatter.formatBytes(1_000L))
        assertEquals("1,0 MB", formatter.formatBytes(1_000_000L))
        assertEquals("1,0 GB", formatter.formatBytes(1_000_000_000L))
        assertEquals("0 Bps", formatter.formatBps(0L))
        assertEquals("999 Bps", formatter.formatBps(999L))
        assertEquals("1,0 Kbps", formatter.formatBps(1_000L))
        assertEquals("1,0 Mbps", formatter.formatBps(1_000_000L))
        assertEquals("0s", formatter.formatDurationMs(-1L))
        assertEquals("1m 00s", formatter.formatDurationMs(60_000L))
        assertEquals("1h 00m", formatter.formatDurationMs(3_600_000L))
    }

    @Test
    fun `AndroidX restores saved Russian selection after its in-memory locale cache is cleared`() {
        createActivity()
        selectLanguage("ru")
        val application = RuntimeEnvironment.getApplication()
        assertEquals("ru", LocaleManagerCompat.getApplicationLocales(application).toLanguageTags())
        activities.forEach { it.destroy() }
        activities.clear()
        resetAppCompatLocaleCache()
        assertEquals("", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        val restored = createActivity().get()

        assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
        assertEquals(
            "ru",
            restored.resources.configuration.locales[0]
                .language,
        )
        assertEquals(
            "Язык системы",
            AndroidStringResolver(application).getString(R.string.language_picker_system_language),
        )
    }

    @Test
    fun `foreground notification text uses selected Russian locale outside Activity context`() {
        createActivity()
        selectLanguage("ru")
        val application = englishContext(RuntimeEnvironment.getApplication())
        assertEquals(
            "en",
            application.resources.configuration.locales[0]
                .language,
        )
        val staticNotification =
            createConnectionNotification(
                context = application,
                channelId = "locale-test",
                title = mergedStringId(application, "notification_title"),
                content = mergedStringId(application, "vpn_notification_content"),
                service = Service::class.java,
            )
        val dynamicNotification =
            createDynamicConnectionNotification(
                context = application,
                channelId = "locale-test",
                title = "RIPDPI",
                content = "↑ 0 B  ↓ 0 B",
                subText = null,
                service = Service::class.java,
                whenTimestamp = 1L,
            )

        assertEquals(
            "Остановить",
            staticNotification.actions
                .single()
                .title
                .toString(),
        )
        assertEquals(
            "Остановить",
            dynamicNotification.actions
                .single()
                .title
                .toString(),
        )
        assertEquals("Локальные процессы VPN запущены", staticNotification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `dynamic notification traffic uses selected Spanish on English service and follows language switch`() {
        createActivity()
        selectLanguage("es")
        val serviceController = Robolectric.buildService(LocaleTestService::class.java).create()
        val context = englishContext(serviceController.get())
        // The content builder is internal to core/service, as is the paused-notification
        // function tested below. Use the same reflection boundary for its production output.
        val builderClass = Class.forName("com.poyka.ripdpi.utility.NotificationContentBuilder")
        val builder = builderClass.getField("INSTANCE").get(null)
        val buildContentText =
            builderClass.getDeclaredMethod(
                "buildContentText",
                Context::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
        try {
            listOf(
                Triple("es", "↑ 1,5 MB  ↓ 1,5 GB  |  01:01:01", "Detener"),
                Triple("en", "↑ 1.5 MB  ↓ 1.5 GB  |  01:01:01", "Stop"),
            ).forEach { (language, expectedContent, expectedAction) ->
                selectLanguage(language)
                assertEquals(
                    "en",
                    context.resources.configuration.locales[0]
                        .language,
                )
                val content =
                    buildContentText.invoke(
                        builder,
                        context,
                        1_500_000L,
                        1_500_000_000L,
                        3_661_000L,
                    ) as String
                val notification =
                    createDynamicConnectionNotification(
                        context = context,
                        channelId = "locale-test",
                        title = "RIPDPI",
                        content = content,
                        subText = null,
                        service = LocaleTestService::class.java,
                        whenTimestamp = 1L,
                    )
                assertEquals(expectedContent, notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(
                    expectedAction,
                    notification.actions
                        .single()
                        .title
                        .toString(),
                )
            }
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun `paused foreground notification uses app Russian for every phase and its actions`() {
        createActivity()
        selectLanguage("ru")
        val serviceController = Robolectric.buildService(LocaleTestService::class.java).create()
        val service = serviceController.get()
        val originalTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        android.provider.Settings.System.putString(
            service.contentResolver,
            android.provider.Settings.System.TIME_12_24,
            null,
        )
        val deadlineMillis = 48_600_000L
        val startPausedForeground =
            Class.forName("com.poyka.ripdpi.services.PausedForegroundNotificationKt").getDeclaredMethod(
                "startPausedForeground",
                Service::class.java,
                com.poyka.ripdpi.data.PauseIntent::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        val messages = pausedNotificationMessages()
        try {
            assertEquals(
                "en",
                service.resources.configuration.locales[0]
                    .language,
            )
            messages.forEach { (phase, expectedContent) ->
                val intent =
                    com.poyka.ripdpi.data.PauseIntent(
                        generation = 1L,
                        token = "locale-fixture",
                        mode = "vpn",
                        createdWallMillis = 0L,
                        createdElapsedMillis = 0L,
                        deadlineWallMillis = deadlineMillis,
                        deadlineElapsedMillis = deadlineMillis,
                        bootCount = 1,
                        phase = phase,
                        failure = null,
                    )
                startPausedForeground.invoke(null, service, intent, "locale-test", 1)
                val notification =
                    org.robolectric.Shadows
                        .shadowOf(service)
                        .lastForegroundNotification
                assertEquals("Приостановить соединение", notification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(expectedContent, notification.extras.getString(Notification.EXTRA_TEXT))
                val actionLabels =
                    if (phase == com.poyka.ripdpi.data.PausePhase.Paused ||
                        phase == com.poyka.ripdpi.data.PausePhase.Deferred
                    ) {
                        listOf("Возобновить сейчас", "Остановить")
                    } else {
                        listOf("Остановить")
                    }
                assertEquals(actionLabels, notification.actions.map { it.title.toString() })
                val actions =
                    notification.actions.map {
                        org.robolectric.Shadows
                            .shadowOf(
                                it.actionIntent,
                            ).savedIntent.action
                    }
                assertEquals("com.poyka.ripdpi.pause.STOP", actions.last())
                if (actionLabels.size == 2) assertEquals("com.poyka.ripdpi.pause.RESUME", actions.first())
            }
        } finally {
            java.util.TimeZone.setDefault(originalTimeZone)
            serviceController.destroy()
        }
    }

    private fun pausedNotificationMessages(): Map<com.poyka.ripdpi.data.PausePhase, String> =
        mapOf(
            com.poyka.ripdpi.data.PausePhase.Paused to "Пауза до 13:30",
            com.poyka.ripdpi.data.PausePhase.CleanupPending to
                "Освобождение ресурсов не завершено. Пауза ещё не подтверждена.",
            com.poyka.ripdpi.data.PausePhase.Releasing to
                "Освобождение ресурсов не завершено. Пауза ещё не подтверждена.",
            com.poyka.ripdpi.data.PausePhase.Resuming to "Возобновление соединения…",
            com.poyka.ripdpi.data.PausePhase.Deferred to "Возобновление требует внимания",
        )

    private fun selectLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        // Creating a delegate syncs the requested locale to AndroidX storage.
        createActivity()
    }

    private fun mergedStringId(
        context: Context,
        name: String,
    ): Int =
        context.resources.getIdentifier(name, "string", context.packageName).also { id ->
            org.junit.Assert.assertNotEquals("Merged service resource must exist: $name", 0, id)
        }

    private fun createActivity(): ActivityController<LocaleTestActivity> =
        Robolectric.buildActivity(LocaleTestActivity::class.java).create().also(activities::add)

    private fun resetAppCompatLocaleCache() {
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }
}

class LocaleTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_RIPDPI)
        super.onCreate(savedInstanceState)
    }
}

class LocaleTestService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(englishContext(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun englishContext(context: Context): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList(Locale.ENGLISH))
    return context.createConfigurationContext(configuration)
}
