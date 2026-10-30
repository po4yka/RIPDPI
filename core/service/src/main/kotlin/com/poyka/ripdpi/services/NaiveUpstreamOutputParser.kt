package com.poyka.ripdpi.services

private const val PaddingEvidence = "negotiated padding type:"
private const val GenericCloseError = "upstream connection closed with an error"

// "[1030/121157.991006:INFO:net/tools/naive/naive_proxy_bin.cc:549] text"
private val ChromiumPrefix = Regex("""^\[[0-9]+/[0-9.]+:([A-Z]+):([^:\]\s]+):[0-9]+]""")
private val ConnectionClosed = Regex("""\bConnection [0-9]+ closed: (\S+)""")
private val NetErrorCode = Regex("""(net::)?ERR_[A-Z_]+""")

/**
 * Reduces one line of upstream Chromium client output to a fixed event or a fixed-shape message.
 * The upstream client logs destinations and URLs at INFO level, so free text never leaves this
 * function: only the net error code token and a source file basename are copied.
 */
internal fun parseNaiveUpstreamLine(
    line: String,
    runtimeKind: String,
): SubprocessRelayOutputEvent? {
    val prefix = ChromiumPrefix.find(line) ?: return null
    val level = prefix.groupValues[1]
    val closeCode = ConnectionClosed.find(line)?.groupValues?.get(1)
    return when {
        level == "ERROR" || level == "FATAL" -> {
            val file = prefix.groupValues[2].substringAfterLast('/')
            SubprocessRelayOutputEvent.PlainError("upstream client error ($file)")
        }

        level != "INFO" -> {
            null
        }

        line.contains(PaddingEvidence) || closeCode == "OK" -> {
            SubprocessRelayOutputEvent.UpstreamOk(runtimeKind)
        }

        closeCode != null -> {
            val code = NetErrorCode.matchEntire(closeCode)?.value
            SubprocessRelayOutputEvent.PlainError(
                code?.let { "upstream connection closed: $it" } ?: GenericCloseError,
            )
        }

        else -> {
            null
        }
    }
}
