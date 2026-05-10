package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RandomHostHeaderGeneratorTest {
    @Test
    fun generatedHostnameMatchesFormatRegex() {
        val hostnameRegex = Regex("^[a-z][a-z0-9]{14}\\.(com|net|org|io|info)$")

        repeat(100) {
            val hostname = RandomHostHeaderGenerator.next()

            assertTrue("$hostname should match fake hostname format", hostnameRegex.matches(hostname))
        }
    }

    @Test
    fun consecutiveCallsReturnDifferentValues() {
        val hostnames = List(100) { RandomHostHeaderGenerator.next() }

        assertEquals(hostnames.size, hostnames.toSet().size)
    }

    @Test
    fun threadSafeUnderConcurrentCalls() {
        val threadCount = 16
        val callsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val generated = Collections.synchronizedSet(mutableSetOf<String>())

        try {
            val tasks =
                List(threadCount) {
                    Callable {
                        repeat(callsPerThread) {
                            generated += RandomHostHeaderGenerator.next()
                        }
                    }
                }

            executor.invokeAll(tasks).forEach { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(threadCount * callsPerThread, generated.size)
    }
}
