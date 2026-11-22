package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import java.net.URI

const val EchTransferComparisonProfileId = "ech-transfer-comparison"
const val Http3TransferComparisonProfileId = "http3-transfer-comparison"

internal const val MaxComparisonBodyBytes = 32_768
private const val MaxPinnedTransferPathLength = 1024

data class VerifiedTransferComparisonInput(
    val url: String = "",
    val connectIp: String = "",
    val expectedBodyBytes: String = "",
    val sha256: String = "",
) {
    fun isValidForEch(): Boolean {
        val host = url.verifiedTransferUriOrNull()?.host ?: return false
        val isDnsHost = ':' !in host && NumericIp.parseAddress(host.removeSuffix(".")) == null
        return isDnsHost && hasValidIntegrityAndPin()
    }

    fun isValidForHttp3(): Boolean =
        url.verifiedPinnedTransferUriOrNull() != null && hasValidIntegrityAndPin(excludeBroadcast = true)

    private fun hasValidIntegrityAndPin(excludeBroadcast: Boolean = false): Boolean {
        val bytes = expectedBodyBytes.toIntOrNull()
        val ip = NumericIp.parseAddress(connectIp)?.toInetAddress() ?: return false
        val validIp =
            !ip.isAnyLocalAddress && !ip.isMulticastAddress &&
                (!excludeBroadcast || ip.hostAddress != "255.255.255.255")
        return validIp && bytes != null && bytes in 1..MaxComparisonBodyBytes && sha256.isVerifiedTransferSha256()
    }

    internal fun toEchTarget(label: String): ThroughputTarget {
        require(isValidForEch()) { "Invalid ECH comparison input" }
        val bytes = expectedBodyBytes.toInt()
        return ThroughputTarget(
            id = EchTransferComparisonProfileId,
            label = label,
            url = url,
            windowBytes = bytes + 1,
            runs = 1,
            verification = DownloadVerification(bytes, sha256.lowercase(), connectIp),
        )
    }

    internal fun toHttp3Target(label: String): ThroughputTarget {
        require(isValidForHttp3()) { "Invalid HTTP/3 comparison input" }
        val bytes = expectedBodyBytes.toInt()
        return ThroughputTarget(
            id = Http3TransferComparisonProfileId,
            label = label,
            url = url,
            connectIp = connectIp,
            windowBytes = bytes + 1,
            runs = 1,
            verification = DownloadVerification(bytes, sha256.lowercase()),
        )
    }
}

internal fun DiagnosticsIntent.withVerifiedEchComparison(input: VerifiedTransferComparisonInput?): DiagnosticsIntent {
    if (profileId != EchTransferComparisonProfileId) {
        require(input == null) { "ECH comparison input requires the ECH comparison profile" }
        return this
    }
    requireNotNull(input) { "ECH comparison requires an explicit endpoint and fixed IP" }
    val target = input.toEchTarget(displayName)
    return copy(throughputTargets = listOf(target), routeProbe = null)
}

internal fun DiagnosticsIntent.withVerifiedHttp3Comparison(input: VerifiedTransferComparisonInput?): DiagnosticsIntent {
    if (profileId != Http3TransferComparisonProfileId) {
        require(input == null) { "HTTP/3 comparison input requires the HTTP/3 comparison profile" }
        return this
    }
    requireNotNull(input) { "HTTP/3 comparison requires an explicit endpoint and fixed IP" }
    val target = input.toHttp3Target(displayName)
    return copy(throughputTargets = listOf(target), routeProbe = null)
}

internal fun ThroughputTarget.isValidVerifiedEchComparison(): Boolean =
    connectIp == null && hasComparisonShape() &&
        verification?.echComparisonIp?.let { address -> comparisonInput(address).isValidForEch() } == true

internal fun ThroughputTarget.isValidVerifiedHttp3Comparison(): Boolean =
    id == Http3TransferComparisonProfileId && verification?.echComparisonIp == null && hasComparisonShape() &&
        connectIp?.let { address -> comparisonInput(address).isValidForHttp3() } == true

internal fun ThroughputTarget.hasComparisonShape(): Boolean =
    connectIps.isEmpty() && port == null && uploadBodyBytes == null && runs == 1 &&
        verification?.let { windowBytes == it.expectedBodyBytes + 1 } == true

private fun ThroughputTarget.comparisonInput(address: String): VerifiedTransferComparisonInput {
    val integrity = requireNotNull(verification)
    return VerifiedTransferComparisonInput(url, address, integrity.expectedBodyBytes.toString(), integrity.sha256)
}

internal fun String.verifiedPinnedTransferUriOrNull(): URI? {
    val uri = verifiedTransferUriOrNull() ?: return null
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    return uri.takeIf {
        '%' !in uri.host && !uri.host.endsWith('.') && path.length <= MaxPinnedTransferPathLength &&
            path.startsWith('/') && !path.startsWith("//") && path.all { char -> char in '!'..'~' && char != '\\' }
    }
}
