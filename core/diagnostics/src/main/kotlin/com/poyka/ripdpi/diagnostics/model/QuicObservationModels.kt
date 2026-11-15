@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class QuicProbeStatus {
    INITIAL_RESPONSE,
    INITIAL_ACK,
    INITIAL_OTHER,
    INITIAL_CLOSE,
    RETRY,
    VERSION_NEGOTIATION,
    TIMEOUT,
    INVALID_RESPONSE,
    SETUP_ERROR,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESPONSE,
    EMPTY,
    ERROR,
    NOT_RUN,
    UNKNOWN,
}

@Serializable
data class QuicObservationFact(
    val host: String,
    val status: QuicProbeStatus,
    val transportFailure: TransportFailureKind = TransportFailureKind.NONE,
)
