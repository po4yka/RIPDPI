package com.poyka.ripdpi.e2e

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keep run-owned fixture receipts after the shared fixture teardown clears its events. */
internal fun writeLocalAcceptanceReceipts(
    context: Context,
    events: List<FixtureEventDto>,
) {
    val runId = InstrumentationRegistry.getArguments().getString("ripdpi.acceptanceRunId") ?: return
    require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")))
    val receipts = JSONArray()
    events.forEach { event ->
        receipts.put(
            JSONObject()
                .put("service", event.service)
                .put("protocol", event.protocol)
                .put("detail", event.detail)
                .put("bytes", event.bytes)
                .put("created_at", event.createdAt),
        )
    }
    File(context.filesDir, "local-acceptance-$runId.json").writeText(
        JSONObject().put("run_id", runId).put("receipts", receipts).toString(),
    )
}
