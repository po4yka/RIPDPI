package com.poyka.ripdpi.services

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

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

internal interface RemoteDeviceUidPolicyQualificationSource {
    fun snapshot(): RemoteDeviceUidPolicyQualification
}

internal object EmptyRemoteDeviceUidPolicyQualificationSource : RemoteDeviceUidPolicyQualificationSource {
    override fun snapshot(): RemoteDeviceUidPolicyQualification = RemoteDeviceUidPolicyQualification()
}

/**
 * Owns the current TUN attribution epoch and creates its JNI callback object.
 */
@Keep
@Singleton
class FlowAttributionBridge
    @Inject
    constructor(
        private val store: FlowAppAttributionStore,
        @param:ApplicationContext private val context: Context?,
        private val eligibility: SoBindToDeviceUidPolicyEligibility,
    ) : RemoteDeviceUidPolicyQualificationSource {
        private val qualificationEpoch = AtomicReference(UidPolicyQualificationEpoch(NativeUidPolicy.Disarmed))
        private val sessionCallback = AtomicReference<SessionFlowAttributionCallback?>(null)

        internal fun nativeUidPolicy(plan: VpnAppRoutingPlan): NativeUidPolicy {
            val packageManager = context?.packageManager
            val policy =
                if (packageManager == null) {
                    NativeUidPolicy.Disarmed
                } else {
                    nativeUidPolicyFor(plan, eligibility.isEligible(), context.packageName) { packageName ->
                        try {
                            packageManager.getApplicationInfo(packageName, 0).uid
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    }
                }
            return policy
        }

        @Synchronized
        internal fun activateUidPolicy(policy: NativeUidPolicy): SessionFlowAttributionCallback {
            val session = store.beginSession()
            val epoch = UidPolicyQualificationEpoch(policy)
            val callback = SessionFlowAttributionCallback(store, session, epoch) { qualificationEpoch.get() === epoch }
            qualificationEpoch.set(epoch)
            sessionCallback.set(callback)
            return callback
        }

        @Synchronized
        internal fun deactivateUidPolicy() {
            qualificationEpoch.set(UidPolicyQualificationEpoch(NativeUidPolicy.Disarmed))
            sessionCallback.getAndSet(null)?.close()
        }

        @Keep
        fun noteFlow(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
            requestKind: Int,
        ): Int =
            sessionCallback.get()?.noteFlow(protocol, localIp, localPort, remoteIp, remotePort, requestKind)?.uid
                ?: InvalidUid

        override fun snapshot(): RemoteDeviceUidPolicyQualification {
            val epoch = qualificationEpoch.get()
            return eligibility
                .qualification()
                .copy(
                    uidPolicyArmed = epoch.policy.mode != NativeUidPolicy.Disarmed.mode,
                    uidResolvedCount = epoch.uidResolvedCount.get(),
                    uidUnresolvedCount = epoch.uidUnresolvedCount.get(),
                    policyDecisionDeniedTcpCount = epoch.policyDecisionDeniedTcpCount.get(),
                    policyDecisionDeniedUdpCount = epoch.policyDecisionDeniedUdpCount.get(),
                    policyDecisionDeniedOtherCount = epoch.policyDecisionDeniedOtherCount.get(),
                ).privacySafe()
        }
    }

/** A retired native worker retains this old epoch, never the replacement callback. */
@Keep
internal class SessionFlowAttributionCallback(
    private val store: FlowAppAttributionStore,
    private val session: Long,
    private val epoch: UidPolicyQualificationEpoch,
    private val isCurrent: () -> Boolean,
) {
    @Keep
    fun noteFlow(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
        requestKind: Int,
    ): FlowOwnerResolution {
        if (!isCurrent()) return FlowOwnerResolution(InvalidUid)
        val owner = store.resolveSessionOwner(session, protocol, localIp, localPort, remoteIp, remotePort, requestKind)
        return if (isCurrent()) {
            epoch.record(protocol, owner.uid, requestKind)
            owner
        } else {
            FlowOwnerResolution(InvalidUid)
        }
    }

    fun close() = store.endSession(session)
}

internal class UidPolicyQualificationEpoch(
    val policy: NativeUidPolicy,
) {
    val uidResolvedCount = AtomicLong()
    val uidUnresolvedCount = AtomicLong()
    val policyDecisionDeniedTcpCount = AtomicLong()
    val policyDecisionDeniedUdpCount = AtomicLong()
    val policyDecisionDeniedOtherCount = AtomicLong()

    fun record(
        protocol: Int,
        uid: Int,
        requestKind: Int,
    ) {
        if (uid == InvalidUid) {
            uidUnresolvedCount.incrementAndGet()
        } else {
            uidResolvedCount.incrementAndGet()
        }
        if (requestKind != AdmissionOnlyFlowRequest || !policy.denies(uid)) return
        when (protocol) {
            TcpProtocolNumber -> policyDecisionDeniedTcpCount.incrementAndGet()
            UdpProtocolNumber -> policyDecisionDeniedUdpCount.incrementAndGet()
            else -> policyDecisionDeniedOtherCount.incrementAndGet()
        }
    }
}

private fun NativeUidPolicy.denies(uid: Int): Boolean =
    when (mode) {
        "allowlist" -> uid == InvalidUid || uid !in uids
        "denylist" -> uid == InvalidUid || uid in uids
        else -> false
    }

private const val TcpProtocolNumber = 6
private const val UdpProtocolNumber = 17
