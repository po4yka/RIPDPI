package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.net.IDN
import java.net.URI
import java.util.Locale

const val SelectiveMatrixProfileId = "selective-availability-matrix"
const val SelectiveMatrixMaxUserHosts = 4
private const val MatrixMaxTargets = 10
private const val MatrixMaxRepetitions = 3
private const val MatrixMaxBodyBytes = 65_536
private const val MatrixMaxHeaderBytes = 16_384
private const val MatrixMaxResponseBudget = 2_097_152L
private const val MatrixMaxTimeoutMs = 10_000L
private const val MatrixMaxHostnameLength = 253
private const val MatrixMaxIdentifierLength = 128
private const val MatrixMaxLabelLength = 256
private const val MatrixMaxUrlLength = 2_048
private const val MatrixMaxDateLength = 64
private const val MatrixMaxConnectIps = 16
private const val MatrixMinTimeoutMs = 100L
private const val MatrixHttpsPort = 443

@Serializable
data class SelectiveMatrixTarget(
    val id: String,
    val label: String,
    val url: String,
    val cohort: String,
    val infrastructureGroup: String,
    val sourceUrl: String,
    val sourceDate: String,
    val lastVerifiedAt: String? = null,
    val connectIps: List<String> = emptyList(),
)

@Serializable
data class SelectiveMatrixConfig(
    @Required
    val version: Int = 1,
    val catalogVersion: String,
    val targets: List<SelectiveMatrixTarget>,
    val repetitions: Int = 2,
    val timeoutMs: Long = 5_000,
    val maxResponseBytes: Int = MatrixMaxBodyBytes,
) {
    internal fun validate() {
        require(version == 1 && catalogVersion.isNotBlank() && catalogVersion.length <= MatrixMaxIdentifierLength)
        require(targets.size in 1..MatrixMaxTargets)
        require(repetitions in 1..MatrixMaxRepetitions)
        require(timeoutMs in MatrixMinTimeoutMs..MatrixMaxTimeoutMs)
        require(maxResponseBytes in 1..MatrixMaxBodyBytes)
        require(
            targets.size.toLong() * repetitions * (maxResponseBytes + MatrixMaxHeaderBytes) <= MatrixMaxResponseBudget,
        )
        require(targets.map { it.id }.distinct().size == targets.size)
        require(targets.count { it.cohort == "user" } <= SelectiveMatrixMaxUserHosts)
        require(targets.map { URI(it.url).host?.lowercase(Locale.ROOT) }.distinct().size == targets.size)
        targets.forEach { target ->
            require(target.id.isNotBlank() && target.label.isNotBlank() && target.infrastructureGroup.isNotBlank())
            require(
                target.id.length <= MatrixMaxIdentifierLength && target.label.length <= MatrixMaxLabelLength &&
                    target.infrastructureGroup.length <= MatrixMaxIdentifierLength,
            )
            require(target.url.length <= MatrixMaxUrlLength && target.sourceUrl.length <= MatrixMaxUrlLength)
            require(
                target.sourceDate.length <= MatrixMaxDateLength &&
                    (target.lastVerifiedAt?.length ?: 0) <= MatrixMaxDateLength,
            )
            require(target.connectIps.size <= MatrixMaxConnectIps)
            require(target.cohort in MatrixCohorts)
            val uri = URI(target.url)
            require(uri.scheme == "https" && uri.port in setOf(-1, MatrixHttpsPort))
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            normalizeSelectiveMatrixHost(requireNotNull(uri.host))
        }
    }
}

/** Syntax validation only. The native runner resolves and rejects non-public destinations before connecting. */
fun normalizeSelectiveMatrixHost(input: String): String {
    val host = IDN.toASCII(input.trim(), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    require(host.length in 1..MatrixMaxHostnameLength && !host.endsWith('.')) { "Enter a public hostname" }
    val labels = host.split('.')
    require(labels.size >= 2 && labels.all { MatrixHostnameLabel.matches(it) }) { "Enter a public hostname" }
    require(labels.last().any(Char::isLetter) && labels.last() !in MatrixLocalSuffixes) { "Enter a public hostname" }
    return host
}

internal fun SelectiveMatrixConfig.withUserHosts(hosts: List<String>): SelectiveMatrixConfig {
    require(hosts.size <= SelectiveMatrixMaxUserHosts)
    val normalized = hosts.map(::normalizeSelectiveMatrixHost).distinct()
    val bundled = targets.filterNot { it.cohort == "user" }
    val existing = bundled.map { URI(it.url).host.lowercase(Locale.ROOT) }.toSet()
    require(normalized.none { it in existing }) { "A user target duplicates a bundled target" }
    return copy(
        targets =
            bundled +
                normalized.mapIndexed { index, host ->
                    SelectiveMatrixTarget(
                        id = "user-${index + 1}",
                        label = host,
                        url = "https://$host/",
                        cohort = "user",
                        infrastructureGroup = "user-unverified",
                        sourceUrl = "",
                        sourceDate = "",
                    )
                },
    ).also(SelectiveMatrixConfig::validate)
}

private val MatrixCohorts = setOf("declared_available", "domestic", "global", "user")
private val MatrixLocalSuffixes =
    setOf("localhost", "local", "internal", "lan", "home", "arpa", "test", "invalid", "onion")
private val MatrixHostnameLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
