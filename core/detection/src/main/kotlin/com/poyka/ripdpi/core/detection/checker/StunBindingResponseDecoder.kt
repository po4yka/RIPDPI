package com.poyka.ripdpi.core.detection.checker

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Reads the reflexive address from a STUN Binding success response (RFC 8489 s14.1, s14.2).
 * Both address families are decoded. XOR-MAPPED-ADDRESS wins over the legacy MAPPED-ADDRESS
 * in any attribute order, and an attribute that cannot be decoded does not stop the search.
 */
internal object StunBindingResponseDecoder {
    fun reflexiveAddress(
        message: ByteArray,
        transactionId: ByteArray,
    ): String? {
        if (!isBindingSuccess(message, transactionId)) return null
        val bodyLength = ByteBuffer.wrap(message, 2, 2).short.toInt() and UnsignedShortMask
        val end = minOf(HeaderBytes + bodyLength, message.size)
        val xorKey =
            ByteBuffer
                .allocate(Ipv6AddressBytes)
                .putInt(MagicCookie)
                .put(transactionId)
                .array()
        var xorMapped: String? = null
        var mapped: String? = null
        var offset = HeaderBytes
        while (xorMapped == null && offset + AttributeHeaderBytes <= end) {
            val attrType = ByteBuffer.wrap(message, offset, 2).short.toInt() and UnsignedShortMask
            val attrLength = ByteBuffer.wrap(message, offset + 2, 2).short.toInt() and UnsignedShortMask
            val valueOffset = offset + AttributeHeaderBytes
            if (valueOffset + attrLength > end) break
            when (attrType) {
                XorMappedAddress -> xorMapped = decodeAddress(message, valueOffset, attrLength, xorKey)
                MappedAddress -> if (mapped == null) mapped = decodeAddress(message, valueOffset, attrLength, null)
            }
            offset =
                valueOffset + attrLength + (AttributeAlignment - attrLength % AttributeAlignment) % AttributeAlignment
        }
        return xorMapped ?: mapped
    }

    private fun isBindingSuccess(
        message: ByteArray,
        transactionId: ByteArray,
    ): Boolean {
        if (message.size < HeaderBytes) return false
        val buffer = ByteBuffer.wrap(message)
        val type = buffer.short.toInt() and UnsignedShortMask
        buffer.short
        val cookie = buffer.int
        val tx = ByteArray(TransactionIdBytes).also(buffer::get)
        return type == BindingResponse && cookie == MagicCookie && tx.contentEquals(transactionId)
    }

    private fun decodeAddress(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        xorKey: ByteArray?,
    ): String? {
        val family = if (length >= AddressValueOffset) bytes[offset + FamilyOffset].toInt() and UnsignedByteMask else 0
        val addressBytes =
            when (family) {
                FamilyIpv4 -> Ipv4AddressBytes
                FamilyIpv6 -> Ipv6AddressBytes
                else -> null
            }?.takeIf { size -> length >= AddressValueOffset + size }
        if (addressBytes == null) return null
        val address =
            ByteArray(addressBytes) { index ->
                val value = bytes[offset + AddressValueOffset + index].toInt()
                (if (xorKey == null) value else value xor xorKey[index].toInt()).toByte()
            }
        return InetAddress.getByAddress(address).hostAddress
    }

    private const val BindingResponse = 0x0101
    private const val MappedAddress = 0x0001
    private const val XorMappedAddress = 0x0020
    private const val MagicCookie = 0x2112A442
    private const val HeaderBytes = 20
    private const val TransactionIdBytes = 12
    private const val AttributeHeaderBytes = 4
    private const val AttributeAlignment = 4
    private const val FamilyOffset = 1
    private const val AddressValueOffset = 4
    private const val FamilyIpv4 = 0x01
    private const val FamilyIpv6 = 0x02
    private const val Ipv4AddressBytes = 4
    private const val Ipv6AddressBytes = 16
    private const val UnsignedByteMask = 0xFF
    private const val UnsignedShortMask = 0xFFFF
}
