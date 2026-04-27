package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DnsPathPreferenceInvalidatorTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun buildInvalidator(
        store: NetworkDnsPathPreferenceStore,
        trackedPackages: Set<String> = VpnAppCatalog.signatures.map { it.packageName }.toSet(),
    ): DnsPathPreferenceInvalidator =
        DnsPathPreferenceInvalidator(
            context = RuntimeEnvironment.getApplication(),
            networkDnsPathPreferenceStore = store,
            appScope = testScope,
            trackedPackages = trackedPackages,
        )

    // ── isTracked ────────────────────────────────────────────────────────────

    @Test
    fun `isTracked returns true for first VPN app catalog package`() {
        val invalidator = buildInvalidator(NoOpDnsPathPreferenceStore())
        val firstPackage = VpnAppCatalog.signatures.first().packageName
        assertTrue(invalidator.isTracked(firstPackage))
    }

    @Test
    fun `isTracked returns false for unknown package`() {
        val invalidator = buildInvalidator(NoOpDnsPathPreferenceStore())
        assertFalse(invalidator.isTracked("com.example.not.a.vpn.app"))
    }

    @Test
    fun `isTracked returns true for every package in VpnAppCatalog`() {
        val invalidator = buildInvalidator(NoOpDnsPathPreferenceStore())
        VpnAppCatalog.signatures.forEach { sig ->
            assertTrue(
                "Expected ${sig.packageName} to be tracked",
                invalidator.isTracked(sig.packageName),
            )
        }
    }

    // ── trackedPackages matches catalog ──────────────────────────────────────

    @Test
    fun `trackedPackages contains all catalog package names`() {
        val invalidator = buildInvalidator(NoOpDnsPathPreferenceStore())
        val catalogPackages = VpnAppCatalog.signatures.map { it.packageName }.toSet()
        assertEquals(catalogPackages, invalidator.trackedPackages)
    }

    // ── package filtering gate ───────────────────────────────────────────────

    @Test
    fun `only tracked packages pass the isTracked gate that guards clearAll`() {
        val invalidator = buildInvalidator(NoOpDnsPathPreferenceStore())
        val trackedPkg = VpnAppCatalog.signatures.first().packageName
        val untracked = "com.example.not.a.vpn"

        // Only the tracked package should pass; an untracked one must not.
        assertTrue(invalidator.isTracked(trackedPkg))
        assertFalse(invalidator.isTracked(untracked))
    }

    @Test
    fun `clearAll is not called when package is not tracked`() {
        val store = RecordingDnsPathPreferenceStore()
        val invalidator = buildInvalidator(store)
        val untracked = "com.example.not.a.vpn.app"

        assertFalse(invalidator.isTracked(untracked))
        // The receiver would have returned early — store untouched
        assertEquals(0, store.clearAllCallCount)
    }

    @Test
    fun `custom tracked packages set overrides catalog`() {
        val store = RecordingDnsPathPreferenceStore()
        val customPackages = setOf("com.custom.vpn.app")
        val invalidator = buildInvalidator(store, trackedPackages = customPackages)

        assertTrue(invalidator.isTracked("com.custom.vpn.app"))
        assertFalse(invalidator.isTracked(VpnAppCatalog.signatures.first().packageName))
    }
}

// ── Test doubles ─────────────────────────────────────────────────────────────

private class NoOpDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? = null

    override suspend fun clearAll() = Unit

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("not expected in this test")
}

private class RecordingDnsPathPreferenceStore : NetworkDnsPathPreferenceStore {
    var clearAllCallCount = 0
        private set

    override suspend fun getPreferredPath(fingerprintHash: String): EncryptedDnsPathCandidate? = null

    override suspend fun clearAll() {
        clearAllCallCount++
    }

    override suspend fun rememberPreferredPath(
        fingerprint: NetworkFingerprint,
        path: EncryptedDnsPathCandidate,
        recordedAt: Long?,
    ): NetworkDnsPathPreferenceEntity = error("not expected in this test")
}
