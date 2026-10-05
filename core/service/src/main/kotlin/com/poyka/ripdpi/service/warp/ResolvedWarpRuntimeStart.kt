package com.poyka.ripdpi.service.warp

import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.data.WarpCredentials

/** A committed automatic transform, scoped to this one resolved start and immutable profile. */
internal class RuntimeWarpProvisioningPatch(
    val profileId: String,
    val before: WarpCredentials,
    val after: WarpCredentials,
) {
    override fun toString(): String = "RuntimeWarpProvisioningPatch([REDACTED])"
}

internal class ResolvedWarpRuntimeStart(
    val configuration: ResolvedRipDpiWarpConfig,
    val requestedPatch: RuntimeWarpProvisioningPatch?,
) {
    override fun toString(): String = "ResolvedWarpRuntimeStart([REDACTED])"
}

internal class RequestedWarpRuntimeReference(
    val profileId: String,
    val revision: Long,
) {
    override fun toString(): String = "RequestedWarpRuntimeReference([REDACTED])"
}
