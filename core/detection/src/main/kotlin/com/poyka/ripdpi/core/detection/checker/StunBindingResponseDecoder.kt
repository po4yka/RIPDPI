package com.poyka.ripdpi.core.detection.checker

import java.net.InetAddress
import java.nio.ByteBuffer

internal object StunBindingResponseDecoder {
    fun reflexiveAddress(
        message: ByteArray,
        transactionId: ByteArray,
    ): String? = readAddress(message, transactionId, requireXor = false)

    fun isValidBindingResponse(
        message: ByteArray,
        transactionId: ByteArray,
    ): Boolean = readAddress(message, transactionId, requireXor = true) != null

    private fun readAddress(
        message: ByteArray,
        transactionId: ByteArray,
        requireXor: Boolean,
    ): String? {
        if (!isBindingSuccess(message, transactionId)) return null
        val xorKey =
            ByteBuffer
                .allocate(Ipv6AddressBytes)
                .putInt(MagicCookie)
                .put(transactionId)
                .array()
        var xorMapped: String? = null
        var mapped: String? = null
        var offset = HeaderBytes
        var valid = true
        while (offset + AttributeHeaderBytes <= message.size) {
            val attrType = ByteBuffer.wrap(message, offset, 2).short.toInt() and UnsignedShortMask
            val attrLength = ByteBuffer.wrap(message, offset + 2, 2).short.toInt() and UnsignedShortMask
            val valueOffset = offset + AttributeHeaderBytes
            val paddedLength = attrLength + (AttributeAlignment - attrLength % AttributeAlignment) % AttributeAlignment
            valid = paddedLength <= message.size - valueOffset &&
                validAttribute(message, valueOffset, attrType, attrLength)
            if (!valid) break
            when (attrType) {
                XorMappedAddress -> {
                    xorMapped = xorMapped ?: decodeAddress(message, valueOffset, attrLength, xorKey)
                }

                MappedAddress -> {
                    mapped = mapped ?: decodeAddress(message, valueOffset, attrLength, null)
                }
            }
            offset = valueOffset + paddedLength
        }
        return (xorMapped ?: mapped.takeUnless { requireXor }).takeIf { valid && offset == message.size }
    }

    private fun isBindingSuccess(
        message: ByteArray,
        transactionId: ByteArray,
    ): Boolean {
        if (message.size < HeaderBytes || transactionId.size != TransactionIdBytes) return false
        val buffer = ByteBuffer.wrap(message)
        val type = buffer.short.toInt() and UnsignedShortMask
        val bodyLength = buffer.short.toInt() and UnsignedShortMask
        val cookie = buffer.int
        val tx = ByteArray(TransactionIdBytes).also(buffer::get)
        return type == BindingResponse && cookie == MagicCookie && tx.contentEquals(transactionId) &&
            bodyLength % AttributeAlignment == 0 && bodyLength == message.size - HeaderBytes
    }

    private fun validAttribute(
        bytes: ByteArray,
        offset: Int,
        type: Int,
        length: Int,
    ): Boolean =
        when (type) {
            XorMappedAddress, MappedAddress -> validAddressLength(bytes, offset, length)
            MessageIntegrity -> length == Sha1Bytes
            MessageIntegritySha256 -> length == Sha256Bytes
            else -> type >= FirstOptionalAttribute
        }

    private fun validAddressLength(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (length < AddressValueOffset) return false
        return when (bytes[offset + FamilyOffset].toInt() and UnsignedByteMask) {
            FamilyIpv4 -> length == AddressValueOffset + Ipv4AddressBytes
            FamilyIpv6 -> length == AddressValueOffset + Ipv6AddressBytes
            else -> true
        }
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
            }?.takeIf { size -> length == AddressValueOffset + size }
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
    private const val MessageIntegrity = 0x0008
    private const val MessageIntegritySha256 = 0x001C
    private const val FirstOptionalAttribute = 0x8000
    private const val Sha1Bytes = 20
    private const val Sha256Bytes = 32
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
