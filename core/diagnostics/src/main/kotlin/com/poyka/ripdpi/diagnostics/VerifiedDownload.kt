package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

const val VerifiedDownloadProfileId = "verified-https-download"
const val MaxVerifiedDownloadBodyBytes = 1_048_576
private const val MaxVerifiedDownloadUrlLength = 2048
private val VerifiedDownloadSha256 = Regex("[0-9a-fA-F]{64}")

data class VerifiedDownloadInput(
    val url: String = "",
    val expectedBodyBytes: String = "",
    val sha256: String = "",
) {
    fun isValid(): Boolean {
        val bytes = expectedBodyBytes.toIntOrNull()
        return bytes != null && bytes in 1..MaxVerifiedDownloadBodyBytes &&
            VerifiedDownloadSha256.matches(sha256) && url.isVerifiedTransferUrl()
    }

    internal fun toTarget(label: String): ThroughputTarget {
        require(isValid()) { "Invalid verified download input" }
        val bytes = expectedBodyBytes.toInt()
        return ThroughputTarget(
            id = VerifiedDownloadProfileId,
            label = label,
            url = url,
            windowBytes = bytes + 1,
            runs = 1,
            verification = DownloadVerification(bytes, sha256.lowercase()),
        )
    }
}

@Serializable
data class DownloadVerification(
    val expectedBodyBytes: Int,
    val sha256: String,
)

internal fun String.isVerifiedTransferUrl(): Boolean {
    val fitsUrlLimit =
        length in 1..MaxVerifiedDownloadUrlLength && encodeToByteArray().size <= MaxVerifiedDownloadUrlLength
    if (!fitsUrlLimit || any(Char::isISOControl)) return false
    return try {
        val uri = URI(this)
        val validAuthority =
            !uri.host.isNullOrBlank() && uri.rawAuthority?.endsWith(':') == false &&
                (uri.port == -1 || uri.port in 1..IpProbeMaxPort)
        val noExtraParts = uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        uri.scheme == "https" && validAuthority && noExtraParts
    } catch (_: URISyntaxException) {
        false
    }
}

internal fun DiagnosticsIntent.withVerifiedDownload(input: VerifiedDownloadInput?): DiagnosticsIntent {
    if (profileId != VerifiedDownloadProfileId) {
        require(input == null) { "Verified download input requires the verified download profile" }
        return this
    }
    val target = requireNotNull(input) { "Verified download requires a user-supplied target" }.toTarget(displayName)
    return copy(throughputTargets = listOf(target), routeProbe = null)
}

internal fun ThroughputTarget.isValidVerifiedDownload(): Boolean {
    val integrity = verification ?: return false
    val usesEndpoint = connectIp == null && connectIps.isEmpty() && port == null
    return VerifiedDownloadInput(url, integrity.expectedBodyBytes.toString(), integrity.sha256).isValid() &&
        usesEndpoint && uploadBodyBytes == null && runs == 1 && windowBytes == integrity.expectedBodyBytes + 1
}
