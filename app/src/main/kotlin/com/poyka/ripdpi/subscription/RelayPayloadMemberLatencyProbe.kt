package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
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
            probeUrl: String,
        ): Long? {
            val mapped =
                try {
                    mapRelayProfile(profile)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            return (
                payloadProbe.measure(mapped.profile, mapped.credentials, probeUrl) as?
                    com.poyka.ripdpi.services.CandidateRelayMeasurement.Succeeded
            )?.latencyMillis
        }
    }
