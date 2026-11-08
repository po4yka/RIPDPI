package com.poyka.ripdpi.e2e

import android.os.SystemClock
import android.util.Log
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.services.FlowAttributionBridge
import com.poyka.ripdpi.services.RemoteDeviceUidPolicyQualification
import kotlinx.coroutines.runBlocking

private const val RecoveryTextLimit = 512

internal data class XrayRecoveryCounters(
    val native: NativeTunnelObservation?,
    val uid: RemoteDeviceUidPolicyQualification?,
    val dnsCompletedQueries: Long,
    val dnsCompletedFailures: Long,
)

internal fun observeXrayRecoveryCounters(
    factory: Tun2SocksBridgeFactory,
    attribution: FlowAttributionBridge,
    snapshot: ServiceTelemetrySnapshot,
): XrayRecoveryCounters =
    runBlocking {
        XrayRecoveryCounters(
            native = (factory as? ObservedNativeTunnelBridgeFactory)?.observe(),
            uid = runCatching { attribution.snapshot() }.getOrNull(),
            dnsCompletedQueries = snapshot.tunnelTelemetry.dnsQueriesTotal,
            dnsCompletedFailures = snapshot.tunnelTelemetry.dnsFailuresTotal,
        )
    }

internal fun XrayProviderE2ETest.logRecoveryObservation(
    phase: String,
    detail: String,
    before: XrayRecoveryCounters? = null,
    after: XrayRecoveryCounters? = null,
) {
    val telemetry = state.telemetry.value.tunnelTelemetry
    Log.i(
        "XrayPeerRecovery",
        "phase=$phase observedAt=${SystemClock.elapsedRealtime()} $detail " +
            "dnsQueries=${telemetry.dnsQueriesTotal} dnsFailures=${telemetry.dnsFailuresTotal} " +
            "endpoint=${boundedRecoveryText(telemetry.resolverEndpoint)} " +
            "lastDnsError=${boundedRecoveryText(telemetry.lastDnsError)} " +
            recoveryCounterDelta(before, after),
    )
}

private fun recoveryCounterDelta(
    before: XrayRecoveryCounters?,
    after: XrayRecoveryCounters?,
): String {
    if (before == null || after == null) return "nativeDeltaObserved=false"
    val first = before.native
    val last = after.native
    val sameGeneration = first != null && last != null && first.generation == last.generation
    val a = first?.forwarding.takeIf { sameGeneration }
    val b = last?.forwarding.takeIf { sameGeneration }
    val delta: (Long?, Long?) -> Long? = { start, end ->
        if (!sameGeneration || start == null || end == null || end < start) null else end - start
    }
    val uidMode = last?.configuredUidMode?.takeIf { it in setOf("disarmed", "allowlist", "denylist") } ?: "unknown"
    val nativeDeltaObserved = a != null && b != null
    return "nativeDeltaObserved=$nativeDeltaObserved sameNativeGeneration=$sameGeneration " +
        "nativeGeneration=${last?.generation} " +
        "startConfigUidMode=$uidMode startConfigUidCount=${last?.configuredUidCount} " +
        "dnsCompletedQueriesDelta=${delta(before.dnsCompletedQueries, after.dnsCompletedQueries)} " +
        "dnsCompletedFailuresDelta=${delta(before.dnsCompletedFailures, after.dnsCompletedFailures)} " +
        "uidPolicyArmedBefore=${before.uid?.uidPolicyArmed} uidPolicyArmedAfter=${after.uid?.uidPolicyArmed} " +
        "uidResolvedDelta=${delta(before.uid?.uidResolvedCount, after.uid?.uidResolvedCount)} " +
        "uidUnresolvedDelta=${delta(before.uid?.uidUnresolvedCount, after.uid?.uidUnresolvedCount)} " +
        "uidDeniedUdpDelta=${delta(
            before.uid?.policyDecisionDeniedUdpCount,
            after.uid?.policyDecisionDeniedUdpCount,
        )} " +
        "tunReadPacketsDelta=${delta(a?.tunReadPackets, b?.tunReadPackets)} " +
        "tunReadBytesDelta=${delta(a?.tunReadBytes, b?.tunReadBytes)} " +
        "tunWritePacketsDelta=${delta(a?.tunWritePackets, b?.tunWritePackets)} " +
        "tunWriteBytesDelta=${delta(a?.tunWriteBytes, b?.tunWriteBytes)} " +
        "tunParseFailuresDelta=${delta(a?.tunParseFailures, b?.tunParseFailures)} " +
        "tunPolicyDropsDelta=${delta(a?.tunPolicyDrops, b?.tunPolicyDrops)} " +
        "tunInterceptorDropsDelta=${delta(a?.tunInterceptorDrops, b?.tunInterceptorDrops)} " +
        "tunQueueDropsDelta=${delta(a?.tunQueueDrops, b?.tunQueueDrops)}"
}

internal fun boundedRecoveryText(value: String?): String {
    val redacted = XrayProfileRedactor.redactText(value.orEmpty()).replace('\n', ' ').replace('\r', ' ')
    return if (redacted.length > RecoveryTextLimit) {
        redacted.take(RecoveryTextLimit) + "[truncated]"
    } else {
        redacted
    }
}
