package com.poyka.ripdpi.diagnostics.export

internal fun primaryReportEvidenceRef(
    pointer: String = "",
    referencePrefix: String = "",
): String = "${referencePrefix}report.json#/primaryReport$pointer"
