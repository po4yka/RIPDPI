package com.poyka.ripdpi.data.xray

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int

/** Projects the supported client shape; supplied fields must survive rendering unchanged. */
internal object XrayJsonProfileReader {
    fun read(
        config: JsonObject,
        name: String,
    ): XrayProfile? =
        runCatching {
            val outbound = config.array("outbounds").first() as JsonObject
            val server = outbound.obj("settings").array("vnext").single() as JsonObject
            val user = server.array("users").single() as JsonObject
            val stream = outbound.obj("streamSettings")
            val security = XrayProfile.Security.valueOf(stream.text("security").uppercase())
            val network = XrayProfile.Network.valueOf(stream.text("network", "tcp").uppercase())
            XrayProfile(
                name = name,
                outbound =
                    XrayProfile.Outbound(
                        serverAddress = server.text("address"),
                        serverPort = server.number("port"),
                        uuid = user.text("id"),
                        flow = user.text("flow", if (network == XrayProfile.Network.XHTTP) "" else "xtls-rprx-vision"),
                        security = security,
                        network = network,
                        reality =
                            if (security ==
                                XrayProfile.Security.REALITY
                            ) {
                                reality(stream.obj("realitySettings"))
                            } else {
                                null
                            },
                        tls = if (security == XrayProfile.Security.TLS) tls(stream.obj("tlsSettings")) else null,
                        xhttp = if (network == XrayProfile.Network.XHTTP) xhttp(stream.obj("xhttpSettings")) else null,
                    ),
                inbound =
                    config["inbounds"]?.let { inbound((it as JsonArray).single() as JsonObject) }
                        ?: XrayProfile.LocalInbound(),
                dns = config["dns"]?.let { dns(it as JsonObject) } ?: XrayProfile.DnsSettings(),
            )
        }.getOrNull()

    /**
     * Omitted fields use typed defaults. Extra fields, unsupported routing, extra
     * peers/users and differing fixed values fail instead of silently disappearing.
     * A single VLESS outbound may omit the renderer's unused direct outbound.
     */
    fun preservesInput(
        input: JsonObject,
        rendered: JsonObject,
    ): Boolean {
        val outbounds = input["outbounds"] as? JsonArray
        val expectedOutbounds = rendered["outbounds"] as? JsonArray
        if (outbounds == null || expectedOutbounds == null) return false
        val expected = JsonObject(rendered + ("outbounds" to JsonArray(expectedOutbounds.take(outbounds.size))))
        return outbounds.isNotEmpty() && matches(input, expected)
    }

    private fun matches(
        input: JsonElement,
        rendered: JsonElement?,
    ): Boolean =
        when {
            input is JsonObject && rendered is JsonObject -> {
                input.all { (key, value) -> matches(value, rendered[key]) }
            }

            input is JsonArray && rendered is JsonArray -> {
                input.size == rendered.size && input.indices.all { matches(input[it], rendered[it]) }
            }

            else -> {
                input == rendered
            }
        }

    private fun reality(value: JsonObject) =
        XrayProfile.Reality(
            publicKey = value.text("publicKey"),
            serverName = value.text("serverName"),
            shortId = value.text("shortId", ""),
            fingerprint = value.text("fingerprint", "chrome"),
        )

    private fun tls(value: JsonObject) =
        XrayProfile.Tls(
            serverName = value.text("serverName"),
            fingerprint = value.text("fingerprint", "chrome"),
            allowInsecure = value.flag("allowInsecure", false),
        )

    private fun xhttp(value: JsonObject) =
        XrayProfile.Xhttp(
            path = value.text("path", "/"),
            mode = value.text("mode", "auto"),
            host = value.text("host", ""),
        )

    private fun inbound(value: JsonObject) =
        XrayProfile.LocalInbound(
            listen = value.text("listen", "127.0.0.1"),
            port = value.number("port"),
            udpEnabled = value.obj("settings").flag("udp", true),
        )

    private fun dns(value: JsonObject) =
        XrayProfile.DnsSettings(
            servers = value.array("servers").map { (it as JsonPrimitive).also { p -> require(p.isString) }.content },
            queryStrategy = value.text("queryStrategy", "UseIP"),
        )
}

private fun JsonObject.obj(key: String) = getValue(key) as JsonObject

private fun JsonObject.array(key: String) = getValue(key) as JsonArray

private fun JsonObject.number(key: String) = (getValue(key) as JsonPrimitive).int

private fun JsonObject.flag(
    key: String,
    default: Boolean,
) = (get(key) as? JsonPrimitive)?.boolean ?: default

private fun JsonObject.text(
    key: String,
    default: String? = null,
): String =
    get(key)?.let { (it as JsonPrimitive).also { value -> require(value.isString) }.content }
        ?: requireNotNull(default)
