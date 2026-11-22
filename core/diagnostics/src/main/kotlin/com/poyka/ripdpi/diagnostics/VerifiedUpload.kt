package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent

const val VerifiedUploadProfileId = "verified-https-upload"
private const val MaxVerifiedUploadBodyBytes = 1_048_576

data class VerifiedUploadInput(
    val url: String = "",
    val bodyBytes: String = "",
) {
    fun isValid(): Boolean {
        val bytes = bodyBytes.toIntOrNull() ?: return false
        return bytes in 1..MaxVerifiedUploadBodyBytes && url.isVerifiedTransferUrl()
    }

    internal fun toTarget(label: String): ThroughputTarget {
        require(isValid()) { "Invalid verified upload input" }
        val bytes = bodyBytes.toInt()
        return ThroughputTarget(
            id = VerifiedUploadProfileId,
            label = label,
            url = url,
            windowBytes = bytes,
            runs = 1,
            uploadBodyBytes = bytes,
        )
    }
}

internal fun DiagnosticsIntent.withVerifiedUpload(input: VerifiedUploadInput?): DiagnosticsIntent {
    if (profileId != VerifiedUploadProfileId) {
        require(input == null) { "Verified upload input requires the verified upload profile" }
        return this
    }
    val target = requireNotNull(input) { "Verified upload requires a user-supplied target" }.toTarget(displayName)
    return copy(throughputTargets = listOf(target), routeProbe = null)
}

internal fun ThroughputTarget.isValidVerifiedUpload(): Boolean {
    val bytes = uploadBodyBytes ?: return false
    val usesEndpoint = connectIp == null && connectIps.isEmpty() && port == null
    return usesEndpoint && verification == null && runs == 1 && windowBytes == bytes &&
        VerifiedUploadInput(url, bytes.toString()).isValid()
}
