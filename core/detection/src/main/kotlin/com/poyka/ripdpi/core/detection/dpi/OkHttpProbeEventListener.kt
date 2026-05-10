package com.poyka.ripdpi.core.detection.dpi

import okhttp3.Call
import okhttp3.EventListener
import java.net.InetSocketAddress
import java.net.Proxy

class OkHttpProbeEventListener : EventListener() {
    @Volatile
    var currentStage: ProbeStage = ProbeStage.TCP_CONNECT
        private set

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        currentStage = ProbeStage.TCP_CONNECT
    }

    override fun secureConnectStart(call: Call) {
        currentStage = ProbeStage.TLS_HANDSHAKE
    }

    override fun requestHeadersStart(call: Call) {
        currentStage = ProbeStage.SENDING_DATA
    }

    override fun responseHeadersStart(call: Call) {
        currentStage = ProbeStage.READING_DATA
    }
}
