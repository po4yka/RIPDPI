package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsProgressModelTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    private fun progress(
        phase: String,
        completedSteps: Int = 2,
        totalSteps: Int = 8,
        message: String = "Probing...",
    ) = ScanProgress(
        sessionId = "test-session",
        phase = phase,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        message = message,
    )

    // --- Phase stepper: connectivity ---

    @Test
    fun `connectivity phase steps - dns phase marks dns active, rest pending`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        assertEquals(4, model.phaseSteps.size)
        assertEquals(PhaseState.Active, model.phaseSteps[0].state) // dns
        assertEquals(PhaseState.Pending, model.phaseSteps[1].state) // reachability
        assertEquals(PhaseState.Pending, model.phaseSteps[2].state) // tcp
        assertEquals(PhaseState.Pending, model.phaseSteps[3].state) // telegram
    }

    @Test
    fun `connectivity phase steps - tcp phase marks dns and reachability completed, tcp active`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        assertEquals(PhaseState.Completed, model.phaseSteps[0].state) // dns
        assertEquals(PhaseState.Completed, model.phaseSteps[1].state) // reachability
        assertEquals(PhaseState.Active, model.phaseSteps[2].state) // tcp
        assertEquals(PhaseState.Pending, model.phaseSteps[3].state) // telegram
    }

    @Test
    fun `connectivity phase steps - finished phase marks all completed`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "finished", completedSteps = 8, totalSteps = 8),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        model.phaseSteps.forEach { step ->
            assertEquals("Expected Completed for ${step.label}", PhaseState.Completed, step.state)
        }
    }

    @Test
    fun `connectivity phase steps - starting phase marks all pending`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "starting", completedSteps = 0),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        model.phaseSteps.forEach { step ->
            assertEquals("Expected Pending for ${step.label}", PhaseState.Pending, step.state)
        }
    }

    @Test
    fun `connectivity phase steps have correct labels`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals("DNS", model.phaseSteps[0].label)
        assertEquals("Reach", model.phaseSteps[1].label)
        assertEquals("TCP", model.phaseSteps[2].label)
        assertEquals("TG", model.phaseSteps[3].label)
    }

    // --- Phase stepper: strategy probe ---

    @Test
    fun `strategy probe phase steps - tcp phase marks tcp active, quic pending`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        assertEquals(2, model.phaseSteps.size)
        assertEquals(PhaseState.Active, model.phaseSteps[0].state) // tcp
        assertEquals(PhaseState.Pending, model.phaseSteps[1].state) // quic
    }

    @Test
    fun `strategy probe phase steps - quic phase marks tcp completed, quic active`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "quic"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        assertEquals(PhaseState.Completed, model.phaseSteps[0].state) // tcp
        assertEquals(PhaseState.Active, model.phaseSteps[1].state) // quic
    }

    @Test
    fun `strategy probe phase steps have correct labels`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals("TCP", model.phaseSteps[0].label)
        assertEquals("QUIC", model.phaseSteps[1].label)
    }

    // --- Tone ---

    @Test
    fun `active phase step has Warning tone`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals(DiagnosticsTone.Warning, model.phaseSteps[0].tone) // active
    }

    @Test
    fun `completed phase step has Positive tone`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals(DiagnosticsTone.Positive, model.phaseSteps[0].tone) // dns = Completed
    }

    @Test
    fun `pending phase step has Neutral tone`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals(DiagnosticsTone.Neutral, model.phaseSteps[3].tone) // telegram = Pending
    }

    // --- Elapsed label ---

    @Test
    fun `elapsed label shows seconds when under one minute`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 42_000L,
            )

        assertEquals("42s", model.elapsedLabel)
    }

    @Test
    fun `elapsed label shows minutes and seconds when over one minute`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 90_000L,
            )

        assertEquals("1m 30s", model.elapsedLabel)
    }

    // --- ETA label ---

    @Test
    fun `eta label is null when fraction is below 10 percent`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", completedSteps = 0, totalSteps = 20),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 5_000L,
            )

        assertNull(model.etaLabel)
    }

    @Test
    fun `eta label is present when fraction is at or above 10 percent`() {
        // 4 of 20 = 20% done, elapsed = 20s => ETA = 80s remaining
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", completedSteps = 4, totalSteps = 20),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 20_000L,
            )

        assertNotNull(model.etaLabel)
        // ~1m 20s remaining (80s)
        assert(model.etaLabel!!.contains("remaining")) { "ETA label should contain 'remaining': ${model.etaLabel}" }
    }

    @Test
    fun `eta label is null when total steps is zero`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", completedSteps = 0, totalSteps = 0),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 10_000L,
            )

        assertNull(model.etaLabel)
    }

    // --- Current probe label ---

    @Test
    fun `current probe label carries through progress message`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", message = "DNS probe youtube.com"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals("DNS probe youtube.com", model.currentProbeLabel)
    }

    @Test
    fun `scan kind is carried through to model`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = true,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals(ScanKind.STRATEGY_PROBE, model.scanKind)
        assertEquals(true, model.isFullAudit)
    }

    // --- Completed probes passthrough ---

    @Test
    fun `completed probes are threaded through to progress model`() {
        val probes =
            listOf(
                CompletedProbeUiModel("youtube.com", "ok", DiagnosticsTone.Positive),
                CompletedProbeUiModel("google.com", "failed", DiagnosticsTone.Negative),
            )
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
                completedProbes = probes,
            )

        assertEquals(2, model.completedProbes.size)
        assertEquals("youtube.com", model.completedProbes[0].target)
        assertEquals(DiagnosticsTone.Positive, model.completedProbes[0].tone)
        assertEquals("google.com", model.completedProbes[1].target)
        assertEquals(DiagnosticsTone.Negative, model.completedProbes[1].tone)
    }

    @Test
    fun `empty completed probes by default`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
                now = 1_000L,
            )

        assertEquals(emptyList<CompletedProbeUiModel>(), model.completedProbes)
    }

    // --- Probe outcome tone ---

    @Test
    fun `probe outcome tone - ok maps to Positive`() {
        assertEquals(DiagnosticsTone.Positive, support.toneForOutcome("ok"))
    }

    @Test
    fun `probe outcome tone - failed maps to Negative`() {
        assertEquals(DiagnosticsTone.Negative, support.toneForOutcome("failed"))
    }

    @Test
    fun `probe outcome tone - skipped maps to Neutral`() {
        assertEquals(DiagnosticsTone.Neutral, support.toneForOutcome("skipped"))
    }

    // --- Scan completed tone ---

    @Test
    fun `scan completed tone is Positive for positive session`() {
        val session = buildSessionRow(tone = DiagnosticsTone.Positive)
        assertEquals(DiagnosticsTone.Positive, scanCompletedTone(session))
    }

    @Test
    fun `scan completed tone is Warning for warning session`() {
        val session = buildSessionRow(tone = DiagnosticsTone.Warning)
        assertEquals(DiagnosticsTone.Warning, scanCompletedTone(session))
    }

    @Test
    fun `scan completed tone is Neutral when session is null`() {
        assertEquals(DiagnosticsTone.Neutral, scanCompletedTone(null))
    }

    private fun buildSessionRow(tone: DiagnosticsTone) =
        DiagnosticsSessionRowUiModel(
            id = "s1",
            profileId = "p1",
            title = "Test",
            subtitle = "",
            pathMode = "RAW_PATH",
            serviceMode = "RIPDPI",
            status = "ok",
            startedAtLabel = "",
            summary = "ok",
            metrics = emptyList(),
            tone = tone,
        )
}
