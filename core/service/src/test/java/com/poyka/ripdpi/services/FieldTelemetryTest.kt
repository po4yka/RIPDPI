package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTelemetryTest {
    @Test
    fun `file backed telemetry salt store is stable until salt file is reset`() {
        val directory = Files.createTempDirectory("telemetry-salt-test")
        val saltFile = directory.resolve("telemetry-salt.txt").toFile()
        saltFile.writeText("persisted-salt")
        val firstStore =
            FileBackedTelemetryInstallSaltStoreDelegate(
                saltFile = saltFile,
                secureRandom = seededSecureRandom(0x01, 0x23, 0x45, 0x67),
            )

        val first = firstStore.loadSalt()
        val second = firstStore.loadSalt()

        assertEquals(first, second)
        assertEquals("persisted-salt", first)
        assertTrue(saltFile.exists())

        assertTrue(saltFile.delete())

        val rotated =
            FileBackedTelemetryInstallSaltStoreDelegate(
                saltFile = saltFile,
                secureRandom = seededSecureRandom(0x10, 0x20, 0x30, 0x40),
            ).loadSalt()

        assertNotEquals("persisted-salt", rotated)
    }

    @Test
    fun `rotateSalt deletes salt file so next load generates a new value`() {
        val directory = Files.createTempDirectory("telemetry-salt-rotate")
        val saltFile = directory.resolve("telemetry-salt.txt").toFile()
        val store =
            FileBackedTelemetryInstallSaltStoreDelegate(
                saltFile = saltFile,
                secureRandom = seededSecureRandom(0xAA, 0xBB, 0xCC, 0xDD),
            )

        val original = store.loadSalt()
        assertTrue(saltFile.exists())

        store.rotateSalt()
        assertTrue(!saltFile.exists())

        val rotated =
            FileBackedTelemetryInstallSaltStoreDelegate(
                saltFile = saltFile,
                secureRandom = seededSecureRandom(0x11, 0x22, 0x33, 0x44),
            ).loadSalt()

        assertNotEquals(original, rotated)
    }

    @Test
    fun `telemetry hash is stable for a salt and differs from scope key`() {
        val fingerprint =
            NetworkFingerprint(
                transport = "wifi",
                networkValidated = true,
                captivePortalDetected = false,
                privateDnsMode = "system",
                dnsServers = listOf("1.1.1.1"),
                wifi =
                    WifiNetworkIdentityTuple(
                        ssid = "ripdpi-lab",
                        bssid = "aa:bb:cc:dd:ee:ff",
                        gateway = "192.0.2.1",
                    ),
            )
        val hasher =
            DefaultTelemetryFingerprintHasher(
                saltStore =
                    object : TelemetryInstallSaltStore {
                        override fun loadSalt(): String = "test-salt"
                        override fun rotateSalt() = Unit
                    },
            )

        val first = hasher.hash(fingerprint)
        val second = hasher.hash(fingerprint)

        assertEquals(first, second)
        assertTrue(first?.startsWith("v1:") == true)
        assertNotEquals(fingerprint.scopeKey(), first)
    }

    @Test
    fun `failure class text mapping covers supported classes`() {
        val cases =
            mapOf(
                "tunnel establishment failed" to FailureClass.TunnelEstablish,
                "dns blocked by upstream" to FailureClass.DnsInterference,
                "tls handshake timeout" to FailureClass.TlsInterference,
                "operation timed out" to FailureClass.Timeout,
                "connection reset by peer" to FailureClass.ResetAbort,
                "network handover detected" to FailureClass.NetworkHandover,
                "socket write failed" to FailureClass.NativeIo,
            )

        cases.forEach { (message, expected) ->
            val actual =
                classifyFailureClass(
                    failureReason = null,
                    proxyTelemetry = NativeRuntimeSnapshot(source = "proxy"),
                    tunnelTelemetry = NativeRuntimeSnapshot(source = "tunnel", lastError = message),
                )
            assertEquals("Unexpected mapping for `$message`", expected, actual)
        }
    }

    @Test
    fun `explicit failure reason takes precedence over native events and handover`() {
        val actual =
            classifyFailureClass(
                failureReason = FailureReason.TunnelEstablishmentFailed,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        nativeEvents =
                            listOf(
                                NativeRuntimeEvent(
                                    source = "proxy",
                                    level = "error",
                                    message = "dns blocked",
                                    createdAt = 10L,
                                ),
                            ),
                    ),
                tunnelTelemetry =
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        networkHandoverClass = "transport_switch",
                    ),
            )

        assertEquals(FailureClass.TunnelEstablish, actual)
    }
}

private fun seededSecureRandom(vararg seedBytes: Int): SecureRandom =
    SecureRandom.getInstance("SHA1PRNG").apply {
        setSeed(seedBytes.map(Int::toByte).toByteArray())
    }
