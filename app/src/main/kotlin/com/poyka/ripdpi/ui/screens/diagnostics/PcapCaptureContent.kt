package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.pcap.PcapReader
import com.poyka.ripdpi.pcap.PcapReaderRecord
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Locale

private const val MaxVisiblePackets = 2_000
private const val MicrosPerSecond = 1_000_000L
private const val HexByteMask = 0xff
private const val HexNibbleMask = 0x0f
private const val HexNibbleBits = 4
private const val HexLineBytes = 16
private const val HexCharsPerByte = 3
private const val HexDigits = "0123456789abcdef"
private const val Ipv4Version = 4
private const val Ipv6Version = 6
private const val Ipv4HeaderBytes = 20
private const val Ipv4AddressBytes = 4
private const val Ipv6HeaderBytes = 40
private const val Ipv4SourceOffset = 12
private const val Ipv4DestinationOffset = 16
private const val Ipv4ProtocolOffset = 9
private const val Ipv6SourceOffset = 8
private const val Ipv6DestinationOffset = 24
private const val Ipv6ProtocolOffset = 6
private const val Ipv6AddressBytes = 16
private const val TcpProtocol = 6
private const val UdpProtocol = 17

internal data class CapturePackets(
    val packets: ImmutableList<PcapPacket>,
    val hasMore: Boolean,
)

internal fun captureFile(
    directory: File,
    fileName: String,
): File {
    if (fileName.isBlank() || fileName != File(fileName).name || !fileName.endsWith(".pcap")) {
        throw IOException("Invalid capture name")
    }
    val file = File(directory, fileName).canonicalFile
    if (file.parentFile != directory.canonicalFile || !file.isFile) {
        throw IOException("Capture unavailable")
    }
    return file
}

internal fun readCapturePackets(
    directory: File,
    fileName: String,
): CapturePackets =
    PcapReader.open(captureFile(directory, fileName)).use { reader ->
        val packets = ArrayList<PcapPacket>()
        var firstTimestamp = 0L
        while (packets.size < MaxVisiblePackets) {
            val record = reader.readOne() ?: break
            if (packets.isEmpty()) firstTimestamp = record.tsMicros
            packets += record.toPacket(firstTimestamp)
        }
        CapturePackets(packets.toPersistentList(), packets.size == MaxVisiblePackets && reader.readOne() != null)
    }

private fun PcapReaderRecord.toPacket(firstTimestamp: Long): PcapPacket {
    val header = bytes.ipHeader()
    val source = header?.address(bytes, header.sourceOffset) ?: "?"
    val destination = header?.address(bytes, header.destinationOffset) ?: "?"
    val protocol =
        when (header?.protocol) {
            TcpProtocol -> PcapPacketProtocol.Tcp
            UdpProtocol -> PcapPacketProtocol.Udp
            else -> PcapPacketProtocol.Other
        }
    val elapsed = (tsMicros - firstTimestamp).coerceAtLeast(0)
    return PcapPacket(
        index = index,
        timeLabel = "%d.%06d".format(Locale.ROOT, elapsed / MicrosPerSecond, elapsed % MicrosPerSecond),
        src = source,
        dst = destination,
        protocol = protocol,
        summary = "$origLen B",
        hexDump = "",
        rawBytes = bytes,
        capturedAtMicros = tsMicros,
    )
}

private fun ByteArray.ipHeader(): IpHeader? =
    when ((firstOrNull()?.toInt() ?: 0) ushr HexNibbleBits) {
        Ipv4Version -> {
            if (
                size >= Ipv4HeaderBytes &&
                (this[0].toInt() and HexNibbleMask) * Ipv4AddressBytes in Ipv4HeaderBytes..size
            ) {
                IpHeader(
                    Ipv4SourceOffset,
                    Ipv4DestinationOffset,
                    Ipv4AddressBytes,
                    this[Ipv4ProtocolOffset].toInt() and HexByteMask,
                )
            } else {
                null
            }
        }

        Ipv6Version -> {
            if (size >= Ipv6HeaderBytes) {
                IpHeader(
                    Ipv6SourceOffset,
                    Ipv6DestinationOffset,
                    Ipv6AddressBytes,
                    this[Ipv6ProtocolOffset].toInt() and HexByteMask,
                )
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

private data class IpHeader(
    val sourceOffset: Int,
    val destinationOffset: Int,
    val addressBytes: Int,
    val protocol: Int,
) {
    fun address(
        bytes: ByteArray,
        offset: Int,
    ): String = InetAddress.getByAddress(bytes.copyOfRange(offset, offset + addressBytes)).hostAddress ?: "?"
}

internal fun formatPcapHex(bytes: ByteArray): String =
    buildString(bytes.size * HexCharsPerByte) {
        bytes.forEachIndexed { position, byte ->
            if (position > 0) append(if (position % HexLineBytes == 0) '\n' else ' ')
            val value = byte.toInt() and HexByteMask
            append(HexDigits[value ushr HexNibbleBits])
            append(HexDigits[value and HexNibbleMask])
        }
    }
