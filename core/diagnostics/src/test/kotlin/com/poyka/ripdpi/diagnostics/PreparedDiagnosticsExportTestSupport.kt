package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveZipWriter
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedExportManager
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedFileStore
import com.poyka.ripdpi.diagnostics.export.PreparedExportClock
import com.poyka.ripdpi.diagnostics.export.PreparedExportTime
import kotlinx.serialization.json.Json

internal fun preparedExportManagerForTest(
    context: Context,
    records: DiagnosticsExportRecordStore,
    json: Json,
    clock: PreparedExportClock = PreparedExportClock { PreparedExportTime(1_700_000_000_000L, 1000L, 1) },
    logcat: LogcatSnapshotCollector = FakeLogcatSnapshotCollector(snapshot = null),
): DiagnosticsPreparedExportManager =
    DiagnosticsPreparedExportManager(
        files = DiagnosticsPreparedFileStore(context.filesDir, json),
        records = records,
        clock = clock,
        zipWriter = DiagnosticsArchiveZipWriter(),
        logcatCollector = logcat,
        logRedactor = DiagnosticsLogRedactor(),
    )
