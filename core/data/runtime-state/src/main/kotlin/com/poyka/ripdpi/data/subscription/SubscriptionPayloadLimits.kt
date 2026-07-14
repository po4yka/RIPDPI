package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import okhttp3.ResponseBody
import okio.Buffer
import java.nio.charset.StandardCharsets

/** Maximum decoded subscription response accepted from a remote endpoint. */
const val MaxSubscriptionPayloadBytes: Long = 2L * 1024L * 1024L

/** Maximum number of selectable profiles accepted from one subscription. */
const val MaxSubscriptionProfiles: Int = 2_048

/** Maximum opaque sing-box config retained in one fallback profile. */
const val MaxSubscriptionRawConfigBytes: Int = 64 * 1024

class SubscriptionPayloadTooLargeException : IllegalArgumentException("subscription payload exceeds limit")

/** Reads at most [MaxSubscriptionPayloadBytes], including responses without a Content-Length header. */
fun ResponseBody.readBoundedSubscriptionPayload(): String {
    val declaredLength = contentLength()
    if (declaredLength > MaxSubscriptionPayloadBytes) throw SubscriptionPayloadTooLargeException()

    val buffer = Buffer()
    val source = source()
    var total = 0L
    while (true) {
        val readLimit = (MaxSubscriptionPayloadBytes + 1L - total).coerceAtMost(ReadChunkBytes)
        val read = source.read(buffer, readLimit)
        if (read == -1L) break
        total += read
        if (total > MaxSubscriptionPayloadBytes) throw SubscriptionPayloadTooLargeException()
    }
    val charset = contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
    return buffer.readString(charset)
}

/** Rejects parser expansion and individually unbounded opaque fallback configs. */
fun List<ProxyProfile>.fitsSubscriptionLimits(): Boolean =
    size <= MaxSubscriptionProfiles &&
        all { profile ->
            profile !is ProxyProfile.RawConfig ||
                profile.config.toByteArray(StandardCharsets.UTF_8).size <= MaxSubscriptionRawConfigBytes
        }

private const val ReadChunkBytes = 8_192L
