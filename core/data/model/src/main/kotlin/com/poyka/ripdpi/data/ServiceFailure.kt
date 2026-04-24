package com.poyka.ripdpi.data

sealed interface FailureReason {
    data class NativeError(
        val message: String,
    ) : FailureReason

    data object TunnelEstablishmentFailed : FailureReason

    data class WarpProvisioningFailed(
        val message: String,
    ) : FailureReason

    data class WarpEndpointUnavailable(
        val message: String,
    ) : FailureReason

    data class WarpRuntimeFailed(
        val message: String,
    ) : FailureReason

    data class RelayFingerprintPolicyRejected(
        val message: String,
    ) : FailureReason

    data class RelayConfigRejected(
        val message: String,
    ) : FailureReason

    data class Unexpected(
        val cause: Throwable,
    ) : FailureReason

    data class PermissionLost(
        val permission: String,
    ) : FailureReason
}

open class ServiceStartupRejectedException(
    val reason: FailureReason,
) : IllegalStateException(reason.displayMessage)

val FailureReason.displayMessage: String
    get() =
        when (this) {
            is FailureReason.NativeError -> message
            is FailureReason.TunnelEstablishmentFailed -> "Tunnel establishment failed"
            is FailureReason.WarpProvisioningFailed -> message
            is FailureReason.WarpEndpointUnavailable -> message
            is FailureReason.WarpRuntimeFailed -> message
            is FailureReason.RelayFingerprintPolicyRejected -> message
            is FailureReason.RelayConfigRejected -> message
            is FailureReason.Unexpected -> cause.message ?: "Unexpected error"
            is FailureReason.PermissionLost -> "Required permission revoked: $permission"
        }

fun classifyFailureReason(
    e: Exception,
    isTunnelContext: Boolean = false,
): FailureReason =
    when (e) {
        is ServiceStartupRejectedException -> {
            e.reason
        }

        is NativeError -> {
            FailureReason.NativeError(e.message ?: "Native error")
        }

        is java.io.IOException -> {
            FailureReason.NativeError(e.message ?: "I/O error")
        }

        is IllegalStateException -> {
            if (isTunnelContext &&
                (e.message?.contains("VPN") == true || e.message?.contains("tunnel") == true)
            ) {
                FailureReason.TunnelEstablishmentFailed
            } else {
                FailureReason.NativeError(e.message ?: "Native error")
            }
        }

        else -> {
            FailureReason.Unexpected(e)
        }
    }
