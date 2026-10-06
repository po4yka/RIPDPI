package com.poyka.ripdpi.services

sealed interface CandidateRelayMeasurement {
    data class Succeeded(
        val latencyMillis: Long,
        val configurationProof: CandidateConfigurationProof,
    ) : CandidateRelayMeasurement {
        init {
            require(latencyMillis >= 0)
        }
    }

    data object Busy : CandidateRelayMeasurement

    data object Unsupported : CandidateRelayMeasurement

    data object EnvironmentChanged : CandidateRelayMeasurement

    data class TimedOut(
        val stage: CandidateMeasurementStage,
    ) : CandidateRelayMeasurement

    data class Failed(
        val stage: CandidateMeasurementStage,
    ) : CandidateRelayMeasurement

    data object CleanupPending : CandidateRelayMeasurement
}

enum class CandidateMeasurementStage { Configuration, Ready, Http }
