package com.poyka.ripdpi.activities

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniResult
import com.poyka.ripdpi.diagnostics.dpi.CompatibleSni
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DoqProbeResult
import com.poyka.ripdpi.diagnostics.dpi.DoqVerdict
import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.ByohIncompatibleReason
import com.poyka.ripdpi.diagnostics.dpich.ByohProbeResult
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en", application = Application::class)
class DiagnosticsSpanishPresentationTest {
    @Test
    fun `all DoQ and bootstrap verdicts use Spanish while evidence remains exact`() {
        val strings = strings(Locale.forLanguageTag("es"))
        val result = dnsResult()
        val model = result.toUiModel(strings)
        assertEquals(
            listOf(
                "DoQ correcto",
                "QUIC bloqueado para DoQ",
                "DoQ rechazado por DPI",
                "Respuestas de DoQ divergentes",
                "Tiempo de espera de DoQ agotado",
            ),
            model.doqRows.map { it.verdict },
        )
        assertEquals(listOf("correcto", "suplantado", "Error de resolución"), model.dohBootstrapRows.map { it.verdict })
        model.doqRows.forEach { row ->
            assertEquals("Provider_Name", row.provider)
            assertEquals("example.test", row.domain)
            assertEquals("192.0.2.1", row.endpoint)
            assertEquals("198.51.100.7", row.resolvedIps)
            assertEquals("raw_error_Detail", row.detail)
        }
        model.dohBootstrapRows.forEach { row ->
            assertEquals("Provider_Name", row.provider)
            assertEquals("dns.example.test", row.hostname)
            assertEquals("192.0.2.1 · AS64500 · Example_Org", row.detail)
        }
        assertEquals(DoqVerdict.entries, result.doqResults.map { it.verdict })
        assertEquals(BootstrapVerdict.entries, result.dohBootstrapResults.map { it.verdict })
    }

    @Test
    fun `English DoQ and bootstrap retain their previous labels`() {
        val model = dnsResult().toUiModel(strings(Locale.ENGLISH))
        assertEquals(
            listOf("doq ok", "doq blocked quic", "doq dpi reject", "doq integrity divergent", "doq timeout"),
            model.doqRows.map { it.verdict },
        )
        assertEquals(listOf("ok", "spoofed", "resolve failed"), model.dohBootstrapRows.map { it.verdict })
    }

    @Test
    fun `BYOH translates every rejection reason and retains the invalid response fallback`() {
        val spanish = strings(Locale.forLanguageTag("es"))
        assertEquals(
            listOf("Cuerpo incompleto", "tiempo de espera agotado", "Error de conexión", "Respuesta no válida"),
            ByohIncompatibleReason.entries.map { reason ->
                ByohProbeResult("example.test", false, 1234L, reason).toByohUiModel(spanish).verdict
            },
        )
        val rejected = ByohProbeResult("MiHost.test", false, 1234L).toByohUiModel(spanish)
        assertEquals("Respuesta no válida", rejected.verdict)
        assertEquals("MiHost.test", rejected.domain)
        assertEquals("1234", rejected.bytesReceived)
        assertEquals(DiagnosticsTone.Warning, rejected.tone)
        val compatible = ByohProbeResult("MiHost.test", true, 1234L).toByohUiModel(spanish)
        assertEquals("compatible", compatible.verdict)
        assertEquals(DiagnosticsTone.Positive, compatible.tone)
        assertEquals(
            listOf("partial body", "timeout", "connection error", "invalid response"),
            ByohIncompatibleReason.entries.map { reason ->
                ByohProbeResult("example.test", false, 0L, reason).toByohUiModel(strings(Locale.ENGLISH)).verdict
            },
        )
    }

    @Test
    fun `SNI summaries handle zero one and two entries with localized metrics and exact identifiers`() {
        val spanish = strings(Locale.forLanguageTag("es"))
        val expected =
            listOf(
                "Se encontraron 0 entradas SNI compatibles en 1 ASN.",
                "Se encontró 1 entrada SNI compatible en 1 ASN.",
                "Se encontraron 2 entradas SNI compatibles en 1 ASN.",
            )
        (0..2).forEach { count ->
            val input = sniResult(count)
            val model = listOf(input).toAllowlistSniUiModel(spanish)
            assertEquals(expected[count], model.summary)
            assertEquals(listOf("ASN", "compatibles", "probados"), model.metrics.map { it.label })
            assertEquals(listOf("1", count.toString(), "9"), model.metrics.map { it.value })
            val row = model.rows.single()
            assertEquals(input.asn, row.asn)
            assertEquals(input.provider, row.provider)
            assertEquals(input.ip, row.ip)
            assertEquals("9", row.triedCount)
            assertEquals(input.compatibleSnis.map { it.sni }, row.compatibleSnis.map { it.value })
            assertEquals(
                input.compatibleSnis.map { "${it.sni} [${it.lineNumber}]" },
                row.compatibleSnis.map { it.label },
            )
            assertEquals(if (count == 0) DiagnosticsTone.Warning else DiagnosticsTone.Positive, row.tone)
        }
        val english = listOf(sniResult(2)).toAllowlistSniUiModel(strings(Locale.ENGLISH))
        assertEquals("2 compatible SNI entries found across 1 ASNs.", english.summary)
        assertEquals(listOf("ASNs", "compatible", "tried"), english.metrics.map { it.label })
        assertEquals(
            "Se encontraron 0 entradas SNI compatibles en 0 ASN.",
            emptyList<AllowlistSniResult>().toAllowlistSniUiModel(spanish).summary,
        )
    }

    @Test
    fun `diagnostic and history snapshots translate known kinds and preserve unknown values`() {
        val context = context(Locale.forLanguageTag("es"))
        val strings = ContextStrings(context)
        val core = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(context), strings)
        val support = DiagnosticsUiFactorySupport(strings, core)
        val factory = HistoryConnectionDetailUiFactory(context, strings, core)
        val labels =
            mapOf(
                "pre_scan" to "Antes del análisis",
                "post_scan" to "Después del análisis",
                "connection_sample" to "Muestra de conexión",
                "failure" to "Fallo",
                "Custom_Stage_v2" to "Custom_Stage_v2",
            )
        labels.forEach { (kind, label) ->
            val input = historySnapshot().copy(snapshotKind = kind)
            assertEquals(label, requireNotNull(support.toNetworkSnapshotUiModel(input, false)).title)
            val detail =
                DiagnosticConnectionDetail(
                    session = historyConnectionSession(),
                    snapshots = listOf(input),
                    telemetry = emptyList(),
                    events = emptyList(),
                    contexts = emptyList(),
                )
            assertEquals(
                label,
                factory
                    .toConnectionDetail(detail)
                    .snapshots
                    .single()
                    .title,
            )
            assertEquals(kind, input.snapshotKind)
        }
        val english = strings(Locale.ENGLISH)
        assertEquals(
            listOf("Pre scan", "Post scan", "Connection sample", "Failure", "Custom_Stage_v2"),
            labels.keys.map { networkSnapshotTitle(it, english) },
        )
        assertTrue(networkSnapshotTitle("", english).isEmpty())
    }

    private fun dnsResult() =
        DnsIntegrityResult(
            domains = emptyList(),
            stubIps = emptySet(),
            dohBlocked = 0,
            doqResults =
                DoqVerdict.entries.map { verdict ->
                    DoqProbeResult(
                        "Provider_Name",
                        "192.0.2.1",
                        "example.test",
                        verdict,
                        listOf("198.51.100.7"),
                        32L,
                        "raw_error_Detail",
                    )
                },
            dohBootstrapResults =
                BootstrapVerdict.entries.map { verdict ->
                    DohBootstrapResult(
                        "Provider_Name",
                        "dns.example.test",
                        "192.0.2.1",
                        64500,
                        "Example_Org",
                        verdict,
                        "as(64500)",
                    )
                },
        )

    private fun sniResult(count: Int) =
        AllowlistSniResult(
            asn = "AS64500",
            provider = "Provider_Name",
            ip = "192.0.2.1",
            compatibleSnis = (1..count).map { CompatibleSni("MiHost$it.test", it) },
            triedCount = 9,
            aborted = false,
        )

    private fun strings(locale: Locale): StringResolver = ContextStrings(context(locale))

    private fun context(locale: Locale): Context {
        val application: Application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply {
                setLocale(locale)
            },
        )
    }

    private class ContextStrings(
        private val context: Context,
    ) : StringResolver {
        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = context.getString(resId, *formatArgs)

        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String = context.resources.getQuantityString(resId, quantity, *formatArgs)
    }
}
