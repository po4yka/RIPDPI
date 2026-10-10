import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class Http3ProbeDefinition(
    val version: Int = 1,
    val host: String = "www.cloudflare.com",
    val port: Int = 443,
    val path: String = "/cdn-cgi/trace",
    val connectIp: String? = null,
    val timeoutMs: Long = 5_000,
    val maxResponseBytes: Long = 65_536,
)

internal fun http3ConnectivityProfile() =
    DiagnosticsProfileDefinition(
        id = "http3-connectivity",
        name = "HTTP/3 connectivity",
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        http3Probe = Http3ProbeDefinition(),
    )

internal fun Http3ProbeDefinition.toJson() =
    buildJsonObject {
        put("version", version)
        put("host", host)
        put("port", port)
        put("path", path)
        connectIp?.let { put("connectIp", it) }
        put("timeoutMs", timeoutMs)
        put("maxResponseBytes", maxResponseBytes)
    }

internal fun DiagnosticsProfileDefinition.validateHttp3Probe() {
    val config = http3Probe ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground && executionPolicy.requiresRawPath)
    require(config == Http3ProbeDefinition()) { "HTTP/3 control endpoint must match the reviewed catalog" }
}
