package com.poyka.ripdpi.hosts

import android.content.Context
import com.poyka.ripdpi.data.HostPackCatalog
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceName
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceRef
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceUrl
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackCatalogSourceBundled
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackSource
import com.poyka.ripdpi.data.curatedHostPackCatalogFromGeosite
import com.poyka.ripdpi.data.hostPackCatalogFromJson
import com.poyka.ripdpi.data.hostPackCatalogSnapshotFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.GeositeCatalog
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface HostPackCatalogRepository {
    suspend fun loadSnapshot(): HostPackCatalogSnapshot

    suspend fun refreshSnapshot(): HostPackCatalogSnapshot
}

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
    HostPackRefreshException("The remote checksum payload did not contain a valid SHA-256 digest.")

class HostPackChecksumMismatchException(
    val expected: String,
    val actual: String,
) : HostPackRefreshException("The downloaded geosite.dat checksum did not match the published SHA-256 digest.")

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
        private val clock: HostPackCatalogClock,
        private val tempFileFactory: HostPackCatalogTempFileFactory,
    ) : HostPackCatalogRepository {
        override suspend fun loadSnapshot(): HostPackCatalogSnapshot =
            withContext(Dispatchers.IO) {
                loadCachedSnapshot() ?: loadBundledSnapshot()
            }

        override suspend fun refreshSnapshot(): HostPackCatalogSnapshot =
            withContext(Dispatchers.IO) {
                val expectedChecksum = parseHostPackChecksum(service.downloadChecksum().requireBodyText())
                val downloadedAtEpochMillis = clock.nowEpochMillis()
                val tempFile = tempFileFactory.create(context.cacheDir)

                try {
                    val actualChecksum = service.downloadGeosite().writeToFileAndDigest(tempFile)
                    if (actualChecksum != expectedChecksum) {
                        throw HostPackChecksumMismatchException(expectedChecksum, actualChecksum)
                    }

                    val catalog = parseCuratedCatalog(tempFile.inputStream(), downloadedAtEpochMillis)
                    val snapshot =
                        HostPackCatalogSnapshot(
                            catalog = catalog,
                            source = HostPackCatalogSourceDownloaded,
                            lastFetchedAtEpochMillis = downloadedAtEpochMillis,
                            verifiedChecksumSha256 = expectedChecksum,
                        )
                    cacheFile().let { cacheFile ->
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(snapshot.toJson(), Charsets.UTF_8)
                    }
                    snapshot
                } finally {
                    tempFile.delete()
                }
            }

        private fun loadBundledSnapshot(): HostPackCatalogSnapshot =
            HostPackCatalogSnapshot(
                catalog = loadBundledCatalog(),
                source = HostPackCatalogSourceBundled,
            )

        private fun loadBundledCatalog(): HostPackCatalog =
            runCatching {
                context.assets.open(HOST_PACK_CATALOG_ASSET_PATH).bufferedReader().use { reader ->
                    hostPackCatalogFromJson(reader.readText())
                }
            }.getOrDefault(HostPackCatalog())

        private fun loadCachedSnapshot(): HostPackCatalogSnapshot? =
            runCatching {
                val snapshotFile = cacheFile()
                if (!snapshotFile.exists()) {
                    null
                } else {
                    hostPackCatalogSnapshotFromJson(snapshotFile.readText(Charsets.UTF_8))
                }
            }.getOrNull()

        private fun parseCuratedCatalog(
            inputStream: InputStream,
            downloadedAtEpochMillis: Long,
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
                    url = HostPackCatalogRemoteSourceUrl,
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

        private fun cacheFile(): File = File(context.filesDir, HOST_PACK_CATALOG_CACHE_PATH)
    }

internal fun parseHostPackChecksum(payload: String): String {
    val firstToken =
        payload.lineSequence().firstNotNullOfOrNull { line ->
            line
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
        }
    val normalized = firstToken?.lowercase(Locale.ROOT)
    if (normalized?.matches(Regex("[0-9a-f]{64}")) != true) {
        throw HostPackChecksumFormatException()
    }
    return normalized
}

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
        ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
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

const val HOST_PACK_CATALOG_ASSET_PATH = "host-packs/catalog.json"
const val HOST_PACK_CATALOG_CACHE_PATH = "host-packs/catalog-cache.json"
