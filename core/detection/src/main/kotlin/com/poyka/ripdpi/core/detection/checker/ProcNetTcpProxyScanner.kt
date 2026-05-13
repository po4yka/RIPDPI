@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import java.io.File
import java.io.IOException

internal interface ProcNetTcpReader {
    fun readTcp(): String

    fun readTcp6(): String
}

internal object FileProcNetTcpReader : ProcNetTcpReader {
    override fun readTcp(): String = File("/proc/net/tcp").readText()

    override fun readTcp6(): String = File("/proc/net/tcp6").readText()
}

internal object ProcNetTcpProxyScanner {
    data class ScanResult(
        val outcome: IndirectSignalOutcome,
        val findings: List<Finding>,
        val evidence: List<EvidenceItem>,
    )

    fun scan(context: Context): ScanResult =
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
    ): ScanResult {
        val sockets = readSockets(reader)
        val proxySockets = sockets.filter { socket -> socket.state == ListenState && socket.port in KnownProxyPorts }
        if (proxySockets.isEmpty()) {
            return ScanResult(IndirectSignalOutcome(), findings = emptyList(), evidence = emptyList())
        }

        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        var detected = false
        var needsReview = false

        proxySockets.forEach { socket ->
            val packageNames = packageResolver(socket.uid).ifEmpty { listOf("uid:${socket.uid}") }
            packageNames.forEach { packageName ->
                val signature = VpnAppCatalog.findByPackageName(packageName)
                if (signature != null) {
                    detected = true
                    val description =
                        "${signature.appName} listens on local proxy port ${socket.port} " +
                            "(${socket.table}, uid ${socket.uid})"
                    findings.add(
                        Finding(
                            description = description,
                            detected = true,
                            source = EvidenceSource.LOCAL_PROXY,
                            confidence = EvidenceConfidence.MEDIUM,
                            family = signature.family,
                            packageName = signature.packageName,
                        ),
                    )
                    evidence.add(
                        EvidenceItem(
                            source = EvidenceSource.LOCAL_PROXY,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = description,
                            family = signature.family,
                            packageName = signature.packageName,
                            kind = signature.kind,
                        ),
                    )
                } else {
                    needsReview = true
                    findings.add(
                        Finding(
                            description =
                                "$packageName listens on known proxy port ${socket.port} " +
                                    "(${socket.table}, uid ${socket.uid})",
                            needsReview = true,
                            source = EvidenceSource.LOCAL_PROXY,
                            confidence = EvidenceConfidence.LOW,
                            packageName = packageName.takeUnless { it.startsWith("uid:") },
                        ),
                    )
                }
            }
        }

        return ScanResult(
            outcome = IndirectSignalOutcome(detected = detected, needsReview = needsReview),
            findings = findings,
            evidence = evidence,
        )
    }

    private fun readSockets(reader: ProcNetTcpReader): List<ProcNetSocket> =
        buildList {
            addAll(readTable("tcp") { reader.readTcp() })
            addAll(readTable("tcp6") { reader.readTcp6() })
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
        val port =
            columns[LocalAddressColumnIndex]
                .substringAfter(':', missingDelimiterValue = "")
                .toIntOrNull(radix = HexRadix)
                ?: return null
        val uid = columns[UidColumnIndex].toIntOrNull() ?: return null
        return ProcNetSocket(
            table = table,
            port = port,
            state = columns[StateColumnIndex],
            uid = uid,
        )
    }

    private data class ProcNetSocket(
        val table: String,
        val port: Int,
        val state: String,
        val uid: Int,
    )

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
}
