package com.poyka.ripdpi.ui.screens.diagnostics.state

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportResult
import com.poyka.ripdpi.diagnostics.dpich.PtProbeStatus
import com.poyka.ripdpi.diagnostics.dpich.PtProbeTrace
import com.poyka.ripdpi.platform.AndroidStringResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun `PT presentation preserves separate operations and HTTP status codes`() {
        listOf("hi", "en").forEach { language ->
            val strings = AndroidStringResolver(context(language))
            val probe =
                PluggableTransportResult(
                    obfs4Tcp = PtProbeTrace("", PtProbeStatus.NOT_CONFIGURED, 0),
                    snowflakeBroker = PtProbeTrace("broker.test", PtProbeStatus.HTTP_RESPONSE, 1, 404),
                    snowflakeStun = PtProbeTrace("stun.test:19302", PtProbeStatus.TIMEOUT, 2),
                    meekHead = PtProbeTrace("front.test", PtProbeStatus.HTTP_RESPONSE, 3, 503),
                )
            val rows = probe.toUiModel(privacyModeEnabled = false, stringResolver = strings).rows
            assertEquals(
                listOf(
                    R.string.diagnostics_pt_label_obfs4,
                    R.string.diagnostics_pt_label_snowflake,
                    R.string.diagnostics_pt_label_stun,
                    R.string.diagnostics_pt_label_meek,
                ),
                rows.map { it.labelResId },
            )
            assertEquals(strings.getString(R.string.diagnostics_pt_status_not_configured), rows[0].status)
            assertEquals("HTTP 404", rows[1].status)
            assertEquals("HTTP 503", rows[3].status)
            assertEquals(DiagnosticsTone.Neutral, rows[1].tone)
            assertEquals("front.test", rows[3].detail)
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
                completion.complete(result(PtProbeTrace("Exact_Host.test", PtProbeStatus.CONNECTED, 1)))
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
                    List(4) { "Exact_Host.test" },
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
                            result(PtProbeTrace("Exact_Host.test", PtProbeStatus.CONNECTED, 1))
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
            completion.complete(result(PtProbeTrace("Exact_Host.test", PtProbeStatus.CONNECTED, 1)))
            runCurrent()
            controller.run()
            runCurrent()

            assertEquals(1, probeCalls)
            assertEquals(DiagnosticsPluggableTransportState.Disabled, controller.tool.value.state)
            assertTrue(controller.tool.value.privacyModeEnabled)
        }

    @Test
    fun `a resumed PT check waits for a cancelled check to release its resources`() =
        runTest {
            val localizedContext = context("en")
            val settings = settings()
            val cleanup = CompletableDeferred<Unit>()
            val observed = PtProbeTrace("Exact_Host.test", PtProbeStatus.CONNECTED, 1)
            var probeCalls = 0
            val controller =
                DiagnosticsPluggableTransportController(
                    scope = backgroundScope,
                    appSettingsRepository = settings,
                    assetLoader = DpiAssetLoader(localizedContext),
                    stringResolver = AndroidStringResolver(localizedContext),
                    runProbe = {
                        probeCalls += 1
                        if (probeCalls == 1) {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) { cleanup.await() }
                            }
                        }
                        result(observed)
                    },
                )
            runCurrent()
            controller.run()
            runCurrent()

            settings.update { setDetectionCheckPrivacyModeEnabled(true) }
            runCurrent()
            settings.update { setDetectionCheckPrivacyModeEnabled(false) }
            runCurrent()
            controller.run()
            runCurrent()

            assertEquals(1, probeCalls)
            assertEquals(DiagnosticsPluggableTransportState.Disabled, controller.tool.value.state)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(DiagnosticsPluggableTransportState.Idle, controller.tool.value.state)

            controller.run()
            runCurrent()
            assertEquals(2, probeCalls)
            assertEquals(DiagnosticsPluggableTransportState.Complete, controller.tool.value.state)
        }

    @Test
    fun `a PT probe cancelled before it returns does not publish its result`() =
        runTest {
            val localizedContext = context("en")
            val controller =
                DiagnosticsPluggableTransportController(
                    scope = backgroundScope,
                    appSettingsRepository = settings(),
                    assetLoader = DpiAssetLoader(localizedContext),
                    stringResolver = AndroidStringResolver(localizedContext),
                    runProbe = {
                        // What the privacy-mode collector does from another thread while the probe finishes.
                        currentCoroutineContext().cancel()
                        result(PtProbeTrace("Exact_Host.test", PtProbeStatus.CONNECTED, 1))
                    },
                )
            runCurrent()

            controller.run()
            runCurrent()

            assertEquals(DiagnosticsPluggableTransportState.Running, controller.tool.value.state)
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

    private fun result(trace: PtProbeTrace) = PluggableTransportResult(trace, trace, trace, trace)

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
