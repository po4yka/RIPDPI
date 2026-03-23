package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkDnsPathPreferenceStoreTest {
    private val clock = FixedDnsPreferenceClock(now = 7_000L)
    private val recordStore = FakeNetworkDnsPathPreferenceRecordStore()
    private val store = DefaultNetworkDnsPathPreferenceStore(recordStore, clock)

    @Test
    fun `remember preferred path uses clock when recordedAt omitted`() =
        runTest {
            val result =
                store.rememberPreferredPath(
                    fingerprint = networkFingerprint(),
                    path = encryptedDnsPath(),
                )

            assertEquals(clock.now(), result.updatedAt)
            assertEquals(clock.now(), recordStore.requireStored(result.fingerprintHash).updatedAt)
        }

    @Test
    fun `remember preferred path uses explicit recordedAt when provided`() =
        runTest {
            val result =
                store.rememberPreferredPath(
                    fingerprint = networkFingerprint(),
                    path = encryptedDnsPath(),
                    recordedAt = 8_888L,
                )

            assertEquals(8_888L, result.updatedAt)
            assertEquals(8_888L, recordStore.requireStored(result.fingerprintHash).updatedAt)
        }
}

private class FakeNetworkDnsPathPreferenceRecordStore : NetworkDnsPathPreferenceRecordStore {
    private val preferences = linkedMapOf<String, NetworkDnsPathPreferenceEntity>()
    private var nextId = 1L

    override suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity? =
        preferences[fingerprintHash]

    override suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long {
        val persisted =
            if (preference.id > 0L) {
                preference
            } else {
                preference.copy(id = nextId++)
            }
        preferences[persisted.fingerprintHash] = persisted
        return persisted.id
    }

    override suspend fun pruneNetworkDnsPathPreferences() = Unit

    fun requireStored(fingerprintHash: String): NetworkDnsPathPreferenceEntity =
        requireNotNull(preferences[fingerprintHash])
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

private fun encryptedDnsPath() =
    EncryptedDnsPathCandidate(
        resolverId = "cloudflare",
        resolverLabel = "Cloudflare",
        protocol = EncryptedDnsProtocolDoh,
        host = "cloudflare-dns.com",
        port = 443,
        tlsServerName = "cloudflare-dns.com",
        bootstrapIps = listOf("1.1.1.1"),
        dohUrl = "https://cloudflare-dns.com/dns-query",
        dnscryptProviderName = "",
        dnscryptPublicKey = "",
    )

private class FixedDnsPreferenceClock(
    private val now: Long,
) : DiagnosticsHistoryClock {
    override fun now(): Long = now
}
