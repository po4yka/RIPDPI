package com.poyka.ripdpi.services

sealed interface ServiceStartPreflightResult {
    data object Allowed : ServiceStartPreflightResult

    data class Rejected(
        val reason: ServiceStartRejectionReason,
    ) : ServiceStartPreflightResult
}
