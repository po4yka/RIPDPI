package com.poyka.ripdpi.diagnostics.contract.profile

import com.poyka.ripdpi.diagnostics.CircumventionTarget
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DnsTarget
import com.poyka.ripdpi.diagnostics.DomainTarget
import com.poyka.ripdpi.diagnostics.QuicTarget
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ServiceTarget
import com.poyka.ripdpi.diagnostics.StrategyProbeRequest
import com.poyka.ripdpi.diagnostics.TcpTarget
import com.poyka.ripdpi.diagnostics.TelegramTarget
import com.poyka.ripdpi.diagnostics.ThroughputTarget
import kotlinx.serialization.Serializable

@Serializable
data class ProfileSpecWire(
    val profileId: String,
    val displayName: String,
    val kind: ScanKind = ScanKind.CONNECTIVITY,
    val family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    val regionTag: String? = null,
    val manualOnly: Boolean = false,
    val packRefs: List<String> = emptyList(),
    val domainTargets: List<DomainTarget> = emptyList(),
    val dnsTargets: List<DnsTarget> = emptyList(),
    val tcpTargets: List<TcpTarget> = emptyList(),
    val quicTargets: List<QuicTarget> = emptyList(),
    val serviceTargets: List<ServiceTarget> = emptyList(),
    val circumventionTargets: List<CircumventionTarget> = emptyList(),
    val throughputTargets: List<ThroughputTarget> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
    val telegramTarget: TelegramTarget? = null,
    val strategyProbe: StrategyProbeRequest? = null,
)

@Serializable
data class BundledDiagnosticProfileWire(
    val id: String,
    val name: String,
    val version: Int,
    val request: ProfileSpecWire,
)

@Serializable
data class BundledDiagnosticsCatalogWire(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val profiles: List<BundledDiagnosticProfileWire> = emptyList(),
)
