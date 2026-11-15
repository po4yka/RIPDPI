@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface ProcNetTcpReader {
    fun readTcp(): String

    fun readTcp6(): String
}

internal object FileProcNetTcpReader : ProcNetTcpReader {
    override fun readTcp(): String = File("/proc/net/tcp").readText()

    override fun readTcp6(): String = File("/proc/net/tcp6").readText()
}

internal object ProcNetTcpProxyScanner {
    fun scan(context: Context): List<Finding> =
        scan(
            reader = FileProcNetTcpReader,
            packageResolver = { uid ->
                context.packageManager
                    .getPackagesForUid(uid)
                    ?.toList()
                    .orEmpty()
            },
        )

    fun scan(
        reader: ProcNetTcpReader,
        packageResolver: (uid: Int) -> List<String>,
    ): List<Finding> {
        val packagesByUid = mutableMapOf<Int, List<String>>()
        return (readTable("tcp", reader::readTcp) + readTable("tcp6", reader::readTcp6))
            .distinct()
            .map { socket ->
                val candidates = packagesByUid.getOrPut(socket.uid) { packageCandidates(socket.uid, packageResolver) }
                Finding(
                    description =
                        "${socket.endpointLabel()} is listening on a known proxy port, " +
                            "UID ${socket.uid}${candidatesLabel(candidates)}",
                    needsReview = true,
                    source = EvidenceSource.LOCAL_PROXY,
                    confidence = EvidenceConfidence.LOW,
                )
            }
    }

    private fun packageCandidates(
        uid: Int,
        resolve: (Int) -> List<String>,
    ): List<String> =
        try {
            resolve(uid).distinct().sorted()
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun candidatesLabel(candidates: List<String>): String =
        if (candidates.isEmpty()) {
            " (package candidates unavailable)"
        } else {
            candidates.joinToString(prefix = " (package candidates: ", postfix = ")") { name ->
                VpnAppCatalog.findByPackageName(name)?.let { "${it.appName} ($name)" } ?: name
            }
        }

    private fun readTable(
        table: String,
        read: () -> String,
    ): List<ProcNetSocket> =
        try {
            read()
                .lineSequence()
                .drop(1)
                .mapNotNull { line -> line.toProcNetSocket(table) }
                .toList()
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun String.toProcNetSocket(table: String): ProcNetSocket? {
        val columns = trim().split(Whitespace)
        if (columns.size <= UidColumnIndex) return null
        if (columns[StateColumnIndex] != ListenState) return null
        val local = columns[LocalAddressColumnIndex]
        val port = local.substringAfter(':', missingDelimiterValue = "").toIntOrNull(HexRadix) ?: return null
        if (port !in KnownProxyPorts) return null
        val address = parseAddress(local.substringBefore(':'), table) ?: return null
        val uid = columns[UidColumnIndex].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcNetSocket(address, port, uid)
    }

    private fun parseAddress(
        address: String,
        table: String,
    ): InetAddress? {
        val byteCount = if (table == "tcp") Ipv4Bytes else Ipv6Bytes
        if (address.length != byteCount * HexDigitsPerByte) return null

        // Linux prints each native-order 32-bit word separately, including in tcp6.
        val bytes = ByteBuffer.allocate(byteCount).order(ByteOrder.nativeOrder())
        for (word in address.chunked(HexDigitsPerWord)) {
            bytes.putInt(word.toUIntOrNull(HexRadix)?.toInt() ?: return null)
        }
        return InetAddress.getByAddress(bytes.array())
    }

    private data class ProcNetSocket(
        val address: InetAddress,
        val port: Int,
        val uid: Int,
    ) {
        fun endpointLabel(): String {
            val host = if (address is Inet6Address) "[${address.hostAddress}]" else address.hostAddress
            val bind =
                when {
                    address.isAnyLocalAddress -> "wildcard"
                    address.isLoopbackAddress -> "loopback"
                    else -> "specific address"
                }
            return "$host:$port ($bind bind)"
        }
    }

    private val Whitespace = Regex("\\s+")
    private val KnownProxyPorts: Set<Int> =
        buildSet {
            addAll(listOf(80, 443, 1080, 3127, 3128, 4080, 5555, 7000, 7044, 8888, 9000, 9150, 12345))
            addAll(8000..8082)
            addAll(9050..9051)
            addAll(16000..16100)
            addAll(VpnAppCatalog.localhostProxyPorts)
        }
    private const val LocalAddressColumnIndex = 1
    private const val StateColumnIndex = 3
    private const val UidColumnIndex = 7
    private const val ListenState = "0A"
    private const val HexRadix = 16
    private const val HexDigitsPerByte = 2
    private const val HexDigitsPerWord = 8
    private const val Ipv4Bytes = 4
    private const val Ipv6Bytes = 16
}
