internal fun policy(
    manualOnly: Boolean,
    allowBackground: Boolean,
    requiresRawPath: Boolean,
): ProfileExecutionPolicyDefinition =
    ProfileExecutionPolicyDefinition(
        manualOnly = manualOnly,
        allowBackground = allowBackground,
        requiresRawPath = requiresRawPath,
    )

internal fun domainTargets(multiline: String): List<DomainTargetDefinition> = lines(multiline).map(::DomainTargetDefinition)

internal fun quicTargets(multiline: String): List<QuicTargetDefinition> =
    lines(multiline).map { host -> QuicTargetDefinition(host = host) }

internal fun udpDnsTargets(
    domains: List<String>,
    servers: List<String>,
): List<DnsTargetDefinition> =
    buildList {
        domains.forEach { domain ->
            servers.forEach { server ->
                add(DnsTargetDefinition(domain = domain, udpServer = server))
            }
        }
    }

internal fun tcpTargets(multiline: String): List<TcpTargetDefinition> =
    lines(multiline).map { entry ->
        val parts = entry.split('|')
        require(parts.size in 4..5) { "Invalid TCP target entry: $entry" }
        TcpTargetDefinition(
            id = parts[0],
            provider = parts[1],
            ip = parts[2],
            port = parts[3].toInt(),
            asn = parts.getOrNull(4)?.takeIf(String::isNotBlank),
        )
    }

internal fun lines(multiline: String): List<String> =
    multiline
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

internal fun packRef(
    id: String,
    version: Int,
): String = "$id@$version"

internal fun parsePackRef(packRef: String): Pair<String, Int> {
    val splitIndex = packRef.lastIndexOf('@')
    require(splitIndex > 0 && splitIndex < packRef.lastIndex) { "Invalid pack ref: $packRef" }
    return packRef.substring(0, splitIndex) to packRef.substring(splitIndex + 1).toInt()
}

internal fun DiagnosticsCatalogIndex.requirePack(id: String): TargetPackDefinition =
    requireNotNull(this[id]) { "Missing diagnostics pack: $id" }

internal fun TargetPackDefinition?.orEmptyDomains(): List<DomainTargetDefinition> = this?.domainTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyDns(): List<DnsTargetDefinition> = this?.dnsTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyTcp(): List<TcpTargetDefinition> = this?.tcpTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyQuic(): List<QuicTargetDefinition> = this?.quicTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyServices(): List<ServiceTargetDefinition> = this?.serviceTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyCircumvention(): List<CircumventionTargetDefinition> =
    this?.circumventionTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyThroughput(): List<ThroughputTargetDefinition> = this?.throughputTargets.orEmpty()

internal fun TargetPackDefinition?.orEmptyWhitelist(): List<String> = this?.whitelistSni.orEmpty()
