internal const val DiagnosticsCatalogGeneratedAt = "2026-04-20"
internal const val DiagnosticsCatalogSchemaVersion = 1

internal data class DiagnosticsCatalog(
    val packs: List<TargetPackDefinition>,
    val profiles: List<DiagnosticsProfileDefinition>,
)

internal class DiagnosticsCatalogIndex(
    packs: List<TargetPackDefinition>,
) {
    private val packsById = packs.associateBy(TargetPackDefinition::id)

    operator fun get(id: String): TargetPackDefinition? = packsById[id]
}

internal fun interface DiagnosticsCatalogPackSource {
    fun load(): List<TargetPackDefinition>
}

internal fun interface DiagnosticsCatalogProfileSource {
    fun load(index: DiagnosticsCatalogIndex): List<DiagnosticsProfileDefinition>
}

internal enum class CatalogScanKind {
    CONNECTIVITY,
    STRATEGY_PROBE,
}

internal enum class CatalogDiagnosticProfileFamily {
    GENERAL,
    WEB_CONNECTIVITY,
    MESSAGING,
    CIRCUMVENTION,
    THROTTLING,
    DPI_FULL,
    AUTOMATIC_PROBING,
    AUTOMATIC_AUDIT,
}

internal enum class CatalogLegalSafety {
    SAFE,
    SENSITIVE,
    UNSAFE,
}

internal enum class CatalogLegalSafetyShippingPolicy {
    ALLOW,
    MANUAL_ONLY,
    DENYLIST,
}

internal data class CatalogLegalSafetyMetadata(
    val classification: CatalogLegalSafety,
    val shippingPolicy: CatalogLegalSafetyShippingPolicy,
    val jurisdictionTag: String,
    val ruleId: String,
)

internal enum class CatalogProfileIntentBucket {
    SAFE_DEFAULT,
    MANUAL_SENSITIVE,
    LAB_ONLY,
}

internal enum class CatalogProbePersistencePolicy {
    MANUAL_ONLY,
    BACKGROUND_ONLY,
    ALWAYS,
}

internal data class ProfileExecutionPolicyDefinition(
    val manualOnly: Boolean,
    val allowBackground: Boolean,
    val requiresRawPath: Boolean,
    val probePersistencePolicy: CatalogProbePersistencePolicy,
)

internal data class DomainTargetDefinition(
    val host: String,
    val legalSafety: CatalogLegalSafety = CatalogLegalSafety.SAFE,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class DnsTargetDefinition(
    val domain: String,
    val udpServer: String? = null,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class TcpTargetDefinition(
    val id: String,
    val provider: String,
    val ip: String,
    val port: Int = 443,
    val asn: String? = null,
)

internal data class QuicTargetDefinition(
    val host: String,
    val port: Int = 443,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class TelegramDcEndpointDefinition(
    val ip: String,
    val label: String,
    val port: Int = 443,
)

internal data class TelegramTargetDefinition(
    val mediaUrl: String,
    val uploadIp: String,
    val uploadPort: Int = 443,
    val dcEndpoints: List<TelegramDcEndpointDefinition> = emptyList(),
    val stallTimeoutMs: Long = 10_000,
    val totalTimeoutMs: Long = 60_000,
    val uploadSizeBytes: Int = 10_485_760,
)

internal data class StrategyProbeDefinition(
    val suiteId: String,
)

internal data class ServiceTargetDefinition(
    val id: String,
    val service: String,
    val bootstrapUrl: String? = null,
    val tcpEndpointHost: String? = null,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class CircumventionTargetDefinition(
    val id: String,
    val tool: String,
    val bootstrapUrl: String? = null,
    val handshakeHost: String? = null,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class ThroughputTargetDefinition(
    val id: String,
    val label: String,
    val url: String,
    val isControl: Boolean = false,
    val windowBytes: Int = 8_388_608,
    val runs: Int = 2,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal data class TargetPackDefinition(
    val id: String,
    val version: Int,
    val domainTargets: List<DomainTargetDefinition> = emptyList(),
    val dnsTargets: List<DnsTargetDefinition> = emptyList(),
    val tcpTargets: List<TcpTargetDefinition> = emptyList(),
    val quicTargets: List<QuicTargetDefinition> = emptyList(),
    val serviceTargets: List<ServiceTargetDefinition> = emptyList(),
    val circumventionTargets: List<CircumventionTargetDefinition> = emptyList(),
    val throughputTargets: List<ThroughputTargetDefinition> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
)

internal data class DiagnosticsProfileDefinition(
    val id: String,
    val name: String,
    val version: Int = 1,
    val kind: CatalogScanKind = CatalogScanKind.CONNECTIVITY,
    val family: CatalogDiagnosticProfileFamily = CatalogDiagnosticProfileFamily.GENERAL,
    val intentBucket: CatalogProfileIntentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
    val legalSafety: CatalogLegalSafety = CatalogLegalSafety.SAFE,
    val regionTag: String? = null,
    val executionPolicy: ProfileExecutionPolicyDefinition,
    val packRefs: List<String> = emptyList(),
    val domainTargets: List<DomainTargetDefinition> = emptyList(),
    val dnsTargets: List<DnsTargetDefinition> = emptyList(),
    val tcpTargets: List<TcpTargetDefinition> = emptyList(),
    val quicTargets: List<QuicTargetDefinition> = emptyList(),
    val serviceTargets: List<ServiceTargetDefinition> = emptyList(),
    val circumventionTargets: List<CircumventionTargetDefinition> = emptyList(),
    val throughputTargets: List<ThroughputTargetDefinition> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
    val telegramTarget: TelegramTargetDefinition? = null,
    val strategyProbe: StrategyProbeDefinition? = null,
)
