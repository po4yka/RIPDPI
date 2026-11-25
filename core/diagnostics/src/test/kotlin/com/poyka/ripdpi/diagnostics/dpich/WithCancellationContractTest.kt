package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class WithCancellationContractTest {
    @Test
    fun `successful action returns its value and leaves normal cleanup to the caller`() =
        runBlocking<Unit> {
            val reader = StringReader("fixture")
            reader.use {
                assertEquals("result", reader.withCancellation { "result" })
                assertEquals('f'.code, reader.read())
            }
            assertThrows(IOException::class.java) { reader.read() }
        }

    @Test
    fun `action failures preserve original errors and coroutine exception recovery`() =
        runBlocking<Unit> {
            val failures = listOf(IOException("io"), IllegalStateException("state"), AssertionError("fatal"))
            for (failure in failures) {
                val reader = StringReader("fixture")
                reader.use {
                    val observed = runCatching { reader.withCancellation<Nothing> { throw failure } }.exceptionOrNull()
                    if (failure is Exception && observed !== failure) {
                        assertEquals(failure.javaClass, observed?.javaClass)
                        assertEquals(failure.message, observed?.message)
                        assertSame(failure, observed?.cause)
                        assertTrue(observed?.stackTrace?.any { it.className == "_COROUTINE._BOUNDARY" } == true)
                    } else {
                        assertSame(failure, observed)
                    }
                    assertEquals('f'.code, reader.read())
                }
                assertThrows(IOException::class.java) { reader.read() }
            }
        }

    @Test
    fun `fatal action failure escapes even after cancellation closes the resource`() =
        runBlocking<Unit> {
            val reader = StringReader("fixture")
            val failure = AssertionError("fatal after cancel")
            val observed = CompletableDeferred<Throwable?>()
            val actionJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val self = requireNotNull(coroutineContext[Job])
                    observed.complete(
                        runCatching {
                            reader.withCancellation<Nothing> {
                                self.cancel()
                                throw failure
                            }
                        }.exceptionOrNull(),
                    )
                }
            actionJob.join()

            assertSame(failure, observed.await())
            assertThrows(IOException::class.java) { reader.read() }
        }
}
