package com.poyka.ripdpi.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    sdkInt: Int,
    uidForPackage: (String) -> Int?,
): NativeUidPolicy {
    if (sdkInt < Build.VERSION_CODES.S) return NativeUidPolicy.Disarmed
    val (mode, packages) =
        when (plan) {
            is VpnAppRoutingPlan.AllowOnly -> "allowlist" to plan.packages
            is VpnAppRoutingPlan.Disallow -> "denylist" to plan.packages
        }
    val uids = packages.mapNotNull(uidForPackage).distinct().sorted()
    return if (mode == "allowlist" && uids.isEmpty()) NativeUidPolicy.Disarmed else NativeUidPolicy(mode, uids)
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
 * native side resolves is `(ILjava/lang/String;ILjava/lang/String;I)I`.
 */
@Keep
class FlowAttributionBridge
    @Inject
    constructor(
        private val store: FlowAppAttributionStore,
        @param:ApplicationContext private val context: Context? = null,
    ) {
        internal fun nativeUidPolicy(plan: VpnAppRoutingPlan): NativeUidPolicy {
            val packageManager = context?.packageManager ?: return NativeUidPolicy.Disarmed
            return nativeUidPolicyFor(plan, Build.VERSION.SDK_INT) { packageName ->
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
        ): Int = store.resolveFlowUid(protocol, localIp, localPort, remoteIp, remotePort)
    }
