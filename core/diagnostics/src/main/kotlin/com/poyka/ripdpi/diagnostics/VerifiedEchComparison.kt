package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent

const val EchTransferComparisonProfileId = "ech-transfer-comparison"
const val MaxEchComparisonBodyBytes = 32_768

data class VerifiedEchComparisonInput(
    val url: String = "",
    val connectIp: String = "",
    val expectedBodyBytes: String = "",
    val sha256: String = "",
) {
    fun isValid(): Boolean {
        val host = url.verifiedTransferUriOrNull()?.host ?: return false
        val isDnsHost = ':' !in host && NumericIp.parseAddress(host.removeSuffix(".")) == null
        val bytes = expectedBodyBytes.toIntOrNull()
        val validBody = bytes != null && bytes in 1..MaxEchComparisonBodyBytes && sha256.isVerifiedTransferSha256()
        return isDnsHost && connectIp.isUsableEchComparisonIp() && validBody
    }

    internal fun toTarget(label: String): ThroughputTarget {
        require(isValid()) { "Invalid ECH comparison input" }
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
}

private fun String.isUsableEchComparisonIp(): Boolean =
    NumericIp.parseAddress(this)?.toInetAddress()?.let { !it.isAnyLocalAddress && !it.isMulticastAddress } == true

internal fun DiagnosticsIntent.withVerifiedEchComparison(input: VerifiedEchComparisonInput?): DiagnosticsIntent {
    if (profileId != EchTransferComparisonProfileId) {
        require(input == null) { "ECH comparison input requires the ECH comparison profile" }
        return this
    }
    requireNotNull(input) { "ECH comparison requires an explicit endpoint and fixed IP" }
    val target = input.toTarget(displayName)
    return copy(throughputTargets = listOf(target), routeProbe = null)
}

internal fun ThroughputTarget.isValidVerifiedEchComparison(): Boolean {
    val integrity = verification ?: return false
    val usesEndpoint = connectIp == null && connectIps.isEmpty() && port == null
    val validInput =
        integrity.echComparisonIp?.let { address ->
            VerifiedEchComparisonInput(url, address, integrity.expectedBodyBytes.toString(), integrity.sha256).isValid()
        } == true
    return validInput && usesEndpoint && uploadBodyBytes == null && runs == 1 &&
        windowBytes == integrity.expectedBodyBytes + 1
}
