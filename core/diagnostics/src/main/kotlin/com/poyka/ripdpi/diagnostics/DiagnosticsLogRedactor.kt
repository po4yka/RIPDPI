package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsLogcat
import javax.inject.Inject

class DiagnosticsLogRedactor
    @Inject
    constructor() {
        fun redactLogcat(content: String): String = redactDiagnosticsLogcat(content)
    }
