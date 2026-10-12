package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RoutingProtectionPresetUiState
import com.poyka.ripdpi.activities.RoutingProtectionSuggestionUiState
import com.poyka.ripdpi.data.FailureClass
import com.poyka.ripdpi.platform.StringResolver

internal class RoutingProtectionPresenter(
    private val stringResolver: StringResolver,
) {
    fun preset(preset: RoutingProtectionPresetUiState): RoutingProtectionPresetUiState =
        if (preset.id == "russian-mainstream") {
            preset.copy(
                title = stringResolver.getString(R.string.routing_protection_preset_russian_mainstream),
                detectionMethod = stringResolver.getString(R.string.routing_protection_preset_detection_method),
                fixCoverage = stringResolver.getString(R.string.routing_protection_preset_fix_coverage),
                limitations = disclosure(),
            )
        } else {
            preset
        }

    fun disclosure(): String = stringResolver.getString(R.string.routing_protection_transport_vpn_disclosure)

    fun suggestion(suggestion: RoutingProtectionSuggestionUiState): RoutingProtectionSuggestionUiState {
        val title =
            when (suggestion.id) {
                "exact_app_routing" -> {
                    stringResolver.getString(R.string.routing_protection_suggest_app_title)
                }

                "full_tunnel" -> {
                    stringResolver.getString(R.string.routing_protection_suggest_full_tunnel_title)
                }

                "anti_correlation" -> {
                    stringResolver.getString(
                        R.string.routing_protection_suggest_anti_correlation_title,
                    )
                }

                "dht_mitigation" -> {
                    stringResolver.getString(R.string.routing_protection_suggest_dht_title)
                }

                else -> {
                    return suggestion
                }
            }
        val body =
            when (suggestion.id) {
                "exact_app_routing" -> {
                    stringResolver.getString(R.string.routing_protection_suggest_app_body)
                }

                "full_tunnel" -> {
                    stringResolver.getString(R.string.routing_protection_full_tunnel_description)
                }

                "anti_correlation" -> {
                    stringResolver.getString(
                        R.string.routing_protection_suggest_anti_correlation_body,
                    )
                }

                else -> {
                    dhtBody(suggestion)
                }
            }
        return suggestion.copy(title = title, body = body)
    }

    private fun dhtBody(suggestion: RoutingProtectionSuggestionUiState): String =
        when (suggestion.bodyVariant) {
            "correlated" -> {
                stringResolver.getString(R.string.routing_protection_suggest_dht_correlated_body).let { body ->
                    listOfNotNull(correlationDetail(suggestion), body).joinToString(" ")
                }
            }

            "runtime_pressure" -> {
                stringResolver.getString(R.string.routing_protection_suggest_dht_pressure_body)
            }

            else -> {
                stringResolver.getString(R.string.routing_protection_suggest_dht_active_body)
            }
        }

    private fun correlationDetail(suggestion: RoutingProtectionSuggestionUiState): String? {
        val failureClass =
            suggestion.correlationFailureClass ?: return suggestion.evidenceDetail?.takeIf(String::isNotBlank)
        val failureResource =
            when (failureClass) {
                FailureClass.TlsInterference -> R.string.routing_protection_failure_tls
                FailureClass.Timeout -> R.string.diagnostics_value_timeout
                FailureClass.ResetAbort -> R.string.routing_protection_failure_reset
                FailureClass.WarpEndpoint -> R.string.routing_protection_failure_warp
                FailureClass.FingerprintPolicy -> R.string.routing_protection_failure_fingerprint
                else -> null
            }
        val failure = failureResource?.let(stringResolver::getString) ?: failureClass.wireValue
        val destination =
            suggestion.correlationDestination
                ?: stringResolver.getString(R.string.routing_protection_known_trigger_cidr)
        return stringResolver.getString(R.string.routing_protection_correlation_format, destination, failure)
    }
}
