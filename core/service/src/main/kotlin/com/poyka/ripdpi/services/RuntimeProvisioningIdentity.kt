package com.poyka.ripdpi.services

/** The runtime provisioning facet of a readiness receipt, using its captured requested configuration. */
internal fun captureProvisionedIdentity(
    requested: RequestedRuntimeConfiguration,
    evidence: RuntimeStartEvidence,
    identities: RuntimeConfigurationIdentityFactory,
): RuntimeConfigurationIdentity? =
    (evidence as? RuntimeStartEvidence.ProxySnapshot)
        ?.requestedWarpPatch
        ?.let { requested.afterRuntimeProvisioning(it, identities) }
