package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsHighlight
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummarySection

object DiagnosticsSummaryTextRenderer {
    fun render(
        document: DiagnosticsSummaryDocument,
        preludeLines: List<String> = emptyList(),
    ): String =
        buildString {
            preludeLines.forEach(::appendLine)
            appendSection(document.header)
            appendSection(document.reportMetadata)
            appendHighlights(document.highlights)
            appendDiagnoses(document)
            appendSection(document.environment)
            appendSection(document.telemetry)
            appendSection(document.rawPreview)
            appendSection(document.warnings)
        }.trim()

    private fun StringBuilder.appendSection(section: DiagnosticsSummarySection) {
        if (section.lines.isEmpty()) {
            return
        }
        appendLine("${section.title}:")
        section.lines.forEach(::appendLine)
    }

    private fun StringBuilder.appendHighlights(highlights: List<DiagnosticsHighlight>) {
        if (highlights.isEmpty()) {
            return
        }
        appendLine("Highlights:")
        highlights.forEach { highlight ->
            appendLine("${highlight.title}=${highlight.summary}")
        }
    }

    private fun StringBuilder.appendDiagnoses(document: DiagnosticsSummaryDocument) {
        if (document.diagnoses.isEmpty()) {
            return
        }
        appendLine("Diagnoses:")
        document.diagnoses.forEach { diagnosis ->
            appendLine("${diagnosis.code}=${diagnosis.summary}")
        }
    }
}
