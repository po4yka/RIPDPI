package com.poyka.ripdpi.ui.screens.detection

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.DiagnosticsExportSupportCodes
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
    sealed interface Preparation {
        data class Ready(
            val intent: Intent,
        ) : Preparation

        data class Failed(
            val supportCode: String,
        ) : Preparation
    }

    fun prepareShareIntent(
        context: Context,
        result: DetectionCheckResult,
        privacyModeEnabled: Boolean,
        format: DetectionExportFormat,
        now: Instant = Instant.now(),
        exportDirectory: File = File(context.cacheDir, ExportDirectory),
    ): Preparation {
        val metadata =
            DetectionExportMetadata(
                timestamp = now.toString(),
                appVersion = BuildConfig.VERSION_NAME,
                buildType = BuildConfig.BUILD_TYPE,
                privacyMode = privacyModeEnabled,
            )
        val fileName = "ripdpi-detection-${FilenameTimestamp.format(now)}.${format.extension}"
        return runCatching { format.render(result, metadata) }
            .fold(
                onSuccess = { body ->
                    prepareFileShareIntent(context, exportDirectory, fileName, format.mimeType, body)
                },
                onFailure = {
                    Preparation.Failed(DiagnosticsExportSupportCodes.ArchiveInconsistentResult)
                },
            )
    }

    private fun prepareFileShareIntent(
        context: Context,
        exportDirectory: File,
        fileName: String,
        mimeType: String,
        body: String,
    ): Preparation =
        runCatching {
            val file =
                exportDirectory
                    .apply {
                        check((exists() && isDirectory) || mkdirs()) { "Cannot create detection export directory" }
                    }.resolve(fileName)
                    .apply { writeText(body) }
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.diagnostics.fileprovider",
                    file,
                )
            Preparation.Ready(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.getOrElse { Preparation.Failed(DiagnosticsExportSupportCodes.ArchiveStorage) }

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
