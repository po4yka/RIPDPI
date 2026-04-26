package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticProfileWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsPackWire
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

fun interface BundledDiagnosticsProfileSource {
    fun readProfilesJson(): String
}

fun interface BundledDiagnosticsCatalogOverrideSource {
    fun readProfilesJsonOrNull(): String?
}

@Singleton
class AssetBundledDiagnosticsProfileSource
    @Inject
    constructor(
        @param:ApplicationContext
        private val context: Context,
    ) : BundledDiagnosticsProfileSource {
        override fun readProfilesJson(): String =
            context.assets
                .open("diagnostics/default_profiles.json")
                .bufferedReader()
                .use { it.readText() }
    }

@Singleton
class FileBundledDiagnosticsCatalogOverrideSource
    @Inject
    constructor(
        @param:ApplicationContext
        private val context: Context,
    ) : BundledDiagnosticsCatalogOverrideSource {
        override fun readProfilesJsonOrNull(): String? =
            File(context.filesDir, DiagnosticsCatalogOverrideRelativePath)
                .takeIf(File::isFile)
                ?.readText()
                ?.takeIf(String::isNotBlank)
    }

@Singleton
class BundledDiagnosticsProfileImporter
    @Inject
    constructor(
        private val profileSource: BundledDiagnosticsProfileSource,
        private val overrideSource: BundledDiagnosticsCatalogOverrideSource,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val clock: DiagnosticsHistoryClock,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun importProfiles() {
            val bundledCatalog = decodeCatalog(profileSource.readProfilesJson())
            val catalog =
                overrideSource
                    .readProfilesJsonOrNull()
                    ?.let(::decodeCatalog)
                    ?.let { overrideCatalog -> bundledCatalog.mergeOverride(overrideCatalog) }
                    ?: bundledCatalog
            val now = clock.now()
            catalog.packs.forEach { pack ->
                val persistedVersion = profileCatalog.getPackVersion(pack.id)
                if (persistedVersion == null || persistedVersion.version < pack.version) {
                    profileCatalog.upsertPackVersion(
                        TargetPackVersionEntity(
                            packId = pack.id,
                            version = pack.version,
                            importedAt = now,
                        ),
                    )
                }
            }
            catalog.profiles.forEach { profile ->
                val existingProfile = profileCatalog.getProfile(profile.id)
                if (existingProfile == null || existingProfile.version < profile.version) {
                    profileCatalog.upsertProfile(
                        DiagnosticProfileEntity(
                            id = profile.id,
                            name = profile.name,
                            source = "bundled",
                            version = profile.version,
                            requestJson =
                                json.encodeToString(
                                    com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
                                        .serializer(),
                                    profile.request,
                                ),
                            updatedAt = now,
                        ),
                    )
                }
            }
        }

        private fun decodeCatalog(payload: String): BundledDiagnosticsCatalogWire =
            json.decodeFromString(BundledDiagnosticsCatalogWire.serializer(), payload)
    }

@dagger.Module
@InstallIn(SingletonComponent::class)
abstract class BundledDiagnosticsProfileSourceModule {
    @Binds
    @Singleton
    abstract fun bindBundledDiagnosticsProfileSource(
        source: AssetBundledDiagnosticsProfileSource,
    ): BundledDiagnosticsProfileSource

    @Binds
    @Singleton
    abstract fun bindBundledDiagnosticsCatalogOverrideSource(
        source: FileBundledDiagnosticsCatalogOverrideSource,
    ): BundledDiagnosticsCatalogOverrideSource
}

internal object EmptyBundledDiagnosticsCatalogOverrideSource : BundledDiagnosticsCatalogOverrideSource {
    override fun readProfilesJsonOrNull(): String? = null
}

private const val DiagnosticsCatalogOverrideRelativePath = "diagnostics/default_profiles.override.json"

private fun BundledDiagnosticsCatalogWire.mergeOverride(
    overrideCatalog: BundledDiagnosticsCatalogWire,
): BundledDiagnosticsCatalogWire {
    require(schemaVersion == overrideCatalog.schemaVersion) {
        "Diagnostics catalog override schema ${overrideCatalog.schemaVersion}" +
            " does not match bundled schema $schemaVersion"
    }
    return copy(
        generatedAt = overrideCatalog.generatedAt ?: generatedAt,
        packs = packs.upsertById(overrideCatalog.packs, BundledDiagnosticsPackWire::id),
        profiles = profiles.upsertById(overrideCatalog.profiles, BundledDiagnosticProfileWire::id),
    )
}

private fun <T, K> List<T>.upsertById(
    overrides: List<T>,
    keySelector: (T) -> K,
): List<T> =
    overrides.fold(this) { items, item ->
        val key = keySelector(item)
        items.filterNot { keySelector(it) == key } + item
    }
