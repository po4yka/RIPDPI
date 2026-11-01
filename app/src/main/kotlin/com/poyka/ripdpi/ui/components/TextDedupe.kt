package com.poyka.ripdpi.ui.components

/**
 * The text to show beside [title], or null when it only repeats the title (ignoring case and surrounding
 * whitespace) or is missing.
 */
fun String?.distinctFrom(title: String): String? =
    this?.takeUnless { it.trim().equals(title.trim(), ignoreCase = true) }
