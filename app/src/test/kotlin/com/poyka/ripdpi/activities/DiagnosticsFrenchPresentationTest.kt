package com.poyka.ripdpi.activities

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr", application = Application::class)
class DiagnosticsFrenchPresentationTest {
    @Test
    fun `RTT lower band is readable in Live highlights metrics and History without changing raw evidence`() {
        val context = languageContext("fr")
        val support = DiagnosticsUiFactorySupport(context)
        val sample = historyTelemetry().copy(proxyRttBand = "lt50", resolverRttBand = "unknown")
        val session = historyConnectionSession().copy(proxyRttBand = "lt50", resolverRttBand = "unknown")
        val live =
            support.buildLiveUiModel(
                activeConnectionSession = session,
                telemetry = listOf(sample),
                currentTelemetry = sample,
                nativeEvents = emptyList(),
                latestSnapshot = null,
                latestContext = null,
            )
        val history =
            HistoryConnectionDetailUiFactory(support.context, support.core).toConnectionDetail(
                DiagnosticConnectionDetail(session, emptyList(), emptyList(), listOf(sample), emptyList()),
            )
        assertEquals(
            "< 50 ms",
            live.highlights.single { it.label == context.getString(R.string.diagnostics_metric_rtt) }.value,
        )
        assertEquals(
            "< 50 ms",
            live.metrics.single { it.label == context.getString(R.string.diagnostics_metric_rtt_band) }.value,
        )
        assertEquals(
            "< 50 ms",
            history.highlights.single { it.label == context.getString(R.string.diagnostics_metric_rtt_band) }.value,
        )
        val rawProxyBand =
            history.contextGroups
                .flatMap { it.fields }
                .single { it.label == context.getString(R.string.diagnostics_field_proxy_rtt_band) }
        assertEquals("lt50", rawProxyBand.value)
        assertEquals("lt50", sample.proxyRttBand)
        assertEquals("lt50", session.proxyRttBand)
    }

    @Test
    fun `all RTT bands and custom values follow English French English for retained Live and History data`() {
        val bands =
            mapOf(
                "lt50" to "< 50 ms",
                "50_99" to "50–99 ms",
                "100_249" to "100–249 ms",
                "250_499" to "250–499 ms",
                "500_plus" to "≥ 500 ms",
                "unknown" to "Unknown",
                "Raw_Custom_Band" to "Raw_Custom_Band",
            )
        bands.forEach { (band, label) ->
            val sample = historyTelemetry().copy(proxyRttBand = band, resolverRttBand = "unknown")
            val session = historyConnectionSession().copy(proxyRttBand = band, resolverRttBand = "unknown")
            listOf("en", "fr", "en").forEach { language ->
                val expected = if (band == "unknown" && language == "fr") "Inconnu" else label
                assertBandPresentation(language, sample, session, expected)
            }
            assertEquals(band, sample.proxyRttBand)
            assertEquals(band, session.proxyRttBand)
        }
    }

    @Test
    fun `five known Live states use French captions and preserve their raw state`() {
        val captions =
            mapOf(
                "Running" to "en cours",
                "Stopped" to "arrêté",
                "Halted" to "interrompu",
                "Failed" to "échec",
                "Reconnecting" to "reconnexion",
            )
        captions.forEach { (rawState, caption) ->
            val sample = historyTelemetry().copy(connectionState = rawState)
            val model = live("fr", sample)
            assertEquals(caption, model.statusLabel)
            assertEquals(if (rawState == "Running") "Le trafic passe par wifi" else caption, model.headline)
            assertEquals(rawState, sample.connectionState)
        }
    }

    @Test
    fun `custom Live state and network remain exact in French`() {
        val sample =
            historyTelemetry().copy(
                connectionState = "custom_STATE: Host.test",
                networkType = "Custom_Network",
            )
        val model = live("fr", sample)
        assertEquals("custom_STATE: Host.test", model.statusLabel)
        assertEquals("custom_STATE: Host.test", model.headline)
        assertEquals("Custom_Network", model.networkLabel)
        val running = live("fr", sample.copy(connectionState = "Running"))
        assertEquals("Le trafic passe par Custom_Network", running.headline)
    }

    @Test
    fun `retained Live state follows English French English without modifying the sample`() {
        val sample = historyTelemetry().copy(connectionState = "Reconnecting")
        assertEquals("reconnecting", live("en", sample).statusLabel)
        assertEquals("reconnexion", live("fr", sample).statusLabel)
        assertEquals("reconnecting", live("en", sample).statusLabel)
        assertEquals("Reconnecting", sample.connectionState)
    }

    @Test
    fun `halted service with retained telemetry localizes the generated Stopped state`() {
        val sample =
            checkNotNull(
                buildCurrentServiceTelemetry(
                    status = AppStatus.Halted,
                    mode = Mode.VPN,
                    telemetry = ServiceTelemetrySnapshot(updatedAt = 123L),
                    activeConnectionSession = null,
                ),
            )
        val model = live("fr", sample)
        assertEquals("arrêté", model.statusLabel)
        assertEquals("arrêté", model.headline)
        assertEquals("Stopped", sample.connectionState)
    }

    @Test
    fun `missing Live sample and session fallback use localized standby captions`() {
        val context = languageContext("fr")
        val missing = live("fr", null)
        assertEquals("Inactif", missing.statusLabel)
        assertEquals(context.getString(R.string.diagnostics_live_headline_standby), missing.headline)
        val fallback = live("fr", null, historyConnectionSession(state = "Reconnecting"))
        assertEquals("reconnexion", fallback.statusLabel)
        assertEquals(context.getString(R.string.diagnostics_live_headline_standby), fallback.headline)
    }

    @Test
    fun `French Live error and warning headlines keep priority and preserve raw event evidence`() {
        val context = languageContext("fr")
        val session = historyConnectionSession(state = "Running")
        val sample = historyTelemetry().copy(connectionState = "Stopped")
        val error =
            DiagnosticEvent(
                "error",
                null,
                source = "test",
                level = "error",
                message = "Raw_ERROR",
                createdAt = 1,
            )
        val degraded = live("fr", sample, session, listOf(error))
        assertEquals(context.getString(R.string.diagnostics_live_headline_error), degraded.headline)
        assertEquals("arrêté", degraded.statusLabel)
        assertEquals("Raw_ERROR", degraded.body)
        val failed = live("fr", sample.copy(connectionState = "Failed"), session)
        assertEquals(context.getString(R.string.diagnostics_live_headline_error), failed.headline)
        assertEquals("échec", failed.statusLabel)
        val warning = error.copy(level = "warn", message = "Raw_WARNING")
        val attention = live("fr", sample, session, listOf(warning))
        assertEquals(context.getString(R.string.diagnostics_live_headline_attention), attention.headline)
        assertEquals("Raw_WARNING", attention.body)
    }

    private fun assertBandPresentation(
        language: String,
        sample: DiagnosticTelemetrySample,
        session: DiagnosticConnectionSession,
        expected: String,
    ) {
        val context = languageContext(language)
        val support = DiagnosticsUiFactorySupport(context)
        val model = live(language, sample, session)
        assertEquals(
            expected,
            model.highlights.single { it.label == context.getString(R.string.diagnostics_metric_rtt) }.value,
        )
        assertEquals(
            expected,
            model.metrics.single { it.label == context.getString(R.string.diagnostics_metric_rtt_band) }.value,
        )
        val factory = HistoryConnectionDetailUiFactory(support.context, support.core)
        listOf(listOf(sample), emptyList()).forEach { samples ->
            val history =
                factory.toConnectionDetail(
                    DiagnosticConnectionDetail(session, emptyList(), emptyList(), samples, emptyList()),
                )
            assertEquals(
                expected,
                history.highlights.single { it.label == context.getString(R.string.diagnostics_metric_rtt_band) }.value,
            )
            assertEquals(
                sample.proxyRttBand,
                history.contextGroups
                    .flatMap { it.fields }
                    .single { it.label == context.getString(R.string.diagnostics_field_proxy_rtt_band) }
                    .value,
            )
        }
    }

    private fun live(
        language: String,
        sample: DiagnosticTelemetrySample?,
        session: DiagnosticConnectionSession? = null,
        events: List<DiagnosticEvent> = emptyList(),
    ): DiagnosticsLiveUiModel =
        DiagnosticsUiFactorySupport(languageContext(language)).buildLiveUiModel(
            activeConnectionSession = session,
            telemetry = listOfNotNull(sample),
            currentTelemetry = sample,
            nativeEvents = events,
            latestSnapshot = null,
            latestContext = null,
        )

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }
}
