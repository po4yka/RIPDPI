package com.poyka.ripdpi.diagnostics.dpi

import android.content.Context
import com.poyka.ripdpi.diagnostics.dpich.IpRange
import com.poyka.ripdpi.diagnostics.dpich.SubnetMetadata
import com.poyka.ripdpi.diagnostics.dpich.WhitelistAsn
import com.poyka.ripdpi.diagnostics.rkn.RknTarget
import com.poyka.ripdpi.diagnostics.rkn.RknTargetListParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import javax.inject.Singleton

interface DpiAssetFileProvider {
    fun overrideFile(relativePath: String): File

    fun openAsset(relativePath: String): InputStream
}

class AndroidDpiAssetFileProvider(
    private val context: Context,
) : DpiAssetFileProvider {
    override fun overrideFile(relativePath: String): File = File(context.filesDir, relativePath)

    override fun openAsset(relativePath: String): InputStream = context.assets.open(relativePath)
}

class DpiAssetLoader(
    private val fileProvider: DpiAssetFileProvider,
    private val json: Json = Json,
) {
    constructor(context: Context) : this(AndroidDpiAssetFileProvider(context))

    private var cachedTcp16Targets: List<Tcp16Target>? = null
    private var cachedDomains: List<String>? = null
    private var cachedEchTargets: List<String>? = null
    private var cachedWhitelistSni: List<String>? = null
    private var cachedByohSyntheticDomains: List<String>? = null
    private var cachedDohProviderFilters: String? = null
    private var cachedObfs4Bridges: List<String>? = null
    private var cachedMeekFronts: List<String>? = null
    private var cachedCidrWhitelistedUrls: List<String>? = null
    private var cachedCidrRegularUrls: List<String>? = null
    private var cachedIpv4WhitelistAsns: List<WhitelistAsn>? = null
    private var cachedDohBootstrapSubnetMetadata: List<SubnetMetadata>? = null
    private var cachedRknWhitelistControl: List<RknTarget>? = null
    private var cachedRknBlacklistTest: List<RknTarget>? = null

    fun loadTcp16Targets(): List<Tcp16Target> =
        cachedTcp16Targets ?: loadText(Tcp16TargetsPath)
            .let(::parseTcp16Targets)
            .also { cachedTcp16Targets = it }

    fun loadDomains(): List<String> =
        cachedDomains ?: loadText(DomainsPath)
            .parseLineList()
            .also { cachedDomains = it }

    fun loadEchTargets(): List<String> =
        cachedEchTargets ?: loadText(EchTargetsPath)
            .parseLineList()
            .also { cachedEchTargets = it }

    fun loadWhitelistSni(): List<String> =
        cachedWhitelistSni ?: loadText(WhitelistSniPath)
            .parseLineList()
            .also { cachedWhitelistSni = it }

    fun loadByohSyntheticDomains(): List<String> =
        cachedByohSyntheticDomains ?: loadText(ByohSyntheticDomainsPath)
            .parseLineList()
            .also { cachedByohSyntheticDomains = it }

    fun loadDohProviderFiltersText(): String =
        cachedDohProviderFilters ?: loadText(DohProviderFiltersPath)
            .also { cachedDohProviderFilters = it }

    fun loadObfs4Bridges(): List<String> =
        cachedObfs4Bridges ?: loadText(Obfs4BridgesPath)
            .parseLineList()
            .also { cachedObfs4Bridges = it }

    fun loadMeekFronts(): List<String> =
        cachedMeekFronts ?: loadText(MeekFrontsPath)
            .parseLineList()
            .also { cachedMeekFronts = it }

    fun loadCidrWhitelistedUrls(): List<String> =
        cachedCidrWhitelistedUrls ?: loadText(CidrWhitelistedUrlsPath)
            .parseLineList()
            .also { cachedCidrWhitelistedUrls = it }

    fun loadCidrRegularUrls(): List<String> =
        cachedCidrRegularUrls ?: loadText(CidrRegularUrlsPath)
            .parseLineList()
            .also { cachedCidrRegularUrls = it }

    fun loadIpv4WhitelistAsns(): List<WhitelistAsn> =
        cachedIpv4WhitelistAsns ?: loadText(Ipv4WhitelistAsnsPath)
            .let(::parseIpv4WhitelistAsns)
            .also { cachedIpv4WhitelistAsns = it }

    fun loadDohBootstrapSubnetMetadata(): List<SubnetMetadata> =
        cachedDohBootstrapSubnetMetadata ?: loadText(DohBootstrapSubnetMetadataPath)
            .let(::parseDohBootstrapSubnetMetadata)
            .also { cachedDohBootstrapSubnetMetadata = it }

    fun loadRknWhitelistControl(): List<RknTarget> =
        cachedRknWhitelistControl ?: loadText(RknWhitelistControlPath)
            .let(RknTargetListParser::parse)
            .also { cachedRknWhitelistControl = it }

    fun loadRknBlacklistTest(): List<RknTarget> =
        cachedRknBlacklistTest ?: loadText(RknBlacklistTestPath)
            .let(RknTargetListParser::parse)
            .also { cachedRknBlacklistTest = it }

    private fun loadText(relativePath: String): String {
        val override = fileProvider.overrideFile(relativePath)
        if (override.isFile) return override.readText()
        return fileProvider.openAsset(relativePath).bufferedReader().use { reader -> reader.readText() }
    }

    private fun parseTcp16Targets(payload: String): List<Tcp16Target> =
        json
            .parseToJsonElement(payload)
            .jsonArray
            .map { element -> element.jsonObject.toTcp16Target() }

    private fun parseIpv4WhitelistAsns(payload: String): List<WhitelistAsn> =
        json
            .parseToJsonElement(payload)
            .jsonArray
            .map { element ->
                val item = element.jsonObject
                WhitelistAsn(
                    provider = item.requireString("provider"),
                    asn =
                        requireNotNull(item["asn"]) { "Missing required IPv4 whitelist ASN field 'asn'" }
                            .jsonPrimitive
                            .int,
                )
            }

    private fun parseDohBootstrapSubnetMetadata(payload: String): List<SubnetMetadata> =
        json
            .parseToJsonElement(payload)
            .jsonArray
            .map { element ->
                val item = element.jsonObject
                SubnetMetadata(
                    range = IpRange(item.requireString("cidr")),
                    asn =
                        requireNotNull(item["asn"]) { "Missing required DoH bootstrap metadata field 'asn'" }
                            .jsonPrimitive
                            .int,
                    org = item.requireString("org"),
                    country = item.requireString("country"),
                )
            }

    private fun JsonObject.toTcp16Target(): Tcp16Target =
        Tcp16Target(
            id = requireString("id"),
            asn = requireString("asn"),
            provider = requireString("provider"),
            ip = requireString("ip"),
            port = requirePort(),
            sni = this["sni"]?.jsonPrimitive?.contentOrNull,
        )

    private fun JsonObject.requireString(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) {
            "Missing required tcp16 target field '$name'"
        }

    private fun JsonObject.requirePort(): Int {
        val portElement = this["port"] ?: this[LegacyTypoPortField]
        return requireNotNull(portElement) {
            "Missing required tcp16 target field 'port'"
        }.jsonPrimitive.int
    }

    private fun String.parseLineList(): List<String> =
        lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith(CommentPrefix) }
            .toList()

    private companion object {
        private const val Tcp16TargetsPath = "dpi/tcp16.json"
        private const val DomainsPath = "dpi/domains.txt"
        private const val EchTargetsPath = "dpi/ech_targets.txt"
        private const val WhitelistSniPath = "dpi/whitelist_sni.txt"
        private const val ByohSyntheticDomainsPath = "dpich/byoh_synthetic_domains.txt"
        private const val DohProviderFiltersPath = "dpich/doh_provider_filters.yaml"
        private const val Obfs4BridgesPath = "dpich/obfs4_bridges.txt"
        private const val MeekFrontsPath = "dpich/meek_fronts.txt"
        private const val CidrWhitelistedUrlsPath = "dpich/cidr_whitelisted_urls.txt"
        private const val CidrRegularUrlsPath = "dpich/cidr_regular_urls.txt"
        private const val Ipv4WhitelistAsnsPath = "dpich/ipv4_whitelist_asns.json"
        private const val DohBootstrapSubnetMetadataPath = "dpich/doh_bootstrap_subnet_metadata.json"
        private const val RknWhitelistControlPath = "rkn/rkn_whitelist_control.txt"
        private const val RknBlacklistTestPath = "rkn/rkn_blacklist_test.txt"
        private const val LegacyTypoPortField = ",port"
        private const val CommentPrefix = "#"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DpiAssetLoaderModule {
    @Provides
    @Singleton
    fun provideDpiAssetLoader(
        @ApplicationContext context: Context,
    ): DpiAssetLoader = DpiAssetLoader(context)
}
