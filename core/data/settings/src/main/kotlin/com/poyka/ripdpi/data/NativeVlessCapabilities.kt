package com.poyka.ripdpi.data

const val RelayVlessFlowVision = "xtls-rprx-vision"
const val RelayVlessFlowVisionUdp443 = "xtls-rprx-vision-udp443"
const val RelayXhttpModeAuto = "auto"
const val RelayXhttpModeStreamUp = "stream-up"
const val RelayXhttpModeStreamOne = "stream-one"
const val RelayXhttpModePacketUp = "packet-up"

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

    /** Native resolves `auto` as Xray does: stream-one with REALITY, packet-up with TLS. */
    fun xhttpModes(security: String): List<String> =
        when (security) {
            RelaySecurityLayerReality, RelaySecurityLayerTls -> {
                listOf(RelayXhttpModeAuto, RelayXhttpModePacketUp, RelayXhttpModeStreamUp, RelayXhttpModeStreamOne)
            }

            else -> {
                emptyList()
            }
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

    /** The first choice the native backend rejects, or `null`; editors put the error on that choice. */
    fun rejectedChoice(
        security: String,
        transport: String,
        flow: String,
        xhttpMode: String = RelayXhttpModeAuto,
        udpEnabled: Boolean = false,
    ): NativeVlessChoice? {
        val selectedFlow = flow.trim().let { if (it in listOf("none", "off")) "" else it }
        val selectedMode = xhttpMode.trim().ifEmpty { RelayXhttpModeAuto }
        return when {
            transports(security).isEmpty() -> NativeVlessChoice.Security

            transport !in transports(security) -> NativeVlessChoice.Transport

            selectedFlow !in flows(security, transport) -> NativeVlessChoice.Flow

            transport == RelayVlessTransportXhttp && selectedMode !in
                xhttpModes(
                    security,
                )
            -> NativeVlessChoice.XhttpMode

            udpEnabled && !supportsUdp(security, transport, selectedFlow) -> NativeVlessChoice.Udp

            else -> null
        }
    }

    /** Return a static reason without copying imported values into diagnostics. */
    fun rejection(
        security: String,
        transport: String,
        flow: String,
        xhttpMode: String = RelayXhttpModeAuto,
        udpEnabled: Boolean = false,
    ): String? = rejectedChoice(security, transport, flow, xhttpMode, udpEnabled)?.reason

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

/** A VLESS choice that [NativeVlessCapabilities] can reject, with its static diagnostic reason. */
enum class NativeVlessChoice(
    val reason: String,
) {
    Security("VLESS security is unsupported"),
    Transport("VLESS transport is unsupported with this security"),
    Flow("VLESS flow is unsupported with this transport"),
    XhttpMode("VLESS XHTTP mode is unsupported with this security"),
    Udp("VLESS UDP requires xHTTP or REALITY TCP with Vision"),
}
