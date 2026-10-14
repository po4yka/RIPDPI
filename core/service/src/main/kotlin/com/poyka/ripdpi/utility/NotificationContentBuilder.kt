package com.poyka.ripdpi.utility

import android.content.Context
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.core.service.R
import java.util.Locale

internal object NotificationContentBuilder {
    private const val BytesPerKilobyte = 1_000L
    private const val BytesPerMegabyte = 1_000_000L
    private const val BytesPerGigabyte = 1_000_000_000L
    private const val SecondsPerMinute = 60L
    private const val SecondsPerHour = 3_600L

    fun buildContentText(
        context: Context,
        txBytes: Long,
        rxBytes: Long,
        elapsedMs: Long,
    ): String {
        val locale =
            ContextCompat
                .getContextForLanguage(context)
                .resources.configuration.locales[0]
        val up = formatBytes(txBytes, locale)
        val down = formatBytes(rxBytes, locale)
        val duration = formatDuration(elapsedMs)
        return "\u2191 $up  \u2193 $down  |  $duration"
    }

    fun buildSubText(
        context: Context,
        activeSessions: Long,
        rttMs: Long?,
    ): String? {
        if (activeSessions <= 0 && rttMs == null) return null
        val resources = ContextCompat.getContextForLanguage(context).resources
        return buildList {
            if (activeSessions > 0) {
                // Android plural selection takes an Int; preserve the exact Long in the label.
                val quantity = activeSessions.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                add(resources.getQuantityString(R.plurals.notification_active_sessions, quantity, activeSessions))
            }
            if (rttMs != null && rttMs > 0) add("RTT ${rttMs}ms")
        }.joinToString("  \u00B7  ")
    }

    private fun formatBytes(
        bytes: Long,
        locale: Locale,
    ): String =
        when {
            bytes >= BytesPerGigabyte -> String.format(locale, "%.1f GB", bytes / BytesPerGigabyte.toFloat())
            bytes >= BytesPerMegabyte -> String.format(locale, "%.1f MB", bytes / BytesPerMegabyte.toFloat())
            bytes >= BytesPerKilobyte -> String.format(locale, "%.1f KB", bytes / BytesPerKilobyte.toFloat())
            else -> "$bytes B"
        }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / BytesPerKilobyte).coerceAtLeast(0L)
        val hours = totalSeconds / SecondsPerHour
        val minutes = (totalSeconds % SecondsPerHour) / SecondsPerMinute
        val seconds = totalSeconds % SecondsPerMinute
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
