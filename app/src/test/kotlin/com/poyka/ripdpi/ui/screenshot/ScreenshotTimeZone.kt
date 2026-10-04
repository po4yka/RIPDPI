package com.poyka.ripdpi.ui.screenshot

import java.util.TimeZone

/** Pin fixed fixture epochs while preserving production and neighboring tests’ local time. */
internal inline fun <T> withUtcScreenshotTime(block: () -> T): T {
    val previous = TimeZone.getDefault()
    return try {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        block()
    } finally {
        TimeZone.setDefault(previous)
    }
}
