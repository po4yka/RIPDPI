package com.poyka.ripdpi.pcap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcapDocumentExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `only the named completed capture resolves`() {
        val directory = temp.newFolder()
        val complete = File(directory, "completed.pcap").apply { writeText("test") }
        val active = File(directory, "in-progress.pcap.active").apply { writeText("test") }
        val unrelated = temp.newFile("outside.pcap")
        Files.createSymbolicLink(File(directory, "alias.pcap").toPath(), active.toPath())

        assertEquals(complete.canonicalFile, resolvePcapCapture(directory, complete.name))
        for (invalid in listOf(active.name, "alias.pcap", "../${unrelated.name}", unrelated.absolutePath)) {
            assertThrows(FileNotFoundException::class.java) { resolvePcapCapture(directory, invalid) }
        }
    }

    @Test
    fun `publication leaves successful output in place`() =
        runTest {
            val document = temp.newFile()
            val expected = byteArrayOf(1, 2, 3)
            publishPcapDocument({ document.writeBytes(expected) }, document::delete)
            assertArrayEquals(expected, document.readBytes())
        }

    @Test
    fun `failed write removes the provider document and reports the cause`() =
        runTest {
            val document = temp.newFile()
            val writeFailure = IOException("write failed")
            val result =
                runCatching {
                    publishPcapDocument(
                        {
                            document.writeText("partial")
                            throw writeFailure
                        },
                        document::delete,
                    )
                }.exceptionOrNull() as PcapDocumentWriteException
            assertTrue(result.documentRemoved)
            assertFalse(document.exists())
            assertEquals(writeFailure, result.cause)
        }

    @Test
    fun `provider refusal is reported as an unfinished document`() =
        runTest {
            val result =
                runCatching {
                    publishPcapDocument({ throw IOException("write failed") }, { false })
                }.exceptionOrNull() as PcapDocumentWriteException
            assertFalse(result.documentRemoved)
        }

    @Test
    fun `cancelling a transfer removes the partial document`() =
        runTest {
            val document = temp.newFile()
            val writing = CompletableDeferred<Unit>()
            val transfer =
                launch {
                    publishPcapDocument(
                        {
                            document.writeText("partial")
                            writing.complete(Unit)
                            awaitCancellation()
                        },
                        document::delete,
                    )
                }
            writing.await()
            transfer.cancelAndJoin()
            assertFalse(document.exists())
        }

    @Test
    fun `cancellation of provider open signals cancellation and closes a late descriptor`() =
        runTest {
            val enteringProvider = CompletableDeferred<Unit>()
            val cancelSignal = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val request =
                launch(Dispatchers.IO) {
                    openPcapDocument(cancelSignal::countDown) {
                        enteringProvider.complete(Unit)
                        check(cancelSignal.await(5, TimeUnit.SECONDS))
                        Closeable { closed.countDown() }
                    }.use { error("The cancelled request must not deliver a descriptor") }
                }

            enteringProvider.await()
            request.cancelAndJoin()
            assertEquals(0L, cancelSignal.count)
            assertEquals(0L, closed.count)
        }

    @Test
    fun `immediate cancellation still rolls back the selected document`() =
        runTest {
            val document = temp.newFile()
            val cancelled = Job().apply { cancel() }
            launch(cancelled, start = CoroutineStart.UNDISPATCHED) {
                publishPcapDocument(
                    { withContext(Dispatchers.IO) { error("cancelled write must not start") } },
                    document::delete,
                )
            }.join()
            assertFalse(document.exists())
        }

    @Test
    fun `provider exceptions retain their identity and publication rollback`() =
        runTest {
            for (failure in listOf(
                ProviderCheckedFailure(7),
                ProviderRuntimeFailure(11),
            )) {
                val document = temp.newFile()
                val published =
                    runCatching {
                        publishPcapDocument({ throw failure }, document::delete)
                    }.exceptionOrNull() as PcapDocumentWriteException
                assertSame(failure, published.cause)
                assertTrue(published.documentRemoved)
                assertFalse(document.exists())

                val opened = runCatching { openPcapDocument<Closeable>({}) { throw failure } }.exceptionOrNull()
                assertSame(failure, opened)
            }
        }

    @Test
    fun `fatal provider errors propagate without publication recovery`() =
        runTest {
            val failure = AssertionError("provider fatal failure")
            val document = temp.newFile()
            val published = runCatching { publishPcapDocument({ throw failure }, document::delete) }.exceptionOrNull()
            assertSame(failure, published)
            assertTrue(document.exists())
            val opened = runCatching { openPcapDocument<Closeable>({}) { throw failure } }.exceptionOrNull()
            assertSame(failure, opened)
        }

    @Test
    fun `staging reaps old process drafts and leaves live preparations alone`() {
        val cache = temp.newFolder()
        PcapExportStaging.create(cache).use { live ->
            val oldProcess = File(cache, "pcap-export/process-dead").apply { mkdirs() }
            File(oldProcess, "owner.lock").createNewFile()
            File(oldProcess, "capture-old.tmp").writeText("stale")

            PcapExportStaging.create(cache).use { ready ->
                assertFalse(oldProcess.exists())
                assertTrue(live.file.exists())
                assertEquals(live.file.parentFile, ready.file.parentFile)
            }
        }
    }
}

private class ProviderCheckedFailure(
    val providerCode: Int,
) : Exception("provider checked failure")

private class ProviderRuntimeFailure(
    val providerCode: Int,
) : IllegalStateException("provider runtime failure")
