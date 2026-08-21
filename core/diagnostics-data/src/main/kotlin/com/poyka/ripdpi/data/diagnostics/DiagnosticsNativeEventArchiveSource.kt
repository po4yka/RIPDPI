package com.poyka.ripdpi.data.diagnostics

enum class DiagnosticsNativeEventArchiveClass(
    val storageValue: String,
) {
    TERMINAL("terminal"),
    CANDIDATE_TRIAL("candidate_trial"),
    CLEANUP("cleanup"),
    RUNTIME_RESTORATION("runtime_restoration"),
    OTHER("other"),
}

data class DiagnosticsNativeEventArchiveClassCounts(
    val terminal: Int = 0,
    val candidateTrial: Int = 0,
    val cleanup: Int = 0,
    val runtimeRestoration: Int = 0,
    val other: Int = 0,
) {
    val total: Int
        get() = terminal + candidateTrial + cleanup + runtimeRestoration + other

    fun count(eventClass: DiagnosticsNativeEventArchiveClass): Int =
        when (eventClass) {
            DiagnosticsNativeEventArchiveClass.TERMINAL -> terminal
            DiagnosticsNativeEventArchiveClass.CANDIDATE_TRIAL -> candidateTrial
            DiagnosticsNativeEventArchiveClass.CLEANUP -> cleanup
            DiagnosticsNativeEventArchiveClass.RUNTIME_RESTORATION -> runtimeRestoration
            DiagnosticsNativeEventArchiveClass.OTHER -> other
        }
}

data class DiagnosticsNativeEventArchiveSource(
    val events: List<NativeSessionEventEntity>,
    val sourceCounts: DiagnosticsNativeEventArchiveClassCounts,
)

data class DiagnosticsNativeEventArchiveClassCountRow(
    val archiveClass: String,
    val eventCount: Int,
)

fun Iterable<NativeSessionEventEntity>.archiveEventClassCounts(): DiagnosticsNativeEventArchiveClassCounts {
    val counts = groupingBy(NativeSessionEventEntity::archiveEventClass).eachCount()
    return DiagnosticsNativeEventArchiveClassCounts(
        terminal = counts[DiagnosticsNativeEventArchiveClass.TERMINAL] ?: 0,
        candidateTrial = counts[DiagnosticsNativeEventArchiveClass.CANDIDATE_TRIAL] ?: 0,
        cleanup = counts[DiagnosticsNativeEventArchiveClass.CLEANUP] ?: 0,
        runtimeRestoration = counts[DiagnosticsNativeEventArchiveClass.RUNTIME_RESTORATION] ?: 0,
        other = counts[DiagnosticsNativeEventArchiveClass.OTHER] ?: 0,
    )
}

fun List<NativeSessionEventEntity>.toNativeEventArchiveSource(): DiagnosticsNativeEventArchiveSource {
    val distinctEvents = distinctBy(NativeSessionEventEntity::id)
    return DiagnosticsNativeEventArchiveSource(
        events = distinctEvents,
        sourceCounts = distinctEvents.archiveEventClassCounts(),
    )
}

fun NativeSessionEventEntity.archiveEventClass(): DiagnosticsNativeEventArchiveClass =
    when {
        message.hasStructuredArchiveToken(
            "event_kind=data_plane_final",
            "event_kind=terminal_report",
            "final_report=",
        ) || subsystem.isOneOfArchiveValues("runtime_terminal", "runtime_terminal_outbox", "data_plane_final") -> {
            DiagnosticsNativeEventArchiveClass.TERMINAL
        }

        subsystem.isOneOfArchiveValues("post_runtime_restore") ||
            stage.isOneOfArchiveValues("post_runtime_restore") ||
            message.hasStructuredArchiveToken(
                "event_kind=post_runtime_restore",
                "event_kind=runtime_restoration",
            ) -> {
            DiagnosticsNativeEventArchiveClass.RUNTIME_RESTORATION
        }

        cleanupReceipt != null ||
            subsystem.isOneOfArchiveValues("candidate_cleanup", "runtime_cleanup") ||
            stage.isOneOfArchiveValues("candidate_cleanup", "runtime_cleanup") ||
            message.hasStructuredArchiveToken("forced_abort=", "cleanup_receipt=", "joined=", "stopped=") -> {
            DiagnosticsNativeEventArchiveClass.CLEANUP
        }

        subsystem.isOneOfArchiveValues("strategy_candidate", "candidate_trial") ||
            stage.isOneOfArchiveValues("strategy_candidate", "candidate_trial", "candidate") -> {
            DiagnosticsNativeEventArchiveClass.CANDIDATE_TRIAL
        }

        else -> {
            DiagnosticsNativeEventArchiveClass.OTHER
        }
    }

private fun String?.hasStructuredArchiveToken(vararg tokens: String): Boolean {
    if (this == null) return false
    return tokens.any { token -> contains(token, ignoreCase = true) }
}

private fun String?.isOneOfArchiveValues(vararg values: String): Boolean =
    this != null && values.any { value -> equals(value, ignoreCase = true) }
