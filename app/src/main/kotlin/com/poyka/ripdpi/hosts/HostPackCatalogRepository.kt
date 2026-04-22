package com.poyka.ripdpi.hosts

import android.content.Context
import com.poyka.ripdpi.data.ControlPlaneCacheDegradation
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.HostPackCatalog
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceName
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceRef
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackCatalogSourceBundled
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackManifest
import com.poyka.ripdpi.data.HostPackSource
import com.poyka.ripdpi.data.curatedHostPackCatalogFromGeosite
import com.poyka.ripdpi.data.hostPackCatalogFromJson
import com.poyka.ripdpi.data.hostPackCatalogSnapshotFromJson
import com.poyka.ripdpi.data.hostPackManifestFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.GeositeCatalog
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val byteUnsignedMask = 0xff
private const val hexByteBase = 0x100
private const val hexRadix = 16

interface HostPackCatalogRepository {
    suspend fun loadSnapshot(): HostPackCatalogLoadResult

    suspend fun refreshSnapshot(): HostPackCatalogSnapshot
}

data class HostPackCatalogLoadResult(
    val snapshot: HostPackCatalogSnapshot,
    val cacheDegradation: ControlPlaneCacheDegradation? = null,
)

fun interface HostPackCatalogClock {
    fun nowEpochMillis(): Long
}

fun interface HostPackCatalogTempFileFactory {
    fun create(cacheDir: File): File
}

@Singleton
class SystemHostPackCatalogClock
    @Inject
    constructor() : HostPackCatalogClock {
        override fun nowEpochMillis(): Long = System.currentTimeMillis()
    }

@Singleton
class DefaultHostPackCatalogTempFileFactory
    @Inject
    constructor() : HostPackCatalogTempFileFactory {
        override fun create(cacheDir: File): File = File.createTempFile("host-pack-geosite-", ".dat", cacheDir)
    }

open class HostPackRefreshException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class HostPackChecksumFormatException :
    HostPackRefreshException("The host pack manifest checksum was not a valid SHA-256 digest.")

class HostPackChecksumMismatchException(
    val expected: String,
    val actual: String,
) : HostPackRefreshException("The downloaded host pack catalog checksum did not match the manifest.")

class HostPackCatalogParseException(
    cause: Throwable,
) : HostPackRefreshException("The verified geosite.dat payload could not be parsed.", cause)

class HostPackCatalogBuildException(
    cause: Throwable,
) : HostPackRefreshException("The verified geosite.dat payload did not contain all curated host packs.", cause)

@Singleton
class DefaultHostPackCatalogRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val service: HostPackCatalogDownloadService,
        private val verifier: HostPackVerifier,
        private val clock: HostPackCatalogClock,
        private val tempFileFactory: HostPackCatalogTempFileFactory,
        private val snapshotWriter: AtomicTextFileWriter,
    ) : HostPackCatalogRepository {
        override suspend fun loadSnapshot(): HostPackCatalogLoadResult =
            withContext(Dispatchers.IO) {
                val cachedResult = loadCachedSnapshot()
                when {
                    cachedResult.snapshot != null -> {
                        HostPackCatalogLoadResult(snapshot = cachedResult.snapshot)
                    }

                    cachedResult.error != null -> {
                        HostPackCatalogLoadResult(
                            snapshot = loadBundledSnapshot(),
                            cacheDegradation =
                                ControlPlaneCacheDegradation(
                                    code = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                                    detail = cachedResult.error.message,
                                ),
                        )
                    }

                    else -> {
                        HostPackCatalogLoadResult(snapshot = loadBundledSnapshot())
                    }
                }
            }

        override suspend fun refreshSnapshot(): HostPackCatalogSnapshot =
            withContext(Dispatchers.IO) {
                val manifest = loadManifest()
                val downloadedAtEpochMillis = clock.nowEpochMillis()
                val tempFile = tempFileFactory.create(context.cacheDir)

                try {
                    val actualChecksum = service.downloadCatalog(manifest.catalogUrl).writeToFileAndDigest(tempFile)
                    val payload = tempFile.readBytes()
                    verifier.verify(
                        manifest = manifest,
                        payload = payload,
                        actualChecksumSha256 = actualChecksum,
                    )
                    val catalog =
                        parseCuratedCatalog(
                            inputStream = payload.inputStream(),
                            downloadedAtEpochMillis = downloadedAtEpochMillis,
                            sourceUrl = manifest.catalogUrl,
                        )
                    val snapshot =
                        HostPackCatalogSnapshot(
                            catalog = catalog,
                            source = HostPackCatalogSourceDownloaded,
                            lastFetchedAtEpochMillis = downloadedAtEpochMillis,
                            manifestVersion = manifest.version,
                            verifiedChecksumSha256 = manifest.catalogChecksumSha256.lowercase(),
                            verifiedSignatureBase64 = manifest.catalogSignatureBase64,
                            verifiedSigningKeyId = manifest.keyId,
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

        private suspend fun loadManifest(): HostPackManifest =
            service
                .downloadManifest()
                .requireBodyText()
                .let { payload ->
                    runCatching {
                        hostPackManifestFromJson(payload)
                    }.getOrElse { error ->
                        throw HostPackManifestParseException(error)
                    }
                }

        private fun loadBundledSnapshot(): HostPackCatalogSnapshot =
            HostPackCatalogSnapshot(
                catalog = loadBundledCatalog(),
                source = HostPackCatalogSourceBundled,
            )

        private fun loadBundledCatalog(): HostPackCatalog =
            runCatching {
                context.assets.open(hostPackCatalogAssetPath).bufferedReader().use { reader ->
                    hostPackCatalogFromJson(reader.readText())
                }
            }.getOrDefault(HostPackCatalog())

        private fun loadCachedSnapshot(): CachedHostPackCatalogSnapshotResult {
            val snapshotFile = cacheFile()
            if (!snapshotFile.exists()) {
                return CachedHostPackCatalogSnapshotResult()
            }

            return runCatching {
                CachedHostPackCatalogSnapshotResult(
                    snapshot = hostPackCatalogSnapshotFromJson(snapshotFile.readText(Charsets.UTF_8)),
                )
            }.getOrElse { error ->
                CachedHostPackCatalogSnapshotResult(error = error)
            }
        }

        private fun parseCuratedCatalog(
            inputStream: InputStream,
            downloadedAtEpochMillis: Long,
            sourceUrl: String,
        ): HostPackCatalog {
            val geositeCatalog =
                runCatching {
                    inputStream.use(GeositeCatalog::parseFrom)
                }.getOrElse { error ->
                    throw HostPackCatalogParseException(error)
                }

            val source =
                HostPackSource(
                    name = HostPackCatalogRemoteSourceName,
                    url = sourceUrl,
                    ref = HostPackCatalogRemoteSourceRef,
                )
            return runCatching {
                curatedHostPackCatalogFromGeosite(
                    geositeCatalog = geositeCatalog,
                    generatedAt = formatHostPackGeneratedAt(downloadedAtEpochMillis),
                    source = source,
                )
            }.getOrElse { error ->
                throw HostPackCatalogBuildException(error)
            }
        }

        private fun cacheFile(): File = File(context.filesDir, hostPackCatalogCachePath)
    }

private data class CachedHostPackCatalogSnapshotResult(
    val snapshot: HostPackCatalogSnapshot? = null,
    val error: Throwable? = null,
)

internal fun Response<ResponseBody>.requireBodyText(): String {
    val body =
        if (isSuccessful) {
            body()
        } else {
            null
        }
            ?: throw IOException("Remote request failed with HTTP ${code()} for ${raw().request.url}")
    return body.use(ResponseBody::string)
}

internal fun Response<ResponseBody>.writeToFileAndDigest(target: File): String {
    val body =
        if (isSuccessful) {
            body()
        } else {
            null
        }
            ?: throw IOException("Remote request failed with HTTP ${code()} for ${raw().request.url}")

    return body.use { responseBody ->
        target.outputStream().use { output ->
            responseBody.byteStream().copyToAndDigest(output)
        }
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
    return digest.digest().toHexString()
}

internal fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte ->
        ((byte.toInt() and byteUnsignedMask) + hexByteBase).toString(hexRadix).substring(1)
    }

internal fun formatHostPackGeneratedAt(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()

@Module
@InstallIn(SingletonComponent::class)
abstract class HostPackCatalogBindingsModule {
    @Binds
    @Singleton
    abstract fun bindHostPackCatalogRepository(repository: DefaultHostPackCatalogRepository): HostPackCatalogRepository

    @Binds
    @Singleton
    abstract fun bindHostPackCatalogClock(clock: SystemHostPackCatalogClock): HostPackCatalogClock

    @Binds
    @Singleton
    abstract fun bindHostPackCatalogTempFileFactory(
        factory: DefaultHostPackCatalogTempFileFactory,
    ): HostPackCatalogTempFileFactory
}

const val hostPackCatalogAssetPath = "host-packs/catalog.json"
const val hostPackCatalogCachePath = "host-packs/catalog-cache.json"
