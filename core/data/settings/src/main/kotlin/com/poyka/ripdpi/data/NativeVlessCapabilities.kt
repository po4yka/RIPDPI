package com.poyka.ripdpi.data

const val RelayVlessFlowVision = "xtls-rprx-vision"
const val RelayVlessFlowVisionUdp443 = "xtls-rprx-vision-udp443"
const val RelayXhttpModeAuto = "auto"
const val RelayXhttpModeStreamUp = "stream-up"
const val RelayXhttpModeStreamOne = "stream-one"

/** Native backend policy shared by import, persistence, editing, and activation. */
object NativeVlessCapabilities {
    fun defaultFlow(
        security: String,
        transport: String,
    ): String =
        if (security == RelaySecurityLayerReality &&
            transport == RelayVlessTransportRealityTcp
        ) {
            RelayVlessFlowVision
        } else {
            ""
        }

    fun transports(security: String): List<String> =
        when (security) {
            RelaySecurityLayerReality -> listOf(RelayVlessTransportRealityTcp, RelayVlessTransportXhttp)
            RelaySecurityLayerTls -> listOf(RelayVlessTransportXhttp)
            else -> emptyList()
        }

    fun flows(
        security: String,
        transport: String,
    ): List<String> =
        when {
            transport !in transports(security) -> emptyList()
            transport == RelayVlessTransportXhttp -> listOf("")
            else -> listOf("", RelayVlessFlowVision, RelayVlessFlowVisionUdp443)
        }

    fun xhttpModes(security: String): List<String> =
        when (security) {
            RelaySecurityLayerReality -> listOf(RelayXhttpModeAuto, RelayXhttpModeStreamUp, RelayXhttpModeStreamOne)
            RelaySecurityLayerTls -> listOf(RelayXhttpModeStreamUp, RelayXhttpModeStreamOne)
            else -> emptyList()
        }

    /** Native XUDP: REALITY TCP with Vision, or any xHTTP stream with the empty flow (as in Xray). */
    fun supportsUdp(
        security: String,
        transport: String,
        flow: String,
    ): Boolean =
        transport in transports(security) &&
            when (transport) {
                RelayVlessTransportRealityTcp -> flow.trim() in listOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443)
                RelayVlessTransportXhttp -> flow.trim() in listOf("", "none", "off")
                else -> false
            }

    /** Return a static reason without copying imported values into diagnostics. */
    fun rejection(
        security: String,
        transport: String,
        flow: String,
        xhttpMode: String = RelayXhttpModeAuto,
        udpEnabled: Boolean = false,
    ): String? {
        val selectedFlow = flow.trim().let { if (it in listOf("none", "off")) "" else it }
        val selectedMode = xhttpMode.trim().ifEmpty { RelayXhttpModeAuto }
        return when {
            transports(security).isEmpty() -> {
                "VLESS security is unsupported"
            }

            transport !in transports(security) -> {
                "VLESS transport is unsupported with this security"
            }

            selectedFlow !in flows(security, transport) -> {
                "VLESS flow is unsupported with this transport"
            }

            transport == RelayVlessTransportXhttp && selectedMode !in xhttpModes(security) -> {
                "VLESS XHTTP mode is unsupported with this security"
            }

            udpEnabled && !supportsUdp(security, transport, selectedFlow) -> {
                "VLESS UDP requires xHTTP or REALITY TCP with Vision"
            }

            else -> {
                null
            }
        }
    }

    fun validate(
        security: String,
        transport: String,
        flow: String,
        xhttpMode: String = RelayXhttpModeAuto,
        udpEnabled: Boolean = false,
    ) {
        val reason = rejection(security, transport, flow, xhttpMode, udpEnabled)
        require(reason == null) { reason.orEmpty() }
    }
}
