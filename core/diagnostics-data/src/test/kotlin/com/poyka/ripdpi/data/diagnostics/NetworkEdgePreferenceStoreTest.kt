package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV6
import com.poyka.ripdpi.data.PreferredEdgeTransportQuic
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkEdgePreferenceStoreTest {
    private val clock = FixedEdgePreferenceClock(now = 9_000L)
    private val recordStore = FakeNetworkEdgePreferenceRecordStore()
    private val store = DefaultNetworkEdgePreferenceStore(recordStore, clock)

    @Test
    fun `remember preferred edges uses clock when recordedAt omitted`() =
        runTest {
            val result =
                store.rememberPreferredEdges(
                    fingerprint = networkFingerprint(),
                    host = "Example.com",
                    transportKind = PreferredEdgeTransportTcp,
                    edges =
                        listOf(
                            PreferredEdgeCandidate(
                                ip = "203.0.113.10",
                                transportKind = PreferredEdgeTransportTcp,
                                ipVersion = PreferredEdgeIpVersionV4,
                            ),
                        ),
                )

            assertEquals(clock.now(), result.updatedAt)
            assertEquals(clock.now(), recordStore.requireStored(result.fingerprintHash, "example.com", "tcp").updatedAt)
        }

    @Test
    fun `record edge result isolates host and transport and exposes runtime map`() =
        runTest {
            val fingerprint = networkFingerprint()

            store.recordEdgeResult(
                fingerprint = fingerprint,
                host = "example.com",
                transportKind = PreferredEdgeTransportTcp,
                ip = "203.0.113.10",
                success = true,
                echCapable = true,
                cdnProvider = "cloudflare",
            )
            store.recordEdgeResult(
                fingerprint = fingerprint,
                host = "example.com",
                transportKind = PreferredEdgeTransportQuic,
                ip = "203.0.113.20",
                success = false,
            )
            store.recordEdgeResult(
                fingerprint = fingerprint,
                host = "media.example",
                transportKind = PreferredEdgeTransportTcp,
                ip = "2001:db8::20",
                success = true,
            )

            val tcpEdges = store.getPreferredEdges(fingerprint.scopeKey(), "example.com", PreferredEdgeTransportTcp)
            assertEquals(listOf("203.0.113.10"), tcpEdges.map { it.ip })
            assertEquals(1, tcpEdges.single().successCount)
            assertTrue(tcpEdges.single().echCapable)
            assertEquals("cloudflare", tcpEdges.single().cdnProvider)

            val quicEdges = store.getPreferredEdges(fingerprint.scopeKey(), "example.com", PreferredEdgeTransportQuic)
            assertEquals(listOf("203.0.113.20"), quicEdges.map { it.ip })
            assertEquals(1, quicEdges.single().failureCount)

            val runtimeEdges = store.getPreferredEdgesForRuntime(fingerprint.scopeKey())
            assertEquals(setOf("example.com", "media.example"), runtimeEdges.keys)
            assertEquals(2, runtimeEdges.getValue("example.com").size)
            assertEquals(
                listOf("203.0.113.10", "203.0.113.20"),
                runtimeEdges.getValue("example.com").map { it.ip },
            )
            assertEquals(PreferredEdgeIpVersionV6, runtimeEdges.getValue("media.example").single().ipVersion)
        }
}

private class FakeNetworkEdgePreferenceRecordStore : NetworkEdgePreferenceRecordStore {
    private val preferences = linkedMapOf<Triple<String, String, String>, NetworkEdgePreferenceEntity>()
    private var nextId = 1L

    override suspend fun getNetworkEdgePreference(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): NetworkEdgePreferenceEntity? = preferences[Triple(fingerprintHash, host, transportKind)]

    override suspend fun getNetworkEdgePreferencesForFingerprint(
        fingerprintHash: String,
    ): List<NetworkEdgePreferenceEntity> = preferences.values.filter { it.fingerprintHash == fingerprintHash }

    override suspend fun upsertNetworkEdgePreference(preference: NetworkEdgePreferenceEntity): Long {
        val persisted =
            if (preference.id > 0L) {
                preference
            } else {
                preference.copy(id = nextId++)
            }
        preferences[Triple(persisted.fingerprintHash, persisted.host, persisted.transportKind)] = persisted
        return persisted.id
    }

    override suspend fun clearNetworkEdgePreferences() {
        preferences.clear()
    }

    override suspend fun pruneNetworkEdgePreferences() = Unit

    fun requireStored(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): NetworkEdgePreferenceEntity = requireNotNull(preferences[Triple(fingerprintHash, host, transportKind)])
}

private fun networkFingerprint() =
    NetworkFingerprint(
        transport = "wifi",
        networkValidated = true,
        captivePortalDetected = false,
        privateDnsMode = "system",
        dnsServers = listOf("1.1.1.1", "8.8.8.8"),
        wifi =
            WifiNetworkIdentityTuple(
                ssid = "ripdpi",
                bssid = "aa:bb:cc:dd:ee:ff",
                gateway = "192.0.2.1",
            ),
    )

private class FixedEdgePreferenceClock(
    private val now: Long,
) : DiagnosticsHistoryClock {
    override fun now(): Long = now
}
