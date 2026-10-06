package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt

internal class RuntimeReadyReceipt(
    val configuration: AppliedRuntimeConfiguration,
    val provisionedRequestedIdentity: RuntimeConfigurationIdentity?,
    val measurementProof: CandidateConfigurationProof?,
)

internal class RuntimeReadyReceiptFactory(
    private val identities: RuntimeConfigurationIdentityFactory,
) {
    private val nativeReceipt = NativeRuntimeConfigurationReceiptFactory(identities)

    fun build(
        session: ServiceRuntimeSession,
        resolution: ConnectionPolicyResolution,
        evidence: RuntimeStartEvidence,
        observedAt: Long,
        attempt: RuntimeConfigurationAttempt,
    ): RuntimeReadyReceipt {
        val requested = resolution.requestedConfiguration
        val effective =
            when (evidence) {
                is RuntimeStartEvidence.ProxySnapshot -> {
                    nativeReceipt.build(session, resolution, evidence)
                }

                is RuntimeStartEvidence.ProviderReady -> {
                    session.effectiveProviderIdentity = evidence.effectiveIdentity
                    evidence.selection
                }
            }
        val configuration =
            AppliedRuntimeConfiguration(
                runtimeId = attempt.runtimeId,
                revision = attempt.revision,
                appliedAt = observedAt,
                mode = session.mode,
                requestedSelection = requested.selection,
                effectiveSelection = effective,
                dns =
                    when (evidence) {
                        is RuntimeStartEvidence.VpnSnapshot -> evidence.tunnel.resolverDns.runtimeDnsSummary()
                        is RuntimeStartEvidence.ProviderReady -> evidence.tunnel.resolverDns.runtimeDnsSummary()
                        else -> resolution.activeDns.runtimeDnsSummary()
                    },
                strategy = effectiveStrategy(resolution, evidence),
                reason = attempt.reason,
            )
        val provisioned = captureProvisionedIdentity(requested, evidence, identities)
        val measurementProof =
            when (evidence) {
                is RuntimeStartEvidence.ProxySnapshot -> {
                    evidence.consumedUpstreams
                        .mapNotNull { it.measuredInputProof }
                        .singleOrNull()
                }

                is RuntimeStartEvidence.ProviderReady -> {
                    evidence.measurementProof
                }
            }
        return RuntimeReadyReceipt(configuration, provisioned, measurementProof)
    }

    private fun effectiveStrategy(
        resolution: ConnectionPolicyResolution,
        evidence: RuntimeStartEvidence,
    ): com.poyka.ripdpi.data.RuntimeConfigurationStrategy =
        when (evidence) {
            is RuntimeStartEvidence.ProxySnapshot -> {
                val effectiveUi =
                    com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences(
                        evidence.effectivePreferences.toNativeConfigJson(),
                    )
                val family =
                    effectiveUi
                        ?.deriveStrategyLaneFamilies(
                            resolution.activeDns,
                        )?.tcpStrategyFamily
                val custom =
                    isConsumedCommandLine(evidence.effectivePreferences.toNativeConfigJson()) ||
                        effectiveUi?.runtimeContext?.strategyChainYaml?.isNotBlank() == true ||
                        (evidence as? RuntimeStartEvidence.VpnSnapshot)
                            ?.tunnel
                            ?.configurationInput
                            ?.settings
                            ?.strategyChainYaml
                            ?.isNotBlank() ==
                        true
                com.poyka.ripdpi.data.RuntimeConfigurationStrategy(
                    enabled = family != null || custom,
                    family = family,
                    custom = custom,
                )
            }

            is RuntimeStartEvidence.ProviderReady -> {
                com.poyka.ripdpi.data
                    .RuntimeConfigurationStrategy(
                        enabled =
                            evidence.tunnel.configurationInput.settings.strategyChainYaml
                                .isNotBlank(),
                        custom =
                            evidence.tunnel.configurationInput.settings.strategyChainYaml
                                .isNotBlank(),
                    )
            }
        }
}

private fun isConsumedCommandLine(nativeConfiguration: String): Boolean =
    kotlinx.serialization.json.Json
        .parseToJsonElement(nativeConfiguration)
        .let { it as? kotlinx.serialization.json.JsonObject }
        ?.get("kind")
        .let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.content == "command_line"
