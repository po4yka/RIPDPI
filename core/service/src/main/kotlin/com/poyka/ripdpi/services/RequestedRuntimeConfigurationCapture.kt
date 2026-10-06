package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RequestedRuntimeConfigurationCapture
    @Inject
    constructor(
        private val identities: RuntimeConfigurationIdentityFactory,
        private val catalogs: RuntimeConfigurationCatalogCapture,
        private val routing: DestinationRoutingPolicySource,
        private val secrets: ProxySessionSecretResolver,
        private val groups: ProxyGroupRepository,
        private val authority: com.poyka.ripdpi.data.PauseIntentAuthority,
        private val experiments: RuntimeExperimentSelectionProvider,
    ) : RequestedRuntimeConfigurationSource {
        private val materialFactory = RequestedRuntimeConfigurationMaterialFactory(identities)
        private val policyCapture = RequestedRuntimePolicyCapture(routing, secrets)

        fun catalogGeneration(): Long = checkNotNull(authority.states.value).profileUtility.catalogGeneration

        override suspend fun capture(
            mode: Mode,
            settings: AppSettings,
            awg: AwgActivationRequest?,
        ): RequestedRuntimeConfiguration {
            val relayInputs = RelayResolutionInputs.capture(settings, experiments.current())
            val policy = policyCapture.capture(settings, awg)
            val preferences = policy.preferences
            val catalog = catalogs.capture(mode, settings, preferences, awg)
            val savedGroups = groups.list()
            val tunnelInput = VpnTunnelConfigurationInput(settings, savedGroups.flatMap { it.packageRoutingRules })
            val groupSelection = catalog.selection
            val transportPolicy =
                CapturedTransportPolicy(
                    relayInputs,
                    requestedTransportPolicyMaterial(mode, settings, policy, catalog.selection.provider, relayInputs),
                )
            return materialFactory.build(mode, settings, catalog, tunnelInput, groupSelection, transportPolicy)
        }
    }
