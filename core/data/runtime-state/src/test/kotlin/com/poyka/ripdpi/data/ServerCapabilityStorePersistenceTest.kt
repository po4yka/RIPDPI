package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerCapabilityStorePersistenceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val fingerprint =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
        )

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("server_capability_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `recreated store isolates networks paths profiles and ip sets`() =
        runTest {
            val store = SharedPreferencesServerCapabilityStore(context)
            val direct =
                store.rememberDirectPathObservation(
                    fingerprint,
                    "example.org:443",
                    ServerCapabilityObservation(ipSetDigest = "aa"),
                    "test",
                    10L,
                )
            val otherIpSet =
                store.rememberDirectPathObservation(
                    fingerprint,
                    "example.org:443",
                    ServerCapabilityObservation(ipSetDigest = "bb"),
                    "test",
                    20L,
                )
            val relay =
                store.rememberRelayObservation(
                    fingerprint,
                    "example.org:443",
                    "primary",
                    ServerCapabilityObservation(),
                    "test",
                    30L,
                )
            val otherProfile =
                store.rememberRelayObservation(
                    fingerprint,
                    "example.org:443",
                    "secondary",
                    ServerCapabilityObservation(),
                    "test",
                    40L,
                )
            val otherNetwork = fingerprint.copy(transport = "cellular")
            val cellular =
                store.rememberDirectPathObservation(
                    otherNetwork,
                    "example.org:443",
                    ServerCapabilityObservation(),
                    "test",
                    50L,
                )

            val reopened = SharedPreferencesServerCapabilityStore(context)
            assertEquals(
                listOf(otherIpSet, direct),
                reopened.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()),
            )
            assertEquals(listOf(otherProfile, relay), reopened.relayCapabilitiesForFingerprint(fingerprint.scopeKey()))
            assertEquals(listOf(cellular), reopened.directPathCapabilitiesForFingerprint(otherNetwork.scopeKey()))
            assertTrue(reopened.relayCapabilitiesForFingerprint(otherNetwork.scopeKey()).isEmpty())
        }

    @Test
    fun `normalized authority merges persisted evidence after recreation`() =
        runTest {
            SharedPreferencesServerCapabilityStore(context).rememberDirectPathObservation(
                fingerprint,
                " EXAMPLE.ORG:443 ",
                ServerCapabilityObservation(quicUsable = true),
                "first",
                10L,
            )
            val merged =
                SharedPreferencesServerCapabilityStore(context).rememberDirectPathObservation(
                    fingerprint,
                    "example.org:443",
                    ServerCapabilityObservation(udpUsable = false),
                    "second",
                    20L,
                )

            val records =
                SharedPreferencesServerCapabilityStore(
                    context,
                ).directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
            assertEquals(listOf(merged), records)
            assertEquals("example.org:443", merged.authority)
            assertEquals(true, merged.quicUsable)
            assertEquals(false, merged.udpUsable)
            assertEquals("second", merged.source)
            assertEquals(20L, merged.updatedAt)
        }

    @Test
    fun `invalid entries do not hide valid evidence and clear removes both paths`() =
        runTest {
            val store = SharedPreferencesServerCapabilityStore(context)
            val direct =
                store.rememberDirectPathObservation(
                    fingerprint,
                    "example.org:443",
                    ServerCapabilityObservation(),
                    "test",
                    10L,
                )
            store.rememberRelayObservation(
                fingerprint,
                "example.org:443",
                "primary",
                ServerCapabilityObservation(),
                "test",
                20L,
            )
            context
                .getSharedPreferences("server_capability_cache", Context.MODE_PRIVATE)
                .edit()
                .putString("direct:broken", "{invalid")
                .putInt("direct:wrong-type", 1)
                .commit()

            val reopened = SharedPreferencesServerCapabilityStore(context)
            assertEquals(listOf(direct), reopened.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()))
            reopened.clearAll()
            val cleared = SharedPreferencesServerCapabilityStore(context)
            assertTrue(cleared.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).isEmpty())
            assertTrue(cleared.relayCapabilitiesForFingerprint(fingerprint.scopeKey()).isEmpty())
        }
}
