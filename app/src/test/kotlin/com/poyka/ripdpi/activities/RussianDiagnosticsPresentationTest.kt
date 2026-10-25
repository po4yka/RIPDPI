package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteEvent
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.SuiteVerdict
import com.poyka.ripdpi.ui.diagnostics.StrategySignaturePresenter
import com.poyka.ripdpi.ui.diagnostics.StrategySignatureValueLabels
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru", sdk = [35], application = Application::class)
class RussianDiagnosticsPresentationTest {
    private val strings = ResourceStringResolver()

    @Test
    fun `all suite summaries and verdicts use Russian resources`() {
        SuiteVerdict.entries.forEach { verdict ->
            assertRussian(verdict.summary(strings))
            assertRussian(verdict.label(strings))
        }
        DpiProbeKind.entries.filterNot { it == DpiProbeKind.TELEGRAM }.forEach { kind ->
            assertRussian(kind.displayLabel(strings))
        }
    }

    @Test
    fun `running rows use localized status and preserve progress counts`() {
        val started = DpiProbeKind.TCP16.startedRow(strings)
        assertRussian(started.status)
        assertEquals("Проверка выполняется.", started.detail)
        val progress = DpiSuiteEvent.ProbeProgress(DpiProbeKind.TCP16, 2, 5).progressRow(strings)
        assertEquals("Завершено шагов: 2/5.", progress.detail)
        assertEquals(started.status, progress.status)
    }

    @Test
    fun `ECH details localize known states and preserve target identifiers`() {
        val row =
            DpiSuiteProbeResult
                .EchReadiness(
                    listOf(
                        EchProbeResult(
                            target = "target_1.example",
                            verdict = EchProbeVerdict.ECH_UNKNOWN_KEY,
                            httpsRrFetched = true,
                            echConfigBytesB64 = null,
                            tlsLatencyMs = null,
                            negotiatedEch = false,
                            errorDetail = "wire-error",
                            vanillaTlsOk = null,
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel(strings)

        assertEquals("Целей, принявших ECH: 0/1", row.detail)
        assertEquals("target_1.example", row.detailRows.single().label)
        assertEquals(
            "неизвестный ключ ECH | HTTPS RR: да | согласовано: нет | обычный TLS: неизвестно | задержка не измерена",
            row.detailRows.single().detail,
        )
        assertEquals(DiagnosticsTone.Warning, row.detailRows.single().tone)
    }

    @Test
    fun `failure and cancellation localize summaries and retain partial evidence`() {
        val partial =
            DpiSuiteProbeResult
                .Failed(DpiProbeKind.TCP16, "custom-wire-error")
                .toDpiSuiteProbeRowUiModel(strings)
        val running =
            DiagnosticsDpiSuiteToolUiModel(
                state = DiagnosticsDpiSuiteState.Running,
                rows = persistentListOf(partial),
            )
        val cancelled = running.withFailure(CancellationException(), strings)
        assertEquals(DiagnosticsDpiSuiteState.Cancelled, cancelled.state)
        assertEquals("Набор проверок DPI отменён; частичные результаты сохранены.", cancelled.summary)
        assertEquals(running.rows, cancelled.rows)

        val failed = running.withFailure(IllegalStateException("user-error-id"), strings)
        assertEquals(DiagnosticsDpiSuiteState.Failed, failed.state)
        assertEquals("Не удалось выполнить набор проверок DPI.", failed.summary)
        assertEquals("Что-то пошло не так. Подробности в разделе «Журналы».", failed.errorMessage)
        assertEquals("custom-wire-error", failed.rows.single().detail)
        assertRussian(failed.rows.single().status)
    }

    @Test
    fun `known skip reason is localized and custom reason remains verbatim`() {
        val known =
            DpiSuiteProbeResult
                .Skipped(DpiProbeKind.WHITELIST_SNI, "TCP16 detected zero blocked ASNs")
                .toDpiSuiteProbeRowUiModel(strings)
        assertEquals("TCP16 не выявил заблокированных ASN", known.detail)
        assertRussian(known.status)
        val custom =
            DpiSuiteProbeResult
                .Skipped(DpiProbeKind.WHITELIST_SNI, "custom_reason")
                .toDpiSuiteProbeRowUiModel(strings)
        assertEquals("custom_reason", custom.detail)
    }

    @Test
    fun `strategy fields localize known prose and retain user and wire values`() {
        val fields =
            StrategySignaturePresenter(strings).fields(
                BypassStrategySignature(
                    mode = "VPN",
                    configSource = "ui",
                    hostAutolearn = "enabled",
                    desyncMethod = "split",
                    chainSummary = "split@host+1",
                    protocolToggles = listOf("TCP", "QUIC"),
                    tlsRecordSplitEnabled = true,
                    tcpStrategyFamily = "circular_seqovl",
                    splitMarker = "host+1",
                    fakeTtlMode = "adaptive_custom",
                    adaptiveFakeTtlBias = -2,
                    fakeSniMode = "fixed",
                    fakeSniValue = "custom_sni.example",
                    fakePayloadSource = "custom_vendor_payload",
                    quicFakeHost = "custom_quic.example",
                    routeGroup = "custom_group",
                ),
            )
        assertTrue(fields.all { field -> field.label.any { it in 'А'..'я' } })
        val values = fields.map { it.value }
        assertTrue(values.containsAll(listOf("VPN", "split", "split@host+1", "TCP/QUIC", "host+1", "custom_group")))
        assertTrue(values.containsAll(listOf("custom_vendor_payload", "custom_quic.example")))
        assertTrue(values.any { it.startsWith("Циклическая ротация (") })
        assertTrue(values.contains("Сначала меньшие TTL (-2)"))
        assertTrue(values.contains("Фиксированный (custom_sni.example)"))
        assertTrue(values.any { it == "включено" })
    }

    @Test
    fun `stable adaptive markers localize while custom lane and offsets stay verbatim`() {
        val labels = StrategySignatureValueLabels(strings)
        assertEquals("адаптивное начало Host/SNI", labels.offset("auto(host)"))
        assertEquals("vendor_custom_lane", labels.lane("vendor_custom_lane"))
        assertEquals("circular_VENDOR_Custom", labels.lane("circular_VENDOR_Custom"))
        assertEquals("sniext+4", labels.offset("sniext+4"))
    }

    private fun assertRussian(value: String) {
        assertTrue("Expected Russian presentation: $value", value.any { it in 'А'..'я' })
    }
}
