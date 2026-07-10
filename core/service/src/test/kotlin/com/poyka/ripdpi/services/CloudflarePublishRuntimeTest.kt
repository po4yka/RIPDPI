package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

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
    fun `named tunnel config rejects injected TunnelID and hostname scalars`() {
        val tunnelError =
            runCatching {
                extractCloudflareNamedTunnelSpec(
                    """{"TunnelID":"550e8400-e29b-41d4-a716-446655440000\\nmetrics: 0.0.0.0:1"}""",
                )
            }.exceptionOrNull()
        val hostnameError =
            runCatching {
                buildCloudflaredConfigYaml(
                    tunnelId = "550e8400-e29b-41d4-a716-446655440000",
                    credentialsFilePath = "/tmp/cloudflared.json",
                    metricsAddress = "127.0.0.1:21345",
                    hostname = "edge.example.com\nservice: file:///private",
                    serviceUrl = "http://127.0.0.1:43128",
                )
            }.exceptionOrNull()

        assertTrue(tunnelError is IllegalArgumentException)
        assertTrue(hostnameError is IllegalArgumentException)
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

        assertTrue(yaml.contains("tunnel: \"550e8400-e29b-41d4-a716-446655440000\""))
        assertTrue(yaml.contains("hostname: \"edge.example.com\""))
        assertTrue(yaml.contains("service: \"http://127.0.0.1:43128\""))
    }

    @Test
    fun `token launch plan keeps token and state paths off argv`() {
        val stateDir = Files.createTempDirectory("ripdpi-cloudflared-token-").toFile()
        try {
            val config =
                sampleResolvedRelayConfig(kind = com.poyka.ripdpi.data.RelayKindCloudflareTunnel)
                    .copy(
                        cloudflareTunnelToken = "fixture-secret-token",
                        cloudflareTunnelCredentialsJson = null,
                        vlessUuid = "550e8400-e29b-41d4-a716-446655440000",
                        xhttpPath = "/private-path",
                    )
            val plan =
                CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()).build(
                    config = config,
                    originSpec = parseCloudflareLocalOriginSpec("http://127.0.0.1:43128"),
                    metricsAddress = "127.0.0.1:43129",
                    stateDir = stateDir,
                )

            assertEquals(listOf("tunnel", "--no-autoupdate", "run"), plan.arguments)
            val argv = plan.arguments.joinToString(" ")
            assertFalse(argv.contains("fixture-secret-token"))
            assertFalse(argv.contains("550e8400-e29b-41d4-a716-446655440000"))
            assertFalse(argv.contains("private-path"))
            assertFalse(argv.contains(stateDir.absolutePath))
            assertEquals("127.0.0.1:43129", plan.environment["TUNNEL_METRICS"])
            assertEquals(stateDir.absolutePath, plan.environment["HOME"])
            val tokenFile = plan.environment.getValue("TUNNEL_TOKEN_FILE")
            assertFalse(argv.contains(tokenFile))
            assertEquals("fixture-secret-token", java.io.File(tokenFile).readText())
            assertOwnerOnly(java.io.File(tokenFile))
        } finally {
            stateDir.deleteRecursively()
        }
    }

    @Test
    fun `named tunnel launch plan uses default home config without path argv`() {
        val stateDir = Files.createTempDirectory("ripdpi-cloudflared-named-").toFile()
        try {
            val config =
                sampleResolvedRelayConfig(kind = com.poyka.ripdpi.data.RelayKindCloudflareTunnel)
                    .copy(
                        cloudflareTunnelToken = null,
                        cloudflareTunnelCredentialsJson =
                            """{"TunnelID":"550e8400-e29b-41d4-a716-446655440000","TunnelSecret":"fixture-secret"}""",
                        vlessUuid = "a6f44fd0-4b10-4f2f-bf76-73f03e047e45",
                        xhttpPath = "/private-path",
                    )
            val plan =
                CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()).build(
                    config = config,
                    originSpec = parseCloudflareLocalOriginSpec("http://127.0.0.1:43128"),
                    metricsAddress = "127.0.0.1:43129",
                    stateDir = stateDir,
                )

            assertEquals(listOf("tunnel", "--no-autoupdate", "run"), plan.arguments)
            val argv = plan.arguments.joinToString(" ")
            assertFalse(argv.contains("550e8400-e29b-41d4-a716-446655440000"))
            assertFalse(argv.contains("a6f44fd0-4b10-4f2f-bf76-73f03e047e45"))
            assertFalse(argv.contains("private-path"))
            assertFalse(argv.contains(stateDir.absolutePath))
            assertEquals(stateDir.absolutePath, plan.environment["HOME"])
            assertTrue(stateDir.resolve(".cloudflared/config.yml").isFile)
            assertTrue(stateDir.resolve(".cloudflared/cloudflared-credentials.json").isFile)
            assertOwnerOnly(stateDir.resolve(".cloudflared"))
            assertOwnerOnly(stateDir.resolve(".cloudflared/config.yml"))
            assertOwnerOnly(stateDir.resolve(".cloudflared/cloudflared-credentials.json"))
        } finally {
            stateDir.deleteRecursively()
        }
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

    private fun assertOwnerOnly(file: java.io.File) {
        val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull() ?: return
        val forbidden =
            setOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            )
        assertTrue("$file exposed non-owner permissions: $permissions", permissions.intersect(forbidden).isEmpty())
    }
}
