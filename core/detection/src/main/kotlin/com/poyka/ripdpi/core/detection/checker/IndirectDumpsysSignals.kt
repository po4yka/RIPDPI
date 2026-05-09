package com.poyka.ripdpi.core.detection.checker

import android.os.Build
import com.poyka.ripdpi.core.detection.ActiveVpnApp
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.core.detection.vpn.VpnDumpsysParser
import kotlinx.coroutines.CancellationException

internal object IndirectDumpsysSignals {
    @Suppress("NestedBlockDepth")
    fun checkDumpsysVpn(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        activeApps: MutableList<ActiveVpnApp>,
    ): IndirectSignalOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return IndirectSignalOutcome()
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "vpn_management"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (VpnDumpsysParser.isUnavailable(output)) {
                findings.add(Finding("dumpsys vpn_management: unavailable"))
                return IndirectSignalOutcome()
            }

            val records = VpnDumpsysParser.parseVpnManagement(output)
            if (records.isEmpty()) {
                findings.add(Finding("dumpsys vpn_management: no active VPNs"))
                return IndirectSignalOutcome()
            }

            var detected = false
            var needsReview = false
            for (record in records) {
                val signature = record.packageName?.let { VpnAppCatalog.findByPackageName(it) }
                val confidence =
                    when {
                        signature != null -> EvidenceConfidence.HIGH
                        record.packageName != null -> EvidenceConfidence.MEDIUM
                        else -> EvidenceConfidence.LOW
                    }
                val description =
                    buildString {
                        append("VPN management: ")
                        append(record.rawLine)
                        signature?.family?.let { append(" [$it]") }
                    }
                findings.add(
                    Finding(
                        description = description,
                        detected = true,
                        source = EvidenceSource.ACTIVE_VPN,
                        confidence = confidence,
                        family = signature?.family,
                        packageName = record.packageName,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.ACTIVE_VPN,
                        detected = true,
                        confidence = confidence,
                        description = record.rawLine,
                        family = signature?.family,
                        packageName = record.packageName,
                        kind = signature?.kind,
                    ),
                )
                activeApps.add(
                    ActiveVpnApp(
                        packageName = record.packageName,
                        serviceName = null,
                        family = signature?.family,
                        kind = signature?.kind,
                        source = EvidenceSource.ACTIVE_VPN,
                        confidence = confidence,
                    ),
                )
                detected = true
                needsReview = needsReview || signature == null
            }

            IndirectSignalOutcome(detected = detected, needsReview = needsReview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("dumpsys vpn_management: ${e.message}"))
            IndirectSignalOutcome()
        }
    }

    @Suppress("NestedBlockDepth")
    fun checkDumpsysVpnService(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        activeApps: MutableList<ActiveVpnApp>,
    ): IndirectSignalOutcome =
        try {
            val process =
                Runtime
                    .getRuntime()
                    .exec(arrayOf("dumpsys", "activity", "services", "android.net.VpnService"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (VpnDumpsysParser.isUnavailable(output)) {
                findings.add(Finding("dumpsys activity services VpnService: unavailable"))
                IndirectSignalOutcome()
            } else {
                val records = VpnDumpsysParser.parseVpnServices(output)
                if (records.isEmpty()) {
                    findings.add(Finding("Active VpnService: not detected"))
                    IndirectSignalOutcome()
                } else {
                    var detected = false
                    var needsReview = false
                    for (record in records) {
                        val signature = record.packageName?.let { VpnAppCatalog.findByPackageName(it) }
                        val confidence =
                            when {
                                signature != null -> EvidenceConfidence.HIGH
                                record.packageName != null -> EvidenceConfidence.MEDIUM
                                else -> EvidenceConfidence.LOW
                            }
                        val serviceDisplay =
                            if (record.packageName != null && record.serviceName != null) {
                                "${record.packageName}/${record.serviceName}"
                            } else {
                                record.rawLine
                            }
                        val description =
                            buildString {
                                append("Active VpnService: ")
                                append(serviceDisplay)
                                signature?.family?.let { append(" [$it]") }
                            }
                        findings.add(
                            Finding(
                                description = description,
                                detected = true,
                                source = EvidenceSource.ACTIVE_VPN,
                                confidence = confidence,
                                family = signature?.family,
                                packageName = record.packageName,
                            ),
                        )
                        evidence.add(
                            EvidenceItem(
                                source = EvidenceSource.ACTIVE_VPN,
                                detected = true,
                                confidence = confidence,
                                description = serviceDisplay,
                                family = signature?.family,
                                packageName = record.packageName,
                                kind = signature?.kind,
                            ),
                        )
                        activeApps.add(
                            ActiveVpnApp(
                                packageName = record.packageName,
                                serviceName = record.serviceName,
                                family = signature?.family,
                                kind = signature?.kind,
                                source = EvidenceSource.ACTIVE_VPN,
                                confidence = confidence,
                            ),
                        )
                        detected = true
                        needsReview = needsReview || signature == null
                    }
                    IndirectSignalOutcome(detected = detected, needsReview = needsReview)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("dumpsys activity services: ${e.message}"))
            IndirectSignalOutcome()
        }
}
