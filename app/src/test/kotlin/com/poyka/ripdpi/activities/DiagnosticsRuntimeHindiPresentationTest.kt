package com.poyka.ripdpi.activities

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtVerdict
import com.poyka.ripdpi.platform.AndroidStringResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en", application = Application::class)
class DiagnosticsRuntimeHindiPresentationTest {
    @Test
    fun `every DPI failure class has Hindi and English presentation`() {
        val expected =
            listOf(
                "TCP कनेक्शन रीसेट",
                "बिना उत्तर पैकेट गिरना",
                "TLS चेतावनी",
                "HTTP अवरोध पृष्ठ",
                "QUIC बाधा",
                "TLS हैंडशेक विफल",
                "कनेक्शन अटका",
                "दूसरे पते पर भेजना",
                "अज्ञात DPI",
            )
        val english =
            listOf(
                "TCP reset",
                "Silent drop",
                "TLS alert",
                "HTTP block page",
                "QUIC breakage",
                "TLS handshake failure",
                "Connection freeze",
                "Redirect",
                "Unknown DPI",
            )
        assertEquals(expected, failureLabels("hi"))
        assertEquals(english, failureLabels("en"))
    }

    @Test
    fun `all PT verdicts translate while evidence and severity remain exact`() {
        val verdicts =
            listOf(
                PtVerdict.PtOk("Raw_Host.test:443"),
                PtVerdict.PtBridgeBlocked("Raw_Bridge_Reason"),
                PtVerdict.PtBrokerBlocked("Raw_Broker_Reason"),
                PtVerdict.PtFrontBlocked("Raw_Front_Reason"),
                PtVerdict.PtStunBlocked("Raw_STUN_Reason"),
                PtVerdict.PtError("Raw_Error_Reason"),
            )
        val expectedHindi =
            listOf("पहुँच उपलब्ध", "ब्रिज अवरुद्ध", "ब्रोकर अवरुद्ध", "फ़्रंट होस्ट अवरुद्ध", "STUN अवरुद्ध", "त्रुटि")
        val expectedEnglish =
            listOf("Reachable", "Bridge blocked", "Broker blocked", "Front blocked", "STUN blocked", "Error")
        listOf("hi" to expectedHindi, "en" to expectedEnglish).forEach { (language, labels) ->
            val strings = AndroidStringResolver(context(language))
            val rows =
                verdicts.flatMap { verdict ->
                    result(verdict).toUiModel(privacyModeEnabled = false, stringResolver = strings).rows
                }
            assertEquals(labels, rows.chunked(3).map { it.first().verdict })
            rows.chunked(3).forEachIndexed { index, chunk ->
                assertEquals(listOf("obfs4", "snowflake", "meek"), chunk.map { it.label })
                val expectedDetail =
                    when (val verdict = verdicts[index]) {
                        is PtVerdict.PtOk -> verdict.detail
                        is PtVerdict.PtBridgeBlocked -> verdict.reason
                        is PtVerdict.PtBrokerBlocked -> verdict.reason
                        is PtVerdict.PtFrontBlocked -> verdict.reason
                        is PtVerdict.PtStunBlocked -> verdict.reason
                        is PtVerdict.PtError -> verdict.reason
                    }
                assertEquals(List(3) { expectedDetail }, chunk.map { it.detail })
                val expectedTone =
                    when (index) {
                        0 -> DiagnosticsTone.Positive
                        5 -> DiagnosticsTone.Negative
                        else -> DiagnosticsTone.Warning
                    }
                assertEquals(List(3) { expectedTone }, chunk.map { it.tone })
            }
            val blank = result(PtVerdict.PtError(" ")).toUiModel(privacyModeEnabled = true, stringResolver = strings)
            assertTrue(blank.privacyModeEnabled)
            assertEquals(
                List(3) { if (language == "hi") "कोई विवरण नहीं" else "no detail" },
                blank.rows.map { it.detail },
            )
        }
    }

    @Test
    fun `PT initial running result and failure use selected language without network`() =
        runTest {
            listOf("hi", "en").forEach { language ->
                val localizedContext = context(language)
                val settings = settings()
                var probeCalls = 0
                var completion = CompletableDeferred<PluggableTransportResult>()
                val controller =
                    DiagnosticsPluggableTransportController(
                        scope = backgroundScope,
                        appSettingsRepository = settings,
                        assetLoader = DpiAssetLoader(localizedContext),
                        stringResolver = AndroidStringResolver(localizedContext),
                        runProbe = {
                            probeCalls += 1
                            completion.await()
                        },
                    )
                assertEquals(R.string.diagnostics_pt_idle, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Idle, controller.tool.value.state)
                assertEquals(idleSummary(language), controller.tool.value.summary)
                runCurrent()
                assertFalse(controller.tool.value.privacyModeEnabled)
                controller.run()
                assertEquals(R.string.diagnostics_pt_running, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Running, controller.tool.value.state)
                assertEquals(
                    if (language == "hi") {
                        "PT एंडपॉइंट पहुँच जाँची जा रही है..."
                    } else {
                        "Checking PT endpoint reachability..."
                    },
                    controller.tool.value.summary,
                )
                runCurrent()
                controller.run()
                runCurrent()
                assertEquals(1, probeCalls)
                completion.complete(result(PtVerdict.PtOk("Exact_Host.test")))
                runCurrent()
                assertEquals(R.string.diagnostics_pt_complete, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Complete, controller.tool.value.state)
                assertEquals(
                    if (language == "hi") {
                        "obfs4, Snowflake, और meek एंडपॉइंट पहुँच जाँची गई."
                    } else {
                        "Checked obfs4, Snowflake, and meek endpoint reachability."
                    },
                    controller.tool.value.summary,
                )
                assertEquals(
                    List(3) { "Exact_Host.test" },
                    controller.tool.value.rows
                        .map { it.detail },
                )
                assertNull(controller.tool.value.errorMessage)
                completion = CompletableDeferred()
                controller.run()
                runCurrent()
                completion.completeExceptionally(IOException("Exact_Raw_Failure"))
                runCurrent()
                assertEquals(R.string.diagnostics_pt_failed, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Failed, controller.tool.value.state)
                assertEquals(
                    if (language == "hi") "PT पहुँच जाँच विफल रही." else "PT reachability check failed.",
                    controller.tool.value.summary,
                )
                assertEquals(
                    if (language == "hi") {
                        "कुछ गलत हो गया। विवरण के लिए लॉग देखें।"
                    } else {
                        "Something went wrong. See Logs for details."
                    },
                    controller.tool.value.errorMessage,
                )
            }
        }

    @Test
    fun `PT privacy mode blocks probes and returns to localized idle when disabled`() =
        runTest {
            listOf("hi", "en").forEach { language ->
                val localizedContext = context(language)
                val settings = settings()
                var probeCalls = 0
                val controller =
                    DiagnosticsPluggableTransportController(
                        scope = backgroundScope,
                        appSettingsRepository = settings,
                        assetLoader = DpiAssetLoader(localizedContext),
                        stringResolver = AndroidStringResolver(localizedContext),
                        runProbe = {
                            probeCalls += 1
                            result(PtVerdict.PtOk("Exact_Host.test"))
                        },
                    )
                runCurrent()
                settings.update { setDetectionCheckPrivacyModeEnabled(true) }
                runCurrent()
                assertEquals(R.string.diagnostics_pt_privacy_disabled, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Disabled, controller.tool.value.state)
                assertTrue(controller.tool.value.privacyModeEnabled)
                val disabledSummary = controller.tool.value.summary
                assertTrue(disabledSummary.isNotBlank())
                if (language == "hi") assertTrue(disabledSummary.any { it in '\u0900'..'\u097f' })
                controller.run()
                runCurrent()
                assertEquals(0, probeCalls)
                assertEquals(disabledSummary, controller.tool.value.summary)
                settings.update { setDetectionCheckPrivacyModeEnabled(false) }
                runCurrent()
                assertEquals(R.string.diagnostics_pt_idle, controller.tool.value.summaryResId)
                assertEquals(DiagnosticsPluggableTransportState.Idle, controller.tool.value.state)
                assertFalse(controller.tool.value.privacyModeEnabled)
                assertEquals(idleSummary(language), controller.tool.value.summary)
            }
        }

    @Test
    fun `PT privacy mode enabled during a probe keeps later runs blocked`() =
        runTest {
            val localizedContext = context("en")
            val settings = settings()
            var probeCalls = 0
            val completion = CompletableDeferred<PluggableTransportResult>()
            val controller =
                DiagnosticsPluggableTransportController(
                    scope = backgroundScope,
                    appSettingsRepository = settings,
                    assetLoader = DpiAssetLoader(localizedContext),
                    stringResolver = AndroidStringResolver(localizedContext),
                    runProbe = {
                        probeCalls += 1
                        completion.await()
                    },
                )
            runCurrent()
            controller.run()
            runCurrent()

            settings.update { setDetectionCheckPrivacyModeEnabled(true) }
            runCurrent()
            completion.complete(result(PtVerdict.PtOk("Exact_Host.test")))
            runCurrent()
            controller.run()
            runCurrent()

            assertEquals(1, probeCalls)
            assertEquals(DiagnosticsPluggableTransportState.Disabled, controller.tool.value.state)
            assertTrue(controller.tool.value.privacyModeEnabled)
        }

    private fun failureLabels(language: String): List<String> {
        val strings = AndroidStringResolver(context(language))
        return DpiFailureClass.entries.map { strings.getString(it.displayLabelRes) }
    }

    private fun settings() =
        FakeAppSettingsRepository(
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDetectionCheckPrivacyModeEnabled(false)
                .build(),
        )

    private fun result(verdict: PtVerdict) = PluggableTransportResult(verdict, verdict, verdict, emptyList())

    private fun idleSummary(language: String): String =
        if (language == "hi") {
            "obfs4, Snowflake और meek के सार्वजनिक PT एंडपॉइंट तक पहुँच जाँचें।"
        } else {
            "Check public PT endpoint reachability for obfs4, Snowflake, and meek."
        }

    private fun context(language: String): Context {
        val application: Application = RuntimeEnvironment.getApplication()
        return application.createConfigurationContext(
            Configuration(application.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        )
    }
}
