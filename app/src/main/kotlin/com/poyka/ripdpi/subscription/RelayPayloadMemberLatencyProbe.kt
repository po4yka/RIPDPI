package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.fieldreport.FieldReportLedger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayPayloadMemberLatencyProbe
    @Inject
    constructor(
        private val payloadProbe: CandidateRelayPayloadProbe,
        private val fieldReports: FieldReportLedger,
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
            val startedNanos = System.nanoTime()
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
            val elapsedMillis = (System.nanoTime() - startedNanos) / NanosPerMilli
            // An opt-in count must never fail the selector probe that it observes.
            try {
                fieldReports.recordProbe(profile, measured, elapsedMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Exception,
            ) {
                Logger.withTag("field-report").w { "field report count failed: ${ignored.javaClass.simpleName}" }
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

private const val NanosPerMilli = 1_000_000L
