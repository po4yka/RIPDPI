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
                    apkLibraryNameHint = "split APK: lib/arm64-v8a/libxray.so",
                    matchedByNameHeuristic = true,
                ),
            )

        assertTrue(suffix.contains("version=1.2.3"))
        assertTrue(suffix.contains("APK library name hint: split APK: lib/arm64-v8a/libxray.so"))
        assertTrue(suffix.contains("services=VpnService, OtherService, ExtraService"))
        assertTrue(suffix.contains("nameHeuristic=true"))
    }

    @Test
    fun metadataSuffixIsEmptyWhenNoMetadataExists() {
        assertEquals("", VpnAppMetadataScanner.formatMetadataSuffix(null))
    }
}
