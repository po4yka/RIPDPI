package com.poyka.ripdpi.ui.screens.diagnostics.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.message
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.ui.screens.history.historyContext
import com.poyka.ripdpi.ui.screens.history.historyScanSession
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
@Config(sdk = [35], qualifiers = "en", application = Application::class)
class DiagnosticsGermanSharePresentationTest {
    @Test
    fun `German readable Telegram summary converts rates and keeps report evidence exact`() {
        val details =
            listOf(
                ProbeDetail("verdict", "slow"),
                ProbeDetail("downloadAvgBps", "187500"),
                ProbeDetail("downloadBytes", "1500000"),
                ProbeDetail("uploadAvgBps", "125"),
                ProbeDetail("uploadBytes", "999"),
                ProbeDetail("dcReachable", "2"),
                ProbeDetail("dcTotal", "3"),
                ProbeDetail("dcPlanned", "5"),
                ProbeDetail(
                    "dcResults",
                    "DC1:ok:42ms:address=192.0.2.10:443;family=ipv4;path=direct|" +
                        "DC1:fail::address=192.0.2.10:80;family=ipv4;path=direct;stage=tcp_connect",
                ),
                ProbeDetail("wsTunnelStatus", "cancelled"),
                ProbeDetail("wsTunnelOpenMs", "123"),
                ProbeDetail("wsTunnelPath", "socks5"),
            )
        val report =
            DiagnosticsSessionProjection(
                results = listOf(ProbeResult("telegram_availability", "Raw_Target", "slow", details)),
                engineAnalysisVersion = "Raw_Engine_V1",
                classifierVersion = "Raw_Classifier_V2",
                diagnoses = listOf(Diagnosis("RAW_CODE", "Exact_Raw_Diagnosis")),
                packVersions = mapOf("Raw_Pack" to 23),
            )
        val body = share("de", report)
        assertTrue(body.contains("1,5 Mbps"))
        assertTrue(body.contains("1,5 MB"))
        assertTrue(body.contains("1,0 Kbps"))
        assertTrue(body.contains("1,00 kB"))
        assertTrue(body.contains("WebSocket-Handshake"))
        assertTrue(body.contains("123 ms"))
        assertTrue(body.contains("Nicht gemessen: 2"))
        assertTrue(body.contains("TCP-Endpunkte"))
        assertTrue(body.contains("DC1 · 192.0.2.10:443"))
        assertTrue(body.contains("DC1 · 192.0.2.10:80"))
        assertTrue(body.contains("Raw_Engine_V1"))
        assertTrue(body.contains("Raw_Classifier_V2"))
        assertTrue(body.contains("Raw_Pack@23"))
        assertTrue(body.contains("Raw_WARNING: Host.test"))
        assertTrue(body.contains("RAW_CODE=Exact_Raw_Diagnosis"))
        assertFalse(body.contains("Highlights:"))
        assertFalse(body.contains("Diagnoses:"))
        assertTrue(body.contains("Download: durchschnittlich 1,5 Mbps"))
        assertFalse(body.contains("slow"))
        assertEquals(details, report.results.single().details)
    }

    @Test
    fun `share keeps the TCP and WebSocket routes separate and preserves old evidence`() {
        val details =
            listOf(
                ProbeDetail(
                    "dcResults",
                    "DC1:ok:42ms:address=[2001:db8::1]:443;family=ipv6;path=socks5|" +
                        "DC1:fail::address=[2001:db8::1]:80;family=ipv6;path=socks5;stage=socks5_negotiation|" +
                        "DC2:reachable=true:medianRttMs=45:ports=443:ok:45ms",
                ),
                ProbeDetail("wsTunnelStatus", "ok"),
                ProbeDetail("wsTunnelOpenMs", "123"),
                ProbeDetail("wsTunnelPath", "direct"),
            )
        val report =
            DiagnosticsSessionProjection(
                results = listOf(ProbeResult("telegram_availability", "Telegram", "partial", details)),
            )
        val intent = DiagnosticsShareIntents.createSummaryShareIntent("Telegram", share("en", report))
        val body = checkNotNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        listOf(
            "DC1 · [2001:db8::1]:443 · Available · 42 ms · Route: SOCKS5",
            "DC1 · [2001:db8::1]:80 · Unreachable · Route: SOCKS5 · Failure stage: SOCKS5 negotiation",
            "DC2 · 443:ok:45ms · Available · 45 ms · Route: Unknown",
            "WebSocket handshake · Available · 123 ms · Route: Direct",
        ).forEach { line -> assertTrue(body.lineSequence().any { it == line }) }

        val legacy =
            report.copy(
                results =
                    listOf(
                        report.results.single().copy(
                            details =
                                listOf(
                                    ProbeDetail("dcResults", "DC2:reachable=true:medianRttMs=45:ports=443:ok:45ms"),
                                    ProbeDetail("wsTunnelStatus", "ok"),
                                    ProbeDetail("wsTunnelOpenMs", "123"),
                                ),
                        ),
                    ),
            )
        val legacyBody = share("en", legacy)
        assertTrue(legacyBody.contains("DC2 · 443:ok:45ms · Available · 45 ms · Route: Unknown"))
        assertTrue(legacyBody.contains("WebSocket handshake · Available · 123 ms · Route: Unknown"))
        assertFalse(legacyBody.contains("Failure stage:"))
    }

    @Test
    fun `share distinguishes stable and alternate source ports for direct TCP`() {
        val endpoints =
            ProbeDetail(
                "dcResults",
                "DC1:ok:42ms:address=192.0.2.10:443;path=direct_route_experiment;route=stable|" +
                    "DC1:ok:58ms:address=192.0.2.10:80;path=direct_route_experiment;route=diversity",
            )
        val report =
            DiagnosticsSessionProjection(
                results = listOf(ProbeResult("telegram_availability", "Telegram", "ok", listOf(endpoints))),
            )
        val body = share("en", report)
        assertTrue(body.contains("DC1 · 192.0.2.10:443 · Available · 42 ms · Route: Direct (stable source port)"))
        assertTrue(body.contains("DC1 · 192.0.2.10:80 · Available · 58 ms · Route: Direct (alternate source port)"))
    }

    @Test
    fun `custom Telegram verdict survives readable translation exactly`() {
        val report =
            DiagnosticsSessionProjection(
                results = listOf(ProbeResult("telegram_availability", "Target", "Raw_Custom_Outcome")),
            )
        assertTrue(share("de", report).contains("Telegram: Raw_Custom_Outcome"))
    }

    @Test
    fun `probe result count uses German singular and plural`() {
        val context = languageContext("de")
        val singular = context.resources.getQuantityString(R.plurals.diagnostics_share_probe_results_format, 1, 1)
        val plural = context.resources.getQuantityString(R.plurals.diagnostics_share_probe_results_format, 2, 2)
        assertFalse(singular.contains("Prüfergebnisse"))
        assertTrue(plural.contains("Prüfergebnisse"))
        assertTrue(
            share("de", DiagnosticsSessionProjection(results = listOf(ProbeResult("dns", "Target", "ok"))))
                .contains(singular),
        )
    }

    @Test
    fun `share presentation follows English German English contexts`() {
        val report =
            DiagnosticsSessionProjection(
                results =
                    listOf(
                        ProbeResult(
                            "telegram_availability",
                            "Target",
                            "slow",
                            listOf(ProbeDetail("downloadAvgBps", "187500")),
                        ),
                    ),
            )
        val english = share("en", report)
        val german = share("de", report)
        assertTrue(english.contains("Download: 1.5 Mbps average"))
        assertTrue(german.contains("1,5 Mbps"))
        assertTrue(german.contains("Download: durchschnittlich"))
        assertEquals(english, share("en", report))
    }

    @Test
    fun `known permission captions translate and unknown permission evidence stays exact`() {
        val initial = checkNotNull(historyContext().context)
        val known =
            initial.copy(
                permissions =
                    initial.permissions.copy(
                        vpnPermissionState = "enabled",
                        notificationPermissionState = "not_required",
                    ),
            )
        val knownBody = share("de", DiagnosticsSessionProjection(), known)
        assertTrue(knownBody.contains("Berechtigungen Erteilt VPN · Nicht erforderlich Benachrichtigungen"))
        val custom =
            initial.copy(
                permissions =
                    initial.permissions.copy(
                        vpnPermissionState = "Custom_PERMISSION",
                        notificationPermissionState = "disabled",
                    ),
            )
        assertTrue(share("de", DiagnosticsSessionProjection(), custom).contains("Custom_PERMISSION"))
        assertEquals("enabled", initial.permissions.vpnPermissionState)
    }

    private fun share(
        language: String,
        report: DiagnosticsSessionProjection,
        diagnosticContext: DiagnosticContextModel? = null,
    ): String =
        DiagnosticsUiFactorySupport(languageContext(language))
            .buildSharePreview(
                latestSession = historyScanSession(report = report),
                latestSnapshot = null,
                latestContext = diagnosticContext,
                telemetry = null,
                nativeEvents =
                    listOf(
                        DiagnosticEvent(
                            "warning",
                            null,
                            source = "native",
                            level = "warn",
                            message = "Raw_WARNING: Host.test",
                            createdAt = 1L,
                        ),
                    ),
                latestReport = report,
            ).body

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }
}
