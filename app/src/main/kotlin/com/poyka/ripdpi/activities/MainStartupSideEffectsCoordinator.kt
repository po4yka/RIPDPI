package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.permissions.PermissionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainStartupSideEffectsCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val crashReportReader: CrashReportReader,
    ) {
        fun start(
            scope: CoroutineScope,
            batteryOptimizationStatus: Flow<PermissionStatus>,
            isBatteryBannerDismissed: () -> Boolean,
            onCrashReportLoaded: (CrashReport) -> Unit,
        ) {
            scope.launch {
                val report = crashReportReader.read()
                if (report != null) {
                    onCrashReportLoaded(report)
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                batteryOptimizationStatus
                    .distinctUntilChanged()
                    .collect { status ->
                        if (status == PermissionStatus.RequiresSettings && isBatteryBannerDismissed()) {
                            appSettingsRepository.update { setBatteryBannerDismissed(false) }
                        }
                    }
            }
        }
    }
