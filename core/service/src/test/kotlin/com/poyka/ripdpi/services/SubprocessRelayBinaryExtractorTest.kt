package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubprocessRelayBinaryExtractorTest {
    @Test
    fun `Snowflake and obfs4 resolve the shared Lyrebird asset`() {
        val manifest =
            """
            {
              "artifacts": [
                {"abi":"arm64-v8a","outputName":"ripdpi-snowflake","upstreamBinary":"lyrebird.upstream"},
                {"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"lyrebird.upstream"}
              ]
            }
            """.trimIndent()

        for (binaryName in listOf("ripdpi-snowflake", "ripdpi-obfs4")) {
            assertEquals(
                "lyrebird.upstream",
                resolvePluggableTransportUpstreamAsset(
                    manifest,
                    "arm64-v8a",
                    binaryName,
                    setOf("lyrebird.upstream"),
                ),
            )
        }
    }

    @Test
    fun `legacy per-launcher upstream remains supported`() {
        assertEquals(
            "ripdpi-snowflake.upstream",
            resolvePluggableTransportUpstreamAsset(
                manifestPayload = null,
                abi = "arm64-v8a",
                binaryName = "ripdpi-snowflake",
                availableAssets = setOf("ripdpi-snowflake.upstream"),
            ),
        )
    }

    @Test
    fun `unsafe manifest path falls back without traversal`() {
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"../escape"}]}"""

        assertNull(
            resolvePluggableTransportUpstreamAsset(
                manifest,
                "arm64-v8a",
                "ripdpi-obfs4",
                setOf("../escape"),
            ),
        )
    }

    @Test
    fun `single-output transport keeps its existing upstream name`() {
        val manifest =
            """{"artifacts":[{"abi":"x86_64","outputName":"ripdpi-webtunnel","upstreamBinary":"ripdpi-webtunnel.upstream"}]}"""

        assertEquals(
            "ripdpi-webtunnel.upstream",
            resolvePluggableTransportUpstreamAsset(
                manifest,
                "x86_64",
                "ripdpi-webtunnel",
                setOf("ripdpi-webtunnel.upstream"),
            ),
        )
    }
}
