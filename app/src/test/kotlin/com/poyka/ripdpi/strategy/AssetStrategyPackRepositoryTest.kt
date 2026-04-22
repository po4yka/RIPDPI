package com.poyka.ripdpi.strategy

import android.app.Application
import android.util.AtomicFile
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
            val catalogPayload = RefreshedCatalogJson
            val signatureBase64 = catalogPayload.signWith(keyPair)
            val checksum = catalogPayload.sha256()
            val manifest =
                StrategyPackManifest(
                    version = "2026.04.1",
                    channel = StrategyPackChannelStable,
                    catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog.json",
                    catalogChecksumSha256 = checksum,
                    catalogSignatureBase64 = signatureBase64,
                    signatureAlgorithm = StrategyPackSignatureAlgorithmSha256WithEcdsa,
                    keyId = DefaultStrategyPackSigningKeyId,
                )
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

            val snapshot = repository.refreshSnapshot(channel = StrategyPackChannelStable)
            val reloaded = repository.loadSnapshot()

            assertEquals(StrategyPackCatalogSourceDownloaded, snapshot.source)
            assertEquals(checksum, snapshot.verifiedChecksumSha256)
            assertEquals("2026.04.1", snapshot.manifestVersion)
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
    fun `refresh keeps current snapshot when signature verification fails`() =
        runTest {
            val catalogPayload = RefreshedCatalogJson
            val checksum = catalogPayload.sha256()
            val manifest =
                StrategyPackManifest(
                    version = "2026.04.2",
                    channel = StrategyPackChannelStable,
                    catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog.json",
                    catalogChecksumSha256 = checksum,
                    catalogSignatureBase64 = Base64.getEncoder().encodeToString(ByteArray(64)),
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

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected signature verification to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackSignatureMismatchException)
                }

            val fallbackSnapshot = repository.loadSnapshot()
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
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

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected compatibility gating to fail") }
                .onFailure { error ->
                    assertTrue(error is StrategyPackCompatibilityException)
                }

            val fallbackSnapshot = repository.loadSnapshot()
            assertEquals(StrategyPackCatalogSourceBundled, fallbackSnapshot.source)
            assertEquals(StrategyPackRefreshPolicyAutomatic, applicationStrategyPackDefaults().refreshPolicy)
        }

    @Test
    fun `refresh preserves previous cached snapshot when atomic cache write fails`() =
        runTest {
            val initialCatalogPayload = RefreshedCatalogJson
            val initialManifest =
                StrategyPackManifest(
                    version = "2026.04.4",
                    channel = StrategyPackChannelStable,
                    catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog-v1.json",
                    catalogChecksumSha256 = initialCatalogPayload.sha256(),
                    catalogSignatureBase64 = initialCatalogPayload.signWith(keyPair),
                    signatureAlgorithm = StrategyPackSignatureAlgorithmSha256WithEcdsa,
                    keyId = DefaultStrategyPackSigningKeyId,
                )
            val initialRepository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(initialManifest),
                            catalogPayload = initialCatalogPayload,
                        ),
                )

            val initialSnapshot = initialRepository.refreshSnapshot()
            val corruptedPayload = RefreshedCatalogJson.replace("mobile-2026", "mobile-2026-next")
            val failingManifest =
                StrategyPackManifest(
                    version = "2026.04.5",
                    channel = StrategyPackChannelStable,
                    catalogUrl = "https://cdn.example.test/strategy-packs/stable/catalog-v2.json",
                    catalogChecksumSha256 = corruptedPayload.sha256(),
                    catalogSignatureBase64 = corruptedPayload.signWith(keyPair),
                    signatureAlgorithm = StrategyPackSignatureAlgorithmSha256WithEcdsa,
                    keyId = DefaultStrategyPackSigningKeyId,
                )
            val failingRepository =
                createRepository(
                    service =
                        FakeStrategyPackDownloadService(
                            manifestPayload = Json.encodeToString(failingManifest),
                            catalogPayload = corruptedPayload,
                        ),
                    snapshotWriter = FailingAtomicTextFileWriter("torn"),
                )

            runCatching { failingRepository.refreshSnapshot() }
                .onSuccess { error("Expected atomic cache write to fail") }
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
}

private class FailingAtomicTextFileWriter(
    private val partialPayload: String,
) : AtomicTextFileWriter {
    override fun write(
        file: File,
        payload: String,
    ) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(partialPayload.toByteArray(Charsets.UTF_8))
            throw IOException("Simulated cache write failure")
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}

private fun applicationStrategyPackDefaults() =
    com.poyka.ripdpi.data.AppSettingsSerializer
        .defaultValue
        .toStrategyPackSettingsModel()

private val RefreshedCatalogJson =
    """
    {
      "schemaVersion": 3,
      "generatedAt": "2026-04-18T13:00:00Z",
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
