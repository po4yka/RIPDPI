package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.activities.DiagnosticsExportSupportCodes
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DetectionExportShareTest {
    @Test
    fun `unwritable export directory returns storage code instead of throwing`() {
        val fileInsteadOfDirectory = File.createTempFile("detection-export", ".tmp")
        try {
            val result =
                DetectionExportShare.prepareShareIntent(
                    context = RuntimeEnvironment.getApplication(),
                    result = minimalResult(),
                    privacyModeEnabled = false,
                    format = DetectionExportFormat.JSON,
                    exportDirectory = fileInsteadOfDirectory,
                )

            assertEquals(
                DetectionExportShare.Preparation.Failed(DiagnosticsExportSupportCodes.ArchiveStorage),
                result,
            )
        } finally {
            fileInsteadOfDirectory.delete()
        }
    }

    private fun minimalResult(): DetectionCheckResult {
        val category = CategoryResult(name = "test", detected = false, findings = emptyList())
        return DetectionCheckResult(
            geoIp = category,
            directSigns = category,
            indirectSigns = category,
            locationSignals = category,
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = false,
                ),
            verdict = Verdict.NOT_DETECTED,
        )
    }
}
