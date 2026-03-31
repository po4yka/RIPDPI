package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import javax.inject.Inject

class HomeDiagnosticsServices
    @Inject
    constructor(
        val workflowService: DiagnosticsHomeWorkflowService,
        val compositeRunService: DiagnosticsHomeCompositeRunService,
    )
