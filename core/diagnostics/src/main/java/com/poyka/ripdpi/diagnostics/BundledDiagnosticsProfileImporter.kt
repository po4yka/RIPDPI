package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.diagnostics.contract.profile.BundledDiagnosticsCatalogWire
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

fun interface BundledDiagnosticsProfileSource {
    fun readProfilesJson(): String
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
class BundledDiagnosticsProfileImporter
    @Inject
    constructor(
        private val profileSource: BundledDiagnosticsProfileSource,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val clock: DiagnosticsHistoryClock,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun importProfiles() {
            val catalog = decodeCatalog(profileSource.readProfilesJson())
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
                                    com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire.serializer(),
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
}
