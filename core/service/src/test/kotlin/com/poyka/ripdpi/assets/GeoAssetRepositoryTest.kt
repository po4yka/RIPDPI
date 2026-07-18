package com.poyka.ripdpi.assets

import android.app.Application
import android.net.Uri
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.data.assets.MinGeoAssetBytes
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeoAssetRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val application: Application = RuntimeEnvironment.getApplication()
    private val settingsRepository = FakeAppSettingsRepository()
    private lateinit var geoDirectory: File

    @Before
    fun setUp() {
        geoDirectory = File(resolveGeoDatabasePaths(application).geoipDbPath).parentFile!!
        geoDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        geoDirectory.deleteRecursively()
    }

    @Test
    fun `valid stream at cap replaces target byte for byte`() {
        val target = temporaryFolder.newFile("geoip.db")
        target.writeText("old")
        val payload = validPayload(MinGeoAssetBytes)

        streamGeoAssetToTarget(ByteArrayInputStream(payload), target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `stream one byte over cap preserves target and removes temp file`() {
        val target = temporaryFolder.newFile("geoip.db")
        val previous = "working database".toByteArray()
        target.writeBytes(previous)
        val maxBytes = MinGeoAssetBytes.toLong()

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetToTarget(
                    ByteArrayInputStream(validPayload(MinGeoAssetBytes + 1)),
                    target,
                    maxBytes,
                )
            }

        assertEquals(GeoAssetIntegrityFailure.TooLarge, error.reason)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `html prefix preserves target and removes temp file`() {
        val target = temporaryFolder.newFile("geosite.db")
        val previous = "working database".toByteArray()
        target.writeBytes(previous)
        val payload = ByteArray(MinGeoAssetBytes) { 'x'.code.toByte() }
        "<!doctype html>".toByteArray().copyInto(payload)

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetToTarget(ByteArrayInputStream(payload), target, payload.size.toLong())
            }

        assertEquals(GeoAssetIntegrityFailure.InvalidPayload, error.reason)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `zero length bulk read makes progress and imports subsequent content`() {
        val target = temporaryFolder.newFile("geoip.db")
        val payload = validPayload(MinGeoAssetBytes + 8)
        val stream = ZeroThenDataInputStream(payload)

        streamGeoAssetToTarget(stream, target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
        assertTrue(stream.zeroReadObserved)
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `read failure preserves target and removes temp file`() {
        val target = temporaryFolder.newFile("geoip.db")
        val previous = "working database".toByteArray()
        target.writeBytes(previous)

        assertThrows(IOException::class.java) {
            streamGeoAssetToTarget(
                FailAfterFirstChunkInputStream(validPayload(MinGeoAssetBytes)),
                target,
                MinGeoAssetBytes.toLong(),
            )
        }

        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `repository opens file uri installs content and updates timestamp`() =
        runTest {
            val payload = validPayload(MinGeoAssetBytes + 16)
            val document = temporaryFolder.newFile("selected.db").apply { writeBytes(payload) }
            val repository = repository()

            repository.importLocalAsset(GeoAssetKind.Geoip, Uri.fromFile(document))

            val target = File(resolveGeoDatabasePaths(application).geoipDbPath)
            assertArrayEquals(payload, target.readBytes())
            assertTrue(settingsRepository.snapshot().geoAssetLastUpdatedEpochMillis > 0L)
            assertNoTemporaryFiles(target.parentFile!!)
        }

    @Test
    fun `uri input provider owns stream closure`() {
        val target = temporaryFolder.newFile("geoip.db")
        val stream = CloseTrackingInputStream(validPayload(MinGeoAssetBytes))

        streamGeoAssetUriToTarget(
            uri = Uri.parse("content://test/geoip.db"),
            target = target,
            maxBytes = MinGeoAssetBytes.toLong(),
            openInput = { stream },
        )

        assertTrue(stream.closed)
    }

    @Test
    fun `uri open failures report stable reason and preserve cause`() {
        listOf(
            IOException("provider failed to open"),
            SecurityException("provider denied access"),
        ).forEachIndexed { index, failure ->
            val target = temporaryFolder.newFile("unopenable-$index.db")

            val error =
                assertThrows(GeoAssetIntegrityException::class.java) {
                    streamGeoAssetUriToTarget(
                        uri = Uri.parse("content://test/missing-$index.db"),
                        target = target,
                        openInput = { throw failure },
                    )
                }

            assertEquals(GeoAssetIntegrityFailure.UnableToOpen, error.reason)
            assertEquals(failure, error.cause)
        }
    }

    @Test
    fun `uri read failure reports stable reason and preserves cause`() {
        val target = temporaryFolder.newFile("geoip.db")
        val failure = IOException("provider stream failed")

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetUriToTarget(
                    uri = Uri.parse("content://test/broken.db"),
                    target = target,
                    openInput = {
                        object : InputStream() {
                            override fun read(): Int = throw failure
                        }
                    },
                )
            }

        assertEquals(GeoAssetIntegrityFailure.UnableToOpen, error.reason)
        assertEquals(failure, error.cause)
    }

    @Test
    fun `uri close failure preserves previous target and removes temporary file`() {
        val previous = validPayload(MinGeoAssetBytes).reversedArray()
        val replacement = validPayload(MinGeoAssetBytes)
        val target = temporaryFolder.newFile("geoip.db").apply { writeBytes(previous) }
        val failure = IOException("provider failed to close")
        val stream =
            object : ByteArrayInputStream(replacement) {
                override fun close() = throw failure
            }

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetUriToTarget(
                    uri = Uri.parse("content://test/close-failure.db"),
                    target = target,
                    maxBytes = MinGeoAssetBytes.toLong(),
                    openInput = { stream },
                )
            }

        assertEquals(GeoAssetIntegrityFailure.UnableToOpen, error.reason)
        assertEquals(failure, error.cause)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `failed atomic rename preserves previous target`() {
        val previous = validPayload(MinGeoAssetBytes).reversedArray()
        val replacement = validPayload(MinGeoAssetBytes)
        val target = temporaryFolder.newFile("geoip.db").apply { writeBytes(previous) }
        val temp = temporaryFolder.newFile("geo-asset-replacement.tmp").apply { writeBytes(replacement) }

        assertThrows(IOException::class.java) {
            replaceGeoAssetTempFile(temp, target, rename = { _, _ -> false })
        }

        assertArrayEquals(previous, target.readBytes())
        assertArrayEquals(replacement, temp.readBytes())
    }

    private fun repository(): DefaultGeoAssetRepository =
        DefaultGeoAssetRepository(
            context = application,
            settingsRepository = settingsRepository,
            downloadService =
                object : GeoAssetDownloadService {
                    override suspend fun fetchLatestReleaseJson(apiUrl: String): String = error("not used")

                    override suspend fun downloadAsset(downloadUrl: String): ByteArray = error("not used")
                },
        )

    private fun validPayload(size: Int): ByteArray = ByteArray(size) { index -> (index + 1).toByte() }

    private fun assertNoTemporaryFiles(directory: File) {
        assertFalse(
            directory.listFiles().orEmpty().any { it.name.startsWith("geo-asset-") && it.name.endsWith(".tmp") },
        )
    }

    private class ZeroThenDataInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var zeroReadObserved: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (!zeroReadObserved) {
                zeroReadObserved = true
                return 0
            }
            return delegate.read(buffer, offset, length)
        }
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailAfterFirstChunkInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnedChunk = false

        override fun read(): Int = throw IOException("synthetic read failure")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (returnedChunk) throw IOException("synthetic read failure")
            returnedChunk = true
            return delegate.read(buffer, offset, minOf(length, MinGeoAssetBytes / 2))
        }
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
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
