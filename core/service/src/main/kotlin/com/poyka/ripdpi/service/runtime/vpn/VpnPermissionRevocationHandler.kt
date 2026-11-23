package com.poyka.ripdpi.service.runtime.vpn

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.services.PermissionChangeEvent

internal class VpnPermissionRevocationHandler(
    private val isLocalNetworkDependent: () -> Boolean,
    private val failAndStop: (runtimeId: String, FailureReason) -> Unit,
) {
    fun handle(
        runtimeId: String,
        event: PermissionChangeEvent,
    ) {
        when (event.kind) {
            PermissionChangeEvent.KIND_VPN_CONSENT -> {
                Logger.e(tag = LogTags.SERVICE) { "VPN consent revoked while running" }
                failAndStop(runtimeId, FailureReason.PermissionLost("VPN"))
            }

            PermissionChangeEvent.KIND_NOTIFICATIONS -> {
                Logger.i(tag = LogTags.SERVICE) { "Notification permission revoked while VPN running" }
            }

            PermissionChangeEvent.KIND_LOCAL_NETWORK -> {
                if (isLocalNetworkDependent()) {
                    Logger.e(
                        tag = LogTags.SERVICE,
                    ) { "Local network permission revoked while LAN-dependent VPN runtime is active" }
                    failAndStop(runtimeId, FailureReason.PermissionLost(LocalNetworkPermission))
                }
            }
        }
    }
}
