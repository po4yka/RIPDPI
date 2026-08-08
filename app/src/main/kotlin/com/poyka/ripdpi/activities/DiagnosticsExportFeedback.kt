package com.poyka.ripdpi.activities

import android.os.Build
import androidx.annotation.StringRes
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveException
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveFailureCode
import java.io.IOException
import java.util.Locale

private const val SupportPayloadFormatVersion = 1
private const val MaxCauseDepth = 8

internal enum class DiagnosticsDocumentExportStage(
    val supportValue: String,
) {
    DESTINATION_OPEN("destination_open"),
    ARCHIVE_WRITE("archive_write"),
}

/**
 * Adds the Android document stage and cleanup outcome without exposing the selected document URI.
 */
internal class DiagnosticsDocumentExportException(
    val stage: DiagnosticsDocumentExportStage,
    val partialDocumentDeleted: Boolean,
    cause: Throwable,
) : IOException("Diagnostics document export failed at ${stage.supportValue}", cause)

internal data class DiagnosticsExportSupportContext(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val gitCommit: String,
    val androidRelease: String,
    val androidApi: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val primaryAbi: String,
    val occurredAtEpochMs: Long,
) {
    companion object {
        fun current(): DiagnosticsExportSupportContext =
            DiagnosticsExportSupportContext(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                gitCommit = BuildConfig.GIT_COMMIT,
                androidRelease = Build.VERSION.RELEASE,
                androidApi = Build.VERSION.SDK_INT,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                occurredAtEpochMs = System.currentTimeMillis(),
            )
    }
}

internal data class DiagnosticsExportFeedback(
    @param:StringRes val messageRes: Int,
    val supportCode: String,
    val supportPayload: String,
)

internal fun diagnosticsArchiveSaveFeedback(
    error: Throwable,
    context: DiagnosticsExportSupportContext = DiagnosticsExportSupportContext.current(),
): DiagnosticsExportFeedback {
    val documentFailure = error as? DiagnosticsDocumentExportException
    val failureCode = error.archiveFailureCode()
    return buildDiagnosticsExportFeedback(
        error = error,
        context = context,
        operation = "diagnostics_archive_save",
        stage = documentFailure?.stage?.supportValue ?: "archive_prepare",
        cleanup = documentFailure.cleanupSupportValue(),
        messageRes = archiveSaveMessage(failureCode, documentFailure?.stage),
        fallbackSupportCode = documentFailure?.stage.toSupportCode(),
    )
}

internal fun diagnosticsArchiveShareFeedback(
    error: Throwable,
    context: DiagnosticsExportSupportContext = DiagnosticsExportSupportContext.current(),
): DiagnosticsExportFeedback =
    buildDiagnosticsExportFeedback(
        error = error,
        context = context,
        operation = "diagnostics_archive_share",
        stage = "archive_prepare",
        cleanup = "not_applicable",
        messageRes = R.string.home_diagnostics_share_failed,
        fallbackSupportCode = DiagnosticsExportSupportCodes.ArchiveIo,
    )

private fun buildDiagnosticsExportFeedback(
    error: Throwable,
    context: DiagnosticsExportSupportContext,
    operation: String,
    stage: String,
    cleanup: String,
    @StringRes messageRes: Int,
    fallbackSupportCode: String,
): DiagnosticsExportFeedback {
    val failureCode = error.archiveFailureCode()
    val supportCode = failureCode?.supportCode ?: fallbackSupportCode
    val exceptionChain =
        error
            .causeSequence()
            .take(MaxCauseDepth)
            .joinToString(" > ") { throwable -> throwable.javaClass.simpleName.ifBlank { "Throwable" } }
    val archiveFailureName = failureCode?.name?.lowercase(Locale.ROOT) ?: "none"

    return DiagnosticsExportFeedback(
        messageRes = messageRes,
        supportCode = supportCode,
        supportPayload =
            buildString {
                appendLine("RIPDPI diagnostics export error")
                appendLine("format_version=$SupportPayloadFormatVersion")
                appendLine("support_code=${supportCode.safeSupportValue()}")
                appendLine("operation=$operation")
                appendLine("stage=$stage")
                appendLine("archive_failure=$archiveFailureName")
                appendLine("partial_document_cleanup=$cleanup")
                appendLine("exception_chain=${exceptionChain.safeSupportValue()}")
                appendLine("occurred_at_epoch_ms=${context.occurredAtEpochMs}")
                appendLine(
                    "app_version=${context.appVersion.safeSupportValue()} (${context.versionCode})",
                )
                appendLine("build_type=${context.buildType.safeSupportValue()}")
                appendLine("git_commit=${context.gitCommit.safeSupportValue()}")
                appendLine(
                    "android=${context.androidRelease.safeSupportValue()} (API ${context.androidApi})",
                )
                appendLine(
                    "device=${context.deviceManufacturer.safeSupportValue()} ${context.deviceModel.safeSupportValue()}",
                )
                append("primary_abi=${context.primaryAbi.safeSupportValue()}")
            },
    )
}

private fun DiagnosticsDocumentExportException?.cleanupSupportValue(): String =
    when (this?.partialDocumentDeleted) {
        true -> "removed"
        false -> "not_removed"
        null -> "not_applicable"
    }

private fun Throwable.archiveFailureCode(): DiagnosticsArchiveFailureCode? =
    causeSequence().filterIsInstance<DiagnosticsArchiveException>().firstOrNull()?.failureCode

@StringRes
private fun archiveSaveMessage(
    failureCode: DiagnosticsArchiveFailureCode?,
    stage: DiagnosticsDocumentExportStage?,
): Int {
    if (failureCode == DiagnosticsArchiveFailureCode.STORAGE) {
        return R.string.diagnostics_archive_storage_failed
    }
    if (failureCode == DiagnosticsArchiveFailureCode.DATABASE) {
        return R.string.diagnostics_archive_database_failed
    }
    if (failureCode == DiagnosticsArchiveFailureCode.INCONSISTENT_RESULT) {
        return R.string.diagnostics_archive_inconsistent_result_failed
    }
    if (failureCode == DiagnosticsArchiveFailureCode.IO) {
        return R.string.diagnostics_archive_destination_write_failed
    }
    return when (stage) {
        DiagnosticsDocumentExportStage.DESTINATION_OPEN -> R.string.diagnostics_archive_destination_open_failed
        DiagnosticsDocumentExportStage.ARCHIVE_WRITE -> R.string.diagnostics_archive_destination_write_failed
        null -> R.string.diagnostics_archive_save_failed
    }
}

private fun DiagnosticsDocumentExportStage?.toSupportCode(): String =
    when (this) {
        DiagnosticsDocumentExportStage.DESTINATION_OPEN -> DiagnosticsExportSupportCodes.ArchiveDestinationOpen
        DiagnosticsDocumentExportStage.ARCHIVE_WRITE -> DiagnosticsExportSupportCodes.ArchiveDestinationWrite
        null -> DiagnosticsExportSupportCodes.ArchiveIo
    }

private fun Throwable.causeSequence(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }

private fun String.safeSupportValue(): String = replace('\n', ' ').replace('\r', ' ')
