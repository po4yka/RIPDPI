package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeRunService
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeWorkflowService
import javax.inject.Inject

class StatusDiagnosticsServices
    @Inject
    constructor(
        val workflowService: DiagnosticsHomeWorkflowService,
        val compositeRunService: DiagnosticsHomeCompositeRunService,
    )
