package com.poyka.ripdpi.ui.screens.status

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot

/** What a stage warning means for the user. */
internal fun StatusConnectionActuatorStage.degradedDetailRes(telemetry: ServiceTelemetrySnapshot): Int =
    when (this) {
        StatusConnectionActuatorStage.Network -> {
            R.string.status_connection_actuator_degraded_network
        }

        StatusConnectionActuatorStage.Dns -> {
            R.string.status_connection_actuator_degraded_dns
        }

        StatusConnectionActuatorStage.Handshake -> {
            R.string.status_connection_actuator_degraded_handshake
        }

        StatusConnectionActuatorStage.Tunnel -> {
            R.string.status_connection_actuator_degraded_tunnel
        }

        // A route change alone says nothing about a relay; only a lost foreign exit does.
        StatusConnectionActuatorStage.Route -> {
            if (isForeignExitLost(telemetry)) {
                R.string.status_connection_actuator_degraded_relay_lost
            } else {
                R.string.status_connection_actuator_degraded_route
            }
        }
    }
