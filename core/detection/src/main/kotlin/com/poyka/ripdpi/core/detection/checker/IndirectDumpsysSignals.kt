package com.poyka.ripdpi.core.detection.checker

import android.os.Build
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.vpn.ParsedActiveVpnRecord
import com.poyka.ripdpi.core.detection.vpn.VpnDumpsysParser
import kotlinx.coroutines.CancellationException

internal object IndirectDumpsysSignals {
    fun check(findings: MutableList<Finding>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            findings +=
                read(
                    args = arrayOf("dumpsys", "vpn_management"),
                    label = "VPN management",
                    parse = VpnDumpsysParser::parseVpnManagement,
                )
        }
        findings +=
            read(
                args = arrayOf("dumpsys", "activity", "services", "android.net.VpnService"),
                label = "VpnService records",
                parse = VpnDumpsysParser::parseVpnServices,
            )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read(
        args: Array<String>,
        label: String,
        parse: (String) -> List<ParsedActiveVpnRecord>,
    ): List<Finding> =
        try {
            val process = Runtime.getRuntime().exec(args)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0 || VpnDumpsysParser.isUnavailable(output)) {
                listOf(Finding("dumpsys $label: unavailable"))
            } else {
                reportedPackages(parse(output))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listOf(Finding("dumpsys $label: unavailable"))
        }

    // VPN management can report a prepared package. A service record also does not prove a live tunnel.
    internal fun reportedPackages(records: List<ParsedActiveVpnRecord>): List<Finding> =
        records
            .mapNotNull { it.packageName }
            .distinct()
            .map { packageName ->
                Finding(
                    description = "VPN package reported by dumpsys: $packageName",
                    needsReview = true,
                    source = EvidenceSource.DUMPSYS,
                    confidence = EvidenceConfidence.LOW,
                    packageName = packageName,
                )
            }
}
