package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import java.util.Locale

/** Known Telegram captions. Unknown evidence values keep their original text. */
@StringRes
internal fun telegramStatusLabelRes(status: String): Int? =
    when (status) {
        "ok" -> R.string.diagnostics_telegram_status_ok
        "slow" -> R.string.diagnostics_telegram_status_slow
        "stalled" -> R.string.diagnostics_telegram_status_stalled
        "blocked" -> R.string.diagnostics_telegram_status_blocked
        "partial" -> R.string.diagnostics_telegram_status_partial
        "error" -> R.string.diagnostics_telegram_status_error
        "cancelled" -> R.string.diagnostics_telegram_status_cancelled
        "deadline_exceeded" -> R.string.diagnostics_telegram_status_deadline_exceeded
        "unknown" -> R.string.diagnostics_telegram_status_unknown
        "unreachable" -> R.string.diagnostics_telegram_status_unreachable
        else -> null
    }

private const val BitsPerByte = 8.0
private const val DecimalKilo = 1_000.0
private const val DecimalMega = 1_000_000.0

/** Native Telegram avgBps and peakBps contain bytes per second, despite their wire names. */
internal fun formatTelegramThroughput(
    bytesPerSecond: Long,
    locale: Locale,
): String {
    val bitsPerSecond = bytesPerSecond.toDouble() * BitsPerByte
    return when {
        bitsPerSecond >= DecimalMega -> String.format(locale, "%.1f Mbps", bitsPerSecond / DecimalMega)
        bitsPerSecond >= DecimalKilo -> String.format(locale, "%.1f Kbps", bitsPerSecond / DecimalKilo)
        else -> String.format(locale, "%.0f bps", bitsPerSecond)
    }
}
