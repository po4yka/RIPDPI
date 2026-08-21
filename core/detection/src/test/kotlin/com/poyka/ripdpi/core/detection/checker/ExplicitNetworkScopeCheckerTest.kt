package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitNetworkScopeCheckerTest {
    @Test
    fun `WebRTC TLS and timing evidence retain explicit network scope`() {
        val evidence =
            listOf(
                WebRtcLeakChecker.reachableStunEvidence("STUN reachable"),
                TlsFingerprintChecker.lowBrowserOverlapEvidence(),
                TimingAnalysisChecker.networkTimingEvidence(
                    confidence = EvidenceConfidence.MEDIUM,
                    description = "Tunnel timing pattern",
                ),
            )

        assertEquals(
            listOf(
                EvidenceSource.NETWORK_CAPABILITIES to DetectionScope.NETWORK_OBSERVATION,
                EvidenceSource.NETWORK_CAPABILITIES to DetectionScope.NETWORK_OBSERVATION,
                EvidenceSource.NETWORK_CAPABILITIES to DetectionScope.NETWORK_OBSERVATION,
            ),
            evidence.map { it.source to it.scope },
        )
    }
}
