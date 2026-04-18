package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflarePublishRuntimeTest {
    @Test
    fun `parseCloudflareLocalOriginSpec normalizes loopback origin URL`() {
        val parsed = parseCloudflareLocalOriginSpec("http://localhost:43128")

        assertEquals("http://localhost:43128", parsed.rawUrl)
        assertEquals("localhost", parsed.host)
        assertEquals(43128, parsed.port)
    }

    @Test
    fun `parseCloudflareLocalOriginSpec rejects non-loopback hosts`() {
        val error =
            runCatching {
                parseCloudflareLocalOriginSpec("http://198.51.100.7:43128")
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("loopback", ignoreCase = true) == true)
    }

    @Test
    fun `extractCloudflareNamedTunnelSpec accepts TunnelID field`() {
        val parsed =
            extractCloudflareNamedTunnelSpec(
                """
                {"TunnelID":"550e8400-e29b-41d4-a716-446655440000","AccountTag":"fixture"}
                """.trimIndent(),
            )

        assertEquals("550e8400-e29b-41d4-a716-446655440000", parsed.tunnelId)
    }

    @Test
    fun `buildCloudflaredConfigYaml writes hostname and origin service`() {
        val yaml =
            buildCloudflaredConfigYaml(
                tunnelId = "550e8400-e29b-41d4-a716-446655440000",
                credentialsFilePath = "/tmp/cloudflared.json",
                metricsAddress = "127.0.0.1:21345",
                hostname = "edge.example.com",
                serviceUrl = "http://127.0.0.1:43128",
            )

        assertTrue(yaml.contains("tunnel: 550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(yaml.contains("hostname: edge.example.com"))
        assertTrue(yaml.contains("service: http://127.0.0.1:43128"))
    }

    @Test
    fun `mergeCloudflarePublishTelemetry reports running helper state and versions`() {
        val merged =
            mergeCloudflarePublishTelemetry(
                relayTelemetry = NativeRuntimeSnapshot(source = "relay"),
                state =
                    CloudflarePublishTelemetryState(
                        helpersRunning = true,
                        originReady = true,
                        cloudflaredReady = true,
                        cloudflaredVersion = "cloudflared 2026.4.1",
                        originVersion = "origin 0.1.0",
                        originListenerAddress = "127.0.0.1:43128",
                    ),
            )

        assertEquals("cloudflared", merged.ptRuntimeKind)
        assertEquals("running", merged.ptRuntimeState)
        assertEquals("cloudflared 2026.4.1 | origin=origin 0.1.0", merged.ptRuntimeVersion)
        assertEquals("127.0.0.1:43128", merged.listenerAddress)
    }

    @Test
    fun `mergeCloudflarePublishTelemetry preserves existing relay error fields`() {
        val merged =
            mergeCloudflarePublishTelemetry(
                relayTelemetry =
                    NativeRuntimeSnapshot(
                        source = "relay",
                        listenerAddress = "127.0.0.1:1080",
                        lastError = "native failure",
                        lastFailureClass = "native",
                    ),
                state =
                    CloudflarePublishTelemetryState(
                        helpersRunning = false,
                        originReady = false,
                        cloudflaredReady = false,
                        originListenerAddress = "127.0.0.1:43128",
                        lastError = "helper failure",
                        lastFailureClass = "helper_exit",
                    ),
            )

        assertEquals("failed", merged.ptRuntimeState)
        assertEquals("127.0.0.1:1080", merged.listenerAddress)
        assertEquals("native failure", merged.lastError)
        assertEquals("native", merged.lastFailureClass)
    }
}
