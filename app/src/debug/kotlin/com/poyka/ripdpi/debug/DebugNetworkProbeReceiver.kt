package com.poyka.ripdpi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class DebugNetworkProbeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ActionProbeTcp) {
            return
        }

        val host = intent.getStringExtra(ExtraHost)
        val port = intent.getIntExtra(ExtraPort, -1)
        val connectTimeoutMs = intent.getIntExtra(ExtraConnectTimeoutMs, DefaultConnectTimeoutMs)
        val pendingResult = goAsync()

        thread(name = "debug-network-probe", isDaemon = true) {
            val extras = Bundle()
            val resultCode =
                runCatching {
                    require(!host.isNullOrBlank()) { "Missing host extra" }
                    require(port in 1..65535) { "Invalid port extra: $port" }

                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                        extras.putBoolean(ExtraOk, true)
                        extras.putString(ExtraLocalAddress, socket.localAddress?.hostAddress)
                        extras.putInt(ExtraLocalPort, socket.localPort)
                    }

                    Activity.RESULT_OK
                }.getOrElse { error ->
                    extras.putBoolean(ExtraOk, false)
                    extras.putString(ExtraErrorClass, error::class.java.name)
                    extras.putString(ExtraErrorMessage, error.message)
                    Activity.RESULT_CANCELED
                }

            pendingResult.resultCode = resultCode
            pendingResult.setResultExtras(extras)
            pendingResult.finish()
        }
    }

    companion object {
        const val ActionProbeTcp = "com.poyka.ripdpi.debug.PROBE_TCP"
        const val ExtraHost = "host"
        const val ExtraPort = "port"
        const val ExtraConnectTimeoutMs = "connect_timeout_ms"
        const val ExtraOk = "ok"
        const val ExtraLocalAddress = "local_address"
        const val ExtraLocalPort = "local_port"
        const val ExtraErrorClass = "error_class"
        const val ExtraErrorMessage = "error_message"

        private const val DefaultConnectTimeoutMs = 3_000
    }
}
