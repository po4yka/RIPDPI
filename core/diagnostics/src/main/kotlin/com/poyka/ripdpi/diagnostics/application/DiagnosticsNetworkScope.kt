package com.poyka.ripdpi.diagnostics.application

import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
import com.poyka.ripdpi.data.NetworkFingerprintProvider

/** Transient callback evidence; copies of a prepared scan retain the original epoch. */
internal class DiagnosticsNetworkScope(
    private val epochProvider: DiagnosticsNetworkEpochProvider,
    private val fingerprintProvider: NetworkFingerprintProvider,
) {
    private val epoch = epochProvider.capture()
    private val fingerprint = fingerprintProvider.capture()?.scopeKey()

    private var invalidated = false

    @Synchronized
    fun isCurrent(): Boolean {
        val sameEpoch = epoch != null && epoch == epochProvider.capture()
        val sameFingerprint = fingerprint != null && fingerprint == fingerprintProvider.capture()?.scopeKey()
        if (!sameEpoch || !sameFingerprint || epoch != epochProvider.capture()) {
            invalidated = true
        }
        return !invalidated
    }

    @Synchronized
    fun invalidate(): Boolean {
        invalidated = true
        return false
    }
}

internal fun PreparedDiagnosticsScan.hasCurrentNetworkScope(provider: NetworkFingerprintProvider): Boolean {
    val scope = networkScope ?: return false
    val expected = networkFingerprint?.scopeKey()
    val valid = scope.isCurrent() && expected != null && expected == provider.capture()?.scopeKey()
    return if (valid) scope.isCurrent() else scope.invalidate()
}
