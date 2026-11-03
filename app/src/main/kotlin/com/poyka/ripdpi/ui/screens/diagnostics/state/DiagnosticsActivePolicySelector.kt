package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticActiveConnectionPolicy

internal fun selectActiveConnectionPolicy(
    serviceMode: Mode,
    activePolicies: Map<Mode, DiagnosticActiveConnectionPolicy>,
): DiagnosticActiveConnectionPolicy? =
    activePolicies[serviceMode]
        ?: activePolicies.values.maxByOrNull(DiagnosticActiveConnectionPolicy::appliedAt)
