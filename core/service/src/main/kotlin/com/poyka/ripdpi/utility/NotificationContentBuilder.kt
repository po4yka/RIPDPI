package com.poyka.ripdpi.utility

import java.util.Locale

internal object NotificationContentBuilder {
    private const val BytesPerKilobyte = 1_000L
    private const val BytesPerMegabyte = 1_000_000L
    private const val BytesPerGigabyte = 1_000_000_000L
    private const val SecondsPerMinute = 60L
    private const val SecondsPerHour = 3_600L

    fun buildContentText(
        txBytes: Long,
        rxBytes: Long,
        elapsedMs: Long,
    ): String {
        val up = formatBytes(txBytes)
        val down = formatBytes(rxBytes)
        val duration = formatDuration(elapsedMs)
        return "\u2191 $up  \u2193 $down  |  $duration"
    }

    fun buildSubText(
        activeSessions: Long,
        rttMs: Long?,
    ): String? {
        if (activeSessions <= 0 && rttMs == null) return null
        val parts = mutableListOf<String>()
        if (activeSessions > 0) {
            parts += "$activeSessions active sessions"
        }
        if (rttMs != null && rttMs > 0) {
            parts += "RTT ${rttMs}ms"
        }
        return parts.joinToString("  \u00B7  ")
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= BytesPerGigabyte -> String.format(Locale.US, "%.1f GB", bytes / BytesPerGigabyte.toFloat())
            bytes >= BytesPerMegabyte -> String.format(Locale.US, "%.1f MB", bytes / BytesPerMegabyte.toFloat())
            bytes >= BytesPerKilobyte -> String.format(Locale.US, "%.1f KB", bytes / BytesPerKilobyte.toFloat())
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
