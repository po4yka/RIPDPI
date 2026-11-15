package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IndirectSignsCheckerProcNetTest {
    @Test
    fun listeningSocketPreservesBindAddressAndListsUidCandidatesOnce() {
        val finding =
            ProcNetTcpProxyScanner
                .scan(
                    reader = reader(tcp = row("020200C0", "0A")),
                    packageResolver = { listOf("com.v2ray.ang", "com.example.shared", "com.v2ray.ang") },
                ).single()

        assertEquals(EvidenceConfidence.LOW, finding.confidence)
        assertTrue(finding.needsReview)
        assertFalse(finding.detected)
        assertTrue(finding.description.contains("192.0.2.2:1080 (specific address bind)"))
        assertTrue(finding.description.contains("package candidates: com.example.shared, v2rayNG (com.v2ray.ang)"))
    }

    @Test
    fun ipv6LoopbackAndIpv4WildcardAreDistinguished() {
        val findings =
            ProcNetTcpProxyScanner.scan(
                reader = reader(tcp = row("00000000", "0A"), tcp6 = row("00000000000000000000000001000000", "0A")),
                packageResolver = { emptyList() },
            )

        val descriptions = findings.map { it.description }
        assertEquals(2, descriptions.size)
        assertTrue(descriptions.any { it.contains("0.0.0.0:1080 (wildcard bind)") })
        assertTrue(descriptions.any { it.contains("(loopback bind)") })
        assertTrue(descriptions.all { it.contains("package candidates unavailable") })
    }

    @Test
    fun nonListeningOrMalformedRowsDoNotProduceCandidates() {
        val findings =
            ProcNetTcpProxyScanner.scan(
                reader = reader(tcp = row("0100007F", "01"), tcp6 = row("broken", "0A")),
                packageResolver = { listOf("com.example.proxy") },
            )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun readErrorIsNonFatal() {
        val findings =
            ProcNetTcpProxyScanner.scan(
                reader =
                    object : ProcNetTcpReader {
                        override fun readTcp(): String = throw IOException("denied")

                        override fun readTcp6(): String = throw IOException("denied")
                    },
                packageResolver = { emptyList() },
            )

        assertTrue(findings.isEmpty())
    }

    private fun reader(
        tcp: String,
        tcp6: String = "",
    ): ProcNetTcpReader =
        object : ProcNetTcpReader {
            override fun readTcp(): String = Header + tcp

            override fun readTcp6(): String = Header + tcp6
        }

    private fun row(
        address: String,
        state: String,
    ): String = "0: $address:0438 00000000:0000 $state 00000000:00000000 00:00000000 00000000 10000 0 12345"

    private companion object {
        const val Header =
            "sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
    }
}
