package com.poyka.ripdpi.core.detection.vpn

import com.poyka.ripdpi.core.detection.VpnAppTechnicalMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppMetadataScannerTest {
    @Test
    fun metadataSuffixIncludesStableTechnicalFields() {
        val suffix =
            VpnAppMetadataScanner.formatMetadataSuffix(
                VpnAppTechnicalMetadata(
                    versionName = "1.2.3",
                    serviceNames = listOf("VpnService", "OtherService", "ExtraService", "IgnoredService"),
                    appType = "native",
                    coreType = "xray",
                    corePath = "lib/arm64-v8a/libxray.so",
                    goVersion = "go1.22.4",
                    matchedByNameHeuristic = true,
                ),
            )

        assertTrue(suffix.contains("version=1.2.3"))
        assertTrue(suffix.contains("core=xray"))
        assertTrue(suffix.contains("services=VpnService, OtherService, ExtraService"))
        assertTrue(suffix.contains("nameHeuristic=true"))
    }

    @Test
    fun metadataSuffixIsEmptyWhenNoMetadataExists() {
        assertEquals("", VpnAppMetadataScanner.formatMetadataSuffix(null))
    }
}
