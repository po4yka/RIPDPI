package com.poyka.ripdpi.services

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/** Verifies descriptor ownership through pipe EOF after an injected protection failure. */
class ProtectSocketFdCleanupProbe {
    enum class FailurePoint {
        BeforeProtect,
        ExtractSecond,
        ReportFailure,
    }

    data class Result(
        val originalFailurePreserved: Boolean,
        val pipeEof: List<Boolean>,
    )

    fun run(failurePoint: FailurePoint): Result {
        val pipes = List(3) { Os.pipe() }
        try {
            val failure = IllegalStateException("Injected descriptor protection failure")
            val extracted = AtomicInteger()
            val monitor =
                object : VpnProtectFailureMonitor {
                    override val events = InMemoryVpnProtectFailureMonitor().events

                    override fun report(event: VpnProtectFailureEvent) {
                        if (failurePoint == FailurePoint.ReportFailure) throw failure
                    }
                }
            val protection =
                ProtectSocketFdProtector(
                    protectFailureMonitor = monitor,
                    fdProtector = { failurePoint != FailurePoint.ReportFailure },
                    clock = { 0L },
                    beforeProtectAncillaryFds = {
                        if (failurePoint == FailurePoint.BeforeProtect) throw failure
                    },
                    fileDescriptorIntExtractor = { descriptor ->
                        if (failurePoint == FailurePoint.ExtractSecond && extracted.incrementAndGet() == 2) {
                            throw failure
                        }
                        ReflectiveProtectSocketFileDescriptorIntExtractor.extract(descriptor)
                    },
                )
            val outcome =
                runCatching {
                    protection.protectAncillaryFds(DescriptorSession(pipes.map { it[1] }.toTypedArray()))
                }
            return Result(outcome.exceptionOrNull() === failure, pipes.map { reachedEof(it[0]) })
        } finally {
            pipes.forEach { pipe -> pipe.forEach { descriptor -> runCatching { Os.close(descriptor) } } }
        }
    }

    private fun reachedEof(reader: FileDescriptor): Boolean {
        val poll =
            StructPollfd().apply {
                fd = reader
                events = OsConstants.POLLIN.toShort()
            }
        if (Os.poll(arrayOf(poll), 1_000) == 0) return false
        if (poll.revents.toInt().and(OsConstants.POLLHUP) == 0) return false
        return Os.read(reader, ByteArray(1), 0, 1) == 0
    }
}

private class DescriptorSession(
    override val ancillaryFileDescriptors: Array<FileDescriptor>,
) : ProtectSocketClientSession {
    override fun readHandshake(): Int = 1

    override fun writeAck(success: Boolean) = Unit

    override fun close() = Unit
}
