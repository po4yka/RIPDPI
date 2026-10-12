package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.ResourceStringResolver
import com.poyka.ripdpi.activities.RoutingProtectionPresetUiState
import com.poyka.ripdpi.activities.RoutingProtectionSuggestionUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.settings.state.buildRoutingProtectionUiState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru", sdk = [35])
class RoutingProtectionPresenterTest {
    private val presenter = RoutingProtectionPresenter(ResourceStringResolver())

    @Test
    fun `known preset localizes by stable ID and preserves package identities`() {
        val source =
            RoutingProtectionPresetUiState(
                id = "russian-mainstream",
                title = "Russian mainstream apps",
                enabled = true,
                matchedPackages = persistentListOf("ru.app.example"),
                detectionMethod = "transport_vpn_and_connectivity_checks",
                fixCoverage = "App exclusion or direct routing for known whitelist-sensitive clients.",
                limitations = "Application exclusion does not hide TRANSPORT_VPN checks made by the app itself.",
            )
        val localized = presenter.preset(source)
        assertEquals("Популярные российские приложения", localized.title)
        assertTrue(localized.fixCoverage.startsWith("Исключение приложений"))
        assertEquals("Проверки VPN-транспорта и подключения", localized.detectionMethod)
        assertEquals("ru.app.example", localized.matchedPackages.single())
        assertEquals(source.id, localized.id)
        assertEquals(source.enabled, localized.enabled)
        assertTrue(localized.limitations.contains("TRANSPORT_VPN"))
        assertEquals("transport_vpn_and_connectivity_checks", source.detectionMethod)
        assertEquals("Russian mainstream apps", source.title)
    }

    @Test
    fun `unknown presets and suggestions retain user supplied text`() {
        val customPreset = RoutingProtectionPresetUiState("custom", "User preset", false)
        assertEquals(customPreset, presenter.preset(customPreset))
        val customSuggestion = RoutingProtectionSuggestionUiState("custom", "User title", "User body")
        assertEquals(customSuggestion, presenter.suggestion(customSuggestion))
    }

    @Test
    fun `all routing suggestions localize known titles and bodies`() {
        listOf("exact_app_routing", "full_tunnel", "anti_correlation", "dht_mitigation").forEach { id ->
            val localized = presenter.suggestion(RoutingProtectionSuggestionUiState(id, "Title", "Body"))
            assertRussian(localized.title)
            assertRussian(localized.body)
        }
        val pressure =
            presenter.suggestion(
                RoutingProtectionSuggestionUiState("dht_mitigation", "Title", "Body", bodyVariant = "runtime_pressure"),
            )
        assertTrue(pressure.body.startsWith("Недавние ошибки"))
        assertEquals(
            "Исключение приложения не скрывает проверки TRANSPORT_VPN, которые выполняет само приложение.",
            presenter.disclosure(),
        )
    }

    @Test
    fun `correlation uses structured telemetry and preserves endpoint`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setAntiCorrelationEnabled(true)
                .build()
        val telemetry =
            ServiceTelemetrySnapshot(
                runtimeFieldTelemetry =
                    RuntimeFieldTelemetry(
                        failureClass = FailureClass.Timeout,
                        dhtTriggerCorrelationActive = true,
                        dhtTriggerCorrelationReason =
                            "Recent UDP traffic to 198.51.100.2:6881 was followed by timeout.",
                    ),
                tunnelTelemetry =
                    NativeRuntimeSnapshot
                        .idle(
                            "tunnel",
                        ).copy(lastDhtTriggerEndpoint = "198.51.100.2:6881"),
            )
        val source =
            settings
                .buildRoutingProtectionUiState(RoutingProtectionCatalogSnapshot(), telemetry)
                .suggestions
                .single { it.id == "dht_mitigation" }
        val localized = presenter.suggestion(source)
        assertTrue(localized.body.startsWith("После недавнего UDP-трафика к 198.51.100.2:6881"))
        assertTrue(localized.body.contains("Включите защиту от триггеров DHT"))
        assertTrue(source.body.startsWith("Recent UDP traffic"))
    }

    @Test
    fun `custom correlation evidence remains verbatim`() {
        val localized =
            presenter.suggestion(
                RoutingProtectionSuggestionUiState(
                    "dht_mitigation",
                    "Title",
                    "Body",
                    bodyVariant = "correlated",
                    evidenceDetail = "vendor_opaque_evidence",
                ),
            )
        assertTrue(localized.body.startsWith("vendor_opaque_evidence "))
        assertTrue(localized.body.contains("Включите защиту от триггеров DHT"))
    }

    private fun assertRussian(value: String) {
        assertTrue("Expected Russian presentation: $value", value.any { it in 'А'..'я' })
    }
}
