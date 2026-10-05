package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt

internal class RuntimeReadyReceipt(
    val configuration: AppliedRuntimeConfiguration,
    val provisionedRequestedIdentity: RuntimeConfigurationIdentity?,
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
                strategy =
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
                    },
                reason = attempt.reason,
            )
        val provisioned =
            (evidence as? RuntimeStartEvidence.ProxySnapshot)
                ?.requestedWarpPatch
                ?.let { requested.afterRuntimeProvisioning(it, identities) }
        return RuntimeReadyReceipt(configuration, provisioned)
    }
}

private fun isConsumedCommandLine(nativeConfiguration: String): Boolean =
    kotlinx.serialization.json.Json
        .parseToJsonElement(nativeConfiguration)
        .let { it as? kotlinx.serialization.json.JsonObject }
        ?.get("kind")
        .let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.content == "command_line"
