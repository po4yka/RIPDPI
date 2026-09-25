package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class DiagnosticToolStateLabelTest {
    @Test
    fun bothToolCardsResolveAllStatesInTheSelectedLocale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = listOf("Простой", "Выполняется", "Завершено", "Ошибка")
        val states =
            listOf(
                DiagnosticsDnsIntegrityState.entries.toList(),
                DiagnosticsDomainReachabilityState.entries.toList(),
            )

        states.forEach { toolStates ->
            assertEquals(expected, toolStates.map { context.getString(diagnosticToolStateLabelRes(it)) })
        }
    }
}
