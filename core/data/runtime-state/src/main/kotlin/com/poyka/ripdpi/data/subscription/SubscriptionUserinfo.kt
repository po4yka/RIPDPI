package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.Serializable

/**
 * Typed view of the `Subscription-Userinfo` HTTP response header most
 * commercial bypass subscriptions emit on a refresh GET.
 *
 * Header format (semicolon-separated `key=value` pairs):
 * ```
 * upload=N; download=N; total=N; expire=UNIX_TS
 * ```
 *
 * Robustness contract — every field is `Long?`:
 * - A missing field is `null`.
 * - A malformed value (non-numeric, empty) is `null`, never an exception.
 *
 * NekoBox's `RawUpdater` hard-fails when a field is absent; RIPDPI deliberately
 * does not, so a provider that emits only `expire=` still completes the
 * refresh. [upload], [download] and [total] are byte counts; [expire] is a Unix
 * timestamp in seconds.
 */
@Serializable
data class SubscriptionUserinfo(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
) {
    /**
     * Bytes used so far (`upload + download`), or `null` when either input is
     * absent.
     */
    val bytesUsed: Long?
        get() {
            val up = upload ?: return null
            val down = download ?: return null
            return up + down
        }

    /**
     * Bytes remaining in the quota (`total - bytesUsed`), or `null` when
     * [total] or [bytesUsed] is absent.
     */
    val bytesRemaining: Long?
        get() {
            val cap = total ?: return null
            val used = bytesUsed ?: return null
            return cap - used
        }

    /**
     * `true` when [expire] is set and lies at or before [nowEpochSeconds]. A
     * userinfo with no [expire] is never reported as expired.
     */
    fun isExpired(nowEpochSeconds: Long): Boolean {
        val expiry = expire ?: return false
        return expiry <= nowEpochSeconds
    }

    companion object {
        /**
         * Parses a raw `Subscription-Userinfo` header value. Unknown keys are
         * ignored; malformed numeric values become `null`. An empty or blank
         * header yields an all-`null` [SubscriptionUserinfo]. Never throws.
         */
        fun parse(header: String): SubscriptionUserinfo {
            val trimmed = header.trim()
            if (trimmed.isEmpty()) return SubscriptionUserinfo()
            val fields = HashMap<String, Long?>()
            for (segment in trimmed.split(';')) {
                val separator = segment.indexOf('=')
                if (separator <= 0) continue
                val key = segment.substring(0, separator).trim().lowercase()
                val value = segment.substring(separator + 1).trim()
                fields[key] = value.toLongOrNull()
            }
            return SubscriptionUserinfo(
                upload = fields["upload"],
                download = fields["download"],
                total = fields["total"],
                expire = fields["expire"],
            )
        }
    }
}
