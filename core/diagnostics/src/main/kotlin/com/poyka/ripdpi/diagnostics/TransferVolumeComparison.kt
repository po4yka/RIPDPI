package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import java.net.InetAddress
import java.net.URI

const val TransferVolumeComparisonProfileId = "transfer-volume-comparison"

private const val ShortTargetId = "transfer-volume-short"
private const val LongTargetId = "transfer-volume-long"
private const val HttpsDefaultPort = 443

data class TransferVolumeComparisonInput(
    val connectIp: String = "",
    val short: VerifiedDownloadInput = VerifiedDownloadInput(),
    val long: VerifiedDownloadInput = VerifiedDownloadInput(),
) {
    fun isValid(): Boolean {
        val address = NumericIp.parseAddress(connectIp) ?: return false
        val ip = InetAddress.getByAddress(address.bytes)
        val validPin = !ip.isAnyLocalAddress && !ip.isMulticastAddress && ip.hostAddress != "255.255.255.255"
        return validPin && hasComparablePaths() && hasValidProofs()
    }

    private fun hasComparablePaths(): Boolean {
        val shortUri = short.url.verifiedPinnedTransferUriOrNull()
        val longUri = long.url.verifiedPinnedTransferUriOrNull()
        return shortUri != null && longUri != null && shortUri.isSameOriginWithDistinctPath(longUri)
    }

    private fun hasValidProofs(): Boolean {
        val shortBytes = short.expectedBodyBytes.toIntOrNull() ?: 0
        val longBytes = long.expectedBodyBytes.toIntOrNull() ?: 0
        val validSizes =
            shortBytes in 1..MaxComparisonBodyBytes && longBytes in (shortBytes + 1)..MaxVerifiedDownloadBodyBytes
        return validSizes && short.sha256.isVerifiedTransferSha256() && long.sha256.isVerifiedTransferSha256()
    }

    internal fun toTargets(): List<ThroughputTarget> {
        require(isValid()) { "Transfer volume comparison requires two verified objects on one pinned HTTPS origin" }
        return listOf(
            short.toTarget("Short response").copy(id = ShortTargetId, connectIp = connectIp),
            long.toTarget("Long response").copy(id = LongTargetId, connectIp = connectIp),
        )
    }
}

private fun URI.isSameOriginWithDistinctPath(other: URI): Boolean =
    host.equals(other.host, ignoreCase = true) && effectiveHttpsPort() == other.effectiveHttpsPort() &&
        rawPath.orEmpty().ifEmpty { "/" } != other.rawPath.orEmpty().ifEmpty { "/" }

private fun URI.effectiveHttpsPort(): Int = if (port == -1) HttpsDefaultPort else port

internal fun DiagnosticsIntent.withTransferVolumeComparison(input: TransferVolumeComparisonInput?): DiagnosticsIntent {
    if (profileId != TransferVolumeComparisonProfileId) {
        require(input == null) { "Transfer volume comparison input requires the volume comparison profile" }
        return this
    }
    val targets = requireNotNull(input) { "Transfer volume comparison requires two user-supplied targets" }.toTargets()
    return copy(throughputTargets = targets, routeProbe = null)
}

internal fun List<ThroughputTarget>.isValidTransferVolumeComparison(): Boolean {
    if (size != 2 || this[0].id != ShortTargetId || this[1].id != LongTargetId) return false
    val (short, long) = this
    val pin = short.connectIp
    val shortInput = short.volumeInputOrNull()
    val longInput = long.volumeInputOrNull()
    val input =
        if (pin != null && shortInput != null && longInput != null) {
            TransferVolumeComparisonInput(pin, shortInput, longInput)
        } else {
            null
        }
    return pin == long.connectIp && input?.isValid() == true
}

private fun ThroughputTarget.volumeInputOrNull(): VerifiedDownloadInput? {
    val proof = verification?.takeIf { it.echComparisonIp == null } ?: return null
    return VerifiedDownloadInput(url, proof.expectedBodyBytes.toString(), proof.sha256).takeIf { hasComparisonShape() }
}
