package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayHealthDecisionEngineTest {
    @Test
    fun `recent data plane success prevents target failure from confirming relay failure`() {
        val engine = RelayHealthDecisionEngine()
        val scope = RelayHealthScope(persistentNetworkHash = "network-hash", sessionGeneration = 7)
        val positive =
            RelayHealthObservation(
                attemptId = "attempt-positive",
                profileToken = "fixture-profile-hash",
                relayKind = "vless_reality",
                capabilityProof = RelayCapabilityProof.TcpOnly,
                scope = scope,
                source = RelayHealthObservationSource.DataPlane,
                outcome = RelayHealthObservationOutcome.Succeeded,
                targetCategory = RelayTargetCategory.ApplicationHttp,
                observedAtMs = 1_000,
                dataPlaneWatermark = 42,
            )
        val targetFailure =
            positive.copy(
                attemptId = "attempt-probe",
                source = RelayHealthObservationSource.ActiveProbe,
                outcome = RelayHealthObservationOutcome.TargetFailure,
                observedAtMs = 20_000,
                dataPlaneWatermark = 42,
            )

        engine.observe(positive)

        assertEquals(
            RelayHealthDecision.Healthy(
                attemptId = "attempt-probe",
                decidedAtMs = 20_000,
                positiveEvidenceWatermark = 42,
            ),
            engine.observe(targetFailure),
        )
    }

    @Test
    fun `target failure without relay stage remains inconclusive`() {
        val observation =
            RelayHealthObservation(
                attemptId = "attempt-target",
                profileToken = "fixture-profile-hash",
                relayKind = "vless_reality",
                capabilityProof = RelayCapabilityProof.TcpOnly,
                scope = RelayHealthScope(persistentNetworkHash = "network-hash", sessionGeneration = 8),
                source = RelayHealthObservationSource.ActiveProbe,
                outcome = RelayHealthObservationOutcome.TargetFailure,
                targetCategory = RelayTargetCategory.ApplicationDns,
                observedAtMs = 10_000,
            )

        assertEquals(
            RelayHealthDecision.Inconclusive(
                attemptId = "attempt-target",
                decidedAtMs = 10_000,
                reason = RelayHealthInconclusiveReason.TargetSpecificFailure,
            ),
            RelayHealthDecisionEngine().observe(observation),
        )
    }

    @Test
    fun `second relay stage failure after debounce confirms relay failure`() {
        val engine = RelayHealthDecisionEngine()
        val first =
            RelayHealthObservation(
                attemptId = "attempt-one",
                profileToken = "fixture-profile-hash",
                relayKind = "vless_reality",
                capabilityProof = RelayCapabilityProof.TcpOnly,
                scope = RelayHealthScope(persistentNetworkHash = "network-hash", sessionGeneration = 9),
                source = RelayHealthObservationSource.NativeRelay,
                outcome = RelayHealthObservationOutcome.RelayStageFailure,
                targetCategory = RelayTargetCategory.ApplicationHttp,
                observedAtMs = 1_000,
                failureStage = RelayFailureStage.RealityTls,
            )
        val second = first.copy(attemptId = "attempt-two", observedAtMs = 21_000)

        engine.observe(first)

        assertEquals(
            RelayHealthDecision.ConfirmedFailed(
                attemptId = "attempt-two",
                decidedAtMs = 21_000,
                failureStage = RelayFailureStage.RealityTls,
                permanent = false,
                observationCount = 2,
            ),
            engine.observe(second),
        )
    }

    @Test
    fun `permanent authentication rejection confirms relay failure immediately`() {
        val observation =
            RelayHealthObservation(
                attemptId = "attempt-auth",
                profileToken = "fixture-profile-hash",
                relayKind = "vless_reality",
                capabilityProof = RelayCapabilityProof.TcpOnly,
                scope = RelayHealthScope(persistentNetworkHash = null, sessionGeneration = 10),
                source = RelayHealthObservationSource.NativeRelay,
                outcome = RelayHealthObservationOutcome.PermanentRejection,
                targetCategory = RelayTargetCategory.ApplicationHttp,
                observedAtMs = 5_000,
                failureStage = RelayFailureStage.VlessAuth,
            )

        assertEquals(
            RelayHealthDecision.ConfirmedFailed(
                attemptId = "attempt-auth",
                decidedAtMs = 5_000,
                failureStage = RelayFailureStage.VlessAuth,
                permanent = true,
                observationCount = 1,
            ),
            RelayHealthDecisionEngine().observe(observation),
        )
    }

    @Test
    fun `missing udp target remains inconclusive`() {
        val observation =
            RelayHealthObservation(
                attemptId = "attempt-udp",
                profileToken = "fixture-profile-hash",
                relayKind = "vless_reality",
                capabilityProof = RelayCapabilityProof.TcpAndUdp,
                scope = RelayHealthScope(persistentNetworkHash = "network-hash", sessionGeneration = 11),
                source = RelayHealthObservationSource.InitialProbe,
                outcome = RelayHealthObservationOutcome.CapabilityUnavailable,
                targetCategory = RelayTargetCategory.ApplicationUdp,
                observedAtMs = 7_000,
            )

        assertEquals(
            RelayHealthDecision.Inconclusive(
                attemptId = "attempt-udp",
                decidedAtMs = 7_000,
                reason = RelayHealthInconclusiveReason.MissingCapabilityTarget,
            ),
            RelayHealthDecisionEngine().observe(observation),
        )
    }
}
