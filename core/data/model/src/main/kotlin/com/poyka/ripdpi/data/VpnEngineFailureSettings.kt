package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.VpnEngineFailureAction

// Unset and unknown values block: only an explicit STOP lets traffic leave on the real network.
fun AppSettings.blocksTrafficOnEngineFailure(): Boolean =
    vpnEngineFailureAction != VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_STOP

fun AppSettings.Builder.setBlocksTrafficOnEngineFailure(block: Boolean): AppSettings.Builder =
    setVpnEngineFailureAction(
        if (block) {
            VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_BLOCK
        } else {
            VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_STOP
        },
    )
