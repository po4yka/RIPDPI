package com.poyka.ripdpi.strategy

import android.app.Application
import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import com.poyka.ripdpi.data.StrategyPackCatalogSourceBundled
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackChannelStable
import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import com.poyka.ripdpi.storage.DefaultAtomicTextFileWriter
import com.poyka.ripdpi.testsupport.CorruptFileFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
class AssetStrategyPackRepositoryRefreshPolicyTest {
    private lateinit var application: Application
    private val refreshClock = StrategyPackClock { 1_712_345_678_000L }
    private lateinit var keyPair: KeyPair
    private lateinit var verifier: StrategyPackVerifier

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.filesDir.resolve("strategy-packs").deleteRecursively()
        keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(256) }
                .generateKeyPair()
        verifier =
            DefaultStrategyPackVerifier(
                keyResolver =
                    AppTrustedSigningKeyResolver { keyId ->
                        require(keyId == DefaultStrategyPackSigningKeyId)
                        keyPair.public
                    },
            )
    }

    @Test
    fun `refresh rejects equal sequence and preserves the cached snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 7)
            val initialSnapshot =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.1")),
                            catalogPayload = initialPayload,
                        ),
                ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val equalPayload = refreshedCatalogJson(sequence = 7)
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(equalPayload, version = "2026.04.2")),
                            catalogPayload = equalPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected anti-rollback rejection for equal sequence") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackRollbackRejectedException)
                }

            val preservedSnapshot = repository.loadSnapshot().snapshot
            assertEquals(initialSnapshot.manifestVersion, preservedSnapshot.manifestVersion)
            assertEquals(initialSnapshot.catalog.sequence, preservedSnapshot.catalog.sequence)
        }

    @Test
    fun `refresh rejects lower sequence and preserves the cached snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 9)
            val initialSnapshot =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.9")),
                            catalogPayload = initialPayload,
                        ),
                ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val rollbackPayload = refreshedCatalogJson(sequence = 8)
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload =
                                Json.encodeToString(manifestFor(rollbackPayload, version = "2026.04.10")),
                            catalogPayload = rollbackPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected anti-rollback rejection for lower sequence") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackRollbackRejectedException)
                }

            val preservedSnapshot = repository.loadSnapshot().snapshot
            assertEquals(initialSnapshot.manifestVersion, preservedSnapshot.manifestVersion)
            assertEquals(initialSnapshot.catalog.sequence, preservedSnapshot.catalog.sequence)
        }

    @Test
    fun `refresh keeps current snapshot when signature verification fails`() =
        runTest {
            val catalogPayload = refreshedCatalogJson(sequence = 7)
            val manifest =
                manifestFor(
                    catalogPayload = catalogPayload,
                    version = "2026.04.2",
                    signatureBase64 =
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(ByteArray(64)),
                )
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifest),
                            catalogPayload = catalogPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected signature verification to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackSignatureMismatchException)
                }

            val fallbackSnapshot = repository.loadSnapshot().snapshot
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
        }

    @Test
    fun `refresh still rejects signature mismatch when rollback override is enabled`() =
        runTest {
            val catalogPayload = refreshedCatalogJson(sequence = 7)
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload =
                                Json.encodeToString(
                                    manifestFor(
                                        catalogPayload = catalogPayload,
                                        version = "2026.04.2",
                                        signatureBase64 =
                                            java.util.Base64
                                                .getEncoder()
                                                .encodeToString(ByteArray(64)),
                                    ),
                                ),
                            catalogPayload = catalogPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = true,
                )
            }.onSuccess { error("Expected signature verification to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackSignatureMismatchException)
                }
        }

    @Test
    fun `refresh rejects incompatible catalog and preserves bundled snapshot`() =
        runTest {
            val catalogPayload =
                """
                {
                  "schemaVersion": 1,
                  "generatedAt": "2026-04-05T09:00:00Z",
                  "channel": "stable",
                  "minAppVersion": "9.9.9",
                  "minNativeVersion": "9.9.9",
                  "packs": []
                }
                """.trimIndent()
            val manifest =
                StrategyPackManifest(
                    version = "2026.04.3",
                    channel = StrategyPackChannelStable,
                    catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog.json",
                    catalogChecksumSha256 = catalogPayload.sha256(),
                    catalogSignatureBase64 = catalogPayload.signWith(keyPair),
                    signatureAlgorithm = StrategyPackSignatureAlgorithmSha256WithEcdsa,
                    keyId = DefaultStrategyPackSigningKeyId,
                )
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifest),
                            catalogPayload = catalogPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected compatibility gating to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackCompatibilityException)
                }

            val fallbackSnapshot = repository.loadSnapshot().snapshot
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
            assertEquals(StrategyPackRefreshPolicyAutomatic, applicationStrategyPackDefaults().refreshPolicy)
        }

    @Test
    fun `refresh rejects stale issuedAt and preserves the cached snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 7)
            createRepository(
                service =
                    FakeDownloadService(
                        manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.1")),
                        catalogPayload = initialPayload,
                    ),
            ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val stalePayload = refreshedCatalogJson(sequence = 8, issuedAt = "2024-03-01T00:00:00Z")
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(stalePayload, version = "2026.04.3")),
                            catalogPayload = stalePayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected stale catalog rejection") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackStaleCatalogException)
                }

            val preservedSnapshot = repository.loadSnapshot().snapshot
            assertEquals(7L, preservedSnapshot.catalog.sequence)
            assertEquals("2026.04.1", preservedSnapshot.manifestVersion)
        }

    @Test
    fun `refresh rejects missing anti rollback metadata`() =
        runTest {
            val missingMetadataPayload =
                refreshedCatalogJson()
                    .replace("  \"sequence\": 7,\n", "")
                    .replace("  \"issuedAt\": \"$RefreshedCatalogIssuedAt\",\n", "")
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload =
                                Json.encodeToString(manifestFor(missingMetadataPayload, version = "2026.04.4")),
                            catalogPayload = missingMetadataPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected missing metadata rejection") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackMissingSecurityMetadataException)
                }
        }

    @Test
    fun `refresh rejects invalid issuedAt metadata`() =
        runTest {
            val invalidIssuedAtPayload = refreshedCatalogJson(issuedAt = "not-a-timestamp")
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload =
                                Json.encodeToString(manifestFor(invalidIssuedAtPayload, version = "2026.04.5")),
                            catalogPayload = invalidIssuedAtPayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected invalid issuedAt rejection") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackInvalidIssuedAtException)
                }
        }

    @Test
    fun `refresh allows equal or lower sequence when rollback override is enabled`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 9)
            createRepository(
                service =
                    FakeDownloadService(
                        manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.9")),
                        catalogPayload = initialPayload,
                    ),
            ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val overridePayload = refreshedCatalogJson(sequence = 8)
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload =
                                Json.encodeToString(manifestFor(overridePayload, version = "2026.04.10")),
                            catalogPayload = overridePayload,
                        ),
                )

            val refreshed =
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = true,
                )
            val reloaded = repository.loadSnapshot().snapshot

            assertEquals(8L, refreshed.catalog.sequence)
            assertEquals(8L, reloaded.catalog.sequence)
            assertEquals("2026.04.10", reloaded.manifestVersion)
        }

    @Test
    fun `refresh override does not bypass stale catalog policy`() =
        runTest {
            val stalePayload = refreshedCatalogJson(sequence = 7, issuedAt = "2024-03-01T00:00:00Z")
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(stalePayload, version = "2026.04.11")),
                            catalogPayload = stalePayload,
                        ),
                )

            runCatching {
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = true,
                )
            }.onSuccess { error("Expected stale catalog rejection") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackStaleCatalogException)
                }
        }

    @Test
    fun `refresh preserves previous cached snapshot when atomic cache write fails`() =
        runTest {
            val initialCatalogPayload = refreshedCatalogJson(sequence = 7)
            val initialManifest = manifestFor(initialCatalogPayload, version = "2026.04.4")
            val initialRepository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(initialManifest),
                            catalogPayload = initialCatalogPayload,
                        ),
                )

            val initialSnapshot =
                initialRepository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            val corruptedPayload =
                refreshedCatalogJson(sequence = 8).replace("mobile-2026", "mobile-2026-next")
            val failingManifest = manifestFor(corruptedPayload, version = "2026.04.5")
            val failingRepository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(failingManifest),
                            catalogPayload = corruptedPayload,
                        ),
                    snapshotWriter = CorruptFileFixture("torn").failingWriter(),
                )

            runCatching {
                failingRepository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            }.onSuccess { error("Expected atomic cache write to fail") }
                .onFailure { error ->
                    assertTrue(error is IOException)
                }

            val preservedSnapshot = failingRepository.loadSnapshot().snapshot
            assertEquals(initialSnapshot.manifestVersion, preservedSnapshot.manifestVersion)
            assertEquals(initialSnapshot.packs.single().id, preservedSnapshot.packs.single().id)
        }

    @Test
    fun `refresh falls back to bundled snapshot when remote manifest is missing`() =
        runTest {
            val repository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestError =
                                IOException(
                                    "Remote request failed with HTTP 404 for $StableManifestUrl",
                                ),
                        ),
                )

            val refreshed =
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )

            assertEquals(StrategyPackCatalogSourceBundled, refreshed.source)
            assertEquals("stable", refreshed.catalog.channel)
        }

    @Test
    fun `refresh preserves cached downloaded snapshot when remote manifest is missing`() =
        runTest {
            val initialCatalogPayload = refreshedCatalogJson(sequence = 7)
            val initialManifest = manifestFor(initialCatalogPayload, version = "2026.04.4")
            val initialRepository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestPayload = Json.encodeToString(initialManifest),
                            catalogPayload = initialCatalogPayload,
                        ),
                )

            val initialSnapshot =
                initialRepository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            val failingRepository =
                createRepository(
                    service =
                        FakeDownloadService(
                            manifestError =
                                IOException(
                                    "Remote request failed with HTTP 404 for $StableManifestUrl",
                                ),
                        ),
                )

            val refreshed =
                failingRepository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )

            assertEquals(StrategyPackCatalogSourceDownloaded, refreshed.source)
            assertEquals(initialSnapshot.manifestVersion, refreshed.manifestVersion)
            assertEquals(initialSnapshot.catalog.sequence, refreshed.catalog.sequence)
        }

    private fun createRepository(
        service: StrategyPackDownloadService,
        tempFileName: String = "strategy-pack-policy-temp.json",
        snapshotWriter: AtomicTextFileWriter = DefaultAtomicTextFileWriter(),
    ): DefaultStrategyPackRepository =
        DefaultStrategyPackRepository(
            context = application,
            service = service,
            verifier = verifier,
            clock = refreshClock,
            tempFileFactory =
                StrategyPackTempFileFactory { cacheDir ->
                    cacheDir.resolve(tempFileName).apply {
                        parentFile?.mkdirs()
                        delete()
                    }
                },
            buildProvenanceProvider =
                StrategyPackBuildProvenanceProvider {
                    StrategyPackBuildProvenance(
                        appVersion = "0.0.4",
                        nativeVersion = "0.0.4",
                    )
                },
            snapshotWriter = snapshotWriter,
        )

    private fun manifestFor(
        catalogPayload: String,
        version: String,
        signatureBase64: String = catalogPayload.signWith(keyPair),
    ): StrategyPackManifest =
        StrategyPackManifest(
            version = version,
            channel = StrategyPackChannelStable,
            catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog.json",
            catalogChecksumSha256 = catalogPayload.sha256(),
            catalogSignatureBase64 = signatureBase64,
            signatureAlgorithm = StrategyPackSignatureAlgorithmSha256WithEcdsa,
            keyId = DefaultStrategyPackSigningKeyId,
        )

    private class FakeDownloadService(
        private val manifestPayload: String = "",
        private val catalogPayload: String = "",
        private val manifestError: Throwable? = null,
        private val catalogError: Throwable? = null,
    ) : StrategyPackDownloadService {
        override suspend fun downloadManifest(url: String): ByteArray {
            manifestError?.let { throw it }
            return manifestPayload.toByteArray(Charsets.UTF_8)
        }

        override suspend fun downloadCatalog(url: String): ByteArray {
            catalogError?.let { throw it }
            return catalogPayload.toByteArray(Charsets.UTF_8)
        }
    }
}
