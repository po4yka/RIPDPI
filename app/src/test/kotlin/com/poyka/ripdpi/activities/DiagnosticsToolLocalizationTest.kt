package com.poyka.ripdpi.activities

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpich.CompressionCodec
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeResult
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeVerdict
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticsToolLocalizationTest {
    @Test
    fun `Chinese domain verdicts use readable labels and preserve targets`() {
        DomainVerdict.entries.forEach { verdict ->
            val row = domain(verdict).toUiModel(0, diagnosticsToolLocaleResolver("zh-CN")).rows.single()
            assertDiagnosticsChinese(row.verdict)
            assertEquals("example.com", row.domain)
            assertEquals("192.0.2.1", row.resolvedIps)
        }
    }

    @Test
    fun `Chinese attempt statuses are localized and HTTP codes stay intact`() {
        AttemptStatus.entries.forEach { status ->
            val row =
                domain(attempt = AttemptResult(status, statusCode = 451))
                    .toUiModel(0, diagnosticsToolLocaleResolver("zh-CN"))
                    .rows
                    .single()
            assertDiagnosticsChinese(row.http)
            assertTrue(row.http.endsWith(" · 451"))
            assertEquals(row.http, row.tls13)
            assertEquals(row.http, row.tls12)
        }
    }

    @Test
    fun `Chinese known errors are translated and unknown remains explicitly unknown`() {
        val errors =
            listOf(
                DpiProbeError.TlsSpoof,
                DpiProbeError.TlsAlertSni,
                DpiProbeError.TlsAlertHandshake,
                DpiProbeError.TlsBlockVersion,
                DpiProbeError.TlsRst,
                DpiProbeError.TlsEof,
                DpiProbeError.TlsDrop,
                DpiProbeError.TlsMitm,
                DpiProbeError.SynDrop,
                DpiProbeError.TcpRst,
                DpiProbeError.TlsRstTls,
                DpiProbeError.TcpAbort,
                DpiProbeError.DnsFail,
                DpiProbeError.Refused,
                DpiProbeError.NetUnreach,
                DpiProbeError.HostUnreach,
                DpiProbeError.Unknown,
            )
        errors.forEach { error ->
            val row =
                domain(attempt = AttemptResult(AttemptStatus.ERROR, error = error))
                    .toUiModel(0, diagnosticsToolLocaleResolver("zh-CN"))
                    .rows
                    .single()
            assertDiagnosticsChinese(row.http.substringAfter(" · "))
            assertFalse(row.http.contains(error.label.lowercase(Locale.US).replace('_', ' ')))
        }
    }

    @Test
    fun `Chinese compression labels translate every verdict and preserve codec bytes and URL`() {
        CompressionProbeVerdict.entries.forEach { verdict ->
            val model = compression(verdict).toUiModel(diagnosticsToolLocaleResolver("zh-CN"))
            assertEquals("https://example.com/custom", model.targetUrl)
            assertEquals(listOf("gzip", "deflate", "brotli", "zstd"), model.rows.map { it.codec })
            model.rows.forEach { row ->
                assertDiagnosticsChinese(row.verdict)
                assertEquals("1024", row.compressedBytes)
                assertEquals("8192", row.decompressedBytes)
            }
        }
    }

    @Test
    fun `English statuses retain clear meanings and codes`() {
        val row =
            domain(
                verdict = DomainVerdict.DNS_FAIL,
                attempt = AttemptResult(AttemptStatus.ERROR, error = DpiProbeError.Refused, statusCode = 503),
            ).toUiModel(0, diagnosticsToolLocaleResolver("en")).rows.single()
        assertEquals("DNS resolution failed", row.verdict)
        assertEquals("error · 503 · connection refused", row.http)
        assertEquals(
            "timeout",
            compression(CompressionProbeVerdict.TIMEOUT).toUiModel(diagnosticsToolLocaleResolver("en")).rows[0].verdict,
        )
    }

    private fun domain(
        verdict: DomainVerdict = DomainVerdict.OK,
        attempt: AttemptResult = AttemptResult(AttemptStatus.OK),
    ) = listOf(
        DomainReachabilityResult("example.com", listOf("192.0.2.1"), attempt, attempt, attempt, verdict),
    )

    private fun compression(verdict: CompressionProbeVerdict) =
        CompressionProbeRunResult(
            targetUrl = "https://example.com/custom",
            includeZstd = true,
            results =
                CompressionCodec.entries.associateWith {
                    CompressionProbeResult(verdict, compressedBytes = 1024L, decompressedBytes = 8192L)
                },
        )
}

internal fun diagnosticsToolLocaleResolver(language: String): StringResolver {
    val application = RuntimeEnvironment.getApplication()
    val configuration = Configuration(application.resources.configuration)
    configuration.setLocales(LocaleList(Locale.forLanguageTag(language)))
    val context = application.createConfigurationContext(configuration)
    return object : StringResolver {
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

internal fun assertDiagnosticsChinese(value: String) {
    assertTrue("Expected Chinese presentation: $value", value.any { it in '\u4e00'..'\u9fff' })
}
