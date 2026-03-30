package com.poyka.ripdpi.security

sealed interface PinVerifyResult {
    data object Success : PinVerifyResult

    data object Failed : PinVerifyResult

    data class LockedOut(
        val remainingMs: Long,
    ) : PinVerifyResult
}
