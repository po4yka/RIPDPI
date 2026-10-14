package com.poyka.ripdpi.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Keeps the root helper's stdout and stderr pipes empty for the whole helper lifetime.
 *
 * The helper writes tracing logs to stderr. A pipe holds about 64 KiB; when nobody reads it, the
 * single-threaded helper blocks in write(2) and stops serving IPC. One daemon thread per stream
 * reads until end of stream (the helper exited) or until [close] closes the read end. Stdout is
 * discarded. Complete stderr lines go to the line sink with a fixed-window rate limit.
 */
internal class RootHelperOutputDrainer private constructor(
    private val streams: List<InputStream>,
    private val threads: List<Thread>,
) {
    /** Close the read ends so blocked reader threads stop even if the helper outlives its launcher. */
    fun close() {
        streams.forEach { stream -> runCatching { stream.close() } }
    }

    /** Returns `true` when every drain thread has stopped within [timeoutMs]. */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        threads.forEach { thread ->
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs > 0) thread.join(remainingMs)
        }
        return threads.none { it.isAlive }
    }

    companion object {
        const val THREAD_NAME_PREFIX = "ripdpi-root-helper-output"
        private const val READ_BUFFER_BYTES = 4096

        fun start(
            process: Process,
            stderrLineSink: (String) -> Unit,
        ): RootHelperOutputDrainer {
            val stdout = process.inputStream
            val stderr = process.errorStream
            val stderrLines = RateLimitedLineForwarder(stderrLineSink, System::nanoTime)
            val threads =
                listOf(
                    startDrainThread("$THREAD_NAME_PREFIX-stdout", stdout) { _, _ -> },
                    startDrainThread("$THREAD_NAME_PREFIX-stderr", stderr, stderrLines::accept),
                )
            return RootHelperOutputDrainer(listOf(stdout, stderr), threads)
        }

        private fun startDrainThread(
            name: String,
            input: InputStream,
            onChunk: (ByteArray, Int) -> Unit,
        ): Thread =
            Thread(
                {
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    var forwarding = true
                    while (true) {
                        val read =
                            try {
                                input.read(buffer)
                            } catch (_: IOException) {
                                -1
                            }
                        if (read < 0) break
                        // A sink failure must never stop the drain: the helper would block again.
                        if (forwarding && read > 0) forwarding = runCatching { onChunk(buffer, read) }.isSuccess
                    }
                    runCatching { input.close() }
                },
                name,
            ).apply {
                isDaemon = true
                start()
            }
    }
}

/**
 * Splits a byte stream into bounded UTF-8 lines and forwards at most [maxLinesPerWindow] lines per
 * [windowNanos]. The first line of a later window reports how many lines the limit dropped.
 */
internal class RateLimitedLineForwarder(
    private val sink: (String) -> Unit,
    private val nanoClock: () -> Long,
    private val maxLinesPerWindow: Int = DEFAULT_MAX_LINES_PER_WINDOW,
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private val pending = ByteArrayOutputStream()
    private var windowStart = nanoClock()
    private var linesInWindow = 0
    private var suppressed = 0L

    fun accept(
        bytes: ByteArray,
        length: Int,
    ) {
        for (index in 0 until length) {
            val byte = bytes[index]
            if (byte == NEWLINE) {
                emit(pending.toString(Charsets.UTF_8.name()).trimEnd('\r'))
                pending.reset()
            } else if (pending.size() < maxLineBytes) {
                pending.write(byte.toInt())
            }
        }
    }

    private fun emit(line: String) {
        if (line.isEmpty()) return
        val now = nanoClock()
        if (now - windowStart >= windowNanos) {
            windowStart = now
            linesInWindow = 0
            if (suppressed > 0) {
                sink("suppressed $suppressed root helper log lines")
                suppressed = 0
            }
        }
        if (linesInWindow < maxLinesPerWindow) {
            linesInWindow += 1
            sink(line)
        } else {
            suppressed += 1
        }
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
        const val DEFAULT_MAX_LINES_PER_WINDOW = 20
        const val DEFAULT_MAX_LINE_BYTES = 512
        val DEFAULT_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
    }
}
