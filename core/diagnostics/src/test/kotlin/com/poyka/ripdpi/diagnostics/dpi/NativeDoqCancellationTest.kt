package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class NativeDoqCancellationTest {
    @Test
    fun cancellationWaitsForNativeExitBeforeReleasingTheRequest() =
        runTest(timeout = 10.seconds) {
            val bindings = PausedBindings()
            val exchange = launch { runExchange(bindings) }
            try {
                assertTrue(withContext(Dispatchers.IO) { bindings.exchangeStarted.awaitSignal() })

                exchange.cancel()

                assertTrue(withContext(Dispatchers.IO) { bindings.cancelled.awaitSignal() })
                assertFalse(exchange.isCompleted)
                assertEquals(0, bindings.releases.get())
            } finally {
                bindings.allowReturn.countDown()
                exchange.cancel()
            }
            exchange.join()
            assertTrue(exchange.isCancelled)
            assertEquals(1, bindings.releases.get())
        }

    @Test
    fun cancellationDuringReservationSkipsTheExchangeAndReleasesTheRequest() =
        runTest(timeout = 10.seconds) {
            val bindings = PausedBindings(pauseReservation = true)
            val exchange = launch { runExchange(bindings) }
            try {
                assertTrue(withContext(Dispatchers.IO) { bindings.reserveStarted.awaitSignal() })

                exchange.cancel()
            } finally {
                bindings.allowReturn.countDown()
                exchange.cancel()
            }
            exchange.join()
            assertTrue(exchange.isCancelled)
            assertEquals(0, bindings.cancelled.count)
            assertEquals(1, bindings.exchangeStarted.count)
            assertEquals(1, bindings.releases.get())
        }

    @Test
    fun failedReservationIsReportedWithoutAnUntrackedExchange() =
        runTest {
            val bindings =
                object : NativeDoqQuicClientBindings {
                    override fun exchange(requestJson: String): String {
                        val request = Json.parseToJsonElement(requestJson).jsonObject
                        assertEquals("reserve", request.getValue("operation").jsonPrimitive.content)
                        return """{"errorKind":"setup","error":"No request capacity"}"""
                    }
                }

            val failure = runCatching { runExchange(bindings) }.exceptionOrNull()
            assertEquals(DoqFailureKind.SETUP, (failure as? DoqExchangeException)?.kind)
        }

    private suspend fun runExchange(bindings: NativeDoqQuicClientBindings) {
        val (_, query) = DoqWireCodec.buildQuery("test.example")
        NativeDoqQuicClient(bindings).exchange("127.0.0.1", 853, "test.example", query, 1_000)
    }

    private class PausedBindings(
        private val pauseReservation: Boolean = false,
    ) : NativeDoqQuicClientBindings {
        val reserveStarted = CountDownLatch(1)
        val exchangeStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val releases = AtomicInteger()

        override fun exchange(requestJson: String): String {
            val request = Json.parseToJsonElement(requestJson).jsonObject
            val operation = request["operation"]?.jsonPrimitive?.content
            if (operation == "reserve") {
                reserveStarted.countDown()
                if (pauseReservation) check(allowReturn.awaitSignal())
                return """{"requestHandle":42}"""
            }

            assertEquals(42, request.getValue("requestHandle").jsonPrimitive.long)
            return when (operation) {
                "cancel" -> {
                    cancelled.countDown()
                    """{"controlOk":true}"""
                }

                "release" -> {
                    releases.incrementAndGet()
                    """{"controlOk":true}"""
                }

                else -> {
                    exchangeStarted.countDown()
                    check(cancelled.awaitSignal())
                    check(allowReturn.awaitSignal())
                    """{"errorKind":"cancelled","error":"cancelled"}"""
                }
            }
        }
    }

    private companion object {
        fun CountDownLatch.awaitSignal(): Boolean = await(3, TimeUnit.SECONDS)
    }
}
