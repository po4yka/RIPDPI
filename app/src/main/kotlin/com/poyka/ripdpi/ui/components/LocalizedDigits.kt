package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat

private const val MaxLongDigits = 18

/**
 * Formats a count with the digits of the app locale, so a Persian badge reads "۳۶" like the
 * body text around it instead of "36". Grouping is off: counts, ports and IDs stay compact.
 */
@Composable
internal fun localizedInteger(value: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, value) {
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }.format(value)
    }
}

/** Localizes a value that is a plain ASCII integer, such as a metric count; other text is kept. */
@Composable
internal fun localizedDigits(text: String): String =
    if (text.isNotEmpty() && text.length <= MaxLongDigits && text.all { it in '0'..'9' }) {
        localizedInteger(text.toLong())
    } else {
        text
    }
