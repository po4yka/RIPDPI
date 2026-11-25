package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun assertSecretNeverPersistedOrExported(
    prepared: PreparedDiagnosticsScan,
    secret: String,
    json: Json,
) {
    assertTrue(prepared.requestJson.contains(secret))
    for (persisted in listOf(prepared.initialSession, prepared.preScanSnapshot, prepared.preScanContext)) {
        assertFalse(persisted.toString().contains(secret))
    }
    val detail =
        json.encodeToString(
            ListSerializer(ProbeDetail.serializer()),
            listOf(ProbeDetail("scanRequest", prepared.requestJson)),
        )
    val exported =
        DiagnosticsArchiveRedactor(json).redact(
            ProbeResultEntity("result", "session", "strategy_probe", "relay", "ok", detail, 1),
        )
    assertFalse(exported.detailJson.contains(secret))
}
