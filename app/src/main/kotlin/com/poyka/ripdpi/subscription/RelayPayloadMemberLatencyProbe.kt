package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayPayloadMemberLatencyProbe
    @Inject
    constructor(
        private val payloadProbe: CandidateRelayPayloadProbe,
    ) : MemberLatencyProbe {
        override suspend fun measure(
            profile: ProxyProfile,
            target: MemberProbeTarget,
        ): MemberProbeResult? {
            val mapped =
                try {
                    mapRelayProfile(profile)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            val measured =
                when (target) {
                    is MemberProbeTarget.Reachability -> {
                        payloadProbe.measure(
                            mapped.profile,
                            mapped.credentials,
                            target.url,
                        )
                    }

                    is MemberProbeTarget.Payload -> {
                        payloadProbe.measurePayload(
                            mapped.profile,
                            mapped.credentials,
                            target.policy,
                        )
                    }
                }
            return when (measured) {
                is CandidateRelayMeasurement.Succeeded -> {
                    MemberProbeResult(measured.latencyMillis, measured.quality)
                }

                CandidateRelayMeasurement.CleanupPending, CandidateRelayMeasurement.Busy -> {
                    MemberProbeResult(null, reason = ProfileQualityReason.CleanupPending)
                }

                CandidateRelayMeasurement.EnvironmentChanged -> {
                    MemberProbeResult(null, reason = ProfileQualityReason.ScopeChanged)
                }

                else -> {
                    null
                }
            }
        }
    }
