package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.diagnostics.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class LogcatSnapshot(
    val content: String,
    val captureScope: String,
    val byteCount: Int,
    val truncated: Boolean = false,
    val sourceByteCount: Long = byteCount.toLong(),
    val retainedByteCount: Long = byteCount.toLong(),
    val droppedByteCount: Long = 0L,
    val preCollectionRingLoss: LogcatPreCollectionRingLossStatus =
        LogcatPreCollectionRingLossStatus.UNKNOWN,
    val earliestRetainedTimestamp: String? = null,
    val latestRetainedTimestamp: String? = null,
    val collectionEntryByteCount: Int = byteCount,
    val postRedactionAccounting: LogcatPostRedactionAccounting? = null,
)

data class LogcatPostRedactionAccounting(
    val sourceByteCount: Long,
    val retainedByteCount: Long,
    val droppedByteCount: Long,
    val entryByteCount: Int,
    val earliestRetainedTimestamp: String? = null,
    val latestRetainedTimestamp: String? = null,
)

enum class LogcatPreCollectionRingLossStatus {
    UNKNOWN,
}

open class LogcatSnapshotCollector
    @Inject
    constructor() {
        companion object {
            const val AppVisibleSnapshotScope = "app_visible_snapshot"
            const val TimeBoundSnapshotScope = "time_bound_snapshot"
            const val MAX_LOGCAT_BYTES = 512 * 1024
        }

        open suspend fun capture(sinceTimestampMs: Long? = null): LogcatSnapshot? =
            withContext(Dispatchers.IO) {
                val output = readLogcatOutput(sinceTimestampMs)
                if (output.content.isBlank()) {
                    null
                } else {
                    val scope =
                        if (sinceTimestampMs != null) TimeBoundSnapshotScope else AppVisibleSnapshotScope
                    LogcatSnapshot(
                        content = output.content,
                        captureScope = scope,
                        byteCount = output.byteCount,
                        truncated = output.truncated,
                        sourceByteCount = output.sourceByteCount,
                        retainedByteCount = output.retainedByteCount,
                        droppedByteCount = output.droppedByteCount,
                        preCollectionRingLoss = output.preCollectionRingLoss,
                        earliestRetainedTimestamp = output.earliestRetainedTimestamp,
                        latestRetainedTimestamp = output.latestRetainedTimestamp,
                    )
                }
            }

        protected open suspend fun readLogcatOutput(sinceTimestampMs: Long? = null): LogcatReadOutput =
            readLogcatProcessOutput(
                command = buildLogcatCommand(android.os.Process.myPid(), sinceTimestampMs, BuildConfig.DEBUG),
                retainHead = sinceTimestampMs != null,
            )
    }

internal class LogcatCommandFailed(
    val exitCode: Int,
) : IOException("logcat exited with code $exitCode")

internal class LogcatCommandTimedOut : IOException("logcat capture timed out")

internal fun logSnapshotFailureDetails(error: Throwable): String =
    buildString {
        append("errorClass=").append(error::class.simpleName ?: "unknown")
        if (error is LogcatCommandFailed) append(" exitCode=").append(error.exitCode)
    }

internal suspend fun readLogcatProcessOutput(
    command: List<String>,
    retainHead: Boolean,
): LogcatReadOutput =
    coroutineScope {
        val process = ProcessBuilder(command).redirectError(File("/dev/null")).start()
        try {
            val output =
                async(Dispatchers.IO) {
                    process.inputStream.use { input ->
                        readLineAlignedLogcatOutput(input, retainHead, LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
                    }
                }
            runInterruptible(Dispatchers.IO) {
                if (!process.waitFor(LogcatTimeoutSeconds, TimeUnit.SECONDS)) throw LogcatCommandTimedOut()
                val exitCode = process.exitValue()
                if (exitCode != 0) throw LogcatCommandFailed(exitCode)
            }
            output.await()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

private const val LogcatTimeoutSeconds = 10L

data class LogcatReadOutput(
    val content: String,
    val byteCount: Int = content.toByteArray(Charsets.UTF_8).size,
    val truncated: Boolean = false,
    val sourceByteCount: Long = byteCount.toLong(),
    val retainedByteCount: Long = byteCount.toLong(),
    val droppedByteCount: Long = 0L,
    val preCollectionRingLoss: LogcatPreCollectionRingLossStatus =
        LogcatPreCollectionRingLossStatus.UNKNOWN,
    val earliestRetainedTimestamp: String? = null,
    val latestRetainedTimestamp: String? = null,
)

internal fun readLineAlignedLogcatOutput(
    bytes: ByteArray,
    retainHead: Boolean,
    maxBytes: Int,
): LogcatReadOutput =
    ByteArrayInputStream(bytes).use { input ->
        readLineAlignedLogcatOutput(
            input = input,
            retainHead = retainHead,
            maxBytes = maxBytes,
        )
    }

private fun readLineAlignedLogcatOutput(
    input: InputStream,
    retainHead: Boolean,
    maxBytes: Int,
): LogcatReadOutput {
    val retention = LineAlignedLogcatRetention(retainHead = retainHead, maxBytes = maxBytes)
    val readBuffer = ByteArray(LogcatReadBufferBytes)
    var read = input.read(readBuffer)
    while (read != -1) {
        retention.append(readBuffer, read)
        read = input.read(readBuffer)
    }
    return retention.finish()
}

private class LineAlignedLogcatRetention(
    private val retainHead: Boolean,
    private val maxBytes: Int,
) {
    private val markerBytes =
        if (retainHead) {
            LogcatTruncationMarker.toByteArray(Charsets.UTF_8)
        } else {
            LogcatTailTruncationMarker.toByteArray(Charsets.UTF_8)
        }
    private val sourceBudget = (maxBytes - markerBytes.size).coerceAtLeast(0)
    private val headBudget = if (retainHead) sourceBudget / 2 else 0
    private val tailBudget = sourceBudget - headBudget
    private val completeLines = mutableListOf<ByteArray>()
    private val headLines = mutableListOf<ByteArray>()
    private val tailLines = ArrayDeque<ByteArray>()
    private val pendingLine = ByteArrayOutputStream()
    private var headBytes = 0
    private var tailBytes = 0
    private var sourceByteCount = 0L
    private var truncated = false
    private var discardingOversizedLine = false

    fun append(
        bytes: ByteArray,
        count: Int,
    ) {
        bytes.take(count).forEach(::appendByte)
    }

    fun finish(): LogcatReadOutput {
        if (!discardingOversizedLine && pendingLine.size() > 0) {
            appendCompleteLine(pendingLine.toByteArray())
        }
        val retainedLines = if (truncated) headLines + tailLines else completeLines
        val retainedByteCount = retainedLines.sumOf { it.size.toLong() }
        val renderedBytes =
            if (truncated) {
                retainedLinesToBytes(headLines) + markerBytes + retainedLinesToBytes(tailLines)
            } else {
                retainedLinesToBytes(completeLines)
            }
        val retainedTimestamps = retainedLines.mapNotNull(::logcatTimestamp)
        return LogcatReadOutput(
            content = renderedBytes.toString(Charsets.UTF_8),
            byteCount = renderedBytes.size,
            truncated = truncated,
            sourceByteCount = sourceByteCount,
            retainedByteCount = retainedByteCount,
            droppedByteCount = (sourceByteCount - retainedByteCount).coerceAtLeast(0L),
            preCollectionRingLoss = LogcatPreCollectionRingLossStatus.UNKNOWN,
            earliestRetainedTimestamp = retainedTimestamps.firstOrNull(),
            latestRetainedTimestamp = retainedTimestamps.lastOrNull(),
        )
    }

    private fun appendByte(byte: Byte) {
        sourceByteCount += 1
        if (!truncated && sourceByteCount > maxBytes) activateTruncation()
        if (discardingOversizedLine) {
            if (byte == NewLineByte) discardingOversizedLine = false
            return
        }
        pendingLine.write(byte.toInt())
        if (pendingLine.size() > maxBytes) {
            pendingLine.reset()
            discardingOversizedLine = true
            tailLines.clear()
            tailBytes = 0
            return
        }
        if (byte == NewLineByte) {
            appendCompleteLine(pendingLine.toByteArray())
            pendingLine.reset()
        }
    }

    private fun appendCompleteLine(line: ByteArray) {
        if (!truncated) {
            completeLines += line
        } else {
            appendTail(line)
        }
    }

    private fun activateTruncation() {
        truncated = true
        completeLines.forEach { line ->
            if (retainHead && headBytes + line.size <= headBudget) {
                headLines += line
                headBytes += line.size
            } else {
                appendTail(line)
            }
        }
        completeLines.clear()
    }

    private fun appendTail(line: ByteArray) {
        if (line.size > tailBudget) return
        while (tailLines.isNotEmpty() && tailBytes + line.size > tailBudget) {
            tailBytes -= tailLines.removeFirst().size
        }
        tailLines.addLast(line)
        tailBytes += line.size
    }
}

private fun retainedLinesToBytes(lines: Collection<ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    lines.forEach(bytes::write)
    return bytes.toByteArray()
}

private fun logcatTimestamp(line: ByteArray): String? =
    LogcatTimestampRegex.find(line.toString(Charsets.UTF_8))?.groupValues?.get(1)

internal fun buildLogcatCommand(
    pid: Int,
    sinceTimestampMs: Long?,
    isDebugBuild: Boolean,
): List<String> =
    buildList {
        add("logcat")
        add("--pid=$pid")
        add("-d")
        if (sinceTimestampMs != null) {
            add("-T")
            add(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceTimestampMs)))
        }
        if (isDebugBuild) {
            add("*:V")
        } else {
            addAll(listOf("ripdpi-native:V", "ripdpi:V", "AndroidRuntime:E", "*:W"))
        }
    }

internal const val Utf8ContinuationMask = 0xC0
internal const val Utf8ContinuationTag = 0x80
private const val LogcatReadBufferBytes = 8192
private const val Utf8ByteMask = 0xFF
private const val Utf8TwoBytePrefixMask = 0xE0
private const val Utf8TwoBytePrefix = 0xC0
private const val Utf8ThreeBytePrefixMask = 0xF0
private const val Utf8ThreeBytePrefix = 0xE0
private const val Utf8FourBytePrefixMask = 0xF8
private const val Utf8FourBytePrefix = 0xF0
private const val Utf8SingleByteLength = 1
private const val Utf8TwoByteLength = 2
private const val Utf8ThreeByteLength = 3
private const val Utf8FourByteLength = 4

private class RollingByteTail(
    private val capacity: Int,
) {
    private var bytes = ByteArray(0)
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray) {
        bytes =
            when {
                chunk.size >= capacity -> {
                    truncated = truncated || bytes.isNotEmpty() || chunk.size > capacity
                    chunk.copyOfRange(chunk.size - capacity, chunk.size)
                }

                bytes.size + chunk.size <= capacity -> {
                    bytes + chunk
                }

                else -> {
                    truncated = true
                    val keep = capacity - chunk.size
                    bytes.copyOfRange(bytes.size - keep, bytes.size) + chunk
                }
            }
    }

    fun toByteArray(): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
            start += 1
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}

internal const val LogcatTruncationMarker = "\n[logcat truncated: head and tail retained]\n"
internal const val LogcatTailTruncationMarker = "\n[logcat truncated: tail retained]\n"
private val NewLineByte = '\n'.code.toByte()
private val LogcatTimestampRegex = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})")

internal fun headAndTailUtf8Bytes(
    value: String,
    maxBytes: Int,
    truncationMarker: String = LogcatTruncationMarker,
): ByteArray =
    RollingByteHeadAndTail(maxBytes, truncationMarker.toByteArray(Charsets.UTF_8))
        .apply { append(value.toByteArray(Charsets.UTF_8)) }
        .toByteArray()

private class RollingByteHeadAndTail(
    private val capacity: Int,
    private val truncationMarker: ByteArray = LogcatTruncationMarker.toByteArray(Charsets.UTF_8),
) {
    private var completeBytes = ByteArray(0)
    private var headBytes = ByteArray(0)
    private var tail = RollingByteTail(0)
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray) {
        if (!truncated && completeBytes.size + chunk.size <= capacity) {
            completeBytes += chunk
            return
        }
        if (!truncated) {
            truncated = true
            val combined = completeBytes + chunk
            val available = (capacity - truncationMarker.size).coerceAtLeast(0)
            val headCapacity = available / 2
            headBytes = headUtf8Bytes(combined, headCapacity)
            tail = RollingByteTail(available - headBytes.size)
            tail.append(combined.copyOfRange(headBytes.size, combined.size))
            completeBytes = ByteArray(0)
        } else {
            tail.append(chunk)
        }
    }

    fun toByteArray(): ByteArray =
        if (!truncated) {
            completeBytes
        } else {
            headBytes + truncationMarker + tail.toByteArray()
        }
}

private fun headUtf8Bytes(
    bytes: ByteArray,
    maxBytes: Int,
): ByteArray =
    when {
        maxBytes <= 0 -> byteArrayOf()
        bytes.size <= maxBytes -> bytes
        else -> headUtf8Prefix(bytes, maxBytes)
    }

private fun headUtf8Prefix(
    bytes: ByteArray,
    maxBytes: Int,
): ByteArray {
    var end = maxBytes
    var continuationBytes = 0
    while (end - continuationBytes > 0 &&
        bytes[end - continuationBytes - 1].toInt() and Utf8ContinuationMask == Utf8ContinuationTag
    ) {
        continuationBytes += 1
    }
    val leadingIndex = end - continuationBytes - 1
    if (leadingIndex >= 0) {
        val leadingByte = bytes[leadingIndex].toInt() and Utf8ByteMask
        val expectedLength =
            when {
                leadingByte and Utf8ContinuationTag == 0 -> Utf8SingleByteLength
                leadingByte and Utf8TwoBytePrefixMask == Utf8TwoBytePrefix -> Utf8TwoByteLength
                leadingByte and Utf8ThreeBytePrefixMask == Utf8ThreeBytePrefix -> Utf8ThreeByteLength
                leadingByte and Utf8FourBytePrefixMask == Utf8FourBytePrefix -> Utf8FourByteLength
                else -> Utf8SingleByteLength
            }
        if (continuationBytes + Utf8SingleByteLength < expectedLength) end = leadingIndex
    }
    return bytes.copyOfRange(0, end)
}

internal fun tailUtf8Bytes(
    value: String,
    maxBytes: Int,
): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return when {
        maxBytes <= 0 -> {
            byteArrayOf()
        }

        bytes.size <= maxBytes -> {
            bytes
        }

        else -> {
            var start = bytes.size - maxBytes
            while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
                start += 1
            }
            bytes.copyOfRange(start, bytes.size)
        }
    }
}
