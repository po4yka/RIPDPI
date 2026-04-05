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

            // DNS protocol constants (RFC 1035)
            private const val DnsHeaderId1: Byte = 0x12
            private const val DnsHeaderId2: Byte = 0x34
            private const val DnsHeaderLength = 12
            private const val DnsMaxUdpPayload = 512
            private const val DnsCompressionMask = 0xC0
            private const val DnsOctetMask = 0xFF
            private const val DnsTypeAValue = 1
            private const val DnsRdataIpv4Length = 4
            private const val DnsByteShift = 8
            private const val DnsRcodeOffset = 3
            private const val DnsRcodeMask = 0x0F
            private const val DnsAnswerCountOffset = 6
            private const val DnsAnswerCountOffset1 = 7
            private const val DnsQuestionCountOffset = 4
            private const val DnsQuestionCountOffset1 = 5
            private const val DnsQTypeQClassLength = 4
            private const val DnsRecordHeaderLength = 10
            private const val DnsRecordTypeHighOffset = 0
            private const val DnsRecordTypeLowOffset = 1
            private const val DnsDataLenHighOffset = 8
            private const val DnsDataLenLowOffset = 9
            private const val DnsPointerSize = 2
            private const val DnsIpOctet1Offset = 1
            private const val DnsIpOctet2Offset = 2
            private const val DnsIpOctet3Offset = 3
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
                    val buf = ByteArray(DnsMaxUdpPayload)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    parseDnsResponseIps(buf, response.length)
                }
            }.getOrDefault(emptySet())

        private fun buildDnsQuery(domain: String): ByteArray {
            val out = mutableListOf<Byte>()
            // Header: ID, flags=standard-query, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
            out.addAll(
                byteArrayOf(
                    DnsHeaderId1,
                    DnsHeaderId2,
                    0x01,
                    0x00, // flags: standard query, recursion desired
                    0x00,
                    0x01, // QDCOUNT = 1
                    0x00,
                    0x00, // ANCOUNT = 0
                    0x00,
                    0x00, // NSCOUNT = 0
                    0x00,
                    0x00, // ARCOUNT = 0
                ).toList(),
            )
            // Question: domain labels
            for (label in domain.split('.')) {
                out.add(label.length.toByte())
                out.addAll(label.toByteArray(Charsets.US_ASCII).toList())
            }
            out.add(0x00) // root label
            out.addAll(byteArrayOf(0x00, 0x01, 0x00, 0x01).toList()) // QTYPE=A, QCLASS=IN
            return out.toByteArray()
        }

        @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
        private fun parseDnsResponseIps(
            packet: ByteArray,
            length: Int,
        ): Set<String> {
            if (length < DnsHeaderLength) return emptySet()
            val rcode = packet[DnsRcodeOffset].toInt() and DnsRcodeMask
            if (rcode != 0) return emptySet() // NXDOMAIN or error
            val answerCount =
                ((packet[DnsAnswerCountOffset].toInt() and DnsOctetMask) shl DnsByteShift) or
                    (packet[DnsAnswerCountOffset1].toInt() and DnsOctetMask)
            val questionCount =
                ((packet[DnsQuestionCountOffset].toInt() and DnsOctetMask) shl DnsByteShift) or
                    (packet[DnsQuestionCountOffset1].toInt() and DnsOctetMask)
            var offset = DnsHeaderLength
            // Skip questions
            repeat(questionCount) {
                while (offset < length) {
                    val b = packet[offset].toInt() and DnsOctetMask
                    if (b == 0) {
                        offset++
                        break
                    }
                    if (b >= DnsCompressionMask) {
                        offset += DnsPointerSize
                        break
                    }
                    offset += b + 1
                }
                offset += DnsQTypeQClassLength // QTYPE + QCLASS
            }
            val ips = mutableSetOf<String>()
            repeat(answerCount) {
                if (offset >= length) return ips
                // Skip name (may be pointer)
                val b = packet[offset].toInt() and DnsOctetMask
                offset +=
                    if (b >= DnsCompressionMask) {
                        DnsPointerSize
                    } else {
                        var o = offset
                        while (o < length) {
                            val lb = packet[o].toInt() and DnsOctetMask
                            if (lb == 0) {
                                o++
                                break
                            }
                            if (lb >= DnsCompressionMask) {
                                o += DnsPointerSize
                                break
                            }
                            o += lb + 1
                        }
                        o - offset
                    }
                if (offset + DnsRecordHeaderLength > length) return ips
                val recordType =
                    ((packet[offset + DnsRecordTypeHighOffset].toInt() and DnsOctetMask) shl DnsByteShift) or
                        (packet[offset + DnsRecordTypeLowOffset].toInt() and DnsOctetMask)
                val dataLen =
                    ((packet[offset + DnsDataLenHighOffset].toInt() and DnsOctetMask) shl DnsByteShift) or
                        (packet[offset + DnsDataLenLowOffset].toInt() and DnsOctetMask)
                offset += DnsRecordHeaderLength
                val isIpv4Answer =
                    recordType == DnsTypeAValue &&
                        dataLen == DnsRdataIpv4Length &&
                        offset + DnsRdataIpv4Length <= length
                if (isIpv4Answer) {
                    ips.add(
                        "${packet[offset].toInt() and DnsOctetMask}." +
                            "${packet[offset + DnsIpOctet1Offset].toInt() and DnsOctetMask}." +
                            "${packet[offset + DnsIpOctet2Offset].toInt() and DnsOctetMask}." +
                            "${packet[offset + DnsIpOctet3Offset].toInt() and DnsOctetMask}",
                    )
                }
                offset += dataLen
            }
            return ips
        }
    }
