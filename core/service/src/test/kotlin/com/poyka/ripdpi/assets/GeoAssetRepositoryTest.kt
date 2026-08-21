package com.poyka.ripdpi.assets

import android.app.Application
import android.net.Uri
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.assets.CustomAssetProviderId
import com.poyka.ripdpi.data.assets.GeoAssetKind
import com.poyka.ripdpi.data.assets.MinGeoAssetBytes
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        geoDirectory = File(resolveGeoDatabasePaths(application).geoDirPath)
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
    fun `read failure reports unable to open and preserves target`() {
        val target = temporaryFolder.newFile("geoip.db")
        val previous = "working database".toByteArray()
        target.writeBytes(previous)

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetToTarget(
                    FailAfterFirstChunkInputStream(validPayload(MinGeoAssetBytes)),
                    target,
                    MinGeoAssetBytes.toLong(),
                )
            }

        assertEquals(GeoAssetIntegrityFailure.UnableToOpen, error.reason)
        assertEquals("synthetic read failure", error.cause?.message)
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
    fun `local import metadata write failure reports storage after installing asset`() =
        runTest {
            val payload = validPayload(MinGeoAssetBytes + 16)
            val document = temporaryFolder.newFile("selected-storage-failure.db").apply { writeBytes(payload) }
            val failure = IOException("metadata storage unavailable")
            settingsRepository.updateFailure = failure

            val error =
                try {
                    repository().importLocalAsset(GeoAssetKind.Geoip, Uri.fromFile(document))
                    error("Expected metadata persistence to fail")
                } catch (error: GeoAssetIntegrityException) {
                    error
                }

            val target = File(resolveGeoDatabasePaths(application).geoipDbPath)
            assertEquals(GeoAssetIntegrityFailure.InstallFailed, error.reason)
            assertEquals(failure, error.cause)
            assertArrayEquals(payload, target.readBytes())
            assertEquals(0L, settingsRepository.snapshot().geoAssetLastUpdatedEpochMillis)
            assertNoTemporaryFiles(target.parentFile!!)
        }

    @Test
    fun `network update metadata write failure reports storage instead of network`() =
        runTest {
            val payload = validPayload(MinGeoAssetBytes + 16)
            settingsRepository.update {
                geoAssetProviderId = CustomAssetProviderId
                geoAssetCustomBaseUrl = "https://assets.example/releases/latest/download"
            }
            val repository =
                repository(
                    object : GeoAssetDownloadService {
                        override suspend fun fetchLatestReleaseJson(apiUrl: String): String = error("not used")

                        override suspend fun downloadAsset(downloadUrl: String): ByteArray = payload
                    },
                )
            val failure = IOException("metadata storage unavailable")
            settingsRepository.updateFailure = failure

            val error =
                try {
                    repository.checkAndUpdate()
                    error("Expected metadata persistence to fail")
                } catch (error: GeoAssetIntegrityException) {
                    error
                }

            assertEquals(GeoAssetIntegrityFailure.InstallFailed, error.reason)
            assertEquals(failure, error.cause)
            assertArrayEquals(payload, File(resolveGeoDatabasePaths(application).geoipDbPath).readBytes())
            assertArrayEquals(payload, File(resolveGeoDatabasePaths(application).geositeDbPath).readBytes())
            assertNoTemporaryFiles(geoDirectory)
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
    fun `cancelled local stream preserves target and removes temporary file`() =
        runTest {
            val previous = validPayload(MinGeoAssetBytes).reversedArray()
            val replacement = validPayload(MinGeoAssetBytes + DEFAULT_BUFFER_SIZE)
            val target = temporaryFolder.newFile("cancelled-geoip.db").apply { writeBytes(previous) }
            val stream = InterruptibleAfterFirstChunkInputStream(replacement)
            val importJob =
                launch(Dispatchers.Default) {
                    streamGeoAssetUriToTargetInterruptibly(
                        uri = Uri.parse("content://test/cancelled.db"),
                        target = target,
                        maxBytes = replacement.size.toLong(),
                        openInput = { stream },
                    )
                }

            try {
                assertTrue(stream.blockedReadStarted.await(ReadStartTimeoutSeconds, TimeUnit.SECONDS))
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.cancelAndJoin() }
                }
            } finally {
                stream.releaseBlockedRead.countDown()
                importJob.cancel()
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.join() }
                }
            }

            assertTrue(importJob.isCancelled)
            assertArrayEquals(previous, target.readBytes())
            assertNoTemporaryFiles(target.parentFile!!)
        }

    @Test
    fun `cancellation after temp preparation removes temporary file`() {
        val previous = validPayload(MinGeoAssetBytes).reversedArray()
        val replacement = validPayload(MinGeoAssetBytes)
        val target = temporaryFolder.newFile("handoff-cancelled-geoip.db").apply { writeBytes(previous) }
        val stream = CloseTrackingInputStream(replacement)

        assertThrows(CancellationException::class.java) {
            streamGeoAssetUriToTarget(
                uri = Uri.parse("content://test/handoff-cancelled.db"),
                target = target,
                maxBytes = replacement.size.toLong(),
                openInput = { stream },
                cancellationCheck = {
                    if (stream.closed) throw CancellationException("synthetic pre-install cancellation")
                },
            )
        }

        assertTrue(stream.closed)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `cancellation during interruptible handoff removes prepared temporary file`() =
        runTest {
            val previous = validPayload(MinGeoAssetBytes).reversedArray()
            val replacement = validPayload(MinGeoAssetBytes)
            val target = temporaryFolder.newFile("interruptible-handoff-geoip.db").apply { writeBytes(previous) }
            val preparationCompleted = CountDownLatch(1)
            val releaseHandoff = CountDownLatch(1)
            val importJob =
                launch(Dispatchers.Default) {
                    streamGeoAssetUriToTargetInterruptibly(
                        uri = Uri.parse("content://test/interruptible-handoff.db"),
                        target = target,
                        maxBytes = replacement.size.toLong(),
                        openInput = { ByteArrayInputStream(replacement) },
                        afterPrepare = {
                            preparationCompleted.countDown()
                            awaitIgnoringInterrupts(releaseHandoff)
                        },
                    )
                }

            try {
                assertTrue(preparationCompleted.await(ReadStartTimeoutSeconds, TimeUnit.SECONDS))
                importJob.cancel()
                releaseHandoff.countDown()
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.join() }
                }
            } finally {
                releaseHandoff.countDown()
                importJob.cancel()
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.join() }
                }
            }

            assertTrue(importJob.isCancelled)
            assertArrayEquals(previous, target.readBytes())
            assertNoTemporaryFiles(target.parentFile!!)
        }

    @Test
    fun `cancellation at install boundary completes file and metadata commit together`() =
        runTest {
            val previous = validPayload(MinGeoAssetBytes).reversedArray()
            val replacement = validPayload(MinGeoAssetBytes + 32)
            val target = temporaryFolder.newFile("commit-boundary-geoip.db").apply { writeBytes(previous) }
            val replaceStarted = CountDownLatch(1)
            val continueReplace = CountDownLatch(1)
            val metadataPersisted = CompletableDeferred<Unit>()
            val importJob =
                launch(Dispatchers.Default) {
                    streamGeoAssetUriToTargetInterruptibly(
                        uri = Uri.parse("content://test/commit-boundary.db"),
                        target = target,
                        maxBytes = replacement.size.toLong(),
                        openInput = { ByteArrayInputStream(replacement) },
                        replaceTemp = { source, destination ->
                            replaceStarted.countDown()
                            continueReplace.await()
                            replaceGeoAssetTempFile(source, destination)
                        },
                        afterInstall = { metadataPersisted.complete(Unit) },
                    )
                }

            try {
                assertTrue(replaceStarted.await(ReadStartTimeoutSeconds, TimeUnit.SECONDS))
                importJob.cancel()
                continueReplace.countDown()
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.join() }
                }
            } finally {
                continueReplace.countDown()
                importJob.cancel()
                withContext(Dispatchers.Default) {
                    withTimeout(CancellationTimeoutMillis) { importJob.join() }
                }
            }

            assertTrue(importJob.isCancelled)
            assertArrayEquals(replacement, target.readBytes())
            assertTrue(metadataPersisted.isCompleted)
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

    @Test
    fun `uri atomic rename failure reports install failure and cleans temp`() {
        val previous = validPayload(MinGeoAssetBytes).reversedArray()
        val replacement = validPayload(MinGeoAssetBytes)
        val target = temporaryFolder.newFile("uri-geoip.db").apply { writeBytes(previous) }
        val failure = IOException("storage rename failed")

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                streamGeoAssetUriToTarget(
                    uri = Uri.parse("content://test/geoip.db"),
                    target = target,
                    maxBytes = replacement.size.toLong(),
                    openInput = { ByteArrayInputStream(replacement) },
                    replaceTemp = { _, _ -> throw failure },
                )
            }

        assertEquals(GeoAssetIntegrityFailure.InstallFailed, error.reason)
        assertEquals(failure, error.cause)
        assertFalse(error.reason == GeoAssetIntegrityFailure.UnableToOpen)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `download atomic rename failure reports install failure and cleans temp`() {
        val previous = validPayload(MinGeoAssetBytes).reversedArray()
        val replacement = validPayload(MinGeoAssetBytes)
        val target = temporaryFolder.newFile("download-geoip.db").apply { writeBytes(previous) }
        val failure = IOException("download storage rename failed")

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                installDownloadedGeoAssetToTarget(
                    bytes = replacement,
                    target = target,
                    replaceTemp = { _, _ -> throw failure },
                )
            }

        assertEquals(GeoAssetIntegrityFailure.InstallFailed, error.reason)
        assertEquals(failure, error.cause)
        assertFalse(error.reason == GeoAssetIntegrityFailure.UnableToOpen)
        assertArrayEquals(previous, target.readBytes())
        assertNoTemporaryFiles(target.parentFile!!)
    }

    @Test
    fun `download storage directory failure reports install failure`() {
        val blockedDirectory = temporaryFolder.newFile("not-a-directory")
        val target = File(blockedDirectory, "geoip.db")

        val error =
            assertThrows(GeoAssetIntegrityException::class.java) {
                installDownloadedGeoAssetToTarget(validPayload(MinGeoAssetBytes), target)
            }

        assertEquals(GeoAssetIntegrityFailure.InstallFailed, error.reason)
        assertTrue(error.cause is IOException)
        assertFalse(target.exists())
        assertTrue(blockedDirectory.isFile)
    }

    private fun repository(
        downloadService: GeoAssetDownloadService =
            object : GeoAssetDownloadService {
                override suspend fun fetchLatestReleaseJson(apiUrl: String): String = error("not used")

                override suspend fun downloadAsset(downloadUrl: String): ByteArray = error("not used")
            },
    ): DefaultGeoAssetRepository =
        DefaultGeoAssetRepository(
            context = application,
            settingsRepository = settingsRepository,
            downloadService = downloadService,
        )

    private fun validPayload(size: Int): ByteArray = ByteArray(size) { index -> (index + 1).toByte() }

    private fun assertNoTemporaryFiles(directory: File) {
        assertFalse(
            directory.listFiles().orEmpty().any { it.name.startsWith("geo-asset-") && it.name.endsWith(".tmp") },
        )
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Cancellation intentionally interrupts the worker before the prepared file is handed back.
            }
        }
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

    private class InterruptibleAfterFirstChunkInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnedChunk = false
        val blockedReadStarted = CountDownLatch(1)
        val releaseBlockedRead = CountDownLatch(1)

        override fun read(): Int = error("Single-byte reads are not expected")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (!returnedChunk) {
                returnedChunk = true
                return delegate.read(buffer, offset, minOf(length, MinGeoAssetBytes))
            }
            blockedReadStarted.countDown()
            try {
                releaseBlockedRead.await()
            } catch (error: InterruptedException) {
                throw IOException("synthetic interrupted read", error)
            }
            return -1
        }
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

    private companion object {
        const val ReadStartTimeoutSeconds = 5L
        const val CancellationTimeoutMillis = 5_000L
    }
}
