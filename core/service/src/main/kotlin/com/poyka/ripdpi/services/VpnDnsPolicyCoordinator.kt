package com.poyka.ripdpi.services

internal class VpnDnsPolicyCoordinator(
    private val resolverRefreshPlanner: VpnResolverRefreshPlanner,
    private val encryptedDnsFailoverController: VpnEncryptedDnsFailoverController,
) {
    suspend fun planRefresh(
        currentSignature: String?,
        currentDestinationRoutingDigest: String?,
        tunnelRunning: Boolean,
    ): ResolverRefreshPlan =
        resolverRefreshPlanner.plan(
            currentSignature = currentSignature,
            currentDestinationRoutingDigest = currentDestinationRoutingDigest,
            tunnelRunning = tunnelRunning,
        )

    suspend fun maybeRecoverEncryptedDns(
        session: VpnRuntimeSession,
        currentDnsSignature: String?,
        telemetry: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
    ): Boolean =
        encryptedDnsFailoverController.evaluate(
            state = session.encryptedDnsFailoverState,
            activeDns = session.currentDns,
            currentDnsSignature = currentDnsSignature ?: session.currentDnsSignature,
            networkScopeKey = session.currentNetworkScopeKey,
            telemetry = telemetry,
        )
}
