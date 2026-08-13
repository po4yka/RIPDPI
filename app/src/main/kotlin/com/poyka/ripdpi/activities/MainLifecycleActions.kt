package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.crash.CrashReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class MainCrashReportActions(
    private val coordinator: MainCrashReportCoordinator,
    private val scope: CoroutineScope,
    private val pendingCrashReport: MutableStateFlow<CrashReport?>,
) {
    fun buildShareText(report: CrashReport): Pair<String, String> = coordinator.buildShareText(report)

    fun dismiss() {
        coordinator.dismiss(scope) {
            pendingCrashReport.value = null
        }
    }
}

class MainAppLockActions(
    private val coordinator: MainAppLockLifecycleCoordinator,
) {
    val isAuthenticated: Boolean
        get() = coordinator.isAuthenticated

    fun onAuthenticated() {
        coordinator.onAuthenticated()
    }
}
