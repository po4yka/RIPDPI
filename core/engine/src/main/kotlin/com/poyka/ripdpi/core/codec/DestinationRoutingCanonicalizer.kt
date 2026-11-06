package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.data.NumericIp

/** Canonical syntax shared by destination-routing wire validation and compiler output. */
internal object DestinationRoutingCanonicalizer {
    fun isCanonicalHost(value: String): Boolean =
        value.length in 1..MaxTokenLength &&
            value.all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '.' } &&
            !value.startsWith('.') &&
            !value.endsWith('.') &&
            !value.contains("..") &&
            value.split('.').all { label ->
                label.length in 1..MaxHostLabelLength &&
                    label.first().isLowerAsciiLetterOrDigit() &&
                    label.last().isLowerAsciiLetterOrDigit()
            }

    fun isCanonicalGeoToken(value: String): Boolean =
        value.length in 1..MaxTokenLength &&
            value.all { it.isLowerAsciiLetterOrDigit() || it == '-' || it == '_' }

    fun isCanonicalCidr(value: String): Boolean = NumericIp.canonicalCidr(value) == value

    private fun Char.isLowerAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private const val MaxTokenLength = 253
    private const val MaxHostLabelLength = 63
}
