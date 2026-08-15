package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState

internal fun BypassApproachVerificationState.uiLabel(): String =
    when (this) {
        BypassApproachVerificationState.NOT_EVALUATED -> "Not evaluated"
        BypassApproachVerificationState.INCOMPLETE_EVIDENCE -> "Incomplete evidence"
        BypassApproachVerificationState.EVALUATED_NO_SUCCESS -> "No successful evaluation"
        BypassApproachVerificationState.EVALUATED_PARTIAL_SUCCESS -> "Partially successful"
        BypassApproachVerificationState.CONFIRMED_WORKING -> "Confirmed working"
    }
