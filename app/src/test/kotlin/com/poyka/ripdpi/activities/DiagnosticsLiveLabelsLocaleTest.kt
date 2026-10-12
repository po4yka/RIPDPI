package com.poyka.ripdpi.activities

import android.content.res.Configuration
import android.os.LocaleList
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DiagnosticsLiveLabelsLocaleTest {
    @Test
    fun `Russian error warning and information summaries select all plural boundaries`() {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag("ru")))
        val support = DiagnosticsUiFactorySupport(application.createConfigurationContext(configuration))
        val counts = listOf(1, 2, 5, 21, 22, 25)
        val errors = listOf("ошибка", "ошибки", "ошибок", "ошибка", "ошибки", "ошибок")
        val warnings =
            listOf(
                "предупреждение",
                "предупреждения",
                "предупреждений",
                "предупреждение",
                "предупреждения",
                "предупреждений",
            )
        val information =
            listOf(
                "информационное событие",
                "информационных события",
                "информационных событий",
                "информационное событие",
                "информационных события",
                "информационных событий",
            )

        counts.forEachIndexed { index, count ->
            assertEquals(
                "$count ${errors[index]} в потоке событий",
                support.buildLiveEventSummaryLabel(events(count, "error")),
            )
            assertEquals(
                "$count ${warnings[index]} в потоке событий",
                support.buildLiveEventSummaryLabel(events(count, "warn")),
            )
            assertEquals(
                "$count ${information[index]} в потоке событий",
                support.buildLiveEventSummaryLabel(events(count, "info")),
            )
            assertEquals(
                "$count ${errors[index]} · $count ${warnings[index]} в потоке событий",
                support.buildLiveEventSummaryLabel(events(count, "error") + events(count, "warn")),
            )
        }
    }

    private fun events(
        count: Int,
        level: String,
    ): List<DiagnosticEvent> =
        List(
            count,
        ) { index ->
            DiagnosticEvent(
                "$level-$index",
                null,
                source = "test",
                level = level,
                message = "",
                createdAt = 1,
            )
        }
}
