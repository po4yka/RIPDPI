@file:Suppress("DEPRECATION") // Robolectric's NetworkInfo controls the calling UID's default network.

package com.poyka.ripdpi.services

import android.Manifest
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkInfo
import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NetworkMetadataCaptureScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkSnapshotFactoryScopeTest {
    private val application = RuntimeEnvironment.getApplication()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(connectivity)
    private var epoch: DiagnosticsNetworkEpoch? = object : DiagnosticsNetworkEpoch {}

    @Test
    fun `stable scope preserves fingerprint and interface MTU`() {
        val snapshot = capture()
        assertEquals("wifi", snapshot.transport)
        assertEquals(1500, snapshot.mtu)
        assertEquals(listOf("192.0.2.53"), snapshot.dnsServers)
    }

    @Test
    fun `new physical epoch during fingerprint collection withdraws native metadata`() {
        assertUnavailable(capture { epoch = object : DiagnosticsNetworkEpoch {} })
    }

    @Test
    fun `link property change during fingerprint collection withdraws native metadata`() {
        assertUnavailable(
            capture {
                shadow.setLinkProperties(connectivity.activeNetwork, LinkProperties().apply { mtu = 1280 })
            },
        )
    }

    @Test
    fun `stable absence of a default network preserves the offline outcome`() {
        epoch = null
        val snapshot = capture(hasDefaultNetwork = false) { error("Offline capture must not read fingerprint data") }
        assertEquals("none", snapshot.transport)
        assertNull(snapshot.mtu)
    }

    @Test
    fun `physical observation alone does not establish that the app is offline`() {
        assertUnavailable(capture(hasDefaultNetwork = false))
    }

    @Test
    fun `missing fingerprint with a default network leaves details unknown`() {
        assertUnavailable(capture(hasFingerprint = false))
    }

    private fun capture(
        hasDefaultNetwork: Boolean = true,
        hasFingerprint: Boolean = true,
        duringFingerprint: () -> Unit = {},
    ): NativeNetworkSnapshot {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        shadow.setActiveNetworkInfo(
            if (hasDefaultNetwork) {
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    true,
                )
            } else {
                null
            },
        )
        connectivity.activeNetwork?.let { network ->
            shadow.setLinkProperties(network, LinkProperties().apply { mtu = 1500 })
        }
        val scope =
            NetworkMetadataCaptureScope(
                application,
                { epoch },
                DirectDnsUnderlayAuthority(),
                VpnRouteLifecycleReceiptStore(),
            )
        val fingerprints =
            object : NetworkFingerprintProvider {
                override fun capture(): NetworkFingerprint? {
                    duringFingerprint()
                    if (!hasFingerprint) return null
                    return NetworkFingerprint(
                        transport = "wifi",
                        networkValidated = true,
                        captivePortalDetected = false,
                        privateDnsMode = "system",
                        dnsServers = listOf("192.0.2.53"),
                    )
                }
            }
        return NetworkSnapshotFactory(application, fingerprints, scope).capture()
    }

    private fun assertUnavailable(snapshot: NativeNetworkSnapshot) {
        assertEquals("unknown", snapshot.transport)
        assertFalse(snapshot.validated)
        assertEquals(emptyList<String>(), snapshot.dnsServers)
        assertNull(snapshot.mtu)
        assertNull(snapshot.wifi)
        assertNull(snapshot.cellular)
    }
}
