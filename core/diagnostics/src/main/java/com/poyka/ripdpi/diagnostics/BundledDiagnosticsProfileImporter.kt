package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import dagger.Binds
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.builtins.ListSerializer
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
            val bundledProfiles =
                json.decodeFromString(
                    ListSerializer(BundledDiagnosticProfile.serializer()),
                    profileSource.readProfilesJson(),
                )
            bundledProfiles.forEach { profile ->
                val packVersion = profileCatalog.getPackVersion(profile.id)
                if (packVersion == null || packVersion.version < profile.version) {
                    val now = clock.now()
                    profileCatalog.upsertProfile(
                        DiagnosticProfileEntity(
                            id = profile.id,
                            name = profile.name,
                            source = "bundled",
                            version = profile.version,
                            requestJson = json.encodeToString(ScanRequest.serializer(), profile.request),
                            updatedAt = now,
                        ),
                    )
                    profileCatalog.upsertPackVersion(
                        TargetPackVersionEntity(
                            packId = profile.id,
                            version = profile.version,
                            importedAt = now,
                        ),
                    )
                }
            }
        }
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
