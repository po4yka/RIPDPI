package com.poyka.ripdpi.strategy

import android.app.Application
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import com.poyka.ripdpi.data.StrategyPackCatalog
import com.poyka.ripdpi.data.StrategyPackCatalogSourceBundled
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackChannelStable
import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.data.toStrategyPackSettingsModel
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import com.poyka.ripdpi.storage.DefaultAtomicTextFileWriter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
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

            val loadResult = repository.loadSnapshot()
            val snapshot = loadResult.snapshot

            assertEquals(StrategyPackCatalogSourceBundled, snapshot.source)
            assertEquals("stable", snapshot.catalog.channel)
            assertEquals(listOf("baseline-stable", "ech-canary"), snapshot.packs.map { it.id })
            assertEquals(
                "browser_family_v2",
                snapshot.catalog.tlsProfiles
                    .first()
                    .id,
            )
            assertNull(loadResult.cacheDegradation)
        }

    @Test
    fun `loadSnapshot falls back to bundled snapshot when cached snapshot is unreadable`() =
        runTest {
            application.filesDir.resolve(strategyPackCatalogCachePath).apply {
                parentFile?.mkdirs()
                writeText("{broken json")
            }
            val repository = createRepository(service = FakeStrategyPackDownloadService())

            val loadResult = repository.loadSnapshot()

            assertEquals(StrategyPackCatalogSourceBundled, loadResult.snapshot.source)
            assertEquals(
                ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                loadResult.cacheDegradation?.code,
            )
        }

    @Test
    fun `loadSnapshot falls back to bundled snapshot when cached snapshot is incompatible`() =
        runTest {
            application.filesDir.resolve(strategyPackCatalogCachePath).apply {
                parentFile?.mkdirs()
                writeText(
                    StrategyPackSnapshot(
                        catalog =
                            StrategyPackCatalog(
                                channel = StrategyPackChannelStable,
                                minAppVersion = "9.9.9",
                                minNativeVersion = "9.9.9",
                            ),
                        source = StrategyPackCatalogSourceDownloaded,
                        lastFetchedAtEpochMillis = refreshClock.nowEpochMillis(),
                    ).toJson(),
                )
            }
            val repository = createRepository(service = FakeStrategyPackDownloadService())

            val loadResult = repository.loadSnapshot()

            assertEquals(StrategyPackCatalogSourceBundled, loadResult.snapshot.source)
            assertEquals(
                ControlPlaneCacheDegradationCode.CachedSnapshotIncompatible,
                loadResult.cacheDegradation?.code,
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
            val reloaded = repository.loadSnapshot().snapshot

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
            val reloaded = repository.loadSnapshot().snapshot

            assertEquals(8L, refreshed.catalog.sequence)
            assertEquals(8L, reloaded.catalog.sequence)
            assertEquals("2026.04.2", reloaded.manifestVersion)
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

internal fun applicationStrategyPackDefaults() =
    com.poyka.ripdpi.data.AppSettingsSerializer
        .defaultValue
        .toStrategyPackSettingsModel()

internal const val StableManifestUrl =
    "https://raw.githubusercontent.com/poyka/ripdpi-strategy-packs/main/stable/manifest.json"
internal const val RefreshedCatalogSequence = 7L
internal const val RefreshedCatalogIssuedAt = "2024-04-05T13:00:00Z"

internal fun refreshedCatalogJson(
    sequence: Long = RefreshedCatalogSequence,
    issuedAt: String = RefreshedCatalogIssuedAt,
): String =
    RefreshedCatalogJson
        .replace("\"sequence\": $RefreshedCatalogSequence,", "\"sequence\": $sequence,")
        .replace("\"issuedAt\": \"$RefreshedCatalogIssuedAt\",", "\"issuedAt\": \"$issuedAt\",")

internal val RefreshedCatalogJson =
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
          "notes": "Browser-family rotation set."
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

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

internal fun String.signWith(keyPair: KeyPair): String {
    val signature = Signature.getInstance(StrategyPackSignatureAlgorithmSha256WithEcdsa)
    signature.initSign(keyPair.private)
    signature.update(toByteArray())
    return Base64.getEncoder().encodeToString(signature.sign())
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }
