package com.poyka.ripdpi.diagnostics.export

import java.util.Base64

internal fun redactEncodedHostCarriers(value: String): String =
    value
        .replaceSensitiveCarrier(DecimalByteArrayCarrierRegex) { carrier ->
            carrier
                .substring(DecimalOpeningBracketLength, carrier.length - DecimalClosingBracketLength)
                .split(',')
                .map { token -> token.trim().toInt().toByte() }
                .toByteArray()
        }.replaceSensitiveCarrier(HexByteCarrierRegex) { carrier ->
            ByteArray(carrier.length / HexCharactersPerByte) { index ->
                val start = index * HexCharactersPerByte
                carrier.substring(start, start + HexCharactersPerByte).toInt(HexRadix).toByte()
            }
        }.replaceSensitiveCarrier(Base64ByteCarrierRegex, ::decodeBase64Carrier)

private fun String.replaceSensitiveCarrier(
    regex: Regex,
    decode: (String) -> ByteArray?,
): String =
    regex.replace(this) { match ->
        val decoded = runCatching { decode(match.value) }.getOrNull()
        if (decoded?.containsDnsName() == true) EncodedHostRedactionMarker else match.value
    }

private fun decodeBase64Carrier(carrier: String): ByteArray? {
    if (carrier.length % Base64BlockSize == InvalidUnpaddedBase64Remainder) return null
    val padded = carrier + "=".repeat((Base64BlockSize - carrier.length % Base64BlockSize) % Base64BlockSize)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrElse {
        runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }
}

private fun ByteArray.containsDnsName(): Boolean {
    val printableRun = StringBuilder()

    fun flush(): Boolean {
        val containsDnsName = containsDiagnosticsDnsName(printableRun)
        printableRun.clear()
        return containsDnsName
    }

    for (byte in this) {
        val octet = byte.toInt() and UnsignedByteMask
        if (octet in PrintableAsciiRange) {
            printableRun.append(octet.toChar())
        } else if (flush()) {
            return true
        }
    }
    return flush()
}

private const val EncodedHostRedactionMarker = "<encoded-host-redacted>"
private const val DecimalOpeningBracketLength = 1
private const val DecimalClosingBracketLength = 1
private const val HexCharactersPerByte = 2
private const val HexRadix = 16
private const val Base64BlockSize = 4
private const val InvalidUnpaddedBase64Remainder = 1
private const val UnsignedByteMask = 0xff
private const val PrintableAsciiStart = 0x21
private const val PrintableAsciiEnd = 0x7e
private const val DecimalBytePattern = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
private val DecimalByteArrayCarrierRegex =
    Regex("\\[(?:\\s*$DecimalBytePattern\\s*,){7,1023}\\s*$DecimalBytePattern\\s*]")
private val HexByteCarrierRegex = Regex("(?i)(?<![A-Za-z0-9])(?:[0-9a-f]{2}){8,1024}(?![A-Za-z0-9])")
private val Base64ByteCarrierRegex =
    Regex("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{12,1368}={0,2}(?![A-Za-z0-9+/_=-])")
private val PrintableAsciiRange = PrintableAsciiStart..PrintableAsciiEnd
