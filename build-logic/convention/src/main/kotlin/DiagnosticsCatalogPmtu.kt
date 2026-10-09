import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PmtuProbeDefinition(
    val version: Int = 1,
    val host: String = "www.cloudflare.com",
    val port: Int = 443,
    val timeoutMs: Long = 10_000,
    val observationMs: Long = 3_000,
    val upperBoundUdpPayloadBytes: Int = 1_472,
)

internal fun pmtuConnectivityProfile() =
    DiagnosticsProfileDefinition(
        id = "pmtu-connectivity",
        name = "Active packet sizes / PMTU",
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        pmtuProbe = PmtuProbeDefinition(),
    )

internal fun PmtuProbeDefinition.toJson() =
    buildJsonObject {
        put("version", version)
        put("host", host)
        put("port", port)
        put("timeoutMs", timeoutMs)
        put("observationMs", observationMs)
        put("upperBoundUdpPayloadBytes", upperBoundUdpPayloadBytes)
    }

internal fun DiagnosticsProfileDefinition.validatePmtuProbe() {
    val config = pmtuProbe ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground && executionPolicy.requiresRawPath)
    require(config == PmtuProbeDefinition()) { "PMTU control endpoint must match the reviewed catalog" }
}
