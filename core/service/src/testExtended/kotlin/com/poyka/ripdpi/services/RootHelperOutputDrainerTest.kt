package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class RootHelperOutputDrainerTest {
    @Test
    fun `forwarder rate limits lines per window and reports suppressed lines`() {
        var now = 0L
        val lines = mutableListOf<String>()
        val forwarder =
            RateLimitedLineForwarder(
                sink = { lines += it },
                nanoClock = { now },
                maxLinesPerWindow = 3,
                windowNanos = 1_000L,
            )

        forwarder.feed((1..5).joinToString(separator = "") { "line $it\n" })
        now = 1_000L
        forwarder.feed("line 6\n")

        assertEquals(
            listOf("line 1", "line 2", "line 3", "suppressed 2 root helper log lines", "line 6"),
            lines,
        )
    }

    @Test
    fun `forwarder joins chunks, trims carriage returns and bounds line length`() {
        val lines = mutableListOf<String>()
        val forwarder = RateLimitedLineForwarder(sink = { lines += it }, nanoClock = { 0L }, maxLineBytes = 8)

        forwarder.feed("par")
        forwarder.feed("tial\r\n")
        forwarder.feed("x".repeat(100) + "\n")

        assertEquals(listOf("partial", "xxxxxxxx"), lines)
    }

    @Test
    fun `drainer consumes both streams to end even when the stderr sink fails`() {
        val stdout = ByteArrayInputStream(ByteArray(LARGE_OUTPUT_BYTES) { 'o'.code.toByte() })
        val stderr = ByteArrayInputStream("helper log line\n".repeat(LARGE_OUTPUT_BYTES / 16).toByteArray())
        val drainer =
            RootHelperOutputDrainer.start(StreamProcess(stdout, stderr)) {
                error("log sink failure")
            }

        assertTrue(drainer.awaitTermination(TimeUnit.SECONDS.toMillis(5)))
        assertEquals(0, stdout.available())
        assertEquals(0, stderr.available())
    }

    private fun RateLimitedLineForwarder.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        accept(bytes, bytes.size)
    }

    private class StreamProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
    ) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    private companion object {
        const val LARGE_OUTPUT_BYTES = 1024 * 1024
    }
}
