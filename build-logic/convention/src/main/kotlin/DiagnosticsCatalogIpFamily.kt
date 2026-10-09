import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class IpFamilyProbeDefinition(
    val version: Int = 1,
    val ipv4Address: String = "1.1.1.1",
    val ipv6Address: String = "2606:4700:4700::1111",
    val port: Int = 443,
    val timeoutMs: Long = 1_500,
)

internal fun ipFamilyConnectivityProfile() =
    DiagnosticsProfileDefinition(
        id = "ip-family-connectivity",
        name = "IPv4, IPv6 and NAT64 connectivity",
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        ipFamilyProbe = IpFamilyProbeDefinition(),
    )

internal fun IpFamilyProbeDefinition.toJson() =
    buildJsonObject {
        put("version", version)
        put("ipv4Address", ipv4Address)
        put("ipv6Address", ipv6Address)
        put("port", port)
        put("timeoutMs", timeoutMs)
    }

internal fun DiagnosticsProfileDefinition.validateIpFamilyProbe() {
    val config = ipFamilyProbe ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground && executionPolicy.requiresRawPath)
    require(config == IpFamilyProbeDefinition()) { "IP family control endpoints must match the reviewed catalog" }
}
