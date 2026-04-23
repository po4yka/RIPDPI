package com.poyka.ripdpi.strategy

import android.content.Context
import com.poyka.ripdpi.data.ControlPlaneCacheDegradation
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.DefaultStrategyPackChannel
import com.poyka.ripdpi.data.StrategyPackCatalog
import com.poyka.ripdpi.data.StrategyPackCatalogSourceBundled
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.data.acceptedSequenceOrNull
import com.poyka.ripdpi.data.checkCompatibility
import com.poyka.ripdpi.data.normalizeStrategyPackChannel
import com.poyka.ripdpi.data.strategyPackCatalogFromJson
import com.poyka.ripdpi.data.strategyPackManifestFromJson
import com.poyka.ripdpi.data.strategyPackSnapshotFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

interface StrategyPackRepository {
    suspend fun loadSnapshot(): StrategyPackLoadResult

    suspend fun refreshSnapshot(
        channel: String = DefaultStrategyPackChannel,
        allowRollbackOverride: Boolean = false,
    ): StrategyPackSnapshot
}

data class StrategyPackLoadResult(
    val snapshot: StrategyPackSnapshot,
    val cacheDegradation: ControlPlaneCacheDegradation? = null,
)

fun interface StrategyPackClock {
    fun nowEpochMillis(): Long
}

fun interface StrategyPackTempFileFactory {
    fun create(cacheDir: File): File
}

@Singleton
class SystemStrategyPackClock
    @Inject
    constructor() : StrategyPackClock {
        override fun nowEpochMillis(): Long = System.currentTimeMillis()
    }

@Singleton
class DefaultStrategyPackTempFileFactory
    @Inject
    constructor() : StrategyPackTempFileFactory {
        override fun create(cacheDir: File): File = File.createTempFile("strategy-pack-", ".json", cacheDir)
    }

@Singleton
class DefaultStrategyPackRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val service: StrategyPackDownloadService,
        private val verifier: StrategyPackVerifier,
        private val clock: StrategyPackClock,
        private val tempFileFactory: StrategyPackTempFileFactory,
        private val buildProvenanceProvider: StrategyPackBuildProvenanceProvider,
        private val snapshotWriter: AtomicTextFileWriter,
    ) : StrategyPackRepository {
        override suspend fun loadSnapshot(): StrategyPackLoadResult =
            withContext(Dispatchers.IO) {
                val provenance = buildProvenanceProvider.current()
                val cachedResult = loadCachedSnapshot()
                val compatibleCachedSnapshot = cachedResult.snapshot?.takeIf { it.isCompatibleWith(provenance) }

                when {
                    compatibleCachedSnapshot != null -> {
                        StrategyPackLoadResult(snapshot = compatibleCachedSnapshot)
                    }

                    cachedResult.snapshot != null -> {
                        StrategyPackLoadResult(
                            snapshot = loadCompatibleBundledSnapshot(provenance),
                            cacheDegradation =
                                ControlPlaneCacheDegradation(
                                    code = ControlPlaneCacheDegradationCode.CachedSnapshotIncompatible,
                                    detail =
                                        cachedResult.snapshot.catalog
                                            .checkCompatibilityVersions()
                                            .reason,
                                ),
                        )
                    }

                    cachedResult.error != null -> {
                        StrategyPackLoadResult(
                            snapshot = loadCompatibleBundledSnapshot(provenance),
                            cacheDegradation =
                                ControlPlaneCacheDegradation(
                                    code = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                                    detail = cachedResult.error.message,
                                ),
                        )
                    }

                    else -> {
                        StrategyPackLoadResult(snapshot = loadCompatibleBundledSnapshot(provenance))
                    }
                }
            }

        override suspend fun refreshSnapshot(
            channel: String,
            allowRollbackOverride: Boolean,
        ): StrategyPackSnapshot =
            withContext(Dispatchers.IO) {
                val normalizedChannel = normalizeStrategyPackChannel(channel)
                val provenance = buildProvenanceProvider.current()
                val previousSnapshot = loadCachedSnapshot().snapshot
                val manifest =
                    runCatching { loadManifest(normalizedChannel) }
                        .getOrElse { error ->
                            if (error.isMissingRemoteManifest()) {
                                return@withContext loadRefreshFallbackSnapshot(
                                    channel = normalizedChannel,
                                    provenance = provenance,
                                )
                            }
                            throw error
                        }
                val downloadedAtEpochMillis = clock.nowEpochMillis()
                val tempFile = tempFileFactory.create(context.cacheDir)

                try {
                    val actualChecksum =
                        service
                            .downloadCatalog(manifest.catalogUrl)
                            .writeToFileAndDigest(tempFile)
                    val payload = tempFile.readBytes()
                    verifier.verify(
                        manifest = manifest,
                        payload = payload,
                        actualChecksumSha256 = actualChecksum,
                    )

                    val catalog = parseCatalog(payload)
                    ensureCompatible(catalog)
                    enforceAntiRollbackPolicy(
                        catalog = catalog,
                        downloadedAtEpochMillis = downloadedAtEpochMillis,
                        acceptedSequence =
                            previousSnapshot
                                ?.takeIf { normalizeStrategyPackChannel(it.catalog.channel) == normalizedChannel }
                                ?.acceptedSequenceOrNull(),
                        allowRollbackOverride = allowRollbackOverride,
                    )
                    val snapshot =
                        StrategyPackSnapshot(
                            catalog = catalog,
                            source = StrategyPackCatalogSourceDownloaded,
                            lastFetchedAtEpochMillis = downloadedAtEpochMillis,
                            manifestVersion = manifest.version,
                            verifiedChecksumSha256 = manifest.catalogChecksumSha256.lowercase(),
                            verifiedSignatureBase64 = manifest.catalogSignatureBase64,
                        )
                    snapshotWriter.write(
                        file = cacheFile(),
                        payload = snapshot.toJson(),
                    )
                    snapshot
                } finally {
                    tempFile.delete()
                }
            }

        private suspend fun loadManifest(channel: String): StrategyPackManifest =
            service
                .downloadManifest(strategyPackManifestUrl(channel))
                .toString(Charsets.UTF_8)
                .let { payload ->
                    runCatching {
                        strategyPackManifestFromJson(payload)
                    }.getOrElse { error ->
                        throw StrategyPackManifestParseException(error)
                    }
                }

        private fun parseCatalog(payload: ByteArray): StrategyPackCatalog =
            runCatching {
                strategyPackCatalogFromJson(payload.toString(Charsets.UTF_8))
            }.getOrElse { error ->
                throw StrategyPackCatalogParseException(error)
            }

        private fun ensureCompatible(catalog: StrategyPackCatalog) {
            val compatibility = catalog.checkCompatibilityVersions()
            if (!compatibility.isCompatible) {
                throw StrategyPackCompatibilityException(
                    compatibility.reason ?: "The downloaded strategy pack catalog is incompatible.",
                )
            }
        }

        private fun enforceAntiRollbackPolicy(
            catalog: StrategyPackCatalog,
            downloadedAtEpochMillis: Long,
            acceptedSequence: Long?,
            allowRollbackOverride: Boolean,
        ) {
            val candidateSequence =
                catalog.sequence.takeIf { it > 0L }
                    ?: throw StrategyPackMissingSecurityMetadataException(catalog.sequence.takeIf { it > 0L })
            val issuedAt = catalog.issuedAt.trim()
            if (issuedAt.isEmpty()) {
                throw StrategyPackMissingSecurityMetadataException(candidateSequence)
            }
            val issuedAtInstant =
                runCatching { Instant.parse(issuedAt) }.getOrElse {
                    throw StrategyPackInvalidIssuedAtException(issuedAt, candidateSequence)
                }
            val oldestAllowedIssuedAt =
                Instant
                    .ofEpochMilli(downloadedAtEpochMillis)
                    .minus(strategyPackMaxAgeDays, ChronoUnit.DAYS)
            if (issuedAtInstant.isBefore(oldestAllowedIssuedAt)) {
                throw StrategyPackStaleCatalogException(issuedAt, candidateSequence)
            }
            if (!allowRollbackOverride && acceptedSequence != null && candidateSequence <= acceptedSequence) {
                throw StrategyPackRollbackRejectedException(
                    acceptedSequence = acceptedSequence,
                    rejectedSequence = candidateSequence,
                )
            }
        }

        private fun loadCompatibleBundledSnapshot(provenance: StrategyPackBuildProvenance): StrategyPackSnapshot =
            loadBundledSnapshot().takeIf { it.isCompatibleWith(provenance) } ?: StrategyPackSnapshot()

        private fun loadBundledSnapshot(): StrategyPackSnapshot =
            runCatching {
                StrategyPackSnapshot(
                    catalog =
                        context.assets
                            .open(strategyPackCatalogAssetPath)
                            .bufferedReader()
                            .use { reader -> strategyPackCatalogFromJson(reader.readText()) },
                    source = StrategyPackCatalogSourceBundled,
                )
            }.getOrDefault(StrategyPackSnapshot())

        private fun loadRefreshFallbackSnapshot(
            channel: String,
            provenance: StrategyPackBuildProvenance,
        ): StrategyPackSnapshot =
            loadCachedSnapshot()
                .snapshot
                ?.takeIf { snapshot ->
                    snapshot.isCompatibleWith(provenance) &&
                        normalizeStrategyPackChannel(snapshot.catalog.channel) == channel
                } ?: loadCompatibleBundledSnapshot(provenance)

        private fun loadCachedSnapshot(): CachedStrategyPackSnapshotResult {
            val file = cacheFile()
            if (!file.exists()) {
                return CachedStrategyPackSnapshotResult()
            }

            return runCatching {
                CachedStrategyPackSnapshotResult(
                    snapshot = strategyPackSnapshotFromJson(file.readText(Charsets.UTF_8)),
                )
            }.getOrElse { error ->
                CachedStrategyPackSnapshotResult(error = error)
            }
        }

        private fun StrategyPackSnapshot.isCompatibleWith(provenance: StrategyPackBuildProvenance): Boolean =
            catalog
                .checkCompatibility(
                    appVersion = provenance.appVersion,
                    nativeVersion = provenance.nativeVersion,
                ).isCompatible

        private fun StrategyPackCatalog.checkCompatibilityVersions() =
            checkCompatibility(
                appVersion = buildProvenanceProvider.current().appVersion,
                nativeVersion = buildProvenanceProvider.current().nativeVersion,
            )

        private fun cacheFile(): File = File(context.filesDir, strategyPackCatalogCachePath)
    }

private data class CachedStrategyPackSnapshotResult(
    val snapshot: StrategyPackSnapshot? = null,
    val error: Throwable? = null,
)

internal fun strategyPackManifestUrl(channel: String): String =
    "$strategyPackBaseUrl$strategyPackManifestPathPrefix/${normalizeStrategyPackChannel(
        channel,
    )}/manifest.json"

internal fun ByteArray.writeToFileAndDigest(target: File): String =
    inputStream().use { input ->
        target.outputStream().use { output ->
            input.copyToAndDigest(output)
        }
    }

internal fun InputStream.copyToAndDigest(output: java.io.OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val readCount = read(buffer)
        if (readCount <= 0) {
            break
        }
        digest.update(buffer, 0, readCount)
        output.write(buffer, 0, readCount)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        ((byte.toInt() and byteUnsignedMask) + hexByteBase).toString(hexRadix).substring(1)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackBindingsModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackRepository(repository: DefaultStrategyPackRepository): StrategyPackRepository

    @Binds
    @Singleton
    abstract fun bindStrategyPackTempFileFactory(
        factory: DefaultStrategyPackTempFileFactory,
    ): StrategyPackTempFileFactory

    @Binds
    @Singleton
    abstract fun bindStrategyPackClock(clock: SystemStrategyPackClock): StrategyPackClock
}

private const val byteUnsignedMask = 0xff
private const val hexByteBase = 0x100
private const val hexRadix = 16
private const val strategyPackMaxAgeDays = 30L
const val strategyPackCatalogAssetPath = "strategy-packs/catalog.json"
const val strategyPackCatalogCachePath = "strategy-packs/catalog.snapshot.json"
const val strategyPackManifestPathPrefix = "poyka/ripdpi-strategy-packs/main"

private fun Throwable.isMissingRemoteManifest(): Boolean =
    this is IOException &&
        message?.contains("HTTP 404") == true &&
        message?.contains("/manifest.json") == true
