package com.poyka.ripdpi.ui.screens.detection

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.export.DetectionExportMetadata
import com.poyka.ripdpi.core.detection.export.DetectionJsonExportFormatter
import com.poyka.ripdpi.core.detection.export.DetectionMarkdownExportFormatter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class DetectionExportFormat(
    val extension: String,
    val mimeType: String,
) {
    MARKDOWN("md", "text/markdown"),
    JSON("json", "application/json"),
}

internal object DetectionExportShare {
    fun createShareIntent(
        context: Context,
        result: DetectionCheckResult,
        privacyModeEnabled: Boolean,
        format: DetectionExportFormat,
        now: Instant = Instant.now(),
    ): Intent {
        val metadata =
            DetectionExportMetadata(
                timestamp = now.toString(),
                appVersion = BuildConfig.VERSION_NAME,
                buildType = BuildConfig.BUILD_TYPE,
                privacyMode = privacyModeEnabled,
            )
        val fileName = "ripdpi-detection-${FilenameTimestamp.format(now)}.${format.extension}"
        val body = format.render(result, metadata)
        val file =
            File(context.cacheDir, ExportDirectory)
                .apply { mkdirs() }
                .resolve(fileName)
                .apply { writeText(body) }
        val uri =
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
                file,
            )
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun renderText(
        result: DetectionCheckResult,
        privacyModeEnabled: Boolean,
        format: DetectionExportFormat,
        now: Instant = Instant.now(),
    ): String =
        format.render(
            result = result,
            metadata =
                DetectionExportMetadata(
                    timestamp = now.toString(),
                    appVersion = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE,
                    privacyMode = privacyModeEnabled,
                ),
        )

    private fun DetectionExportFormat.render(
        result: DetectionCheckResult,
        metadata: DetectionExportMetadata,
    ): String =
        when (this) {
            DetectionExportFormat.MARKDOWN -> DetectionMarkdownExportFormatter.format(result, metadata)
            DetectionExportFormat.JSON -> DetectionJsonExportFormatter.format(result, metadata)
        }

    private const val ExportDirectory = "detection-exports"
    private val FilenameTimestamp =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
}
