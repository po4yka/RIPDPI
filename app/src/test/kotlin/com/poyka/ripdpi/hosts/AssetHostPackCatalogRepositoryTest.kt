package com.poyka.ripdpi.hosts

import android.app.Application
import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import com.poyka.ripdpi.data.HostPackCatalogSourceBundled
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackManifest
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.GeositeCatalog
import com.poyka.ripdpi.proto.GeositeDomain
import com.poyka.ripdpi.proto.GeositeEntry
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import com.poyka.ripdpi.storage.DefaultAtomicTextFileWriter
import com.poyka.ripdpi.testsupport.CorruptFileFixture
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class AssetHostPackCatalogRepositoryTest {
    private lateinit var application: Application
    private val refreshClock = HostPackCatalogClock { 1_710_000_000_000L }
    private lateinit var keyPair: KeyPair
    private lateinit var verifier: HostPackVerifier

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.filesDir.resolve("host-packs").deleteRecursively()
        keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(256) }
                .generateKeyPair()
        verifier =
            DefaultHostPackVerifier(
                keyResolver =
                    AppTrustedSigningKeyResolver { keyId ->
                        if (keyId == DefaultStrategyPackSigningKeyId) {
                            keyPair.public
                        } else {
                            null
                        }
                    },
            )
    }

    @Test
    fun `loads bundled host pack catalog from assets when cache is absent`() =
        runTest {
            val repository = createRepository(service = FakeHostPackCatalogDownloadService())

            val snapshot = repository.loadSnapshot()

            assertEquals(HostPackCatalogSourceBundled, snapshot.source)
            assertEquals(listOf("youtube", "telegram", "discord"), snapshot.packs.map { it.id })
            assertTrue(snapshot.packs.all { it.hostCount == it.hosts.size })
        }

    @Test
    fun `refresh downloads verifies signature and persists downloaded snapshot`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val catalogUrl = "https://cdn.example.test/host-packs/geosite.dat"
            val manifest =
                hostPackManifest(
                    version = "2026.04.1",
                    catalogUrl = catalogUrl,
                    catalogPayload = geositeBytes,
                )
            val tempFile = application.cacheDir.resolve("host-pack-refresh-success.dat")
            val service =
                FakeHostPackCatalogDownloadService(
                    manifestPayload = manifest.toJson(),
                    geositePayload = geositeBytes,
                )
            val repository =
                createRepository(
                    service = service,
                    tempFileName = tempFile.name,
                )

            val snapshot = repository.refreshSnapshot()
            val reloaded = repository.loadSnapshot()

            assertEquals(catalogUrl, service.requestedCatalogUrl)
            assertEquals(HostPackCatalogSourceDownloaded, snapshot.source)
            assertEquals(manifest.version, snapshot.manifestVersion)
            assertEquals(manifest.catalogChecksumSha256, snapshot.verifiedChecksumSha256)
            assertEquals(manifest.catalogSignatureBase64, snapshot.verifiedSignatureBase64)
            assertEquals(manifest.keyId, snapshot.verifiedSigningKeyId)
            assertEquals(refreshClock.nowEpochMillis(), snapshot.lastFetchedAtEpochMillis)
            assertEquals(HostPackCatalogSourceDownloaded, reloaded.source)
            assertEquals(manifest.version, reloaded.manifestVersion)
            assertEquals(manifest.keyId, reloaded.verifiedSigningKeyId)
            assertFalse(tempFile.exists())
            assertEquals(
                catalogUrl,
                reloaded.packs
                    .first()
                    .sources
                    .single()
                    .url,
            )
            assertEquals(listOf("youtube.com", "ytimg.com"), reloaded.packs.first().hosts)
        }

    @Test
    fun `refresh keeps current snapshot when signature verification fails`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.2",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                    signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(64)),
                )
            val tempFile = application.cacheDir.resolve("host-pack-refresh-failed.dat")
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                    tempFileName = tempFile.name,
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected signature verification to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackSignatureMismatchException)
                }

            val fallbackSnapshot = repository.loadSnapshot()

            assertEquals(HostPackCatalogSourceBundled, fallbackSnapshot.source)
            assertTrue(fallbackSnapshot.lastFetchedAtEpochMillis == null)
            assertFalse(tempFile.exists())
        }

    @Test
    fun `refresh rejects invalid manifest json`() =
        runTest {
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = "{bad json",
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected manifest parsing to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackManifestParseException)
                }
        }

    @Test
    fun `refresh rejects malformed manifest checksum`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.3",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                    checksum = "not-a-sha256",
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected checksum format validation to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackChecksumFormatException)
                }
        }

    @Test
    fun `refresh rejects checksum mismatch`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.4",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                    checksum = ByteArray(32).sha256(),
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected checksum validation to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackChecksumMismatchException)
                }
        }

    @Test
    fun `refresh rejects unknown signing key`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.5",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                    keyId = "rotated-key",
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected unknown key validation to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackUnknownSigningKeyException)
                }
        }

    @Test
    fun `refresh rejects unsupported signature algorithm`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.6",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                    signatureAlgorithm = "SHA512withRSA",
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected algorithm validation to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackUnsupportedSignatureAlgorithmException)
                }
        }

    @Test
    fun `refresh surfaces verified protobuf parse failures`() =
        runTest {
            val payload = "not-a-protobuf".toByteArray()
            val manifest =
                hostPackManifest(
                    version = "2026.04.7",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = payload,
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = payload,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected protobuf parsing to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackCatalogParseException)
                }
        }

    @Test
    fun `refresh surfaces verified curated catalog build failures`() =
        runTest {
            val geositeBytes = incompleteGeositeBytes()
            val manifest =
                hostPackManifest(
                    version = "2026.04.8",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite.dat",
                    catalogPayload = geositeBytes,
                )
            val repository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = manifest.toJson(),
                            geositePayload = geositeBytes,
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected curated catalog build to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackCatalogBuildException)
                }
        }

    @Test
    fun `refresh preserves previous cached snapshot when atomic cache write fails`() =
        runTest {
            val initialPayload = curatedGeositeBytes()
            val initialManifest =
                hostPackManifest(
                    version = "2026.04.9",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite-v1.dat",
                    catalogPayload = initialPayload,
                )
            val initialRepository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = initialManifest.toJson(),
                            geositePayload = initialPayload,
                        ),
                )

            val initialSnapshot = initialRepository.refreshSnapshot()
            val updatedPayload =
                GeositeCatalog
                    .parseFrom(curatedGeositeBytes())
                    .toBuilder()
                    .addEntry(
                        GeositeEntry
                            .newBuilder()
                            .setCountryCode("youtube")
                            .addDomain(
                                GeositeDomain
                                    .newBuilder()
                                    .setType(GeositeDomain.Type.FULL)
                                    .setValue("m.youtube.com")
                                    .build(),
                            ).build(),
                    ).build()
                    .toByteArray()
            val failingManifest =
                hostPackManifest(
                    version = "2026.04.10",
                    catalogUrl = "https://cdn.example.test/host-packs/geosite-v2.dat",
                    catalogPayload = updatedPayload,
                )
            val failingRepository =
                createRepository(
                    service =
                        FakeHostPackCatalogDownloadService(
                            manifestPayload = failingManifest.toJson(),
                            geositePayload = updatedPayload,
                        ),
                    snapshotWriter = CorruptFileFixture("torn").failingWriter(),
                )

            runCatching { failingRepository.refreshSnapshot() }
                .onSuccess { error("Expected atomic cache write to fail") }
                .onFailure { error ->
                    assertTrue(error is IOException)
                }

            val preservedSnapshot = failingRepository.loadSnapshot()
            assertEquals(initialSnapshot.manifestVersion, preservedSnapshot.manifestVersion)
            assertEquals(initialSnapshot.packs.first().hosts, preservedSnapshot.packs.first().hosts)
        }

    private fun createRepository(
        service: HostPackCatalogDownloadService,
        tempFileName: String = "host-pack-temp.dat",
        snapshotWriter: AtomicTextFileWriter = DefaultAtomicTextFileWriter(),
    ): DefaultHostPackCatalogRepository =
        DefaultHostPackCatalogRepository(
            context = application,
            service = service,
            verifier = verifier,
            clock = refreshClock,
            tempFileFactory = hostPackTempFileFactory(tempFileName),
            snapshotWriter = snapshotWriter,
        )

    private fun hostPackManifest(
        version: String,
        catalogUrl: String,
        catalogPayload: ByteArray,
        checksum: String = catalogPayload.sha256(),
        signatureBase64: String = catalogPayload.signWith(keyPair),
        signatureAlgorithm: String = StrategyPackSignatureAlgorithmSha256WithEcdsa,
        keyId: String = DefaultStrategyPackSigningKeyId,
    ): HostPackManifest =
        HostPackManifest(
            version = version,
            catalogUrl = catalogUrl,
            catalogChecksumSha256 = checksum,
            catalogSignatureBase64 = signatureBase64,
            signatureAlgorithm = signatureAlgorithm,
            keyId = keyId,
        )

    private class FakeHostPackCatalogDownloadService(
        private val manifestPayload: String = "",
        private val geositePayload: ByteArray = byteArrayOf(),
    ) : HostPackCatalogDownloadService {
        var requestedCatalogUrl: String? = null

        override suspend fun downloadManifest() =
            Response.success(manifestPayload.toResponseBody("application/json".toMediaType()))

        override suspend fun downloadCatalog(url: String): Response<okhttp3.ResponseBody> {
            requestedCatalogUrl = url
            return Response.success(geositePayload.toResponseBody("application/octet-stream".toMediaType()))
        }
    }

    private fun hostPackTempFileFactory(fileName: String): HostPackCatalogTempFileFactory =
        HostPackCatalogTempFileFactory { cacheDir ->
            cacheDir.resolve(fileName).apply {
                parentFile?.mkdirs()
                delete()
            }
        }
}

private fun curatedGeositeBytes(): ByteArray =
    GeositeCatalog
        .newBuilder()
        .addEntry(
            GeositeEntry
                .newBuilder()
                .setCountryCode("youtube")
                .addDomain(
                    GeositeDomain
                        .newBuilder()
                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                        .setValue("youtube.com")
                        .build(),
                ).addDomain(
                    GeositeDomain
                        .newBuilder()
                        .setType(GeositeDomain.Type.FULL)
                        .setValue("ytimg.com")
                        .build(),
                ).build(),
        ).addEntry(
            GeositeEntry
                .newBuilder()
                .setCountryCode("telegram")
                .addDomain(
                    GeositeDomain
                        .newBuilder()
                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                        .setValue("telegram.org")
                        .build(),
                ).build(),
        ).addEntry(
            GeositeEntry
                .newBuilder()
                .setCountryCode("discord")
                .addDomain(
                    GeositeDomain
                        .newBuilder()
                        .setType(GeositeDomain.Type.FULL)
                        .setValue("discord.gg")
                        .build(),
                ).build(),
        ).build()
        .toByteArray()

private fun incompleteGeositeBytes(): ByteArray =
    GeositeCatalog
        .newBuilder()
        .addEntry(
            GeositeEntry
                .newBuilder()
                .setCountryCode("youtube")
                .addDomain(
                    GeositeDomain
                        .newBuilder()
                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                        .setValue("youtube.com")
                        .build(),
                ).build(),
        ).build()
        .toByteArray()

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()

private fun ByteArray.signWith(keyPair: KeyPair): String =
    Signature
        .getInstance(StrategyPackSignatureAlgorithmSha256WithEcdsa)
        .apply {
            initSign(keyPair.private)
            update(this@signWith)
        }.sign()
        .let(Base64.getEncoder()::encodeToString)
