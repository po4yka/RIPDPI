package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

internal fun DiagnosticsCapabilityEvidence.homeUiModel(strings: StringResolver): DiagnosticsCapabilityEvidenceUiModel =
    DiagnosticsCapabilityEvidenceUiModel(
        authority = authority,
        summary = HomeCapabilitySummaryPresentation(this, strings).summary(),
        fields =
            buildList {
                addAll(details.map { it.homeCapabilityField(strings) })
                if (source.isNotBlank()) {
                    add(
                        DiagnosticsFieldUiModel(strings.getString(R.string.diagnostics_field_source), source),
                    )
                }
            }.toImmutableList(),
    )

private val capabilityFieldLabels =
    mapOf(
        "Policy" to R.string.home_diagnostics_capability_policy,
        "DNS class" to R.string.home_diagnostics_capability_dns_class,
        "QUIC mode" to R.string.home_diagnostics_capability_quic_mode,
        "Preferred stack" to R.string.home_diagnostics_capability_preferred_stack,
        "TCP family" to R.string.home_diagnostics_capability_tcp_family,
        "Transport class" to R.string.home_diagnostics_capability_transport_class,
        "Reason" to R.string.diagnostics_ip_reason,
        "Auth" to R.string.home_diagnostics_capability_auth,
        "Multiplex" to R.string.home_diagnostics_capability_multiplex,
        "HTTPS proxy" to R.string.home_diagnostics_capability_https_proxy,
        "Fallback" to R.string.home_diagnostics_capability_fallback,
        "Last failure" to R.string.home_diagnostics_capability_last_failure,
    )
private val capabilityFlagLabels = setOf("QUIC", "UDP", "Auth", "Multiplex", "ShadowTLS", "HTTPS proxy")

private fun DiagnosticsAppliedSetting.homeCapabilityField(strings: StringResolver): DiagnosticsFieldUiModel {
    val caption = capabilityFieldLabels[label]?.let { strings.getString(it) } ?: label
    val translated =
        when {
            label in capabilityFlagLabels && value == "Available" -> {
                strings.getString(
                    R.string.home_diagnostics_capability_available,
                )
            }

            label in capabilityFlagLabels && value == "Blocked" -> {
                strings.getString(
                    R.string.home_diagnostics_capability_blocked,
                )
            }

            label == "Fallback" && value == "Required" -> {
                strings.getString(R.string.home_diagnostics_capability_required)
            }

            label == "Fallback" && value == "Not required" -> {
                strings.getString(
                    R.string.home_diagnostics_capability_not_required,
                )
            }

            else -> {
                value
            }
        }
    return DiagnosticsFieldUiModel(caption, translated)
}

private const val HomeCapabilitySummaryItemLimit = 3

/** Verify the complete generated line from its fields before translating any fragment. */
private class HomeCapabilitySummaryPresentation(
    private val evidence: DiagnosticsCapabilityEvidence,
    private val strings: StringResolver,
) {
    private val fields = evidence.details.associate { it.label to it.value }

    fun summary(): String {
        val parts = policyParts() + flagParts() + tailParts()
        val expected =
            parts
                .take(HomeCapabilitySummaryItemLimit)
                .joinToString(" · ") {
                    it.first
                }.ifBlank { "Capability evidence recorded for this authority." }
        return when {
            evidence.summary != expected -> evidence.summary
            parts.isEmpty() -> strings.getString(R.string.home_diagnostics_capability_recorded)
            else -> parts.take(HomeCapabilitySummaryItemLimit).joinToString(" · ") { it.second }
        }
    }

    private fun policyParts(): List<Pair<String, String>> =
        buildList {
            when (fields["Policy"]) {
                "OWNED_STACK_ONLY" -> {
                    add("Owned stack required" to strings.getString(R.string.home_diagnostics_capability_owned_stack))
                }

                "NO_DIRECT_SOLUTION" -> {
                    add("No direct solution" to strings.getString(R.string.home_diagnostics_capability_no_direct))
                }
            }
            fields["DNS class"]?.let { raw ->
                val classification = raw.lowercase(Locale.US)
                add(
                    "DNS $classification" to
                        strings.getString(R.string.home_diagnostics_capability_dns_format, classification),
                )
            }
        }

    private fun flagParts(): List<Pair<String, String>> =
        buildList {
            listOf("QUIC", "UDP").forEach { protocol ->
                flag(protocol)?.let { available ->
                    val suffix = if (available) "usable" else "blocked"
                    val resource =
                        if (available) {
                            R.string.home_diagnostics_capability_usable_format
                        } else {
                            R.string.home_diagnostics_capability_blocked_format
                        }
                    add("$protocol $suffix" to strings.getString(resource, protocol))
                }
            }
            listOf("Auth", "Multiplex", "ShadowTLS", "HTTPS proxy").forEach { protocol ->
                flag(protocol)?.let { available -> add(acceptedFlag(protocol, available)) }
            }
        }

    private fun acceptedFlag(
        protocol: String,
        available: Boolean,
    ): Pair<String, String> {
        if (protocol == "Multiplex") {
            return if (available) {
                "Multiplex reusable" to
                    strings.getString(R.string.home_diagnostics_capability_multiplex_reusable)
            } else {
                "Multiplex limited" to strings.getString(R.string.home_diagnostics_capability_multiplex_limited)
            }
        }
        val caption = capabilityFieldLabels[protocol]?.let { strings.getString(it) } ?: protocol
        val suffix = if (available) "accepted" else "rejected"
        val resource =
            if (available) {
                R.string.home_diagnostics_capability_accepted_format
            } else {
                R.string.home_diagnostics_capability_rejected_format
            }
        return "$protocol $suffix" to strings.getString(resource, caption)
    }

    private fun tailParts(): List<Pair<String, String>> =
        buildList {
            if (fields["Fallback"] ==
                "Required"
            ) {
                add("Fallback required" to strings.getString(R.string.home_diagnostics_capability_fallback_required))
            }
            fields["Last failure"]?.let { raw ->
                add("Failure $raw" to strings.getString(R.string.home_diagnostics_capability_failure_format, raw))
            }
        }

    private fun flag(label: String): Boolean? =
        when (fields[label]) {
            "Available" -> true
            "Blocked" -> false
            else -> null
        }
}
