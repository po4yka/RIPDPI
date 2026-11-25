import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TlsHopComparisonDefinition(
    val host: String = "",
    val controlSni: String = "",
    val testSni: String = "",
)

internal fun tlsHopComparisonProfile() =
    DiagnosticsProfileDefinition(
        id = "tls-hop-comparison",
        name = "TLS hop comparison",
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        tlsHopComparison = TlsHopComparisonDefinition(),
    )

internal fun TlsHopComparisonDefinition.toJson() =
    buildJsonObject {
        put("host", host)
        put("controlSni", controlSni)
        put("testSni", testSni)
    }

internal fun DiagnosticsProfileDefinition.validateTlsHopComparison() {
    val config = tlsHopComparison ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground && executionPolicy.requiresRawPath)
    require(config == TlsHopComparisonDefinition()) { "TLS hop comparison must not bundle a target" }
}
