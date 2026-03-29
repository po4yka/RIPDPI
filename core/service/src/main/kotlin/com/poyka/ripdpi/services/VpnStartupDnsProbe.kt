package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsPathCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight DNS integrity probe for VPN cold start.
 *
 * When the VPN starts on a network where no diagnostic has ever been run
 * and DNS mode is plain UDP, this probe compares a system DNS resolution
 * against a direct UDP query to Cloudflare (1.1.1.1) for a canary domain.
 *
 * If the answers differ, DNS tampering is likely and the canonical encrypted
 * DNS path (Cloudflare DoH) should be used instead.
 */
@Singleton
class VpnStartupDnsProbe
    @Inject
    constructor() {
        companion object {
            private const val CANARY_DOMAIN = "www.google.com"
            private const val REFERENCE_DNS_SERVER = "1.1.1.1"
            private const val REFERENCE_DNS_PORT = 53
            private const val PROBE_TIMEOUT_MS = 3_000L
            private const val UDP_TIMEOUT_MS = 2_000
        }

        /**
         * Returns the canonical encrypted DNS path if DNS tampering is detected,
         * or `null` if DNS appears clean or the check fails/times out.
         */
        suspend fun probeIfTampered(dnsMode: String): EncryptedDnsPathCandidate? {
            if (dnsMode != DnsModePlainUdp) return null
            return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    detectTampering()
                }
            }
        }

        private fun detectTampering(): EncryptedDnsPathCandidate? =
            runCatching {
                val systemIps = resolveViaSystem(CANARY_DOMAIN)
                if (systemIps.isEmpty()) return@runCatching null
                val referenceIps = resolveViaUdp(CANARY_DOMAIN, REFERENCE_DNS_SERVER, REFERENCE_DNS_PORT)
                if (referenceIps.isEmpty()) return@runCatching null
                val tampered = systemIps.none { it in referenceIps }
                if (tampered) canonicalDefaultEncryptedDnsPathCandidate() else null
            }.getOrNull()

        private fun resolveViaSystem(domain: String): Set<String> =
            runCatching {
                InetAddress
                    .getAllByName(domain)
                    .map { it.hostAddress }
                    .filterNotNull()
                    .toSet()
            }.getOrDefault(emptySet())

        private fun resolveViaUdp(
            domain: String,
            server: String,
            port: Int,
        ): Set<String> =
            runCatching {
                val query = buildDnsQuery(domain)
                val serverAddr = InetSocketAddress(InetAddress.getByName(server), port)
                DatagramSocket().use { socket ->
                    socket.soTimeout = UDP_TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, serverAddr))
                    val buf = ByteArray(512)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    parseDnsResponseIps(buf, response.length)
                }
            }.getOrDefault(emptySet())

        private fun buildDnsQuery(domain: String): ByteArray {
            val out = mutableListOf<Byte>()
            // Header: ID=0x1234, flags=0x0100 (standard query), QDCOUNT=1
            out.addAll(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).toList())
            // Question: domain labels
            for (label in domain.split('.')) {
                out.add(label.length.toByte())
                out.addAll(label.toByteArray(Charsets.US_ASCII).toList())
            }
            out.add(0x00) // root label
            out.addAll(byteArrayOf(0x00, 0x01, 0x00, 0x01).toList()) // QTYPE=A, QCLASS=IN
            return out.toByteArray()
        }

        private fun parseDnsResponseIps(
            packet: ByteArray,
            length: Int,
        ): Set<String> {
            if (length < 12) return emptySet()
            val rcode = packet[3].toInt() and 0x0F
            if (rcode != 0) return emptySet() // NXDOMAIN or error
            val answerCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            val questionCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
            var offset = 12
            // Skip questions
            repeat(questionCount) {
                while (offset < length) {
                    val b = packet[offset].toInt() and 0xFF
                    if (b == 0) {
                        offset++
                        break
                    }
                    if (b >= 0xC0) {
                        offset += 2
                        break
                    }
                    offset += b + 1
                }
                offset += 4 // QTYPE + QCLASS
            }
            val ips = mutableSetOf<String>()
            repeat(answerCount) {
                if (offset >= length) return ips
                // Skip name (may be pointer)
                val b = packet[offset].toInt() and 0xFF
                offset +=
                    if (b >= 0xC0) {
                        2
                    } else {
                        var o = offset
                        while (o < length) {
                            val lb = packet[o].toInt() and 0xFF
                            if (lb == 0) {
                                o++
                                break
                            }
                            if (lb >= 0xC0) {
                                o += 2
                                break
                            }
                            o += lb + 1
                        }
                        o - offset
                    }
                if (offset + 10 > length) return ips
                val recordType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                val dataLen = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
                offset += 10
                if (recordType == 1 && dataLen == 4 && offset + 4 <= length) {
                    ips.add(
                        "${packet[offset].toInt() and 0xFF}." +
                            "${packet[offset + 1].toInt() and 0xFF}." +
                            "${packet[offset + 2].toInt() and 0xFF}." +
                            "${packet[offset + 3].toInt() and 0xFF}",
                    )
                }
                offset += dataLen
            }
            return ips
        }
    }
