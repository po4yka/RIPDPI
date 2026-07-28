package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceGate
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

internal class DiagnosticsRemoteDeviceAcceptance
    @Inject
    constructor(
        private val gate: RemoteDeviceAcceptanceGate,
        private val stringResolver: StringResolver,
    ) {
        val report = gate.report

        fun start(scope: CoroutineScope) = gate.start(scope)

        fun share(emit: (DiagnosticsEffect) -> Boolean) {
            emit(shareEffect())
        }

        private fun shareEffect(): DiagnosticsEffect =
            DiagnosticsEffect.ShareSummaryRequested(
                title = stringResolver.getString(R.string.diagnostics_device_gate_title),
                body = gate.renderRedactedReport(),
            )
    }
