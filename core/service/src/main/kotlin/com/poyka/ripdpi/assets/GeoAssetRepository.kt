package com.poyka.ripdpi.assets

import android.content.Context
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.assets.AssetProvider
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.data.assets.GeoAssetRepo
import com.poyka.ripdpi.data.assets.assetProviderById
import com.poyka.ripdpi.data.assets.customAssetDownloadUrl
import com.poyka.ripdpi.data.assets.githubLatestReleaseApiUrl
import com.poyka.ripdpi.data.assets.githubReleaseAssetDownloadUrl
import com.poyka.ripdpi.data.assets.isAssetUpdateAvailable
import com.poyka.ripdpi.data.assets.isPlausibleGeoAssetPayload
import com.poyka.ripdpi.data.assets.parseGithubLatestReleaseTag
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a geo-asset update check / apply for one provider. */
data class GeoAssetUpdateResult(
    val providerId: String,
    val geoipUpdated: Boolean,
    val geositeUpdated: Boolean,
    val geoipTag: String?,
    val geositeTag: String?,
    val anyChecked: Boolean,
) {
    val updatedAny: Boolean get() = geoipUpdated || geositeUpdated
}

/** Raised when a downloaded asset fails the fail-closed validity gate. */
class GeoAssetIntegrityException(
    message: String,
) : IOException(message)

interface GeoAssetRepository {
    /**
     * Checks the active provider's GitHub Releases `latest` tag for geoip and geosite, downloads and
     * atomically swaps each `.db` only when the remote tag differs from the stored tag, then persists
     * the new tags. The live native swap is deferred to next service start (no reload JNI exists; see
     * the asset-provider follow-up task).
     */
    suspend fun checkAndUpdate(): GeoAssetUpdateResult

    /** Copies a user-picked local `.db` ([bytes]) into the asset dir for [kind], validating first. */
    suspend fun importLocalAsset(
        kind: GeoAssetKind,
        bytes: ByteArray,
    )
}

@Singleton
class DefaultGeoAssetRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: AppSettingsRepository,
        private val downloadService: GeoAssetDownloadService,
    ) : GeoAssetRepository {
        override suspend fun checkAndUpdate(): GeoAssetUpdateResult =
            withContext(Dispatchers.IO) {
                val settings = settingsRepository.settings.first()
                val provider = assetProviderById(settings.geoAssetProviderId)
                val geoipResult =
                    updateOne(
                        provider = provider,
                        kind = GeoAssetKind.Geoip,
                        repo = provider.geoipRepo,
                        customBaseUrl = settings.geoAssetCustomBaseUrl,
                        storedTag = settings.geoAssetGeoipVersionTag,
                    )
                val geositeResult =
                    updateOne(
                        provider = provider,
                        kind = GeoAssetKind.Geosite,
                        repo = provider.geositeRepo,
                        customBaseUrl = settings.geoAssetCustomBaseUrl,
                        storedTag = settings.geoAssetGeositeVersionTag,
                    )

                persistTagsAndTimestamp(geoipResult, geositeResult)

                GeoAssetUpdateResult(
                    providerId = provider.id,
                    geoipUpdated = geoipResult.updated,
                    geositeUpdated = geositeResult.updated,
                    geoipTag = geoipResult.newTag,
                    geositeTag = geositeResult.newTag,
                    anyChecked = geoipResult.checked || geositeResult.checked,
                )
            }

        override suspend fun importLocalAsset(
            kind: GeoAssetKind,
            bytes: ByteArray,
        ) {
            withContext(Dispatchers.IO) {
                if (!isPlausibleGeoAssetPayload(bytes)) {
                    throw GeoAssetIntegrityException("Imported geo asset failed the validity gate.")
                }
                atomicWrite(targetFile(kind), bytes)
                settingsRepository.update {
                    geoAssetLastUpdatedEpochMillis = System.currentTimeMillis()
                }
            }
        }

        private suspend fun updateOne(
            provider: AssetProvider,
            kind: GeoAssetKind,
            repo: GeoAssetRepo?,
            customBaseUrl: String,
            storedTag: String,
        ): SingleAssetOutcome {
            // Custom provider: no Releases API tag, so download unconditionally from the base URL.
            if (provider.isCustom) {
                val base = customBaseUrl.trim()
                if (base.isEmpty()) {
                    return SingleAssetOutcome(checked = false)
                }
                val bytes = downloadService.downloadAsset(customAssetDownloadUrl(base, kind))
                applyValidatedAsset(kind, bytes)
                return SingleAssetOutcome(checked = true, updated = true, newTag = CustomTagSentinel)
            }

            val resolvedRepo = repo ?: return SingleAssetOutcome(checked = false)
            val remoteTag =
                parseGithubLatestReleaseTag(
                    downloadService.fetchLatestReleaseJson(githubLatestReleaseApiUrl(resolvedRepo)),
                )
            if (!isAssetUpdateAvailable(storedTag = storedTag, remoteTag = remoteTag)) {
                return SingleAssetOutcome(checked = true, updated = false, newTag = storedTag.ifEmpty { remoteTag })
            }

            val tag = requireNotNull(remoteTag)
            val bytes = downloadService.downloadAsset(githubReleaseAssetDownloadUrl(resolvedRepo, tag))
            applyValidatedAsset(kind, bytes)
            return SingleAssetOutcome(checked = true, updated = true, newTag = tag)
        }

        private fun applyValidatedAsset(
            kind: GeoAssetKind,
            bytes: ByteArray,
        ) {
            if (!isPlausibleGeoAssetPayload(bytes)) {
                throw GeoAssetIntegrityException("Downloaded ${kind.name} asset failed the validity gate.")
            }
            atomicWrite(targetFile(kind), bytes)
        }

        private suspend fun persistTagsAndTimestamp(
            geoip: SingleAssetOutcome,
            geosite: SingleAssetOutcome,
        ) {
            if (!geoip.updated && !geosite.updated) {
                return
            }
            settingsRepository.update {
                geoip.newTag?.let { if (geoip.updated) geoAssetGeoipVersionTag = it }
                geosite.newTag?.let { if (geosite.updated) geoAssetGeositeVersionTag = it }
                geoAssetLastUpdatedEpochMillis = System.currentTimeMillis()
            }
        }

        private fun targetFile(kind: GeoAssetKind): File {
            val paths = resolveGeoDatabasePaths(context)
            val path = if (kind == GeoAssetKind.Geoip) paths.geoipDbPath else paths.geositeDbPath
            return File(path)
        }

        private fun atomicWrite(
            target: File,
            bytes: ByteArray,
        ) {
            target.parentFile?.mkdirs()
            val temp = File.createTempFile("geo-asset-", ".tmp", target.parentFile)
            try {
                temp.outputStream().use { it.write(bytes) }
                if (!temp.renameTo(target)) {
                    // Cross-filesystem fallback: copy then drop the temp.
                    temp.copyTo(target, overwrite = true)
                }
            } finally {
                temp.delete()
            }
        }

        private data class SingleAssetOutcome(
            val checked: Boolean,
            val updated: Boolean = false,
            val newTag: String? = null,
        )

        private companion object {
            /** Custom provider has no Releases tag; this sentinel records that a custom asset is present. */
            const val CustomTagSentinel = "custom"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class GeoAssetRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindGeoAssetRepository(repository: DefaultGeoAssetRepository): GeoAssetRepository
}
