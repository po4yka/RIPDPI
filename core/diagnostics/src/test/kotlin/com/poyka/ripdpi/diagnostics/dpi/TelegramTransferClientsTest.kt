package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TelegramTransferClientsTest {
    @Test
    fun slowProgressConsumerKeepsExactBodyCountsAndBothHttpOutcomes() =
        runBlocking {
            MockWebServer().use { downloadServer ->
                MockWebServer().use { uploadServer ->
                    val payload = ByteArray(4 * 1024 * 1024)
                    downloadServer.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
                    uploadServer.bodyLimit = 0
                    uploadServer.enqueue(
                        MockResponse
                            .Builder()
                            .code(413)
                            .headersDelay(250, TimeUnit.MILLISECONDS)
                            .build(),
                    )
                    downloadServer.start()
                    uploadServer.start()
                    val result =
                        test(
                            OkHttpTelegramDownloadClient(url = downloadServer.url("/download").toString()),
                            OkHttpTelegramUploadClient(url = uploadServer.url("/upload").toString()),
                        ).runWithProgress()
                            .onEach { delay(10) }
                            .filterIsInstance<TelegramTestProgress.Completed>()
                            .last()
                            .result

                    assertEquals(payload.size.toLong(), result.download.bytesTotal)
                    assertEquals(200, result.download.httpStatus)
                    assertEquals(TelegramProbeStatus.INCOMPLETE, result.download.status)
                    val uploaded = requireNotNull(uploadServer.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals("POST", uploaded.method)
                    assertEquals(TelegramSpeedTest.UploadBytes, uploaded.bodySize)
                    assertEquals(uploaded.bodySize, result.upload.bytesTotal)
                    assertEquals(413, result.upload.httpStatus)
                    assertTrue(requireNotNull(result.upload.responseDelayMs) >= 200)
                    assertEquals(TelegramTransferFailure.HTTP_REJECTED, result.upload.failure)
                    assertEquals(TelegramTestVerdict.PARTIAL, result.verdict)
                }
            }
        }

    @Test
    fun downloadCapsSampleBytesAndRejectsUnexpectedContentEncoding() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("oversized body").build())
                server.enqueue(
                    MockResponse
                        .Builder()
                        .setHeader("Content-Encoding", "gzip")
                        .body("encoded")
                        .build(),
                )
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(206)
                        .body("part")
                        .build(),
                )
                server.start()
                val download = OkHttpTelegramDownloadClient(url = server.url("/download").toString(), sampleBytes = 5)
                val probe = test(download, acceptedUpload)

                assertEquals(5L, probe.run().download.bytesTotal)
                assertEquals(TelegramTransferFailure.PROTOCOL, probe.run().download.failure)
                val partialResponse = probe.run().download
                assertEquals(206, partialResponse.httpStatus)
                assertEquals(0L, partialResponse.bytesTotal)
                assertEquals(TelegramTransferFailure.PROTOCOL, partialResponse.failure)
                assertEquals("identity", server.takeRequest(1, TimeUnit.SECONDS)?.headers?.get("Accept-Encoding"))
            }
        }

    @Test
    fun uploadDoesNotFollowRedirects() =
        runBlocking {
            MockWebServer().use { server ->
                server.bodyLimit = 0
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .setHeader("Location", "/elsewhere")
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(200).build())
                server.start()

                val result =
                    test(acceptedDownload, OkHttpTelegramUploadClient(url = server.url("/upload").toString())).run()

                assertEquals(302, result.upload.httpStatus)
                assertEquals(TelegramTransferFailure.HTTP_REJECTED, result.upload.failure)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun cancellationStopsDownloadWhileResponseBodyIsBlocked() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("waiting")
                        .bodyDelay(8, TimeUnit.SECONDS)
                        .build(),
                )
                server.start()
                val bodyStarted = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val client =
                    OkHttpClient
                        .Builder()
                        .eventListener(
                            object : EventListener() {
                                override fun responseBodyStart(call: Call) {
                                    bodyStarted.complete(Unit)
                                }

                                override fun canceled(call: Call) {
                                    cancelled.complete(Unit)
                                }
                            },
                        ).build()
                val probe =
                    test(OkHttpTelegramDownloadClient(client, server.url("/download").toString()), acceptedUpload)
                val run = launch { probe.run() }

                withTimeout(3_000) { bodyStarted.await() }
                withTimeout(3_000) {
                    run.cancelAndJoin()
                    cancelled.await()
                }
            }
        }

    private fun test(
        download: TelegramTransferClient,
        upload: TelegramTransferClient,
    ): TelegramSpeedTest = TelegramSpeedTest(download, upload, TelegramDcPinger { _, _, _ -> 42 })

    private val acceptedDownload = acceptedTransfer(TelegramSpeedTest.DownloadBytes)
    private val acceptedUpload = acceptedTransfer(TelegramSpeedTest.UploadBytes)

    private fun acceptedTransfer(bytes: Long) =
        TelegramTransferClient { observer ->
            observer.payloadStarted()
            observer.transferred(bytes.toInt())
            observer.responseHeaders(200)
        }
}
