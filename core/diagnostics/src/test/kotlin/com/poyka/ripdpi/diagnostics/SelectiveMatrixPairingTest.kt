package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict.AVAILABLE_ON_BOTH
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict.CONTROL
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict.INCONCLUSIVE_FAILED_ON_BOTH
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict.INCONCLUSIVE_LEG_INVALID
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict.PATH_SPECIFIC
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectiveMatrixPairingTest {
    @Test
    fun `revision mismatch makes the comparison unavailable and names the target set`() {
        val cellular = leg("cellular", startedAt = MINUTE * 5, revisions = Revisions.copy(targetSet = "other"))

        val result = compareSelectiveMatrixSessions(leg("wifi", startedAt = 0), cellular)

        assertEquals(
            SelectiveMatrixComparison.Unavailable(listOf(SelectiveMatrixPairMismatch.TARGET_SET)),
            result,
        )
    }

    @Test
    fun `second leg 45 minutes later is beyond the age limit`() {
        val result = compareSelectiveMatrixSessions(leg("wifi", startedAt = 0), leg("cellular", MINUTE * 45))

        assertEquals(SelectiveMatrixComparison.Unavailable(listOf(SelectiveMatrixPairMismatch.AGE_LIMIT)), result)
    }

    @Test
    fun `sessions saved before revisions on the same transport list every mismatch`() {
        val result =
            compareSelectiveMatrixSessions(
                leg("wifi", startedAt = 0, revisions = null),
                leg("wifi", startedAt = MINUTE),
            )

        assertEquals(
            SelectiveMatrixComparison.Unavailable(
                listOf(SelectiveMatrixPairMismatch.REVISIONS_MISSING, SelectiveMatrixPairMismatch.SAME_TRANSPORT),
            ),
            result,
        )
    }

    @Test
    fun `target that fails on both legs is inconclusive and outside the denominator`() {
        val result =
            compared(
                leg("wifi", 0, target = failed("tls")),
                leg("cellular", MINUTE, target = failed("tls")),
            )

        assertEquals(INCONCLUSIVE_FAILED_ON_BOTH, result.row("site").verdict)
        assertEquals(0, result.pathSpecificCount)
        assertEquals(0, result.comparableCount)
    }

    @Test
    fun `network change during the second leg invalidates it and shows no path-specific rows`() {
        val result =
            compared(
                leg("wifi", 0, target = available()),
                leg("cellular", MINUTE, target = failed("tcp"), networkChanged = true),
            )

        assertTrue(result.first.valid)
        assertFalse(result.second.valid)
        assertEquals(INCONCLUSIVE_LEG_INVALID, result.row("site").verdict)
        assertEquals(0, result.pathSpecificCount)
    }

    @Test
    fun `payload on wifi and failure on cellular with valid controls is path-specific`() {
        // Given in reverse order: the comparison orders legs by start time.
        val result =
            compared(
                leg("cellular", startedAt = MINUTE * 20, target = failed("tls")),
                leg("wifi", startedAt = MINUTE * 2, target = available()),
            )

        val row = result.row("site")
        assertEquals(PATH_SPECIFIC, row.verdict)
        assertEquals(SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.AVAILABLE, null, 2, 2), row.first)
        assertEquals(SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.FAILED, "tls", 2, 0), row.second)
        assertEquals(SelectiveMatrixLegSummary("wifi", MINUTE * 2, MINUTE * 3, true, true), result.first)
        assertEquals(SelectiveMatrixLegSummary("cellular", MINUTE * 20, MINUTE * 21, true, true), result.second)
        assertEquals(CONTROL, result.row("control").verdict)
        assertEquals(1, result.pathSpecificCount)
        assertEquals(1, result.comparableCount)
    }

    @Test
    fun `failed control on one leg keeps every row out of the denominator`() {
        val result =
            compared(
                leg("wifi", 0, target = available()),
                leg("cellular", MINUTE, target = failed("tls"), control = failed("tcp")),
            )

        assertFalse(result.second.controlsValid)
        assertEquals(SelectiveMatrixPairVerdict.INCONCLUSIVE_CONTROL_FAILED, result.row("site").verdict)
        assertEquals(0, result.comparableCount)
    }

    @Test
    fun `incomplete repetitions are unmeasured and success on both legs counts`() {
        val result =
            compared(
                leg("wifi", 0, target = listOf("matrix_target_available" to null)),
                leg("cellular", MINUTE, target = failed("tls")),
            )
        assertEquals(SelectiveMatrixPairVerdict.INCONCLUSIVE_UNMEASURED, result.row("site").verdict)

        val both = compared(leg("wifi", 0), leg("cellular", MINUTE))
        assertEquals(AVAILABLE_ON_BOTH, both.row("site").verdict)
        assertEquals(1, both.comparableCount)
    }

    @Test
    fun `revisions follow target order and probe parameters separately`() {
        val settings = AppSettings.getDefaultInstance()
        val base = selectiveMatrixRevisions(Matrix, ScanPathMode.RAW_PATH, settings)

        val reordered =
            selectiveMatrixRevisions(Matrix.copy(targets = Matrix.targets.reversed()), ScanPathMode.RAW_PATH, settings)
        assertNotEquals(base.targetSet, reordered.targetSet)
        assertEquals(base.probeParameters, reordered.probeParameters)

        val moreRepetitions = selectiveMatrixRevisions(Matrix.copy(repetitions = 3), ScanPathMode.RAW_PATH, settings)
        assertEquals(base.targetSet, moreRepetitions.targetSet)
        assertNotEquals(base.probeParameters, moreRepetitions.probeParameters)
        assertEquals(base.appConfiguration, moreRepetitions.appConfiguration)
        assertEquals(base, selectiveMatrixRevisions(Matrix, ScanPathMode.RAW_PATH, settings))
    }

    private fun compared(
        first: SelectiveMatrixLeg,
        second: SelectiveMatrixLeg,
    ) = compareSelectiveMatrixSessions(first, second) as SelectiveMatrixComparison.Compared

    private fun SelectiveMatrixComparison.Compared.row(id: String) = rows.single { it.targetId == id }

    private fun available() = List(2) { "matrix_target_available" to null }

    private fun failed(stage: String) = List(2) { "matrix_target_transport_failed" to stage }

    private fun leg(
        transport: String,
        startedAt: Long,
        revisions: SelectiveMatrixRevisions? = Revisions,
        target: List<Pair<String, String?>> = available(),
        control: List<Pair<String, String?>> = available(),
        networkChanged: Boolean = false,
    ): SelectiveMatrixLeg {
        val summaryDetails =
            listOfNotNull(
                ProbeDetail("repetitionsPerTarget", "2"),
                ProbeDetail("reason", "network_scope_unverified").takeIf { networkChanged },
            )
        val results =
            attempts("control", "declared_available", control) + attempts("site", "global", target) +
                ProbeResult("selective_availability_summary", "matrix", "matrix_mixed", summaryDetails)
        return SelectiveMatrixLeg(transport, startedAt, startedAt + MINUTE, revisions, results)
    }

    private fun attempts(
        id: String,
        cohort: String,
        outcomes: List<Pair<String, String?>>,
    ) = outcomes.map { (outcome, failureStage) ->
        ProbeResult(
            "selective_availability",
            "label-$id",
            outcome,
            listOfNotNull(
                ProbeDetail("targetId", id),
                ProbeDetail("cohort", cohort),
                failureStage?.let { ProbeDetail("failureStage", it) },
            ),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        val Revisions = SelectiveMatrixRevisions("targets", "probe", "config")
        val Matrix =
            SelectiveMatrixConfig(
                catalogVersion = "test-v1",
                targets =
                    listOf("a", "b").map { id ->
                        SelectiveMatrixTarget(id, id, "https://$id.example/", "global", id, "", "")
                    },
            )
    }
}
