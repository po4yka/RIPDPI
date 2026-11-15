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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.buffer
import okio.source
import java.io.File
import java.io.InputStream
import javax.inject.Singleton

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
private const val Tcp16CatalogMaxBytes = 256L * 1_024
private const val Tcp16CatalogMaxEntries = 512

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
    private var cachedDpiSuiteTargets: DpiSuiteTargets? = null
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
        cachedTcp16Targets ?: loadTcp16Catalog(Tcp16TargetsPath)
            .let(json::parseTcp16Targets)
            .also { cachedTcp16Targets = it }

    fun loadDomains(): List<String> = loadDpiSuiteTargets().domains

    fun loadDpiSuiteTargets(): DpiSuiteTargets =
        cachedDpiSuiteTargets ?: DpiSuiteTargets
            .fromEntries(loadText(DomainsPath).parseLineList())
            .also { cachedDpiSuiteTargets = it }

    fun loadEchTargets(): List<String> =
        cachedEchTargets ?: loadText(EchTargetsPath)
            .parseLineList()
            .also { cachedEchTargets = it }

    fun loadWhitelistSni(): List<String> =
        cachedWhitelistSni ?: loadTcp16Catalog(WhitelistSniPath)
            .parseLineList()
            .also { entries -> require(entries.size <= Tcp16CatalogMaxEntries) { "Too many TCP16 SNI entries" } }
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
            .let(json::parseIpv4WhitelistAsns)
            .also { cachedIpv4WhitelistAsns = it }

    fun loadDohBootstrapSubnetMetadata(): List<SubnetMetadata> =
        cachedDohBootstrapSubnetMetadata ?: loadText(DohBootstrapSubnetMetadataPath)
            .let(json::parseDohBootstrapSubnetMetadata)
            .also { cachedDohBootstrapSubnetMetadata = it }

    fun loadRknWhitelistControl(): List<RknTarget> =
        cachedRknWhitelistControl ?: loadText(RknWhitelistControlPath)
            .let(RknTargetListParser::parse)
            .also { cachedRknWhitelistControl = it }

    fun loadRknBlacklistTest(): List<RknTarget> =
        cachedRknBlacklistTest ?: loadText(RknBlacklistTestPath)
            .let(RknTargetListParser::parse)
            .also { cachedRknBlacklistTest = it }

    private fun loadText(relativePath: String): String =
        open(relativePath).bufferedReader().use { reader -> reader.readText() }

    private fun open(relativePath: String): InputStream {
        val override = fileProvider.overrideFile(relativePath)
        return if (override.isFile) override.inputStream() else fileProvider.openAsset(relativePath)
    }

    private fun loadTcp16Catalog(relativePath: String): String =
        open(relativePath).source().buffer().use { source ->
            require(!source.request(Tcp16CatalogMaxBytes + 1L)) { "TCP16 catalog exceeds the size limit" }
            source.readUtf8()
        }

    private fun String.parseLineList(): List<String> =
        lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith(CommentPrefix) }
            .toList()
}

private fun Json.parseTcp16Targets(payload: String): List<Tcp16Target> =
    parseToJsonElement(payload)
        .jsonArray
        .also { entries -> require(entries.size <= Tcp16CatalogMaxEntries) { "Too many TCP16 targets" } }
        .mapIndexed { index, element ->
            try {
                element.jsonObject.toTcp16Target()
            } catch (_: IllegalArgumentException) {
                Tcp16Target(id = "entry ${index + 1}", asn = "", provider = "", ip = "", port = 0, sni = null)
            }
        }

private fun Json.parseIpv4WhitelistAsns(payload: String): List<WhitelistAsn> =
    parseToJsonElement(payload)
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

private fun Json.parseDohBootstrapSubnetMetadata(payload: String): List<SubnetMetadata> =
    parseToJsonElement(payload)
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

private fun JsonObject.toTcp16Target(): Tcp16Target {
    val port = requirePort()
    val scheme = optionalTcp16String("scheme")
    return Tcp16Target(
        id = requireTcp16String("id"),
        asn = requireTcp16String("asn"),
        provider = requireTcp16String("provider"),
        ip = requireTcp16String("ip"),
        port = port,
        sni = optionalTcp16String("sni"),
        transport = if (scheme == null) Tcp16Transport.forDefaultPort(port) else Tcp16Transport.fromScheme(scheme),
    )
}

private fun JsonObject.optionalTcp16String(name: String): String? =
    this[name]?.let { value ->
        val primitive = value.jsonPrimitive
        if (primitive.contentOrNull == null) return@let null
        require(primitive.isString) { "Invalid TCP16 field '$name'" }
        primitive.content
    }

private fun JsonObject.requireTcp16String(name: String): String =
    requireNotNull(optionalTcp16String(name)) { "Missing TCP16 field '$name'" }

private fun JsonObject.requireString(name: String): String =
    requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) {
        "Missing required tcp16 target field '$name'"
    }

private fun JsonObject.requirePort(): Int {
    val portElement = this["port"] ?: this[LegacyTypoPortField]
    val port = portElement as? JsonPrimitive
    require(port != null && !port.isString) { "Invalid TCP16 port" }
    return port.int
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
