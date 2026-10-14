package com.poyka.ripdpi.activities

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
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
        assertTrue(body.contains("999 B"))
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
