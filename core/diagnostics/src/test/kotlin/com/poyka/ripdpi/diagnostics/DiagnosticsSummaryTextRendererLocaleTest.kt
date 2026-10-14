package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsHighlight
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsSummaryTextRendererLocaleTest {
    private val document =
        DiagnosticsSummaryDocument(
            highlights = listOf(DiagnosticsHighlight("RAW_CODE", "Exact_Raw_Highlight")),
            diagnoses = listOf(Diagnosis("RAW_CODE", "Exact_Raw_Diagnosis")),
        )

    @Test
    fun `explicit translated headings preserve evidence and section order`() {
        assertEquals(
            "Höhepunkte:\nRAW_CODE=Exact_Raw_Highlight\nDiagnosen:\nRAW_CODE=Exact_Raw_Diagnosis",
            DiagnosticsSummaryTextRenderer.render(
                document,
                highlightsTitle = "Höhepunkte",
                diagnosesTitle = "Diagnosen",
            ),
        )
    }

    @Test
    fun `default headings preserve existing technical export output`() {
        assertEquals(
            "Highlights:\nRAW_CODE=Exact_Raw_Highlight\nDiagnoses:\nRAW_CODE=Exact_Raw_Diagnosis",
            DiagnosticsSummaryTextRenderer.render(document),
        )
    }
}
