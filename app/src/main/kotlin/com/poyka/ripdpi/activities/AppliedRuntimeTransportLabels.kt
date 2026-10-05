package com.poyka.ripdpi.activities

internal fun String?.confirmedTransportLabel(): String? =
    when (this) {
        "tcp", "reality_tcp" -> "TCP"
        "udp" -> "UDP"
        "quic" -> "QUIC"
        "grpc" -> "gRPC"
        "ws", "websocket" -> "WebSocket"
        "wireguard" -> "WireGuard"
        else -> null
    }
