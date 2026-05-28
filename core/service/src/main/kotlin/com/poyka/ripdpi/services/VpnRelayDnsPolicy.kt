package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.effectiveRelayDnsOverTunnelEnabled
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.proto.AppSettings

internal fun forceTunnelDnsForRelay(settings: AppSettings): Boolean =
    settings.toRelaySettingsModel().enabled && settings.effectiveRelayDnsOverTunnelEnabled()
