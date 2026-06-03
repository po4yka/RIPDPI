package com.poyka.ripdpi.assets

import android.content.Context
import com.poyka.ripdpi.data.DnsProviderCatalog
import com.poyka.ripdpi.data.DnsProviderCatalogEntry
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.DnsProviderDefinition
import com.poyka.ripdpi.data.dnsProviderCatalogFromJson
import com.poyka.ripdpi.data.toDnsProviderDefinition
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.InputStream
import javax.inject.Singleton

private const val DnsProvidersAssetPath = "dns/dns-providers.json"

/**
 * Stable sentinel id for the user-defined "custom" resolver. It has no bundled bootstrap and is
 * always offered as a selectable preset alongside the bundled catalog entries.
 */
const val DnsProviderCatalogCustomId = DnsProviderCustom

/**
 * A selectable DNS resolver preset surfaced in settings UI: either a bundled catalog [entry] or
 * the user-defined custom sentinel (when [entry] is null).
 */
data class DnsProviderPreset(
    val id: String,
    val entry: DnsProviderCatalogEntry?,
) {
    val isCustom: Boolean
        get() = entry == null
}

/**
 * Abstracts asset + filesDir-override access for the DNS provider catalog, mirroring
 * [com.poyka.ripdpi.diagnostics.dpi.DpiAssetFileProvider] so a downloaded override can shadow the
 * bundled asset without changing the loader.
 */
interface DnsProviderCatalogFileProvider {
    fun overrideFile(relativePath: String): File

    fun openAsset(relativePath: String): InputStream
}

class AndroidDnsProviderCatalogFileProvider(
    private val context: Context,
) : DnsProviderCatalogFileProvider {
    override fun overrideFile(relativePath: String): File = File(context.filesDir, relativePath)

    override fun openAsset(relativePath: String): InputStream = context.assets.open(relativePath)
}

/**
 * Loads the bundled DNS provider catalog asset, parses it with kotlinx serialization, and caches the
 * result. Mirrors [com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader]: a filesDir override at the same
 * relative path shadows the bundled asset when present.
 */
class DnsProviderCatalogLoader(
    private val fileProvider: DnsProviderCatalogFileProvider,
) {
    constructor(context: Context) : this(AndroidDnsProviderCatalogFileProvider(context))

    private var cachedCatalog: DnsProviderCatalog? = null

    /** Parsed catalog wrapper (`{ "schema", "providers" }`), cached after first load. */
    fun loadCatalog(): DnsProviderCatalog =
        cachedCatalog ?: dnsProviderCatalogFromJson(loadText(DnsProvidersAssetPath))
            .also { cachedCatalog = it }

    /** All bundled provider entries, in declaration order. */
    fun entries(): List<DnsProviderCatalogEntry> = loadCatalog().providers

    /** Looks up a bundled provider entry by its stable id. */
    fun byId(id: String): DnsProviderCatalogEntry? = loadCatalog().byId(id)

    /** Maps a bundled entry onto the legacy [DnsProviderDefinition] used by the DNS settings pipeline. */
    fun definitionById(id: String): DnsProviderDefinition? = byId(id)?.toDnsProviderDefinition()

    /**
     * Selectable presets for settings UI: every bundled entry followed by the user-defined custom
     * sentinel, which carries no bundled entry.
     */
    fun presets(): List<DnsProviderPreset> =
        entries().map { entry -> DnsProviderPreset(id = entry.id, entry = entry) } +
            DnsProviderPreset(id = DnsProviderCatalogCustomId, entry = null)

    private fun loadText(relativePath: String): String {
        val override = fileProvider.overrideFile(relativePath)
        if (override.isFile) return override.readText()
        return fileProvider.openAsset(relativePath).bufferedReader().use { reader -> reader.readText() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DnsProviderCatalogLoaderModule {
    @Provides
    @Singleton
    fun provideDnsProviderCatalogLoader(
        @ApplicationContext context: Context,
    ): DnsProviderCatalogLoader = DnsProviderCatalogLoader(context)
}
