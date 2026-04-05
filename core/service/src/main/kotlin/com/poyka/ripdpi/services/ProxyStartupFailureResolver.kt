package com.poyka.ripdpi.services

@Suppress("ReturnCount")
internal fun resolveProxyStartupFailure(
    readinessError: Exception,
    proxyStartWasActive: Boolean,
    proxyStartResult: Result<Int>,
): Exception {
    if (proxyStartWasActive) {
        return readinessError
    }

    val proxyStartFailure = proxyStartResult.exceptionOrNull()
    if (proxyStartFailure != null) {
        return proxyStartFailure as? Exception ?: IllegalStateException("Proxy runtime failed", proxyStartFailure)
    }

    val exitCode = proxyStartResult.getOrNull()
    val message =
        if (exitCode == null || exitCode == 0) {
            "Proxy exited before becoming ready"
        } else {
            "Proxy exited with code $exitCode before becoming ready"
        }
    return IllegalStateException(message, readinessError)
}
