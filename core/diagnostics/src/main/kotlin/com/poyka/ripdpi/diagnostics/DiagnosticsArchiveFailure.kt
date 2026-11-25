package com.poyka.ripdpi.diagnostics

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
