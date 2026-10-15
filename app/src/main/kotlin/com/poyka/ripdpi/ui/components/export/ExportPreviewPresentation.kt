package com.poyka.ripdpi.ui.components.export

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

internal enum class ExportPreviewPurpose {
    ShareSummary,
    ShareArchive,
    SaveArchive,
    SaveLogs,
}

@Immutable
internal sealed interface ExportPreviewPresentation {
    data object Preparing : ExportPreviewPresentation

    data class Ready(
        val summary: String,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
        val entryNames: ImmutableList<String>,
        val purpose: ExportPreviewPurpose,
    ) : ExportPreviewPresentation

    @ConsistentCopyVisibility
    data class Error private constructor(
        val message: String?,
        @param:StringRes val messageRes: Int?,
    ) : ExportPreviewPresentation {
        constructor(
            @StringRes messageRes: Int,
        ) : this(null, messageRes)

        constructor(message: String) : this(message, null)
    }
}
