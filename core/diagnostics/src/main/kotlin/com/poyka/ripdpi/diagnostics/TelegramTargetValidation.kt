package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NumericIp
import java.net.URI

private const val MaxEndpointPort = 65_535
private const val MaxMediaUrlBytes = 2_048
private const val MaxAsciiCode = 127
private const val MaxDnsNameLength = 253
private const val MaxTelegramUploadBytes = 16 * 1024 * 1024
private const val MaxTelegramTransferMillis = 60_000L
private const val MaxTelegramDcEndpoints = 32
private const val MaxTelegramDcLabelBytes = 64
private val TelegramDnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

// Keep input checks before permission preflight, which can resolve a host.
internal fun TelegramTarget.validateForScan(): URI {
    val media = parseTelegramMediaUrl(mediaUrl)
    require(
        uploadIp.isTelegramEndpointHost() && uploadPort in 1..MaxEndpointPort,
    ) { "Invalid Telegram upload endpoint" }
    require(uploadSizeBytes in 1..MaxTelegramUploadBytes) { "Invalid Telegram upload size" }
    require(
        totalTimeoutMs in 1L..MaxTelegramTransferMillis && stallTimeoutMs in 1L..totalTimeoutMs,
    ) { "Invalid Telegram transfer timeouts" }
    validateDcEndpoints(dcEndpoints)
    return media
}

private fun parseTelegramMediaUrl(url: String): URI {
    require(url.length in 1..MaxMediaUrlBytes && url.all { it.code <= MaxAsciiCode }) { "Invalid Telegram media URL" }
    val uri = URI(url)
    val host = requireNotNull(uri.host) { "Telegram media URL requires a host" }
    val suffix = uri.rawAuthority.removePrefix(host)
    val validHost =
        if (host.startsWith('[') && host.endsWith(']')) {
            NumericIp.parseAddress(host.removeSurrounding("[", "]"))?.isIPv6 == true
        } else {
            host.isTelegramEndpointHost()
        }
    val validPort =
        suffix.isEmpty() ||
            (suffix.startsWith(':') && suffix.drop(1).all { it in '0'..'9' } && uri.port in 1..MaxEndpointPort)
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            validHost &&
            validPort,
    ) { "Invalid Telegram HTTPS media endpoint" }
    return uri
}

private fun validateDcEndpoints(endpoints: List<TelegramDcEndpoint>) {
    require(endpoints.size <= MaxTelegramDcEndpoints) { "Too many Telegram DC endpoints" }
    val addresses = HashSet<Pair<String, Int>>()
    endpoints.forEach { endpoint ->
        val address = requireNotNull(NumericIp.parseAddress(endpoint.ip)) { "Invalid Telegram DC address" }
        require(endpoint.port in 1..MaxEndpointPort && addresses.add(address.toCanonicalString() to endpoint.port)) {
            "Invalid or duplicate Telegram DC endpoint"
        }
        require(
            endpoint.label.isNotBlank() &&
                endpoint.label.length <= MaxTelegramDcLabelBytes &&
                endpoint.label.encodeToByteArray().size <= MaxTelegramDcLabelBytes &&
                endpoint.label.none { it in ":|" || it.isISOControl() },
        ) { "Invalid Telegram DC label" }
    }
}

private fun String.isTelegramEndpointHost(): Boolean {
    if (NumericIp.parseAddress(this) != null) return true
    val name = removeSuffix(".")
    return name.length in 1..MaxDnsNameLength &&
        !name.substringAfterLast('.').all { it in '0'..'9' } &&
        name.split('.').all(TelegramDnsLabel::matches)
}
