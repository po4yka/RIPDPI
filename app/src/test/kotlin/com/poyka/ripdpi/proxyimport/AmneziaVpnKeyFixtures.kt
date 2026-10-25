package com.poyka.ripdpi.proxyimport

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater

/** Builds AmneziaVPN share keys the way amnezia-client's ExportController does. */
internal object AmneziaVpnKeyFixtures {
    fun keyJson(
        conf: String,
        container: String = "amnezia-awg2",
        protocolKey: String = "awg",
        dns1: String? = null,
        dns2: String? = null,
    ): String {
        val lastConfig = buildJsonObject { put("config", conf) }.toString()
        return buildJsonObject {
            put("hostName", "203.0.113.7")
            put("defaultContainer", container)
            dns1?.let { put("dns1", it) }
            dns2?.let { put("dns2", it) }
            put(
                "containers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("container", container)
                            put(protocolKey, buildJsonObject { put("last_config", JsonPrimitive(lastConfig)) })
                        },
                    )
                },
            )
        }.toString()
    }

    /** Qt `qCompress`: 4-byte big-endian length, then a zlib stream. */
    fun qCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(8)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    fun vpnKey(
        conf: String,
        dns1: String? = null,
        dns2: String? = null,
    ): String = "vpn://" + base64Url(qCompress(keyJson(conf, dns1 = dns1, dns2 = dns2).toByteArray()))

    /** One QR code of an AmneziaVPN series: qint16 1984, quint8 count, quint8 index, QByteArray slice. */
    fun qrChunk(
        count: Int,
        index: Int,
        slice: ByteArray,
    ): String {
        val buffer = ByteBuffer.allocate(2 + 1 + 1 + 4 + slice.size)
        buffer
            .putShort(1984)
            .put(count.toByte())
            .put(index.toByte())
            .putInt(slice.size)
            .put(slice)
        return base64Url(buffer.array())
    }

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
