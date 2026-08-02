package com.poyka.ripdpi.diagnostics

/**
 * Stable, non-sensitive codes that a user can send to support when a diagnostics archive fails.
 *
 * Do not add exception messages, paths, or identifiers to these values: they are shown directly
 * in the UI and copied to the clipboard.
 */
enum class DiagnosticsArchiveFailureCode(
    val supportCode: String,
) {
    STORAGE("archive_storage"),
    IO("archive_io"),
    DATABASE("archive_database"),
    INCONSISTENT_RESULT("archive_inconsistent_result"),
}

class DiagnosticsArchiveException(
    val failureCode: DiagnosticsArchiveFailureCode,
    cause: Throwable,
) : IllegalStateException("Diagnostics archive failed: ${failureCode.supportCode}", cause)
