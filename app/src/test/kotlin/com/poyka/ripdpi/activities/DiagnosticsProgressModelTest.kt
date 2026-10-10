package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeLiveProgress
import com.poyka.ripdpi.diagnostics.StrategyProbeProgressLane
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
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
        strategyProbeProgress: StrategyProbeLiveProgress? = null,
    ) = ScanProgress(
        sessionId = "test-session",
        phase = phase,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        message = message,
        strategyProbeProgress = strategyProbeProgress,
    )

    @Test
    fun `current phase does not invent completed or planned phases`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(1, model.phaseSteps.size)
        assertEquals("TCP", model.phaseSteps.single().label)
        assertEquals(PhaseState.Active, model.phaseSteps.single().state)
        assertEquals(DiagnosticsTone.Warning, model.phaseSteps.single().tone)
    }

    @Test
    fun `strategy progress shows only the reported lane`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "quic"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(1, model.phaseSteps.size)
        assertEquals("QUIC", model.phaseSteps.single().label)
        assertEquals(PhaseState.Active, model.phaseSteps.single().state)
    }

    @Test
    fun `IP family progress does not inherit unrelated connectivity phases`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "ip_family"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(1, model.phaseSteps.size)
        assertEquals(PhaseState.Active, model.phaseSteps.single().state)
    }

    @Test
    fun `finished phase does not claim every probe succeeded`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "finished", completedSteps = 8, totalSteps = 8),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(emptyList<PhaseStepUiModel>(), model.phaseSteps)
    }

    @Test
    fun `finished cancellation does not mark the last active phase complete`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp").copy(isFinished = true, message = "Cancelled"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(emptyList<PhaseStepUiModel>(), model.phaseSteps)
    }

    // --- Scan start time ---

    @Test
    fun `model carries the raw scan start so the card can drive its own clock`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns"),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 1_234L,
            )

        assertEquals(1_234L, model.scanStartedAtMs)
    }

    // --- Fraction clamping ---

    @Test
    fun `fraction is clamped when the engine reports more completed steps than planned`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", completedSteps = 25, totalSteps = 20),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(1f, model.fraction)
    }

    @Test
    fun `fraction is zero when the plan reports no steps`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "dns", completedSteps = 3, totalSteps = 0),
                scanKind = ScanKind.CONNECTIVITY,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals(0f, model.fraction)
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
            )

        assertEquals("DNS probe youtube.com", model.currentProbeLabel)
    }

    @Test
    fun `strategy probe progress prefers structured candidate label and counters`() {
        val model =
            support.toProgressUiModel(
                progress =
                    progress(
                        phase = "tcp",
                        message = "Testing TCP candidate",
                        strategyProbeProgress =
                            StrategyProbeLiveProgress(
                                lane = StrategyProbeProgressLane.TCP,
                                candidateIndex = 3,
                                candidateTotal = 14,
                                candidateId = "tcp_fake_tls",
                                candidateLabel = "TCP fake TLS",
                            ),
                    ),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals("TCP fake TLS", model.currentProbeLabel)
        assertEquals(DiagnosticsStrategyProbeProgressLaneUiModel.TCP, model.strategyProbeProgress?.lane)
        assertEquals(3, model.strategyProbeProgress?.candidateIndex)
        assertEquals(14, model.strategyProbeProgress?.candidateTotal)
        assertEquals("tcp_fake_tls", model.strategyProbeProgress?.candidateId)
    }

    @Test
    fun `strategy probe progress falls back to message when structured metadata is absent`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp", message = "Testing TCP candidate"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = false,
                scanStartedAt = 0L,
            )

        assertEquals("Testing TCP candidate", model.currentProbeLabel)
        assertNull(model.strategyProbeProgress)
    }

    @Test
    fun `scan kind is carried through to model`() {
        val model =
            support.toProgressUiModel(
                progress = progress(phase = "tcp"),
                scanKind = ScanKind.STRATEGY_PROBE,
                isFullAudit = true,
                scanStartedAt = 0L,
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
            )

        assertEquals(emptyList<CompletedProbeUiModel>(), model.completedProbes)
    }

    // --- Probe outcome tone ---

    @Test
    fun `probe outcome tone - dns match maps to Positive`() {
        assertEquals(
            DiagnosticsTone.Positive,
            support.core.toneForProbeOutcome("dns_integrity", ScanPathMode.RAW_PATH, "dns_match"),
        )
    }

    @Test
    fun `probe outcome tone - udp blocked maps to Warning`() {
        assertEquals(
            DiagnosticsTone.Warning,
            support.core.toneForProbeOutcome("dns_integrity", ScanPathMode.RAW_PATH, "udp_blocked"),
        )
    }

    @Test
    fun `probe outcome tone - whitelist sni failed maps to Negative`() {
        assertEquals(
            DiagnosticsTone.Negative,
            support.core.toneForProbeOutcome("tcp_fat_header", ScanPathMode.RAW_PATH, "whitelist_sni_failed"),
        )
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
            metrics = persistentListOf(),
            tone = tone,
        )
}
