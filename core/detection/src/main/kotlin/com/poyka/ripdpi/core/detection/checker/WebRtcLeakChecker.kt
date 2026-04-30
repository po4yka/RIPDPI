package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
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

object WebRtcLeakChecker {
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
            val findings = mutableListOf<Finding>()
            val evidence = mutableListOf<EvidenceItem>()
            var detected = false
            var needsReview = false

            if (webRtcProtectionEnabled) {
                findings.add(Finding("WebRTC protection: enabled"))
            } else {
                findings.add(
                    Finding(
                        description = "WebRTC protection: disabled",
                        needsReview = true,
                        source = EvidenceSource.NETWORK_CAPABILITIES,
                        confidence = EvidenceConfidence.LOW,
                    ),
                )
                needsReview = true
            }

            val stunResult = probeStunReachability()
            when (stunResult) {
                StunProbeResult.REACHABLE -> {
                    if (!webRtcProtectionEnabled) {
                        detected = true
                        findings.add(
                            Finding(
                                description = "STUN server reachable - WebRTC can expose real IP",
                                detected = true,
                                source = EvidenceSource.NETWORK_CAPABILITIES,
                                confidence = EvidenceConfidence.MEDIUM,
                            ),
                        )
                        evidence.add(
                            EvidenceItem(
                                source = EvidenceSource.NETWORK_CAPABILITIES,
                                detected = true,
                                confidence = EvidenceConfidence.MEDIUM,
                                description = "STUN server reachable without WebRTC protection",
                            ),
                        )
                    } else {
                        findings.add(Finding("STUN server reachable (protected by WebRTC filter)"))
                    }
                }

                StunProbeResult.BLOCKED -> {
                    findings.add(Finding("STUN server: blocked (good - reduces WebRTC leak risk)"))
                }

                StunProbeResult.ERROR -> {
                    findings.add(Finding("STUN server: check failed"))
                }
            }

            CategoryResult(
                name = "WebRTC Leak",
                detected = detected,
                findings = findings,
                needsReview = needsReview,
                evidence = evidence,
            )
        }

    private fun probeStunReachability(): StunProbeResult {
        for ((host, port) in STUN_SERVERS) {
            val result = sendStunBinding(host, port)
            if (result != StunProbeResult.ERROR) return result
        }
        return StunProbeResult.ERROR
    }

    @Suppress("MagicNumber", "TooGenericExceptionCaught")
    private fun sendStunBinding(
        host: String,
        port: Int,
    ): StunProbeResult =
        try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress(address, port))

                // STUN Binding Request: type=0x0001, length=0, magic=0x2112A442, txn=random
                val request = ByteArray(20)
                request[0] = 0x00
                request[1] = 0x01
                // length = 0
                // magic cookie
                request[4] = 0x21
                request[5] = 0x12
                request[6] = 0xA4.toByte()
                request[7] = 0x42
                // random transaction ID (bytes 8-19)
                for (i in 8..19) request[i] = (Math.random() * 256).toInt().toByte()

                val sendPacket = DatagramPacket(request, request.size, address, port)
                socket.send(sendPacket)

                val response = ByteArray(128)
                val recvPacket = DatagramPacket(response, response.size)
                socket.receive(recvPacket)

                // Check if response is STUN Binding Response (type 0x0101)
                if (recvPacket.length >= 20 && response[0] == 0x01.toByte() && response[1] == 0x01.toByte()) {
                    StunProbeResult.REACHABLE
                } else {
                    StunProbeResult.ERROR
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            StunProbeResult.BLOCKED
        } catch (_: Exception) {
            StunProbeResult.ERROR
        }

    private enum class StunProbeResult {
        REACHABLE,
        BLOCKED,
        ERROR,
    }
}
