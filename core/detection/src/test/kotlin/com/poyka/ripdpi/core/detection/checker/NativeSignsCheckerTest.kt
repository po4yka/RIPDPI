package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSignsCheckerTest {
    @Test
    fun hiddenInterfaceProducesHighConfidenceFinding() {
        val result =
            NativeSignsChecker.check(
                bridge = FakeNativeSignsBridge(nativeInterfaces = listOf("wlan0", "tun0")),
                jvmInterfaceProvider = { listOf("wlan0") },
            )

        assertEquals(listOf("tun0"), result.hiddenInterfaces)
        assertTrue(result.category.detected)
        assertTrue(
            result.category.findings.any { finding ->
                finding.source == EvidenceSource.NATIVE_SIGNS &&
                    finding.confidence == EvidenceConfidence.HIGH &&
                    finding.description.contains("tun0")
            },
        )
    }

    @Test
    fun fridaInMapsProducesHighConfidenceFinding() {
        val result =
            NativeSignsChecker.check(
                bridge = FakeNativeSignsBridge(mapsLines = listOf("7a1000-7b2000 r-xp /data/local/tmp/frida-agent.so")),
                jvmInterfaceProvider = { emptyList() },
            )

        assertEquals(listOf("frida"), result.hookMarkers)
        assertTrue(result.category.detected)
        assertTrue(result.category.evidence.any { it.confidence == EvidenceConfidence.HIGH })
    }

    @Test
    fun reviewSignalProducesFindingWithoutStrongDetection() {
        val result =
            NativeSignsChecker.check(
                bridge = FakeNativeSignsBridge(reviewSignals = listOf("Modified mount")),
                jvmInterfaceProvider = { emptyList() },
            )

        assertEquals(listOf("Modified mount"), result.reviewSignals)
        assertTrue(result.category.needsReview)
        assertFalse(result.category.detected)
        assertTrue(result.category.findings.any { it.description == "Modified mount" })
    }

    @Test
    fun libraryLoadFailureDoesNotCrash() {
        val result =
            NativeSignsChecker.check(
                bridge = ThrowingNativeSignsBridge,
                jvmInterfaceProvider = { emptyList() },
            )

        assertFalse(result.hasError)
        assertFalse(result.category.detected)
        val finding = result.category.findings.single()
        assertTrue(finding.description.contains("unavailable"))
    }

    private data class FakeNativeSignsBridge(
        private val nativeInterfaces: List<String> = emptyList(),
        private val mapsLines: List<String> = emptyList(),
        private val reviewSignals: List<String> = emptyList(),
    ) : NativeSignsBridge {
        override fun snapshot(): NativeSignsSnapshot =
            NativeSignsSnapshot(
                nativeInterfaces = nativeInterfaces,
                mapsLines = mapsLines,
                reviewSignals = reviewSignals,
            )
    }

    private object ThrowingNativeSignsBridge : NativeSignsBridge {
        override fun snapshot(): NativeSignsSnapshot = throw UnsatisfiedLinkError("missing native signs")
    }
}
