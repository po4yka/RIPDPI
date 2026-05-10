package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IndirectSignsCheckerProcNetTest {
    @Test
    fun listeningSocketOnKnownProxyPortProducesFinding() {
        val result =
            ProcNetTcpProxyScanner.scan(
                reader = fakeReader(tcp = procNetTcpLine(portHex = "0438", state = "0A", uid = 10_000)),
                packageResolver = { listOf("com.example.proxy") },
            )

        assertTrue(result.findings.any { it.description.contains("1080") })
    }

    @Test
    fun nonListenStateSocketNotReported() {
        val result =
            ProcNetTcpProxyScanner.scan(
                reader = fakeReader(tcp = procNetTcpLine(portHex = "0438", state = "01", uid = 10_000)),
                packageResolver = { listOf("com.example.proxy") },
            )

        assertTrue(result.findings.isEmpty())
        assertFalse(result.outcome.detected)
    }

    @Test
    fun catalogMatchUpgradesConfidenceToMedium() {
        val result =
            ProcNetTcpProxyScanner.scan(
                reader = fakeReader(tcp = procNetTcpLine(portHex = "0438", state = "0A", uid = 10_000)),
                packageResolver = { listOf("com.v2ray.ang") },
            )

        assertTrue(
            result.findings.any {
                it.description.contains("v2rayNG") && it.confidence == EvidenceConfidence.MEDIUM
            },
        )
        assertTrue(result.outcome.detected)
    }

    @Test
    fun nonCatalogPackageProducesLowConfidenceInformational() {
        val result =
            ProcNetTcpProxyScanner.scan(
                reader = fakeReader(tcp = procNetTcpLine(portHex = "0438", state = "0A", uid = 10_000)),
                packageResolver = { listOf("com.example.proxy") },
            )

        assertTrue(result.findings.any { it.confidence == EvidenceConfidence.LOW })
        assertTrue(result.outcome.needsReview)
        assertFalse(result.outcome.detected)
    }

    @Test
    fun readErrorIsNonFatal() {
        val result =
            ProcNetTcpProxyScanner.scan(
                reader =
                    object : ProcNetTcpReader {
                        override fun readTcp(): String = throw IOException("denied")

                        override fun readTcp6(): String = throw IOException("denied")
                    },
                packageResolver = { emptyList() },
            )

        assertTrue(result.findings.isEmpty())
        assertFalse(result.outcome.detected)
        assertFalse(result.outcome.needsReview)
    }

    private fun fakeReader(
        tcp: String = "",
        tcp6: String = "",
    ): ProcNetTcpReader =
        object : ProcNetTcpReader {
            override fun readTcp(): String = tcp

            override fun readTcp6(): String = tcp6
        }

    private fun procNetTcpLine(
        portHex: String,
        state: String,
        uid: Int,
    ): String =
        """
        sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
         0: 0100007F:$portHex 00000000:0000 $state 00000000:00000000 00:00000000 00000000 $uid        0 12345 1 0000000000000000 100 0 0 10 0
        """.trimIndent()
}
