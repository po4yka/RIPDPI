package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainCrashReportCoordinator
    @Inject
    constructor(
        private val crashReportReader: CrashReportReader,
    ) {
        fun buildShareText(report: CrashReport): Pair<String, String> = crashReportReader.buildShareText(report)

        fun dismiss(
            scope: CoroutineScope,
            onDismissed: () -> Unit,
        ) {
            scope.launch {
                onDismissed()
                crashReportReader.delete()
            }
        }
    }
