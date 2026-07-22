package com.poyka.ripdpi.services

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal data class NativeUidPolicy(
    val mode: String,
    val uids: List<Int>,
) {
    companion object {
        val Disarmed = NativeUidPolicy("disarmed", emptyList())
    }
}

internal fun nativeUidPolicyFor(
    plan: VpnAppRoutingPlan,
    eligible: Boolean,
    ownPackage: String,
    uidForPackage: (String) -> Int?,
): NativeUidPolicy {
    if (!eligible) return NativeUidPolicy.Disarmed
    return if (plan is VpnAppRoutingPlan.Disallow && plan.packages == setOf(ownPackage)) {
        // An own-package-only plan excludes just the VPN process to prevent a self-loop.
        // Every third-party UID is already routed into the TUN by Android, so the
        // native SO_BIND guard cannot add security here. Disarming also avoids
        // treating getConnectionOwnerUid(INVALID_UID) for an explicitly tun-bound
        // socket as a false denial.
        NativeUidPolicy.Disarmed
    } else {
        val (mode, packages) =
            when (plan) {
                is VpnAppRoutingPlan.AllowOnly -> "allowlist" to plan.packages
                is VpnAppRoutingPlan.Disallow -> "denylist" to plan.packages
            }
        val uids = packages.mapNotNull(uidForPackage).distinct().sorted()
        NativeUidPolicy(mode, uids)
    }
}

internal const val AdmissionOnlyFlowRequest = 1

internal fun resolveFlowRequest(
    store: FlowAppAttributionStore,
    protocol: Int,
    localIp: String,
    localPort: Int,
    remoteIp: String,
    remotePort: Int,
    requestKind: Int,
): Int =
    if (requestKind == AdmissionOnlyFlowRequest) {
        store.resolveFlowUidOnly(protocol, localIp, localPort, remoteIp, remotePort)
    } else {
        store.resolveFlowUid(protocol, localIp, localPort, remoteIp, remotePort)
    }

/**
 * The Kotlin object the tun2socks native worker calls over JNI (`noteFlow`) to
 * report a freshly seen flow's 5-tuple. It delegates straight to
 * [FlowAppAttributionStore], which resolves the owning app off the hot path and
 * owns the in-memory attribution map.
 *
 * `@Keep` (on the class and the method) is load-bearing: `noteFlow` is invoked
 * only via JNI, so without it R8 would rename or strip the method in release
 * builds and the native `call_method` lookup would fail. The JNI signature the
 * native side resolves is `(ILjava/lang/String;ILjava/lang/String;II)I`.
 */
@Keep
class FlowAttributionBridge
    @Inject
    constructor(
        private val store: FlowAppAttributionStore,
        @param:ApplicationContext private val context: Context?,
        private val eligibility: SoBindToDeviceUidPolicyEligibility,
    ) {
        internal fun nativeUidPolicy(plan: VpnAppRoutingPlan): NativeUidPolicy {
            val packageManager = context?.packageManager ?: return NativeUidPolicy.Disarmed
            return nativeUidPolicyFor(plan, eligibility.isEligible(), context.packageName) { packageName ->
                try {
                    packageManager.getApplicationInfo(packageName, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }

        @Keep
        fun noteFlow(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
            requestKind: Int,
        ): Int = resolveFlowRequest(store, protocol, localIp, localPort, remoteIp, remotePort, requestKind)
    }
