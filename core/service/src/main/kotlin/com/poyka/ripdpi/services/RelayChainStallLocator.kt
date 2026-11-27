package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject

fun interface RelayHopVolumeMeasurement {
    suspend fun measureVolume(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): RelayVolumeMeasurement
}

data class RelayVolumeMeasurement(
    val measurement: CandidateRelayMeasurement,
    val receivedBytes: Long?,
)

sealed interface RelayChainVolumeStop {
    data object SingleEndpoint : RelayChainVolumeStop

    data object Unmeasured : RelayChainVolumeStop

    data object Delivered : RelayChainVolumeStop

    data class Stopped(
        val hop: Int,
        val hopCount: Int,
        val receivedBytes: Long,
    ) : RelayChainVolumeStop
}

class RelayChainStallLocator internal constructor(
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialStore,
    private val measurement: RelayHopVolumeMeasurement,
) {
    @Inject
    constructor(
        profiles: RelayProfileStore,
        credentials: RelayCredentialStore,
        probe: CandidateRelayPayloadProbe,
    ) : this(profiles, credentials, probe as RelayHopVolumeMeasurement)

    suspend fun locate(
        profileId: String,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): RelayChainVolumeStop {
        val profile = profiles.load(profileId)
        val hopIds = profile?.takeIf { it.kind == RelayKindChainRelay }?.hopProfileIds()
        return when {
            profile == null || hopIds?.any(String::isBlank) == true -> RelayChainVolumeStop.Unmeasured
            hopIds == null -> RelayChainVolumeStop.SingleEndpoint
            else -> walkPrefixes(profile, hopIds, control, timeoutMillis)
        }
    }

    private suspend fun walkPrefixes(
        chain: RelayProfileRecord,
        hopIds: List<String>,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): RelayChainVolumeStop {
        var stop: RelayChainVolumeStop? = null
        var hop = 0
        while (stop == null && hop < hopIds.size) {
            hop++
            stop = measurePrefix(chain, hopIds, hop, control, timeoutMillis)
        }
        return stop ?: RelayChainVolumeStop.Delivered
    }

    private suspend fun measurePrefix(
        chain: RelayProfileRecord,
        hopIds: List<String>,
        hop: Int,
        control: PayloadProbeControl,
        timeoutMillis: Long,
    ): RelayChainVolumeStop? {
        val prefix =
            if (hop == 1) {
                profiles.load(hopIds.first())
            } else {
                chain.copy(chainMiddleProfileIds = hopIds.subList(1, hop - 1), chainExitProfileId = hopIds[hop - 1])
            }
        return if (prefix == null) {
            RelayChainVolumeStop.Unmeasured
        } else {
            val result =
                measurement.measureVolume(
                    prefix,
                    credentials.load(prefix.id) ?: RelayCredentialRecord(prefix.id),
                    control,
                    timeoutMillis,
                )
            verdict(hop, hopIds.size, result, control)
        }
    }

    private fun verdict(
        hop: Int,
        hopCount: Int,
        result: RelayVolumeMeasurement,
        control: PayloadProbeControl,
    ): RelayChainVolumeStop? {
        val outcome = result.measurement
        val stage =
            (outcome as? CandidateRelayMeasurement.TimedOut)?.stage
                ?: (outcome as? CandidateRelayMeasurement.Failed)?.stage
        val received = if (stage == CandidateMeasurementStage.Ready) 0L else result.receivedBytes
        return when {
            outcome is CandidateRelayMeasurement.Succeeded -> {
                null
            }

            stage == null || stage == CandidateMeasurementStage.Configuration ||
                received == null || received >= control.expectedBytes -> {
                RelayChainVolumeStop.Unmeasured
            }

            else -> {
                RelayChainVolumeStop.Stopped(hop, hopCount, received)
            }
        }
    }
}

private fun RelayProfileRecord.hopProfileIds(): List<String> =
    listOf(chainEntryProfileId) + chainMiddleProfileIds + chainExitProfileId
