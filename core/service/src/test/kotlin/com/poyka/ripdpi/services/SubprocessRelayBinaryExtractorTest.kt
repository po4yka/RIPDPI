package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

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
        val manifest =
            """
            {"artifacts":[
              {"abi":"arm64-v8a","outputName":"ripdpi-snowflake","upstreamBinary":"ripdpi-snowflake.upstream"}
            ]}
            """.trimIndent()

        assertEquals(
            "ripdpi-snowflake.upstream",
            resolvePluggableTransportUpstreamAsset(
                manifestPayload = manifest,
                abi = "arm64-v8a",
                binaryName = "ripdpi-snowflake",
                availableAssets = setOf("ripdpi-snowflake.upstream"),
            ),
        )
    }

    @Test
    fun `unsafe manifest path fails closed without traversal`() {
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"../escape"}]}"""

        assertThrows(IllegalArgumentException::class.java) {
            resolvePluggableTransportUpstreamAsset(
                manifest,
                "arm64-v8a",
                "ripdpi-obfs4",
                setOf("../escape"),
            )
        }
    }

    @Test
    fun `single-output transport keeps its existing upstream name`() {
        val manifest =
            """
            {"artifacts":[
              {"abi":"x86_64","outputName":"ripdpi-webtunnel","upstreamBinary":"ripdpi-webtunnel.upstream"}
            ]}
            """.trimIndent()

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

    @Test
    fun `stub manifest ignores and removes stale legacy upstream`() {
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":null}]}"""
        val tempDir = Files.createTempDirectory("ripdpi-pt-extractor").toFile()
        try {
            val staleUpstream = tempDir.resolve("ripdpi-obfs4.upstream")
            staleUpstream.writeText("stale")

            assertNull(
                resolvePluggableTransportUpstreamAsset(
                    manifest,
                    "arm64-v8a",
                    "ripdpi-obfs4",
                    setOf("ripdpi-obfs4.upstream"),
                ),
            )
            removeStalePluggableTransportFile(staleUpstream)

            assertEquals(false, staleUpstream.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `malformed manifest does not fall back to legacy asset`() {
        for (manifest in listOf(null, "{not-json")) {
            assertThrows(IllegalArgumentException::class.java) {
                resolvePluggableTransportUpstreamAsset(
                    manifest,
                    "arm64-v8a",
                    "ripdpi-obfs4",
                    setOf("ripdpi-obfs4.upstream"),
                )
            }
        }
    }
}
