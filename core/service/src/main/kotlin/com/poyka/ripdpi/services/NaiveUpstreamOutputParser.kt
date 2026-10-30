package com.poyka.ripdpi.services

private const val GenericCloseError = "upstream connection closed with an error"
private const val UnrecognizedFormatError = "upstream client output format not recognized"
private const val FormatCheckLineLimit = 20
private const val NoPaddingType = "None"

// "[1030/121157.991006:INFO:net/tools/naive/naive_proxy_bin.cc:549] text"
private val ChromiumPrefix = Regex("""^\[[0-9]+/[0-9.]+:([A-Z]+):([^:\]\s]+):[0-9]+]""")
private val ConnectionAnnounced = Regex("""\bConnection ([0-9]+) to """)
private val ConnectionClosed = Regex("""\bConnection ([0-9]+) closed: (\S+)""")
private val PaddingType = Regex("""negotiated padding type: (\S+)""")
private val NetErrorCode = Regex("""(net::)?ERR_[A-Z0-9_]+""")

/**
 * Reduces lines of upstream Chromium client output to fixed events or fixed-shape messages. The
 * upstream client logs destinations and URLs at INFO level, so free text never leaves this class:
 * only a net error code token and a source file basename are copied.
 *
 * The parser is stateful for one process: a `Connection n closed: OK` line is health evidence only
 * after `Connection n to ` showed that a tunnel was requested. Call [reset] on each process start.
 */
internal class NaiveUpstreamOutputParser {
    private val announcedConnections = HashSet<String>()
    private var linesSeen = 0
    private var formatRecognized = false
    private var formatErrorEmitted = false

    @Synchronized
    fun reset() {
        announcedConnections.clear()
        linesSeen = 0
        formatRecognized = false
        formatErrorEmitted = false
    }

    @Synchronized
    fun parse(
        line: String,
        runtimeKind: String,
    ): SubprocessRelayOutputEvent? {
        val prefix = ChromiumPrefix.find(line)
        linesSeen++
        if (prefix == null) return unrecognizedFormatEvent()
        formatRecognized = true
        val level = prefix.groupValues[1]
        return when {
            level == "ERROR" || level == "FATAL" -> {
                val file = prefix.groupValues[2].substringAfterLast('/')
                SubprocessRelayOutputEvent.PlainError("upstream client error ($file)")
            }

            level == "INFO" -> {
                parseInfo(line, runtimeKind)
            }

            else -> {
                null
            }
        }
    }

    private fun unrecognizedFormatEvent(): SubprocessRelayOutputEvent? =
        if (!formatRecognized && !formatErrorEmitted && linesSeen >= FormatCheckLineLimit) {
            formatErrorEmitted = true
            SubprocessRelayOutputEvent.PlainError(UnrecognizedFormatError)
        } else {
            null
        }

    private fun parseInfo(
        line: String,
        runtimeKind: String,
    ): SubprocessRelayOutputEvent? {
        ConnectionAnnounced.find(line)?.let { announcedConnections += it.groupValues[1] }
        val padding = PaddingType.find(line)?.groupValues?.get(1)
        val closed = ConnectionClosed.find(line)
        return when {
            padding != null -> {
                SubprocessRelayOutputEvent.UpstreamOk(runtimeKind).takeIf { padding != NoPaddingType }
            }

            closed != null -> {
                val announced = announcedConnections.remove(closed.groupValues[1])
                parseClose(closed.groupValues[2], announced, runtimeKind)
            }

            else -> {
                null
            }
        }
    }

    private fun parseClose(
        closeCode: String,
        announced: Boolean,
        runtimeKind: String,
    ): SubprocessRelayOutputEvent? =
        when {
            closeCode == "OK" -> {
                SubprocessRelayOutputEvent.UpstreamOk(runtimeKind).takeIf { announced }
            }

            NetErrorCode.matchEntire(closeCode) == null -> {
                SubprocessRelayOutputEvent.PlainError(GenericCloseError)
            }

            isProxySideNetError(closeCode) -> {
                SubprocessRelayOutputEvent.PlainError("upstream connection closed: $closeCode")
            }

            else -> {
                null
            }
        }
}
