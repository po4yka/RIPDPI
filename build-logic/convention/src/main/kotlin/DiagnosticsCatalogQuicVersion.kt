import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class QuicVersionMatrixDefinition(
    val host: String = "",
    val controlSni: String = "",
    val testSni: String = "",
)

internal fun quicVersionMatrixProfile() =
    DiagnosticsProfileDefinition(
        id = "quic-version-matrix",
        name = "QUIC version matrix",
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        quicVersionMatrix = QuicVersionMatrixDefinition(),
    )

internal fun QuicVersionMatrixDefinition.toJson() =
    buildJsonObject {
        put("host", host)
        put("controlSni", controlSni)
        put("testSni", testSni)
    }

internal fun DiagnosticsProfileDefinition.validateQuicVersionMatrix() {
    val config = quicVersionMatrix ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground && executionPolicy.requiresRawPath)
    require(config == QuicVersionMatrixDefinition()) { "QUIC version matrix must not bundle a target" }
}
