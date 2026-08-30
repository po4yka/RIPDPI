package com.poyka.ripdpi.data

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareWorkerTransportConfigTest {
    @Test
    fun `unsafe Worker URLs fail typed validation`() {
        val unsafeUrls =
            listOf(
                "https://edge.example/relay\r\nx",
                "https://edge.example/relay path",
                "https://edge.example:0/relay",
                "https://edge_example/relay",
            )

        unsafeUrls.forEach { url ->
            val failure =
                runCatching {
                    CloudflareWorkerTransportConfig(
                        workerUrl = url,
                        credentialRef = "worker",
                        authBearer = SecretString("secret-token"),
                    )
                }.exceptionOrNull()

            assertTrue("expected rejection for $url", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `non-header-safe Worker bearers fail typed validation`() {
        for (bearer in listOf("secret token", "秘密", "=padding-only", "x".repeat(4097))) {
            val failure =
                runCatching {
                    CloudflareWorkerTransportConfig(
                        workerUrl = "https://edge.example/relay",
                        credentialRef = "worker",
                        authBearer = SecretString(bearer),
                    )
                }.exceptionOrNull()

            assertTrue("expected rejection for unsafe bearer", failure is IllegalArgumentException)
        }
    }
}
