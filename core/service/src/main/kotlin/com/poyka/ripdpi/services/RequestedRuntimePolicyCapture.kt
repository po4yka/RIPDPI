package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource

internal class RequestedRuntimePolicy(
    val preferences: RipDpiProxyPreferences,
    val destinationDigest: String,
)

internal class RequestedRuntimePolicyCapture(
    private val routing: DestinationRoutingPolicySource,
    private val secrets: ProxySessionSecretResolver,
) {
    suspend fun capture(
        settings: AppSettings,
        awg: AwgActivationRequest?,
    ): RequestedRuntimePolicy {
        val destination =
            when (val snapshot = routing.snapshot()) {
                is DestinationRoutingPolicySnapshot.Available -> {
                    snapshot.policy
                }

                is DestinationRoutingPolicySnapshot.Unavailable -> {
                    error(
                        "Destination routing policy is unavailable: ${snapshot.reason}",
                    )
                }
            }
        val preferences: RipDpiProxyPreferences =
            if (settings.enableCmdSettings && awg == null) {
                RipDpiProxyCmdPreferences(
                    settings.cmdArgs,
                    hostAutolearnStorePath = null,
                    runtimeContext = null,
                    destinationRouting = destination,
                )
            } else {
                RipDpiProxyUIPreferences.fromSettings(
                    settings,
                    destinationRouting = destination,
                    rootMode = settings.rootModeEnabled,
                    awg = awg,
                    workerBearer = secrets.currentBearer(settings),
                )
            }
        return RequestedRuntimePolicy(preferences, destination.canonicalDigest)
    }
}
