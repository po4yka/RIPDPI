package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

object WebRtcLeakChecker {
    private val random = SecureRandom()
    private val STUN_SERVERS =
        listOf(
            "stun.l.google.com" to 19302,
            "stun1.l.google.com" to 19302,
        )

    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        webRtcProtectionEnabled: Boolean = false,
    ): CategoryResult =
        withContext(dispatchers.io) {
            resultFrom(webRtcProtectionEnabled, probeStunReachability())
        }

    internal fun resultFrom(
        webRtcProtectionEnabled: Boolean,
        stunReachable: Boolean,
    ): CategoryResult {
        val observation = "STUN server returned a binding response on the app network path"
        return CategoryResult(
            name = "WebRTC Leak",
            detected = false,
            findings =
                listOf(
                    Finding("WebRTC protection: ${if (webRtcProtectionEnabled) "enabled" else "disabled"}"),
                    Finding(if (stunReachable) observation else "STUN server: no valid binding response"),
                ),
            evidence = if (stunReachable) listOf(reachableStunEvidence(observation)) else emptyList(),
        )
    }

    internal fun reachableStunEvidence(description: String): EvidenceItem =
        EvidenceItem(
            source = EvidenceSource.NETWORK_CAPABILITIES,
            scope = DetectionScope.NETWORK_OBSERVATION,
            detected = false,
            confidence = EvidenceConfidence.MEDIUM,
            description = description,
        )

    internal fun probeStunReachability(probe: (String, Int) -> Boolean = ::sendStunBinding): Boolean =
        STUN_SERVERS.any { (host, port) -> probe(host, port) }

    @Suppress("MagicNumber", "TooGenericExceptionCaught")
    private fun sendStunBinding(
        host: String,
        port: Int,
    ): Boolean =
        try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress(address, port))

                // STUN Binding Request: type=0x0001, length=0, magic=0x2112A442, txn=random
                val request = ByteArray(20)
                random.nextBytes(request)
                request[0] = 0x00
                request[1] = 0x01
                request[2] = 0x00
                request[3] = 0x00
                request[4] = 0x21
                request[5] = 0x12
                request[6] = 0xA4.toByte()
                request[7] = 0x42

                val sendPacket = DatagramPacket(request, request.size, address, port)
                socket.send(sendPacket)

                val response = ByteArray(1500)
                val recvPacket = DatagramPacket(response, response.size)
                socket.receive(recvPacket)

                validBindingResponse(response, recvPacket.length, request)
            }
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: Exception) {
            false
        }

    @Suppress("MagicNumber")
    internal fun validBindingResponse(
        response: ByteArray,
        size: Int,
        request: ByteArray,
    ): Boolean {
        if (size < 20 || size > response.size || request.size < 20 ||
            response[0] != 0x01.toByte() || response[1] != 0x01.toByte()
        ) return false
        val length = (response[2].toInt() and 0xff) * 256 + (response[3].toInt() and 0xff)
        if (length % 4 != 0 || length != size - 20 || (4 until 20).any { response[it] != request[it] }) {
            return false
        }

        var offset = 20
        var hasMappedAddress = false
        while (offset < size) {
            if (size - offset < 4) return false
            val type = (response[offset].toInt() and 0xff) * 256 + (response[offset + 1].toInt() and 0xff)
            val attributeLength =
                (response[offset + 2].toInt() and 0xff) * 256 +
                    (response[offset + 3].toInt() and 0xff)
            val valueOffset = offset + 4
            offset = valueOffset + ((attributeLength + 3) and -4)
            if (offset > size) return false
            if (type == 0x0020) {
                if (attributeLength != 8 && attributeLength != 20) return false
                val family = response[valueOffset + 1].toInt() and 0xff
                if (!((family == 1 && attributeLength == 8) || (family == 2 && attributeLength == 20))) {
                    return false
                }
                hasMappedAddress = true
            }
        }
        return hasMappedAddress
    }
}
