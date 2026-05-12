package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.InetAddress

enum class BootstrapVerdict {
    OK,
    SPOOFED,
    RESOLVE_FAILED,
}

data class DohProviderFilter(
    val providerName: String,
    val dohHostname: String,
    val expectedFilter: String,
)

data class DohBootstrapResult(
    val providerName: String,
    val dohHostname: String,
    val bootstrapIp: String?,
    val discoveredAsn: Int?,
    val discoveredOrg: String?,
    val verdict: BootstrapVerdict,
    val expectedFilter: String,
)

fun interface DohBootstrapResolver {
    suspend fun resolve(hostname: String): List<String>
}

object SystemOnlyDohBootstrapResolver : DohBootstrapResolver {
    override suspend fun resolve(hostname: String): List<String> =
        InetAddress
            .getAllByName(hostname)
            .mapNotNull { address -> address.hostAddress }
}

class DohBootstrapSpoofingDetector(
    private val resolver: DohBootstrapResolver = SystemOnlyDohBootstrapResolver,
    private val metadata: SubnetMetadataLookup,
    private val providers: List<DohProviderFilter>,
) {
    suspend fun checkAll(): Map<String, DohBootstrapResult> =
        coroutineScope {
            providers
                .map { provider -> async { provider.providerName to check(provider) } }
                .awaitAll()
                .toMap()
        }

    private suspend fun check(provider: DohProviderFilter): DohBootstrapResult =
        try {
            val bootstrapIp = resolver.resolve(provider.dohHostname).firstOrNull()
            if (bootstrapIp == null) {
                provider.result(BootstrapVerdict.RESOLVE_FAILED)
            } else {
                provider.classify(bootstrapIp)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            provider.result(BootstrapVerdict.RESOLVE_FAILED)
        }

    private suspend fun DohProviderFilter.classify(bootstrapIp: String): DohBootstrapResult {
        val discoveredAsn = metadata.asnForIp(bootstrapIp)
        val discoveredOrg = metadata.orgTermsForIp(bootstrapIp).firstOrNull()
        val expectedFilterAst = SubnetFilterDsl.parse(expectedFilter)
        val discoveredRange = metadata.subnetForIp(bootstrapIp)
        val matchesIpMetadata = metadata.matchesFilterForIp(bootstrapIp, expectedFilterAst)
        val expectedRanges = SubnetFilterEvaluator(metadata).evaluate(expectedFilterAst)
        val verdict =
            if (matchesIpMetadata || (discoveredRange != null && discoveredRange in expectedRanges)) {
                BootstrapVerdict.OK
            } else {
                BootstrapVerdict.SPOOFED
            }
        return result(
            verdict = verdict,
            bootstrapIp = bootstrapIp,
            discoveredAsn = discoveredAsn,
            discoveredOrg = discoveredOrg,
        )
    }

    private fun DohProviderFilter.result(
        verdict: BootstrapVerdict,
        bootstrapIp: String? = null,
        discoveredAsn: Int? = null,
        discoveredOrg: String? = null,
    ): DohBootstrapResult =
        DohBootstrapResult(
            providerName = providerName,
            dohHostname = dohHostname,
            bootstrapIp = bootstrapIp,
            discoveredAsn = discoveredAsn,
            discoveredOrg = discoveredOrg,
            verdict = verdict,
            expectedFilter = expectedFilter,
        )
}

fun DpiAssetLoader.loadDohProviderFilters(): List<DohProviderFilter> =
    loadDohProviderFiltersText().parseDohProviderFilters()

internal fun String.parseDohProviderFilters(): List<DohProviderFilter> {
    val providers = mutableListOf<MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null
    lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
        .forEach { line ->
            when {
                line.startsWith("- ") -> {
                    val provider = mutableMapOf<String, String>().also(providers::add)
                    current = provider
                    provider.putLine(line.removePrefix("- "))
                }

                else -> {
                    current?.putLine(line)
                }
            }
        }
    return providers.map { values ->
        DohProviderFilter(
            providerName = requireNotNull(values["name"]) { "Missing DoH provider name" },
            dohHostname = requireNotNull(values["dohHostname"]) { "Missing DoH provider hostname" },
            expectedFilter = requireNotNull(values["expectedFilter"]) { "Missing DoH provider filter" },
        )
    }
}

private fun MutableMap<String, String>.putLine(line: String) {
    val key = line.substringBefore(':').trim()
    val value = line.substringAfter(':', missingDelimiterValue = "").trim().trim('"')
    if (key.isNotBlank() && value.isNotBlank()) {
        put(key, value)
    }
}
