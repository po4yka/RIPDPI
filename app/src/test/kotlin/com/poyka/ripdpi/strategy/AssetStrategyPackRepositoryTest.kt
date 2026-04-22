package com.poyka.ripdpi.strategy

import android.app.Application
import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import com.poyka.ripdpi.data.StrategyPackCatalogSourceBundled
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackChannelStable
import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.data.toStrategyPackSettingsModel
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import com.poyka.ripdpi.storage.DefaultAtomicTextFileWriter
import com.poyka.ripdpi.testsupport.CorruptFileFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class AssetStrategyPackRepositoryTest {
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
    fun `loads bundled strategy pack catalog from assets when cache is absent`() =
        runTest {
            val repository = createRepository(service = FakeStrategyPackDownloadService())

            val snapshot = repository.loadSnapshot()

            assertEquals(StrategyPackCatalogSourceBundled, snapshot.source)
            assertEquals("stable", snapshot.catalog.channel)
            assertEquals(listOf("baseline-stable", "ech-canary"), snapshot.packs.map { it.id })
            assertEquals(
                "browser_family_v2",
                snapshot.catalog.tlsProfiles
                    .first()
                    .id,
            )
        }

    @Test
    fun `refresh downloads verifies signature and persists downloaded snapshot`() =
        runTest {
            val catalogPayload = refreshedCatalogJson(sequence = 7)
            val checksum = catalogPayload.sha256()
            val manifest = manifestFor(catalogPayload, version = "2026.04.1")
            val tempFile = application.cacheDir.resolve("strategy-pack-refresh-success.json")
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(manifest),
                            catalogPayload = catalogPayload,
                        ),
                    tempFileName = tempFile.name,
                )

            val snapshot =
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            val reloaded = repository.loadSnapshot()

            assertEquals(StrategyPackCatalogSourceDownloaded, snapshot.source)
            assertEquals(checksum, snapshot.verifiedChecksumSha256)
            assertEquals("2026.04.1", snapshot.manifestVersion)
            assertEquals(7L, snapshot.catalog.sequence)
            assertEquals(RefreshedCatalogIssuedAt, snapshot.catalog.issuedAt)
            assertEquals(
                "browser_family_v2",
                snapshot.catalog.tlsProfiles
                    .single()
                    .id,
            )
            assertEquals(
                listOf(
                    "chrome_stable",
                    "chrome_desktop_stable",
                    "firefox_stable",
                    "firefox_ech_stable",
                    "safari_stable",
                    "edge_stable",
                ),
                snapshot.catalog.tlsProfiles
                    .single()
                    .allowedProfileIds,
            )
            assertEquals(StrategyPackCatalogSourceDownloaded, reloaded.source)
            assertEquals("mobile-2026", reloaded.packs.single().id)
            assertFalse(tempFile.exists())
        }

    @Test
    fun `refresh accepts higher sequence and persists the newer snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 7)
            createRepository(
                service =
                    FakeStrategyPackDownloadService(
                        manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.1")),
                        catalogPayload = initialPayload,
                    ),
            ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val updatedPayload = refreshedCatalogJson(sequence = 8)
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(updatedPayload, version = "2026.04.2")),
                            catalogPayload = updatedPayload,
                        ),
                )

            val refreshed =
                repository.refreshSnapshot(
                    channel = StrategyPackChannelStable,
                    allowRollbackOverride = false,
                )
            val reloaded = repository.loadSnapshot()

            assertEquals(8L, refreshed.catalog.sequence)
            assertEquals(8L, reloaded.catalog.sequence)
            assertEquals("2026.04.2", reloaded.manifestVersion)
        }

    @Test
    fun `refresh rejects equal sequence and preserves the cached snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 7)
            val initialSnapshot =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.1")),
                            catalogPayload = initialPayload,
                        ),
                ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val equalPayload = refreshedCatalogJson(sequence = 7)
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
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

            val preservedSnapshot = repository.loadSnapshot()
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
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.9")),
                            catalogPayload = initialPayload,
                        ),
                ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val rollbackPayload = refreshedCatalogJson(sequence = 8)
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
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

            val preservedSnapshot = repository.loadSnapshot()
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
                    signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(64)),
                )
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
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

            val fallbackSnapshot = repository.loadSnapshot()
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
        }

    @Test
    fun `refresh still rejects signature mismatch when rollback override is enabled`() =
        runTest {
            val catalogPayload = refreshedCatalogJson(sequence = 7)
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload =
                                Json.encodeToString(
                                    manifestFor(
                                        catalogPayload = catalogPayload,
                                        version = "2026.04.2",
                                        signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(64)),
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
                        FakeStrategyPackDownloadService(
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

            val fallbackSnapshot = repository.loadSnapshot()
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
            assertEquals(StrategyPackRefreshPolicyAutomatic, applicationStrategyPackDefaults().refreshPolicy)
        }

    @Test
    fun `refresh rejects stale issuedAt and preserves the cached snapshot`() =
        runTest {
            val initialPayload = refreshedCatalogJson(sequence = 7)
            createRepository(
                service =
                    FakeStrategyPackDownloadService(
                        manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.1")),
                        catalogPayload = initialPayload,
                    ),
            ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val stalePayload = refreshedCatalogJson(sequence = 8, issuedAt = "2024-03-01T00:00:00Z")
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
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

            val preservedSnapshot = repository.loadSnapshot()
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
                        FakeStrategyPackDownloadService(
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
                        FakeStrategyPackDownloadService(
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
                    FakeStrategyPackDownloadService(
                        manifestPayload = Json.encodeToString(manifestFor(initialPayload, version = "2026.04.9")),
                        catalogPayload = initialPayload,
                    ),
            ).refreshSnapshot(channel = StrategyPackChannelStable, allowRollbackOverride = false)

            val overridePayload = refreshedCatalogJson(sequence = 8)
            val repository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
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
            val reloaded = repository.loadSnapshot()

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
                        FakeStrategyPackDownloadService(
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
                        FakeStrategyPackDownloadService(
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
                        FakeStrategyPackDownloadService(
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

            val preservedSnapshot = failingRepository.loadSnapshot()
            assertEquals(initialSnapshot.manifestVersion, preservedSnapshot.manifestVersion)
            assertEquals(initialSnapshot.packs.single().id, preservedSnapshot.packs.single().id)
        }

    private fun createRepository(
        service: StrategyPackDownloadService,
        tempFileName: String = "strategy-pack-temp.json",
        snapshotWriter: AtomicTextFileWriter = DefaultAtomicTextFileWriter(),
    ): DefaultStrategyPackRepository =
        DefaultStrategyPackRepository(
            context = application,
            service = service,
            verifier = verifier,
            clock = refreshClock,
            tempFileFactory = strategyPackTempFileFactory(tempFileName),
            buildProvenanceProvider =
                StrategyPackBuildProvenanceProvider {
                    StrategyPackBuildProvenance(
                        appVersion = "0.0.4",
                        nativeVersion = "0.0.4",
                    )
                },
            snapshotWriter = snapshotWriter,
        )

    private class FakeStrategyPackDownloadService(
        private val manifestPayload: String = "",
        private val catalogPayload: String = "",
    ) : StrategyPackDownloadService {
        override suspend fun downloadManifest(url: String): ByteArray = manifestPayload.toByteArray(Charsets.UTF_8)

        override suspend fun downloadCatalog(url: String): ByteArray = catalogPayload.toByteArray(Charsets.UTF_8)
    }

    private fun strategyPackTempFileFactory(fileName: String): StrategyPackTempFileFactory =
        StrategyPackTempFileFactory { cacheDir ->
            cacheDir.resolve(fileName).apply {
                parentFile?.mkdirs()
                delete()
            }
        }

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
}

private fun applicationStrategyPackDefaults() =
    com.poyka.ripdpi.data.AppSettingsSerializer
        .defaultValue
        .toStrategyPackSettingsModel()

private const val RefreshedCatalogSequence = 7L
private const val RefreshedCatalogIssuedAt = "2024-04-05T13:00:00Z"

private fun refreshedCatalogJson(
    sequence: Long = RefreshedCatalogSequence,
    issuedAt: String = RefreshedCatalogIssuedAt,
): String =
    RefreshedCatalogJson
        .replace("\"sequence\": $RefreshedCatalogSequence,", "\"sequence\": $sequence,")
        .replace("\"issuedAt\": \"$RefreshedCatalogIssuedAt\",", "\"issuedAt\": \"$issuedAt\",")

private val RefreshedCatalogJson =
    """
    {
      "schemaVersion": 3,
      "generatedAt": "2024-04-05T13:00:00Z",
      "sequence": 7,
      "issuedAt": "2024-04-05T13:00:00Z",
      "channel": "stable",
      "minAppVersion": "0.0.4",
      "minNativeVersion": "0.0.4",
      "notes": "Downloaded strategy pack catalog.",
      "tlsProfiles": [
        {
          "id": "browser_family_v2",
          "title": "Browser-family stable rotation",
          "catalogVersion": "v1",
          "allowedProfileIds": [
            "chrome_stable",
            "chrome_desktop_stable",
            "firefox_stable",
            "firefox_ech_stable",
            "safari_stable",
            "edge_stable"
          ],
          "rotationEnabled": true,
          "browserFamilies": [
            "chrome",
            "firefox",
            "safari",
            "edge"
          ],
          "echPolicy": "opportunistic",
          "proxyModeNotice": "browser_native_tls_suppressed",
          "acceptanceCorpusRef": "phase11_tls_template_acceptance",
          "acceptanceReportRef": "tls_template_acceptance_report",
          "reviewedAt": "2026-04-18",
          "notes": "Phase 11 browser-family rotation set."
        }
      ],
      "morphPolicies": [
        {
          "id": "balanced",
          "title": "Balanced"
        }
      ],
      "hostLists": [
        {
          "id": "video",
          "title": "Video",
          "hosts": [
            "youtube.com"
          ]
        }
      ],
      "featureFlags": [
        {
          "id": "cloudflare_consume_validation",
          "enabled": true,
          "scope": "pack"
        }
      ],
      "packs": [
        {
          "id": "mobile-2026",
          "version": "2026.04.1",
          "title": "Mobile 2026",
          "description": "Aggressive mobile-network strategy pack.",
          "tlsProfileSetId": "browser_family_v2",
          "morphPolicyId": "balanced",
          "hostListRefs": [
            "video"
          ],
          "triggerMetadata": [
            "torst",
            "http_redirect"
          ],
          "featureFlagIds": [
            "cloudflare_consume_validation"
          ],
          "strategies": [
            {
              "id": "tlsrec_fake_rich",
              "label": "TLS fake rich",
              "recommendedProxyConfigJson": "{\"strategyPackId\":\"mobile-2026\"}",
              "candidateIds": [
                "tlsrec_fake_rich"
              ]
            }
          ]
        }
      ]
    }
    """.trimIndent()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

private fun String.signWith(keyPair: KeyPair): String {
    val signature = Signature.getInstance(StrategyPackSignatureAlgorithmSha256WithEcdsa)
    signature.initSign(keyPair.private)
    signature.update(toByteArray())
    return Base64.getEncoder().encodeToString(signature.sign())
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }
