package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDomainReachabilityState
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
        assertEquals(
            expected,
            DiagnosticsDnsIntegrityState.entries.map { context.getString(diagnosticToolStateLabelRes(it)) },
        )
        assertEquals(
            expected,
            DiagnosticsDomainReachabilityState.entries.map { context.getString(diagnosticToolStateLabelRes(it)) },
        )
    }
}
