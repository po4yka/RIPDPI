package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class TelegramSpeedTestTest {
    @Test
    fun downloadCompletesInFullReturnsOkStatus() =
        runTest {
            val test = telegramTest(download = transferChunks(TelegramSpeedTest.DownloadBytes))

            val result = test.run()

            assertEquals(TelegramProbeStatus.OK, result.download.status)
            assertEquals(TelegramSpeedTest.DownloadBytes, result.download.bytesTotal)
        }

    @Test
    fun downloadTimeoutKeepsItsBytesAndLastPayloadTime() =
        runTest {
            val clock = FakeClock()
            val test =
                telegramTest(
                    clock = clock,
                    download =
                        TelegramTransferClient {
                            it.responseHeaders(200)
                            it.payloadStarted()
                            clock.now = 5_000
                            it.transferred(1_000_000)
                            throw SocketTimeoutException()
                        },
                )

            val result = test.run()

            assertEquals(TelegramProbeStatus.STALLED, result.download.status)
            assertEquals(5, result.download.dropAtSec)
            assertEquals(1_000_000L, result.download.bytesTotal)
            assertEquals(TelegramTransferFailure.TIMEOUT, result.download.failure)
        }

    @Test
    fun successfulDownloadWithNoBodyHasIncompleteSample() =
        runTest {
            val test = telegramTest(download = TelegramTransferClient { it.responseHeaders(200) })

            val result = test.run()

            assertEquals(TelegramProbeStatus.INCOMPLETE, result.download.status)
        }

    @Test
    fun uploadCompletesReturnsOk() =
        runTest {
            val test = telegramTest(upload = transferChunks(TelegramSpeedTest.UploadBytes))

            val result = test.run()

            assertEquals(TelegramProbeStatus.OK, result.upload.status)
            assertEquals(TelegramSpeedTest.UploadBytes, result.upload.bytesTotal)
        }

    @Test
    fun dcConnectRunsOutsideTheCallerThread() =
        runBlocking {
            val caller = Thread.currentThread()
            val pinger =
                TelegramDcPinger(
                    socketConnector =
                        FakeDcConnector { _, _, _ ->
                            assertNotSame(caller, Thread.currentThread())
                            42
                        },
                )

            assertEquals(5, pinger.pingAll().count(DcReachability::reachable))
        }

    @Test
    fun dcPingAllFiveReachableReturnsFiveOfFive() =
        runTest {
            val test = telegramTest(dcConnector = FakeDcConnector { _, _, _ -> 42 })

            val result = test.run()

            assertEquals(5, result.dcResults.count { dc -> dc.reachable })
            assertEquals(5, result.dcResults.size)
        }

    @Test
    fun dcTimeoutPreservesOtherEndpointsAndItsOwnError() =
        runTest {
            val test =
                telegramTest(
                    dcConnector =
                        FakeDcConnector { ip, _, _ ->
                            if (ip == "149.154.167.51") throw SocketTimeoutException()
                            42
                        },
                )

            val result = test.run()

            assertEquals(4, result.dcResults.count { dc -> dc.reachable })
            assertEquals(TelegramDcFailure.TIMEOUT, result.dcResults.single { it.label == "DC2" }.failure)
        }

    @Test
    fun verdictPartialWhenUploadCompletesAndOtherPathsFail() =
        runTest {
            val test =
                telegramTest(
                    download = TelegramTransferClient { it.responseHeaders(403) },
                    dcConnector = FakeDcConnector { _, _, _ -> null },
                )

            val result = test.run()

            assertEquals(TelegramTestVerdict.PARTIAL, result.verdict)
        }

    @Test
    fun verdictPartialWhenDownloadTimesOut() =
        runTest {
            val test =
                telegramTest(
                    download =
                        TelegramTransferClient {
                            it.responseHeaders(200)
                            it.payloadStarted()
                            it.transferred(1_000_000)
                            delay(TelegramSpeedTest.TotalTimeoutMs + 1)
                        },
                )

            val result = test.run()

            assertEquals(TelegramTestVerdict.PARTIAL, result.verdict)
            assertEquals(TelegramProbeStatus.STALLED, result.download.status)
            assertEquals(200, result.download.httpStatus)
        }

    @Test
    fun verdictPartialWhenDcThreeOfFive() =
        runTest {
            val reachable = setOf("149.154.175.53", "149.154.167.51", "149.154.175.100")
            val test =
                telegramTest(
                    dcConnector =
                        FakeDcConnector { ip, _, _ ->
                            if (ip in reachable) 42 else null
                        },
                )

            val result = test.run()

            assertEquals(TelegramTestVerdict.PARTIAL, result.verdict)
        }

    @Test
    fun probeResultUsesTelegramCardDetailKeys() =
        runTest {
            val result = telegramTest().run().toProbeResult()
            val details = result.details.associate { detail -> detail.key to detail.value }

            assertEquals("telegram_availability", result.probeType)
            assertEquals("ok", details["verdict"])
            assertEquals("ok", details["downloadStatus"])
            assertEquals("ok", details["uploadStatus"])
            assertEquals("5", details["dcReachable"])
            assertEquals("5", details["dcTotal"])
            assertEquals(true, details.getValue("dcResults").contains("DC1:ok:42ms"))
            assertEquals("none", details["downloadError"])
            assertEquals("none", details["uploadError"])
        }

    @Test
    fun runWithProgressEmitsTransferProgressBeforeCompleted() =
        runTest {
            val events = telegramTest().runWithProgress().toList()

            assertEquals(TelegramTestProgress.Completed::class, events.last()::class)
            assertEquals(
                true,
                events
                    .filterIsInstance<TelegramTestProgress.Transfer>()
                    .any { event -> event.kind == TelegramTransferKind.DOWNLOAD && event.bytesTotal > 0 },
            )
        }

    private fun telegramTest(
        clock: FakeClock = FakeClock(),
        download: TelegramTransferClient = transferChunks(TelegramSpeedTest.DownloadBytes),
        upload: TelegramTransferClient = transferChunks(TelegramSpeedTest.UploadBytes),
        dcConnector: TelegramDcSocketConnector = FakeDcConnector { _, _, _ -> 42 },
    ): TelegramSpeedTest =
        TelegramSpeedTest(
            downloadClient = download,
            uploadClient = upload,
            dcPinger = TelegramDcPinger(socketConnector = dcConnector),
            clockMs = clock::current,
        )

    private fun transferChunks(totalBytes: Long) =
        TelegramTransferClient { observer ->
            observer.payloadStarted()
            var remaining = totalBytes
            while (remaining > 0) {
                val chunk = minOf(64 * 1024L, remaining).toInt()
                observer.transferred(chunk)
                remaining -= chunk
            }
            observer.responseHeaders(200)
        }

    private class FakeClock {
        var now: Long = 0

        fun current(): Long = now
    }

    private class FakeDcConnector(
        private val handler: (String, Int, Int) -> Long?,
    ) : TelegramDcSocketConnector {
        override suspend fun connect(
            ip: String,
            port: Int,
            timeoutMs: Int,
        ): Long = handler(ip, port, timeoutMs) ?: throw ConnectException()
    }
}
