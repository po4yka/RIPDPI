package com.poyka.ripdpi.services.network

/** Dimensions of network change that require scoped policy re-evaluation. */
enum class ScopedPolicyChange {
    DnsChange,
    RouteChange,
    MeteredChange,
    CaptiveChange,
    SuspendedChange,
    TransportChange,
}
