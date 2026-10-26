package com.poyka.ripdpi.data

/** Shows only whether a secret or a user-pasted endpoint is present, never its value. */
internal fun redacted(value: String?): String =
    when {
        value == null -> "null"
        value.isEmpty() -> "\"\""
        else -> "<redacted>"
    }

/** Shows only how many addresses a list holds (network-fingerprint-privacy). */
internal fun redacted(values: List<String>): String = if (values.isEmpty()) "[]" else "<redacted ${values.size}>"
