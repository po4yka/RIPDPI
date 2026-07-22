package com.poyka.ripdpi.assets

import android.content.Context
import android.net.Uri
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.assets.AssetProvider
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.data.assets.GeoAssetRepo
import com.poyka.ripdpi.data.assets.MinGeoAssetBytes
import com.poyka.ripdpi.data.assets.assetProviderById
import com.poyka.ripdpi.data.assets.customAssetDownloadUrl
import com.poyka.ripdpi.data.assets.githubLatestReleaseApiUrl
import com.poyka.ripdpi.data.assets.githubReleaseAssetDownloadUrl
import com.poyka.ripdpi.data.assets.isAssetUpdateAvailable
import com.poyka.ripdpi.data.assets.isPlausibleGeoAssetPayload
import com.poyka.ripdpi.data.assets.parseGithubLatestReleaseTag
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

/** Stable failure categories that the UI can localize without exposing exception text. */
enum class GeoAssetIntegrityFailure(
    internal val diagnosticMessage: String,
) {
    UnableToOpen("Unable to open imported geo asset."),
    InvalidPayload("Geo asset failed the validity gate."),
    TooLarge("Imported geo asset exceeds the local size limit."),
    InstallFailed("Geo asset could not be installed in local storage."),
}

/** Raised when a geo asset fails a local, fail-closed integrity gate. */
class GeoAssetIntegrityException(
    val reason: GeoAssetIntegrityFailure,
    cause: Throwable? = null,
) : IOException(reason.diagnosticMessage, cause)

interface GeoAssetRepository {
    /**
     * Checks the active provider's GitHub Releases `latest` tag for geoip and geosite, downloads and
     * atomically swaps each `.db` only when the remote tag differs from the stored tag, then persists
     * the new tags. The live native swap is deferred to next service start (no reload JNI exists; see
     * the asset-provider follow-up task).
     */
    suspend fun checkAndUpdate(): GeoAssetUpdateResult

    /**
     * Opens and closes a user-picked local `.db` ([uri]), enforces the local import size limit, and
     * atomically installs it for [kind] only after validation succeeds.
     */
    suspend fun importLocalAsset(
        kind: GeoAssetKind,
        uri: Uri,
    )
}

internal const val GeoAssetMaxLocalImportBytes: Long = 64L * 1024L * 1024L

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
            uri: Uri,
        ) {
            streamGeoAssetUriToTargetInterruptibly(
                uri = uri,
                target = targetFile(kind),
                openInput = context.contentResolver::openInputStream,
                afterInstall = {
                    persistAssetMetadata {
                        geoAssetLastUpdatedEpochMillis = System.currentTimeMillis()
                    }
                },
            )
        }

        // Guard-clause heavy by design: custom-provider, empty-base, no-repo, and
        // up-to-date cases each short-circuit with their own outcome before the
        // download path. Flattening would hurt readability more than the early returns.
        @Suppress("ReturnCount")
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
                throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InvalidPayload)
            }
            installDownloadedGeoAssetToTarget(bytes, targetFile(kind))
        }

        private suspend fun persistTagsAndTimestamp(
            geoip: SingleAssetOutcome,
            geosite: SingleAssetOutcome,
        ) {
            if (!geoip.updated && !geosite.updated) {
                return
            }
            persistAssetMetadata {
                geoip.newTag?.let { if (geoip.updated) geoAssetGeoipVersionTag = it }
                geosite.newTag?.let { if (geosite.updated) geoAssetGeositeVersionTag = it }
                geoAssetLastUpdatedEpochMillis = System.currentTimeMillis()
            }
        }

        private suspend fun persistAssetMetadata(update: AppSettings.Builder.() -> Unit) {
            try {
                settingsRepository.update(update)
            } catch (error: GeoAssetIntegrityException) {
                throw error
            } catch (error: IOException) {
                throwInstallFailed(error)
            } catch (error: SecurityException) {
                throwInstallFailed(error)
            }
        }

        private fun targetFile(kind: GeoAssetKind): File {
            val paths = resolveGeoDatabasePaths(context)
            val path = if (kind == GeoAssetKind.Geoip) paths.geoipDbPath else paths.geositeDbPath
            return File(path)
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

internal fun streamGeoAssetUriToTarget(
    uri: Uri,
    target: File,
    maxBytes: Long = GeoAssetMaxLocalImportBytes,
    openInput: (Uri) -> InputStream?,
    replaceTemp: (File, File) -> Unit = ::replaceGeoAssetTempFile,
    cancellationCheck: () -> Unit = {},
) {
    val temp = prepareGeoAssetUriTemp(uri, target, maxBytes, openInput, cancellationCheck)
    try {
        cancellationCheck()
        installGeoAssetTemp(temp, target, replaceTemp)
    } finally {
        temp.delete()
    }
}

internal suspend fun streamGeoAssetUriToTargetInterruptibly(
    uri: Uri,
    target: File,
    maxBytes: Long = GeoAssetMaxLocalImportBytes,
    openInput: (Uri) -> InputStream?,
    replaceTemp: (File, File) -> Unit = ::replaceGeoAssetTempFile,
    afterInstall: suspend () -> Unit = {},
) {
    val operationContext = currentCoroutineContext()
    val cancellationCheck = { operationContext.ensureActive() }
    val temp =
        runInterruptible(Dispatchers.IO) {
            prepareGeoAssetUriTemp(uri, target, maxBytes, openInput, cancellationCheck)
        }
    try {
        cancellationCheck()
        withContext(NonCancellable + Dispatchers.IO) {
            installGeoAssetTemp(temp, target, replaceTemp)
            afterInstall()
        }
    } finally {
        temp.delete()
    }
}

private fun openGeoAssetInput(
    uri: Uri,
    openInput: (Uri) -> InputStream?,
): InputStream {
    val input =
        try {
            openInput(uri)
        } catch (error: IOException) {
            throwUnableToOpen(error)
        } catch (error: SecurityException) {
            throwUnableToOpen(error)
        }
    return input ?: throwUnableToOpen()
}

private fun prepareGeoAssetUriTemp(
    uri: Uri,
    target: File,
    maxBytes: Long,
    openInput: (Uri) -> InputStream?,
    cancellationCheck: () -> Unit,
): File {
    cancellationCheck()
    val input = openGeoAssetInput(uri, openInput)
    var temp: File? = null
    try {
        input.use { temp = writeGeoAssetInputToTemp(it, target, maxBytes, cancellationCheck) }
        cancellationCheck()
        return checkNotNull(temp)
    } catch (error: GeoAssetIntegrityException) {
        temp?.delete()
        throw error
    } catch (error: IOException) {
        temp?.delete()
        cancellationCheck()
        throwUnableToOpen(error)
    } catch (error: SecurityException) {
        temp?.delete()
        cancellationCheck()
        throwUnableToOpen(error)
    }
}

internal fun streamGeoAssetToTarget(
    input: InputStream,
    target: File,
    maxBytes: Long = GeoAssetMaxLocalImportBytes,
) {
    val temp = writeGeoAssetInputToTemp(input, target, maxBytes)
    try {
        installGeoAssetTemp(temp, target)
    } finally {
        temp.delete()
    }
}

internal fun installDownloadedGeoAssetToTarget(
    bytes: ByteArray,
    target: File,
    replaceTemp: (File, File) -> Unit = ::replaceGeoAssetTempFile,
) {
    val temp = createGeoAssetTempFile(target)
    try {
        writeGeoAssetTemp(temp) { output -> output.write(bytes) }
        installGeoAssetTemp(temp, target, replaceTemp)
    } finally {
        temp.delete()
    }
}

private fun writeGeoAssetInputToTemp(
    input: InputStream,
    target: File,
    maxBytes: Long,
    cancellationCheck: () -> Unit = {},
): File {
    require(maxBytes >= MinGeoAssetBytes) { "Local geo asset limit must allow the validation prefix." }
    val temp = createGeoAssetTempFile(target)
    var prepared = false
    try {
        val copyState = GeoAssetCopyState(maxBytes)

        writeGeoAssetTemp(temp) { output ->
            copyGeoAssetInput(input, output, copyState, cancellationCheck)
        }

        cancellationCheck()
        // This prefix is equivalent to the complete-payload decision while the validator checks
        // only minimum length and the first 16 bytes. Update both contracts together if it deepens.
        if (!isPlausibleGeoAssetPayload(copyState.validationPrefix())) {
            throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InvalidPayload)
        }
        prepared = true
        return temp
    } finally {
        if (!prepared) temp.delete()
    }
}

private fun throwUnableToOpen(cause: Throwable? = null): Nothing =
    throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.UnableToOpen, cause)

private fun throwInstallFailed(cause: Throwable): Nothing =
    throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InstallFailed, cause)

private fun createGeoAssetTempFile(target: File): File =
    try {
        val targetDirectory = requireNotNull(target.absoluteFile.parentFile)
        if (!targetDirectory.exists() && !targetDirectory.mkdirs() && !targetDirectory.isDirectory) {
            throw IOException("Unable to create geo asset storage directory.")
        }
        if (!targetDirectory.isDirectory) {
            throw IOException("Unable to create geo asset storage directory.")
        }
        File.createTempFile("geo-asset-", ".tmp", targetDirectory)
    } catch (error: IOException) {
        throwInstallFailed(error)
    } catch (error: SecurityException) {
        throwInstallFailed(error)
    }

private inline fun writeGeoAssetTemp(
    temp: File,
    write: (OutputStream) -> Unit,
) {
    try {
        temp.outputStream().buffered().use(write)
    } catch (error: GeoAssetIntegrityException) {
        throw error
    } catch (error: IOException) {
        throwInstallFailed(error)
    } catch (error: SecurityException) {
        throwInstallFailed(error)
    }
}

private fun copyGeoAssetInput(
    input: InputStream,
    output: java.io.OutputStream,
    state: GeoAssetCopyState,
    cancellationCheck: () -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    cancellationCheck()
    var readCount = readGeoAssetInput(cancellationCheck) { input.read(buffer) }
    while (readCount >= 0) {
        cancellationCheck()
        if (readCount == 0) {
            readCount = readGeoAssetInput(cancellationCheck, input::read)
            cancellationCheck()
            if (readCount >= 0) {
                buffer[0] = readCount.toByte()
                state.write(buffer, 1, output)
            }
        } else {
            state.write(buffer, readCount, output)
        }
        cancellationCheck()
        readCount = readGeoAssetInput(cancellationCheck) { input.read(buffer) }
    }
}

private inline fun readGeoAssetInput(
    cancellationCheck: () -> Unit,
    read: () -> Int,
): Int =
    try {
        read()
    } catch (error: IOException) {
        cancellationCheck()
        throwUnableToOpen(error)
    } catch (error: SecurityException) {
        cancellationCheck()
        throwUnableToOpen(error)
    }

private class GeoAssetCopyState(
    private val maxBytes: Long,
) {
    private val prefix = ByteArray(MinGeoAssetBytes)
    private var prefixSize = 0
    private var totalBytes = 0L

    fun write(
        bytes: ByteArray,
        count: Int,
        output: java.io.OutputStream,
    ) {
        if (totalBytes > maxBytes - count.toLong()) {
            throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.TooLarge)
        }
        val prefixCount = minOf(count, prefix.size - prefixSize).coerceAtLeast(0)
        if (prefixCount > 0) bytes.copyInto(prefix, prefixSize, 0, prefixCount)
        prefixSize += prefixCount
        try {
            output.write(bytes, 0, count)
        } catch (error: IOException) {
            throwInstallFailed(error)
        } catch (error: SecurityException) {
            throwInstallFailed(error)
        }
        totalBytes += count
    }

    fun validationPrefix(): ByteArray = prefix.copyOf(prefixSize)
}

internal fun replaceGeoAssetTempFile(
    temp: File,
    target: File,
    rename: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
) {
    if (!rename(temp, target)) {
        throw IOException("Unable to atomically install geo asset.")
    }
}

private fun installGeoAssetTemp(
    temp: File,
    target: File,
    replaceTemp: (File, File) -> Unit = ::replaceGeoAssetTempFile,
) {
    try {
        replaceTemp(temp, target)
    } catch (error: GeoAssetIntegrityException) {
        throw error
    } catch (error: IOException) {
        throwInstallFailed(error)
    } catch (error: SecurityException) {
        throwInstallFailed(error)
    }
}
