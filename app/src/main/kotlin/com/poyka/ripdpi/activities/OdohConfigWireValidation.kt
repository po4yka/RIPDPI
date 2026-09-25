package com.poyka.ripdpi.activities

private const val HexRadix = 16
private const val HexByteChars = 2
private const val HexHighShift = 4
private const val ByteShift = 8
private const val ConfigLengthPrefixSize = 2
private const val ConfigHeaderSize = 4
private const val ConfigContentsSize = 40
private const val SupportedOdohVersion = 1
private const val SupportedKemId = 32
private const val SupportedKdfId = 1
private const val SupportedAeadId = 1
private const val PublicKeySize = 32
private const val KdfOffset = 2
private const val AeadOffset = 4
private const val PublicKeyLengthOffset = 6

/** Matches the length and ciphersuite checks used by the native ODoH config parser. */
fun OdohResolverFields.hasSupportedConfigWire(): Boolean {
    val bytes = decodeHexBytes(configsHex) ?: return false
    return containsSupportedConfig(bytes)
}

private fun decodeHexBytes(hex: String): List<Int>? {
    val validHex = hex.takeIf { it.isNotEmpty() && it.length % HexByteChars == 0 }
    val decoded =
        validHex?.chunked(HexByteChars)?.mapNotNull { pair ->
            val high = pair[0].digitToIntOrNull(HexRadix)
            val low = pair[1].digitToIntOrNull(HexRadix)
            if (high == null || low == null) null else (high shl HexHighShift) or low
        }
    return decoded?.takeIf { it.size == hex.length / HexByteChars }
}

private fun containsSupportedConfig(bytes: List<Int>): Boolean {
    if (bytes.size < ConfigLengthPrefixSize) return false
    var offset = ConfigLengthPrefixSize
    var valid = readU16(bytes, 0) == bytes.size - ConfigLengthPrefixSize
    var supported = false
    while (valid && offset < bytes.size) {
        valid = bytes.size - offset >= ConfigHeaderSize
        if (valid) {
            val version = readU16(bytes, offset)
            val contentsSize = readU16(bytes, offset + ConfigLengthPrefixSize)
            offset += ConfigHeaderSize
            valid = contentsSize == ConfigContentsSize && bytes.size - offset >= contentsSize
            if (valid) {
                val suite =
                    listOf(
                        readU16(bytes, offset),
                        readU16(bytes, offset + KdfOffset),
                        readU16(bytes, offset + AeadOffset),
                        readU16(bytes, offset + PublicKeyLengthOffset),
                    )
                valid = suite == listOf(SupportedKemId, SupportedKdfId, SupportedAeadId, PublicKeySize)
                supported = supported || (valid && version == SupportedOdohVersion)
                offset += contentsSize
            }
        }
    }
    return valid && supported && offset == bytes.size
}

private fun readU16(
    bytes: List<Int>,
    offset: Int,
): Int = (bytes[offset] shl ByteShift) or bytes[offset + 1]
