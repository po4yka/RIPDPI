package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun downloadStallsAfterFiveSecondsReturnsStalled() =
        runTest {
            val clock = FakeClock()
            val test =
                telegramTest(
                    clock = clock,
                    download =
                        flow {
                            clock.now = 5_000
                            emit(1_000_000)
                            delay(TelegramSpeedTest.StallTimeoutMs + 1)
                        },
                )

            val result = test.run()

            assertEquals(TelegramProbeStatus.STALLED, result.download.status)
            assertEquals(5, result.download.dropAtSec)
        }

    @Test
    fun downloadZeroBytesReturnsBlocked() =
        runTest {
            val test = telegramTest(download = flow { })

            val result = test.run()

            assertEquals(TelegramProbeStatus.BLOCKED, result.download.status)
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
    fun dcPingAllFiveReachableReturnsFiveOfFive() =
        runTest {
            val test = telegramTest(dcConnector = FakeDcConnector { _, _, _ -> 42 })

            val result = test.run()

            assertEquals(5, result.dcResults.count { dc -> dc.reachable })
            assertEquals(5, result.dcResults.size)
        }

    @Test
    fun dcPingDc2OnlyBlockedReturnsFourOfFive() =
        runTest {
            val test =
                telegramTest(
                    dcConnector =
                        FakeDcConnector { ip, _, _ ->
                            if (ip == "149.154.167.51") null else 42
                        },
                )

            val result = test.run()

            assertEquals(4, result.dcResults.count { dc -> dc.reachable })
        }

    @Test
    fun verdictBlockedWhenDcZeroAndDownloadBlocked() =
        runTest {
            val test =
                telegramTest(
                    download = flow { },
                    dcConnector = FakeDcConnector { _, _, _ -> null },
                )

            val result = test.run()

            assertEquals(TelegramTestVerdict.BLOCKED, result.verdict)
        }

    @Test
    fun verdictSlowWhenDownloadStalled() =
        runTest {
            val test =
                telegramTest(
                    download =
                        flow {
                            emit(1_000_000)
                            delay(TelegramSpeedTest.StallTimeoutMs + 1)
                        },
                )

            val result = test.run()

            assertEquals(TelegramTestVerdict.SLOW, result.verdict)
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
        download: kotlinx.coroutines.flow.Flow<Int> = transferChunks(TelegramSpeedTest.DownloadBytes),
        upload: kotlinx.coroutines.flow.Flow<Int> = transferChunks(TelegramSpeedTest.UploadBytes),
        dcConnector: TelegramDcSocketConnector = FakeDcConnector { _, _, _ -> 42 },
    ): TelegramSpeedTest =
        TelegramSpeedTest(
            downloadClient = TelegramTransferClient { download },
            uploadClient = TelegramTransferClient { upload },
            dcPinger = TelegramDcPinger(socketConnector = dcConnector, clockMs = clock::current),
            clockMs = clock::current,
        )

    private fun transferChunks(totalBytes: Long) =
        flow {
            var remaining = totalBytes
            while (remaining > 0) {
                val chunk = minOf(64 * 1024L, remaining).toInt()
                emit(chunk)
                remaining -= chunk
            }
        }

    private class FakeClock {
        var now: Long = 0

        fun current(): Long = now
    }

    private class FakeDcConnector(
        private val handler: (String, Int, Int) -> Long?,
    ) : TelegramDcSocketConnector {
        override fun connect(
            ip: String,
            port: Int,
            timeoutMs: Int,
        ): Long? = handler(ip, port, timeoutMs)
    }
}
