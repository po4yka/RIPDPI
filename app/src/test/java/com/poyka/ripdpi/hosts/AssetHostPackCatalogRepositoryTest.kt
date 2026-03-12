package com.poyka.ripdpi.hosts

import android.app.Application
import com.poyka.ripdpi.data.HostPackCatalogRemoteSourceUrl
import com.poyka.ripdpi.data.HostPackCatalogSourceBundled
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.proto.GeositeCatalog
import com.poyka.ripdpi.proto.GeositeDomain
import com.poyka.ripdpi.proto.GeositeEntry
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class AssetHostPackCatalogRepositoryTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.filesDir.resolve("host-packs").deleteRecursively()
    }

    @Test
    fun `loads bundled host pack catalog from assets when cache is absent`() =
        runTest {
            val repository =
                DefaultHostPackCatalogRepository(
                    context = application,
                    service = FakeHostPackCatalogDownloadService(),
                )

            val snapshot = repository.loadSnapshot()

            assertEquals(HostPackCatalogSourceBundled, snapshot.source)
            assertEquals(listOf("youtube", "telegram", "discord"), snapshot.packs.map { it.id })
            assertTrue(snapshot.packs.all { it.hostCount == it.hosts.size })
        }

    @Test
    fun `refresh downloads verifies checksum and persists downloaded snapshot`() =
        runTest {
            val geositeBytes = curatedGeositeBytes()
            val checksum = geositeBytes.sha256()
            val repository =
                DefaultHostPackCatalogRepository(
                    context = application,
                    service =
                        FakeHostPackCatalogDownloadService(
                            checksumPayload = "$checksum  geosite.dat\n",
                            geositePayload = geositeBytes,
                        ),
                )

            val snapshot = repository.refreshSnapshot()
            val reloaded = repository.loadSnapshot()

            assertEquals(HostPackCatalogSourceDownloaded, snapshot.source)
            assertEquals(checksum, snapshot.verifiedChecksumSha256)
            assertNotNull(snapshot.lastFetchedAtEpochMillis)
            assertEquals(HostPackCatalogSourceDownloaded, reloaded.source)
            assertEquals(checksum, reloaded.verifiedChecksumSha256)
            assertEquals(HostPackCatalogRemoteSourceUrl, reloaded.packs.first().sources.single().url)
            assertEquals(listOf("youtube.com", "ytimg.com"), reloaded.packs.first().hosts)
        }

    @Test
    fun `checksum parser accepts sha256sum payload format`() {
        assertEquals(
            "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438",
            parseHostPackChecksum(
                "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438  geosite.dat\n",
            ),
        )
    }

    @Test
    fun `refresh keeps current snapshot when checksum verification fails`() =
        runTest {
            val repository =
                DefaultHostPackCatalogRepository(
                    context = application,
                    service =
                        FakeHostPackCatalogDownloadService(
                            checksumPayload =
                                "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438  geosite.dat\n",
                            geositePayload = curatedGeositeBytes(),
                        ),
                )

            runCatching { repository.refreshSnapshot() }
                .onSuccess { error("Expected checksum verification to fail") }
                .onFailure { error ->
                    assertTrue(error is HostPackChecksumMismatchException)
                }

            val fallbackSnapshot = repository.loadSnapshot()

            assertEquals(HostPackCatalogSourceBundled, fallbackSnapshot.source)
            assertTrue(fallbackSnapshot.lastFetchedAtEpochMillis == null)
        }

    private class FakeHostPackCatalogDownloadService(
        private val checksumPayload: String = "",
        private val geositePayload: ByteArray = byteArrayOf(),
    ) : HostPackCatalogDownloadService {
        override suspend fun downloadGeosite() =
            Response.success(geositePayload.toResponseBody("application/octet-stream".toMediaType()))

        override suspend fun downloadChecksum() =
            Response.success(checksumPayload.toResponseBody("text/plain".toMediaType()))
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

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()
