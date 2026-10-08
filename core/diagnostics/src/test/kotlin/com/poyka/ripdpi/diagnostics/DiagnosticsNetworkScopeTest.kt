package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsNetworkScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsNetworkScopeTest {
    @Test
    fun `stable physical network retains scope`() {
        val scope = DiagnosticsNetworkScope(FakeDiagnosticsNetworkEpochProvider(), MutableNetworkFingerprintProvider())
        assertTrue(scope.isCurrent())
        assertTrue(scope.isCurrent())
    }

    @Test
    fun `missing initial fingerprint cannot gain authority later`() {
        val fingerprint = MutableNetworkFingerprintProvider(null)
        val scope = DiagnosticsNetworkScope(FakeDiagnosticsNetworkEpochProvider(), fingerprint)
        fingerprint.fingerprint = FakeNetworkFingerprintProvider().capture()
        assertFalse(scope.isCurrent())
    }

    @Test
    fun `fingerprint recovery cannot restore rejected authority`() {
        val fingerprint = MutableNetworkFingerprintProvider()
        val original = fingerprint.fingerprint
        val scope = DiagnosticsNetworkScope(FakeDiagnosticsNetworkEpochProvider(), fingerprint)
        fingerprint.fingerprint = null
        assertFalse(scope.isCurrent())
        fingerprint.fingerprint = original
        assertFalse(scope.isCurrent())
    }

    @Test
    fun `physical epoch loss cannot restore rejected authority`() {
        val epoch = FakeDiagnosticsNetworkEpochProvider()
        val original = epoch.epoch
        val scope = DiagnosticsNetworkScope(epoch, MutableNetworkFingerprintProvider())
        epoch.epoch = null
        assertFalse(scope.isCurrent())
        epoch.epoch = original
        assertFalse(scope.isCurrent())
    }

    @Test
    fun `callback epoch rejects an unobserved return to the same fingerprint`() {
        val epoch = FakeDiagnosticsNetworkEpochProvider()
        val scope = DiagnosticsNetworkScope(epoch, MutableNetworkFingerprintProvider())
        epoch.epoch = FakeDiagnosticsNetworkEpoch(3)
        assertFalse(scope.isCurrent())
    }
}
