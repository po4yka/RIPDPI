package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class DiagnosticsViewModelBootstrapper
    @Inject
    constructor(
        private val diagnosticsBootstrapper: DiagnosticsBootstrapper,
    ) {
        fun initialize(
            scope: CoroutineScope,
            initializeScanActions: () -> Unit,
        ) {
            scope.launch {
                diagnosticsBootstrapper.initialize()
            }
            initializeScanActions()
        }
    }
