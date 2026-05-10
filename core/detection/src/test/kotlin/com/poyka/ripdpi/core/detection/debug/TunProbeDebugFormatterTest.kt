package com.poyka.ripdpi.core.detection.debug

import org.junit.Assert.assertTrue
import org.junit.Test

class TunProbeDebugFormatterTest {
    @Test
    fun formatSectionContainsPerPathFields() {
        val output =
            TunProbeDebugFormatter.formatSection(
                TunProbeDebugSnapshot(
                    debugEnabled = true,
                    vpnPath =
                        TunProbeDebugPath(
                            available = true,
                            selectedMode = "strict",
                            selectedIp = "10.0.0.2",
                        ),
                    underlyingPath =
                        TunProbeDebugPath(
                            available = false,
                            error = "missing network",
                        ),
                ),
            )

        assertTrue(output.contains("[tunProbe]"))
        assertTrue(output.contains("vpnPath.selectedMode=strict"))
        assertTrue(output.contains("underlyingPath.available=false"))
    }
}
