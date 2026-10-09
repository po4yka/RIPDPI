package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransferEvidenceValidationTest {
    @Test
    fun `negative body counters are rejected before presentation`() {
        val evidence =
            TransferEvidence(
                runs =
                    listOf(
                        TransferMeasurement(
                            runIndex = 1,
                            runCount = 1,
                            receivedBodyByteCount = -1,
                            elapsedMs = 100,
                            terminationReason = "read_error",
                        ),
                    ),
            )

        assertNull(parseTransferEvidence(Json.encodeToString(TransferEvidence.serializer(), evidence)))
    }

    @Test
    fun `valid bounded evidence preserves unknown expected length`() {
        assertNotNull(parseTransferEvidence(encode(validMeasurement())))
    }

    @Test
    fun `invalid counts timing samples and completion cannot become evidence`() {
        val valid = validMeasurement()
        val invalid =
            listOf(
                valid.copy(runIndex = 0),
                valid.copy(runCount = 11),
                valid.copy(firstBodyByteMs = null),
                valid.copy(lastBodyProgressMs = 101),
                valid.copy(firstBodyByteMs = 80),
                valid.copy(expectedBodyByteCount = 1),
                valid.copy(samples = List(65) { TransferProgressSample(50, 2) }),
                valid.copy(samples = listOf(TransferProgressSample(80, 2), TransferProgressSample(60, 1))),
                valid.copy(terminationReason = "provider_block"),
                valid.copy(terminationReason = null),
                valid.copy(responseComplete = true),
                valid.copy(terminationReason = "window_limit", windowComplete = false),
            )
        invalid.forEach { assertNull(it.toString(), parseTransferEvidence(encode(it))) }
    }

    @Test
    fun `oversized unknown version and unrecognized fields are rejected`() {
        assertNull(parseTransferEvidence(" ".repeat(65_537)))
        assertNull(parseTransferEvidence(encode(validMeasurement()).replace("\"version\":1", "\"version\":2")))
        assertNull(parseTransferEvidence("{\"body\":\"private\",\"version\":1,\"runs\":[]}"))
    }

    private fun validMeasurement() =
        TransferMeasurement(
            runIndex = 1,
            runCount = 1,
            receivedBodyByteCount = 2,
            elapsedMs = 100,
            firstBodyByteMs = 20,
            lastBodyProgressMs = 50,
            terminationReason = "idle_timeout",
            samples = listOf(TransferProgressSample(0, 0), TransferProgressSample(50, 2)),
        )

    private fun encode(run: TransferMeasurement): String =
        Json.encodeToString(TransferEvidence.serializer(), TransferEvidence(runs = listOf(run)))
}
