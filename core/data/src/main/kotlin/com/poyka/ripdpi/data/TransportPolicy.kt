package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

const val CurrentTransportPolicyEnvelopeVersion = 1
const val DirectModeNoDirectSolutionCooldownMs = 30 * 60 * 1000L

@Serializable
enum class QuicMode {
    ALLOW,
    SOFT_DISABLE,
    HARD_DISABLE,
}

@Serializable
enum class PreferredStack {
    H3,
    H2,
    H1,
}

@Serializable
enum class DnsMode {
    SYSTEM,
    DOH_PRIMARY,
    DOH_SECONDARY,
}

@Serializable
enum class DirectDnsClassification {
    CLEAN,
    POISONED,
    DIVERGENT,
    ECH_CAPABLE,
    NO_HTTPS_RR,
}

@Serializable
enum class TcpFamily {
    NONE,
    SEG_PRE_SNI,
    SEG_MID_SNI,
    SEG_POST_SNI,
    REC_PRE_SNI,
    REC_MID_SNI,
    TWO_PHASE_SEND,
}

@Serializable
enum class DirectModeOutcome {
    TRANSPARENT_OK,
    OWNED_STACK_ONLY,
    NO_DIRECT_SOLUTION,
}

@Serializable
enum class DirectModeVerdictResult {
    TRANSPARENT_WORKS,
    OWNED_STACK_ONLY,
    NO_DIRECT_SOLUTION,
}

@Serializable
enum class DirectTransportClass {
    QUIC_BLOCK_SUSPECT,
    SNI_TLS_SUSPECT,
    IP_BLOCK_SUSPECT,
}

@Serializable
enum class DirectModeReasonCode {
    QUIC_BLOCKED,
    TCP_POST_CLIENT_HELLO_FAILURE,
    IP_BLOCKED,
    NO_TCP_FALLBACK,
    OWNED_STACK_REQUIRED,
    UNKNOWN_DIRECT_FAILURE,
}

@Serializable
data class TransportPolicy(
    val quicMode: QuicMode = QuicMode.ALLOW,
    val preferredStack: PreferredStack = PreferredStack.H3,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val tcpFamily: TcpFamily = TcpFamily.NONE,
    val outcome: DirectModeOutcome = DirectModeOutcome.TRANSPARENT_OK,
)

@Serializable
data class TransportPolicyEnvelope(
    val version: Int = CurrentTransportPolicyEnvelopeVersion,
    val policy: TransportPolicy = TransportPolicy(),
    val ipSetDigest: String = "",
    val dnsClassification: DirectDnsClassification? = null,
    val transportClass: DirectTransportClass? = null,
    val reasonCode: DirectModeReasonCode? = null,
    val cooldownUntil: Long? = null,
) {
    fun isCooldownActive(nowMillis: Long): Boolean = cooldownUntil?.let { it > nowMillis } == true
}

fun defaultTransportPolicyEnvelope(): TransportPolicyEnvelope = TransportPolicyEnvelope()

fun TransportPolicyEnvelope.derivedQuicUsable(): Boolean? =
    when (policy.quicMode) {
        QuicMode.ALLOW -> true
        QuicMode.SOFT_DISABLE, QuicMode.HARD_DISABLE -> false
    }

fun TransportPolicyEnvelope.derivedUdpUsable(): Boolean? =
    when (policy.quicMode) {
        QuicMode.ALLOW -> true
        QuicMode.SOFT_DISABLE, QuicMode.HARD_DISABLE -> false
    }

fun TransportPolicyEnvelope.derivedFallbackRequired(): Boolean? =
    when {
        policy.tcpFamily != TcpFamily.NONE -> true
        policy.outcome == DirectModeOutcome.OWNED_STACK_ONLY -> true
        policy.outcome == DirectModeOutcome.NO_DIRECT_SOLUTION -> true
        else -> null
    }

fun TransportPolicyEnvelope.derivedHandshakeFailureClass(): String? =
    when (transportClass) {
        DirectTransportClass.SNI_TLS_SUSPECT -> reasonCode?.name?.lowercase()
        else -> null
    }
