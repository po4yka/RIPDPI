package com.poyka.ripdpi.services

/** Available only after native bridge readiness; the input includes merged profile interface settings. */
internal class RuntimeTunnelReadyEvidence(
    val configurationInput: VpnTunnelConfigurationInput,
    val forceTunnelDns: Boolean,
    val resolverDns: com.poyka.ripdpi.data.ActiveDnsSettings,
    val splitStrictDnsPolicy: ValidatedSplitStrictDnsPolicy?,
    val interfacePolicySignature: String,
    val sharedProxyPath: Boolean = false,
) {
    val encryptedDnsUsesProxy: Boolean
        get() = splitStrictDnsPolicy != null || forceTunnelDns || resolverDns.routeThroughProxy

    val encryptedDnsUsesSharedProxy: Boolean
        get() = encryptedDnsUsesProxy && sharedProxyPath

    override fun toString(): String = "RuntimeTunnelReadyEvidence([REDACTED])"
}
