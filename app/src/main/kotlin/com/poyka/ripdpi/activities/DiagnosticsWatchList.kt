package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.exit.ProcessExitCorrelationKind
import com.poyka.ripdpi.diagnostics.exit.processExitCorrelationKind

/**
 * Selects the events the overview watch list shows: warnings and errors only, without process exits that
 * Android reports for an ordinary stop (for example a force-stop), and with identical repeats folded into
 * one row that keeps the newest time and a count. Expects newest-first input, as the event store returns it.
 */
internal fun List<DiagnosticsEventUiModel>.toWatchList(): List<DiagnosticsEventUiModel> =
    filter { it.tone == DiagnosticsTone.Negative || it.tone == DiagnosticsTone.Warning }
        .filterNot { isExpectedProcessExit(it.message) }
        .foldIdentical()

/**
 * Folds rows with the same source, severity and message into the first (newest) one and adds up their
 * counts. Guided mode applies it again after it rewrites raw messages, because different raw events can
 * then read the same.
 */
internal fun List<DiagnosticsEventUiModel>.foldIdentical(): List<DiagnosticsEventUiModel> =
    groupBy { Triple(it.source, it.severity, it.message) }
        .values
        .map { repeats -> repeats.first().copy(occurrences = repeats.sumOf { it.occurrences }) }

/** True for a process exit that Android reports for an ordinary stop, such as a force-stop. */
internal fun isExpectedProcessExit(message: String): Boolean =
    processExitCorrelationKind(message) == ProcessExitCorrelationKind.ExpectedLifecycle
