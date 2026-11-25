package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
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
            executionStatus = if (stunReachable) CheckExecutionStatus.COMPLETED else CheckExecutionStatus.INCONCLUSIVE,
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

    internal suspend fun probeStunReachability(): Boolean =
        probeStunReachability { host, port -> sendStunBinding(host, port) }

    internal suspend fun probeStunReachability(probe: suspend (String, Int) -> Boolean): Boolean =
        STUN_SERVERS.any { (host, port) ->
            currentCoroutineContext().ensureActive()
            probe(host, port)
        }

    @Suppress("MagicNumber", "TooGenericExceptionCaught")
    internal suspend fun sendStunBinding(
        host: String,
        port: Int,
    ): Boolean =
        try {
            withTimeoutOrNull(3000) {
                val address = resolveStunAddress(host)
                DatagramSocket().use { socket ->
                    socket.withStunCancellation {
                        socket.connect(InetSocketAddress(address, port))

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

                        socket.send(DatagramPacket(request, request.size))
                        socket.receiveStunResponse(3000) { response ->
                            true.takeIf { validBindingResponse(response, response.size, request) }
                        } == true
                    }
                }
            } == true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    @Suppress("MagicNumber")
    internal fun validBindingResponse(
        response: ByteArray,
        size: Int,
        request: ByteArray,
    ): Boolean {
        if (size < 20 || size > response.size || request.size < 20) return false
        return StunBindingResponseDecoder.isValidBindingResponse(
            response.copyOf(size),
            request.copyOfRange(8, 20),
        )
    }
}
