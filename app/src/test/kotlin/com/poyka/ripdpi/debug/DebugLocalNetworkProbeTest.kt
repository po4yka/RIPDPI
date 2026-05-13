package com.poyka.ripdpi.debug

import android.content.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DebugLocalNetworkProbeTest {
    private val debugProbeAction = "com.poyka.ripdpi.DEBUG_PROBE"

    @Test
    fun `emulator profile resolves default lab endpoints`() {
        val config =
            NetworkProbeConfig.fromIntent(
                Intent(debugProbeAction)
                    .putExtra("profile", "emulator")
                    .putExtra("mode", "vpn"),
            )

        assertEquals(LabProfile.Emulator, config.profile)
        assertEquals(ProbeMode.Vpn, config.mode)
        assertEquals("10.0.2.2", config.dnsServer)
        assertEquals("http://10.0.2.2:8080/get", config.httpUrl)
        assertEquals("https://10.0.2.2:8443/", config.httpsUrl)
        assertEquals("10.0.2.2", config.tcpHost)
        assertEquals(9000, config.tcpPort)
        assertEquals("10.0.2.2", config.udpHost)
        assertEquals(9001, config.udpPort)
        assertEquals("https://10.0.2.2:9443/h3/ok", config.quicUrl)
        assertTrue(config.requireVpnActive)
        assertTrue(config.requireProxyReady)
    }

    @Test
    fun `device profile requires explicit lab host`() {
        val error =
            runCatching {
                NetworkProbeConfig.fromIntent(
                    Intent(debugProbeAction)
                        .putExtra("profile", "device"),
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("lab_host"))
    }

    @Test
    fun `network probe result emits spec shaped json and typed quic unsupported state`() {
        val result =
            NetworkProbeResult(
                runId = "run-1",
                timestampEpochMs = 1L,
                appVersionName = "1-debug",
                appVersionCode = 2L,
                profile = LabProfile.Emulator,
                mode = ProbeMode.Vpn,
                vpnEstablished = true,
                tunActive = true,
                proxyReady = true,
                relayReady = null,
                dns = DnsProbeResult(true, true, "10.0.2.2", "ok.test", listOf("10.0.2.2"), 12, null),
                http = HttpProbeResult(true, true, "http://10.0.2.2:8080/get", 200, 512, 35, null),
                https = TlsProbeResult(true, true, "https://10.0.2.2:8443/", "TLS_AES_128_GCM_SHA256", null, 54, null),
                tcp = TcpProbeResult(true, true, "10.0.2.2", 9000, true, 8, null),
                udp = UdpProbeResult(true, true, "10.0.2.2", 9001, true, 10, null),
                quic =
                    QuicProbeResult(
                        attempted = false,
                        ok = false,
                        url = "https://10.0.2.2:9443/h3/ok",
                        httpStatus = null,
                        fallbackUsed = false,
                        latencyMs = null,
                        errorCode = "QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE",
                    ),
                egressProtected = true,
                ipv4RouteInstalled = true,
                ipv6RouteInstalled = false,
                fallbackUsed = false,
                verdict = ProbeVerdict.Degraded,
                errors = emptyList(),
            )

        val json = Json.parseToJsonElement(result.toJson()).jsonObject

        assertEquals("run-1", json["runId"]?.jsonPrimitive?.content)
        assertEquals("Emulator", json["profile"]?.jsonPrimitive?.content)
        assertEquals("Vpn", json["mode"]?.jsonPrimitive?.content)
        assertEquals("Degraded", json["verdict"]?.jsonPrimitive?.content)
        val dnsServer =
            json["dns"]
                ?.jsonObject
                ?.get("server")
                ?.jsonPrimitive
                ?.content
        val quicErrorCode =
            json["quic"]
                ?.jsonObject
                ?.get("errorCode")
                ?.jsonPrimitive
                ?.content

        assertEquals("10.0.2.2", dnsServer)
        assertEquals(
            "QUIC_UNSUPPORTED_ANDROID_DEBUG_PROBE",
            quicErrorCode,
        )
    }

    @Test
    fun `debug probe controls are not present in production source set`() {
        val mainManifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        val mainSources =
            sourceFile("src/main/kotlin", "app/src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString(separator = "\n") { it.readText() }

        assertFalse(mainManifest.contains(debugProbeAction))
        assertFalse(mainManifest.contains("DebugNetworkProbeReceiver"))
        assertFalse(mainSources.contains(debugProbeAction))
        assertTrue(
            sourceFile("src/debug/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml")
                .readText()
                .contains("DebugNetworkProbeReceiver"),
        )
    }

    private fun sourceFile(
        appRelativePath: String,
        repoRelativePath: String,
    ): File =
        listOf(
            File(appRelativePath),
            File(repoRelativePath),
        ).first { it.exists() }
}
