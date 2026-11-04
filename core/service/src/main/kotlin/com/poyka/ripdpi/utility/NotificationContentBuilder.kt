package com.poyka.ripdpi.utility

import android.content.Context
import androidx.core.content.ContextCompat
import java.util.Locale

internal object NotificationContentBuilder {
    private const val BytesPerKilobyte = 1_000L
    private const val BytesPerMegabyte = 1_000_000L
    private const val BytesPerGigabyte = 1_000_000_000L

    fun buildContentText(
        context: Context,
        txBytes: Long,
        rxBytes: Long,
    ): String {
        val locale =
            ContextCompat
                .getContextForLanguage(context)
                .resources.configuration.locales[0]
        val up = formatBytes(txBytes, locale)
        val down = formatBytes(rxBytes, locale)
        return "\u2191 $up  \u2193 $down"
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
}
