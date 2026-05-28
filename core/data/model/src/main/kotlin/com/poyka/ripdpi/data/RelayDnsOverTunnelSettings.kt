package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

fun AppSettings.effectiveRelayDnsOverTunnelEnabled(): Boolean =
    !hasRelayDnsOverTunnelEnabled() || relayDnsOverTunnelEnabled
