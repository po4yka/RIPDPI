package com.poyka.ripdpi.utility

import java.util.Locale

@Suppress("MagicNumber")
internal object NotificationContentBuilder {
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
            bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000f)
            bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
            bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000f)
            else -> "$bytes B"
        }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
