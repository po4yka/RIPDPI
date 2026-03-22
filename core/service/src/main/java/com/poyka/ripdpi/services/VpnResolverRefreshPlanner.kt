package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ResolverOverrideStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class VpnResolverRefreshPlanner
    @Inject
    constructor(
        private val connectionPolicyResolver: ConnectionPolicyResolver,
        private val resolverOverrideStore: ResolverOverrideStore,
    ) {
        suspend fun plan(
            currentSignature: String?,
            tunnelRunning: Boolean,
        ): ResolverRefreshPlan {
            val resolverOverride = resolverOverrideStore.override.value
            val connectionPolicy =
                connectionPolicyResolver.resolve(
                    mode = Mode.VPN,
                    resolverOverride = resolverOverride,
                )
            val plan =
                planResolverRefresh(
                    settings = connectionPolicy.settings,
                    override = resolverOverride,
                    currentSignature = currentSignature,
                    tunnelRunning = tunnelRunning,
                ).copy(connectionPolicy = connectionPolicy)
            if (plan.resolution.shouldClearOverride && resolverOverride != null) {
                resolverOverrideStore.clear()
            }
            return plan
        }
    }
