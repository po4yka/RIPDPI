import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class Http3ProbeDefinition(
    val version: Int = 1,
    val host: String = "www.google.com",
    val port: Int = 443,
    val path: String = "/robots.txt",
    val connectIp: String? = null,
    val timeoutMs: Long = 5_000,
    val maxResponseBytes: Long = 65_536,
)

internal val Http3CloudflareProbe = Http3ProbeDefinition(host = "www.cloudflare.com", path = "/cdn-cgi/trace")

private val ReviewedHttp3Probes = setOf(Http3ProbeDefinition(), Http3CloudflareProbe)

internal fun http3ConnectivityProfile() =
    DiagnosticsProfileDefinition(
        id = "http3-connectivity",
        name = "HTTP/3 connectivity",
        version = 2,
        family = CatalogDiagnosticProfileFamily.GENERAL,
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = true),
        http3Probe = Http3ProbeDefinition(),
    )

internal fun http3CloudflareConnectivityProfile() =
    http3ConnectivityProfile().copy(
        id = "http3-connectivity-cloudflare",
        version = 1,
        name = "HTTP/3 connectivity (Cloudflare)",
        http3Probe = Http3CloudflareProbe,
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
    require(config in ReviewedHttp3Probes) { "HTTP/3 control endpoint must match the reviewed catalog" }
}
