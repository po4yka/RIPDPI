package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.ui.components.export.ExportPreviewCard
import com.poyka.ripdpi.ui.components.export.ExportPreviewPresentation
import com.poyka.ripdpi.ui.components.export.ExportPreviewPurpose
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportPreviewScreenshotTest {
    @Test fun archiveLight() = capture("archive_light", ready())

    @Test fun archiveDark() = capture("archive_dark", ready(), dark = true)

    @Test fun summaryLight() = capture("summary_light", ready(ExportPreviewPurpose.ShareSummary))

    @Test fun logsDark() = capture("logs_dark", ready(ExportPreviewPurpose.SaveLogs), dark = true)

    @Test fun preparingLight() = capture("preparing_light", ExportPreviewPresentation.Preparing)

    @Test fun errorDark() = capture("error_dark", ExportPreviewPresentation.Error("Storage unavailable"), dark = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun archiveRtlFont2() = capture("archive_rtl_font2", ready(), rtl = true, font = 2f)

    @Test fun logsFont2() = capture("logs_font2", ready(ExportPreviewPurpose.SaveLogs), font = 2f)

    private fun capture(
        name: String,
        presentation: ExportPreviewPresentation,
        dark: Boolean = false,
        rtl: Boolean = false,
        font: Float = 1f,
    ) {
        captureSingle(
            name = name,
            widthDp = 411,
            heightDp = 900,
            darkMode = dark,
            fontScale = font,
            layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            testClassFqn = javaClass.name,
        ) {
            ExportPreviewCard(presentation, {}, {})
        }
    }

    private fun ready(purpose: ExportPreviewPurpose = ExportPreviewPurpose.SaveArchive) =
        ExportPreviewPresentation.Ready(
            summary =
                if (purpose == ExportPreviewPurpose.SaveLogs) {
                    "12:04 · Service ready\n12:05 · DNS probe completed"
                } else {
                    "DNS: 3 passed\nHTTP: 1 passed\nActive connection path"
                },
            fileName = if (purpose == ExportPreviewPurpose.SaveLogs) "ripdpi-log.txt" else "ripdpi-report.zip",
            mimeType = if (purpose == ExportPreviewPurpose.SaveLogs) "text/plain" else "application/zip",
            byteCount = 12785,
            entryNames =
                if (purpose == ExportPreviewPurpose.SaveLogs) {
                    persistentListOf("ripdpi-log.txt")
                } else {
                    persistentListOf("summary.txt", "manifest.json", "report.json", "logs.json")
                },
            purpose = purpose,
        )
}
