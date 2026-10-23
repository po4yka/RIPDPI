package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.CandidateXrayPayloadProbe
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayPayloadMemberLatencyProbe
    @Inject
    constructor(
        private val payloadProbe: CandidateRelayPayloadProbe,
        private val xrayProbe: CandidateXrayPayloadProbe,
    ) : MemberLatencyProbe {
        override suspend fun measure(
            profile: ProxyProfile,
            target: MemberProbeTarget,
        ): MemberProbeResult? {
            val measured =
                if (profile is ProxyProfile.VlessReality &&
                    profile.xhttpPath == null && profile.xhttpHost == null
                ) {
                    val xray = profile.asXray()
                    when (target) {
                        is MemberProbeTarget.Reachability -> xrayProbe.measure(xray, target.url)
                        is MemberProbeTarget.Payload -> xrayProbe.measurePayload(xray, target.policy)
                    }
                } else {
                    val mapped =
                        try {
                            mapRelayProfile(profile)
                        } catch (_: IllegalArgumentException) {
                            null
                        } ?: return null
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

private fun ProxyProfile.VlessReality.asXray() =
    XrayProfile(
        name = displayName,
        outbound =
            XrayProfile.Outbound(
                serverAddress = server,
                serverPort = serverPort,
                uuid = uuid,
                security = XrayProfile.Security.REALITY,
                network = XrayProfile.Network.TCP,
                flow = flow,
                reality =
                    XrayProfile.Reality(
                        publicKey = realityPublicKey,
                        serverName = serverName,
                        shortId = realityShortId,
                        fingerprint = fingerprint ?: "chrome",
                    ),
            ),
    )
