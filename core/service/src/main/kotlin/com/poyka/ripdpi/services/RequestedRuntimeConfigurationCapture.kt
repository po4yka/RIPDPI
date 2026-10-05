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
        private val selections: com.poyka.ripdpi.data.selector.SelectorSelectionStore,
    ) {
        private val materialFactory = RequestedRuntimeConfigurationMaterialFactory(identities)
        private val policyCapture = RequestedRuntimePolicyCapture(routing, secrets)

        suspend fun capture(
            mode: Mode,
            settings: AppSettings,
            awg: AwgActivationRequest?,
        ): RequestedRuntimeConfiguration {
            val policy = policyCapture.capture(settings, awg)
            val preferences = policy.preferences
            val catalog = catalogs.capture(mode, settings, preferences, awg)
            val savedGroups = groups.list()
            val tunnelInput = VpnTunnelConfigurationInput(settings, savedGroups.flatMap { it.packageRoutingRules })
            val selectedGroup = savedGroups.filter { it.isSelector && it.members.isNotEmpty() }.minByOrNull { it.order }
            val memberId = selectedGroup?.let { selections.snapshot(it.id).profileId }
            val groupSelection =
                if (memberId != null && memberId == catalog.selection.profileId) {
                    catalog.selection.copy(selectorGroupId = selectedGroup?.id, selectorMemberId = memberId)
                } else {
                    catalog.selection
                }
            return materialFactory.build(mode, settings, policy, catalog, tunnelInput, groupSelection)
        }
    }
