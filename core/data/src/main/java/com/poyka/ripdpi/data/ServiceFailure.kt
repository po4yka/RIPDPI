package com.poyka.ripdpi.data

sealed interface FailureReason {
    data class NativeError(val message: String) : FailureReason
    data object TunnelEstablishmentFailed : FailureReason
    data class Unexpected(val cause: Throwable) : FailureReason
}

val FailureReason.displayMessage: String
    get() = when (this) {
        is FailureReason.NativeError -> message
        is FailureReason.TunnelEstablishmentFailed -> "Tunnel establishment failed"
        is FailureReason.Unexpected -> cause.message ?: "Unexpected error"
    }

fun classifyFailureReason(e: Exception, isTunnelContext: Boolean = false): FailureReason =
    when (e) {
        is NativeError ->
            FailureReason.NativeError(e.message ?: "Native error")
        is java.io.IOException ->
            FailureReason.NativeError(e.message ?: "I/O error")
        is IllegalStateException -> {
            if (isTunnelContext &&
                (e.message?.contains("VPN") == true || e.message?.contains("tunnel") == true)
            ) {
                FailureReason.TunnelEstablishmentFailed
            } else {
                FailureReason.NativeError(e.message ?: "Native error")
            }
        }
        else -> FailureReason.Unexpected(e)
    }
