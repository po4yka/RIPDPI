package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.ui.logs.LogEntryMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LogAggregatorUseCaseTest {
    private val aggregator =
        LogAggregatorUseCase(
            LogEntryMapper(FakeStringResolver(), LocalizedTimestampFormatter(RuntimeEnvironment.getApplication())),
        )

    @Test
    fun `merge preserves first source precedence and stable equal timestamp order`() {
        val manual = entry("manual", "duplicate", 42L)
        val service = entry("service", "service", 42L)
        val runtime = entry("runtime", "runtime", 42L)
        val diagnostics = entry("diagnostics", "diagnostics", 42L)

        val result =
            aggregator.merge(
                manualLogs = listOf(manual),
                serviceLogs = listOf(manual.copy(id = "duplicate-service"), service),
                runtimeLogs = listOf(manual.copy(id = "duplicate-runtime"), runtime),
                diagnosticsLogs = listOf(manual.copy(id = "duplicate-diagnostics"), diagnostics),
                clearedAfter = null,
            )

        assertEquals(listOf("manual", "service", "runtime", "diagnostics"), result.map { it.id })
    }

    @Test
    fun `merge filters before retaining newest 250 and keeps clear boundary inclusive`() {
        val result =
            aggregator.merge(
                manualLogs = (0L..300L).reversed().map { entry("$it", "entry-$it", it) },
                serviceLogs = emptyList(),
                runtimeLogs = emptyList(),
                diagnosticsLogs = emptyList(),
                clearedAfter = 51L,
            )

        assertEquals((51L..300L).toList(), result.map { it.createdAtMs })
    }

    @Test
    fun `persisted diagnostic event keeps precedence over matching live event`() {
        val persisted =
            DiagnosticEvent(
                id = "persisted",
                sessionId = "completed-scan",
                source = "dns",
                level = "warn",
                message = "Probe completed",
                createdAt = 42L,
            )

        val result =
            aggregator.diagnosticLogs(
                persisted = listOf(persisted),
                live = listOf(persisted.copy(id = "live")),
                activeProgress = null,
                activeConnection = null,
            )

        assertEquals(listOf("persisted"), result.map { it.id })
        assertFalse(result.single().isActiveSession)
    }

    private fun entry(
        id: String,
        message: String,
        createdAt: Long,
    ): LogEntry =
        LogEntry(
            id = id,
            createdAtMs = createdAt,
            timestamp = "00:00:00",
            subsystem = LogSubsystem.Proxy,
            severity = LogSeverity.Info,
            message = message,
            source = "test",
        )
}
