package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.diagnostics.FieldFailureStage

sealed interface CandidateRelayMeasurement {
    data class Succeeded(
        val latencyMillis: Long,
        val configurationProof: CandidateConfigurationProof,
        val quality: com.poyka.ripdpi.data.ProfileQualityMeasurement? = null,
    ) : CandidateRelayMeasurement {
        init {
            require(latencyMillis >= 0)
        }
    }

    data object Busy : CandidateRelayMeasurement

    data object Unsupported : CandidateRelayMeasurement

    data object EnvironmentChanged : CandidateRelayMeasurement

    /** [failureStage] is the typed field-report stage of the failure, when the probe or native runtime named one. */
    data class TimedOut(
        val stage: CandidateMeasurementStage,
        val failureStage: FieldFailureStage? = null,
    ) : CandidateRelayMeasurement

    data class Failed(
        val stage: CandidateMeasurementStage,
        val failureStage: FieldFailureStage? = null,
    ) : CandidateRelayMeasurement

    data object CleanupPending : CandidateRelayMeasurement
}

enum class CandidateMeasurementStage { Configuration, Ready, Http }
