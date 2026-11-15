package com.poyka.ripdpi.assets

import android.app.Application
import android.net.Uri
import com.poyka.ripdpi.core.GeoDatabaseValidator
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.data.assets.MinGeoAssetBytes
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeoAssetPublicationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val application: Application = RuntimeEnvironment.getApplication()
    private val settingsRepository = FakeAppSettingsRepository()
    private lateinit var geoDirectory: File

    @Before
    fun setUp() {
        geoDirectory = File(resolveGeoDatabasePaths(application).geoDirPath)
        geoDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        geoDirectory.deleteRecursively()
    }

    @Test
    fun `same tag repairs missing and changed database files`() =
        runTest {
            val downloads = TaggedGeoDownloads()
            val repository = repository(downloads)
            assertTrue(repository.checkAndUpdate().updatedAny)
            val target = File(resolveGeoDatabasePaths(application).geoipDbPath)
            assertTrue(target.delete())

            val missingResult = repository.checkAndUpdate()
            assertTrue(missingResult.geoipUpdated)
            assertFalse(missingResult.geositeUpdated)
            assertArrayEquals(downloads.geoipPayload, target.readBytes())

            target.writeBytes(validPayload(MinGeoAssetBytes))
            assertTrue(repository.checkAndUpdate().geoipUpdated)
            assertArrayEquals(downloads.geoipPayload, target.readBytes())
        }

    @Test
    fun `same tag from another provider replaces installed files`() =
        runTest {
            val downloads = TaggedGeoDownloads()
            val repository = repository(downloads)
            assertTrue(repository.checkAndUpdate().updatedAny)
            settingsRepository.update { geoAssetProviderId = "soffchen" }
            downloads.geoipPayload = GeoAssetTestFixtures.asn()
            downloads.geositePayload = GeoAssetTestFixtures.geosite("other.example")

            val result = repository.checkAndUpdate()
            assertTrue(result.geoipUpdated)
            assertTrue(result.geositeUpdated)
            val paths = resolveGeoDatabasePaths(application)
            assertArrayEquals(downloads.geoipPayload, File(paths.geoipDbPath).readBytes())
            assertArrayEquals(downloads.geositePayload, File(paths.geositeDbPath).readBytes())
        }

    @Test
    fun `local import clears remote provenance and only the imported kind version`() =
        runTest {
            val downloads = TaggedGeoDownloads()
            val repository = repository(downloads)
            repository.checkAndUpdate()
            val localPayload = GeoAssetTestFixtures.asn()
            val document = temporaryFolder.newFile("override.db").apply { writeBytes(localPayload) }

            repository.importLocalAsset(GeoAssetKind.Geoip, Uri.fromFile(document))
            val paths = resolveGeoDatabasePaths(application)
            assertArrayEquals(localPayload, File(paths.geoipDbPath).readBytes())
            assertEquals("", settingsRepository.snapshot().geoAssetGeoipVersionTag)
            assertEquals("release-1", settingsRepository.snapshot().geoAssetGeositeVersionTag)

            val result = repository.checkAndUpdate()
            assertTrue(result.geoipUpdated)
            assertFalse(result.geositeUpdated)
            assertArrayEquals(downloads.geoipPayload, File(paths.geoipDbPath).readBytes())
        }

    @Test
    fun `same provider tag and unchanged database bytes remain up to date`() =
        runTest {
            val downloads = TaggedGeoDownloads()
            val repository = repository(downloads)
            assertTrue(repository.checkAndUpdate().updatedAny)
            downloads.downloadFailure = IOException("An unchanged asset must not need another download")

            val result = repository.checkAndUpdate()
            assertFalse(result.updatedAny)
            assertTrue(result.anyChecked)
        }

    @Test
    fun `native validation failure preserves local target and metadata`() =
        runTest {
            val paths = resolveGeoDatabasePaths(application)
            settingsRepository.update {
                geoAssetGeoipVersionTag = "last-good"
                geoAssetLastUpdatedEpochMillis = 123L
            }
            val previous = GeoAssetTestFixtures.country()
            File(paths.geoipDbPath).apply { parentFile?.mkdirs() }.writeBytes(previous)
            val document = temporaryFolder.newFile("invalid-local.db").apply { writeBytes(byteArrayOf(0, 1)) }
            val repository =
                repository(
                    validator =
                        GeoDatabaseValidator {
                            _,
                            _,
                            ->
                            throw IllegalArgumentException("Invalid native database")
                        },
                )

            val error =
                runCatching {
                    repository.importLocalAsset(GeoAssetKind.Geoip, Uri.fromFile(document))
                }.exceptionOrNull()

            assertEquals(GeoAssetIntegrityFailure.InvalidPayload, (error as? GeoAssetIntegrityException)?.reason)
            assertArrayEquals(previous, File(paths.geoipDbPath).readBytes())
            assertEquals(123L, settingsRepository.snapshot().geoAssetLastUpdatedEpochMillis)
            assertEquals("last-good", settingsRepository.snapshot().geoAssetGeoipVersionTag)
            assertNoTemporaryFiles(geoDirectory)
        }

    @Test
    fun `unexpected validation failure removes the prepared local file`() {
        val previous = GeoAssetTestFixtures.country()
        val target = temporaryFolder.newFile("retained.db").apply { writeBytes(previous) }

        assertThrows(IllegalStateException::class.java) {
            streamGeoAssetUriToTarget(
                uri = Uri.parse("content://documents/validation-failure.db"),
                target = target,
                openInput = { ByteArrayInputStream(GeoAssetTestFixtures.asn()) },
                validateTemp = { throw IllegalStateException("Native validation did not finish") },
            )
        }

        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `cancellation during download validation preserves installed files and metadata`() =
        runTest {
            val downloads = TaggedGeoDownloads()
            repository(downloads).checkAndUpdate()
            val beforeSettings = settingsRepository.snapshot()
            val beforeFiles = checkNotNull(geoDirectory.listFiles()).associate { it.name to it.readBytes() }
            downloads.tag = "release-2"
            downloads.geoipPayload = GeoAssetTestFixtures.asn()
            val validationStarted = CountDownLatch(1)
            val releaseValidation = CountDownLatch(1)
            val repository =
                repository(
                    downloads,
                    GeoDatabaseValidator { _, _ ->
                        validationStarted.countDown()
                        check(releaseValidation.await(5, TimeUnit.SECONDS))
                    },
                )
            val updateJob = backgroundScope.launch(Dispatchers.Default) { repository.checkAndUpdate() }
            try {
                withContext(Dispatchers.IO) { assertTrue(validationStarted.await(5, TimeUnit.SECONDS)) }
                updateJob.cancel()
                releaseValidation.countDown()
                updateJob.join()

                assertTrue(updateJob.isCancelled)
                assertEquals(beforeSettings, settingsRepository.snapshot())
                val afterFiles = checkNotNull(geoDirectory.listFiles()).associate { it.name to it.readBytes() }
                assertEquals(beforeFiles.keys, afterFiles.keys)
                beforeFiles.forEach { (name, content) -> assertArrayEquals(content, afterFiles.getValue(name)) }
                assertNoTemporaryFiles(geoDirectory)
            } finally {
                releaseValidation.countDown()
                updateJob.cancel()
            }
        }

    private inner class TaggedGeoDownloads : GeoAssetDownloadService {
        var geoipPayload = GeoAssetTestFixtures.country()
        var geositePayload = GeoAssetTestFixtures.geosite()
        var downloadFailure: IOException? = null
        var tag = "release-1"

        override suspend fun fetchLatestReleaseJson(apiUrl: String): String = """{"tag_name":"$tag"}"""

        override suspend fun downloadAsset(downloadUrl: String): ByteArray {
            downloadFailure?.let { throw it }
            return if (downloadUrl.endsWith("geoip.db")) geoipPayload else geositePayload
        }
    }

    private fun repository(
        downloadService: GeoAssetDownloadService =
            object : GeoAssetDownloadService {
                override suspend fun fetchLatestReleaseJson(apiUrl: String): String = error("not used")

                override suspend fun downloadAsset(downloadUrl: String): ByteArray = error("not used")
            },
        validator: GeoDatabaseValidator = GeoDatabaseValidator { _, _ -> },
    ): DefaultGeoAssetRepository =
        DefaultGeoAssetRepository(
            context = application,
            settingsRepository = settingsRepository,
            downloadService = downloadService,
            validator = validator,
        )

    private fun validPayload(size: Int): ByteArray = ByteArray(size) { index -> (index + 1).toByte() }

    private fun assertNoTemporaryFiles(directory: File) {
        assertFalse(
            directory.listFiles().orEmpty().any { it.name.startsWith("geo-asset-") && it.name.endsWith(".tmp") },
        )
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
        var updateFailure: IOException? = null

        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            updateFailure?.let { throw it }
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }
    }
}
