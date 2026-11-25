package com.poyka.ripdpi.data.diagnostics

import org.xbill.DNS.DNSInput
import org.xbill.DNS.Flags
import org.xbill.DNS.Header
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.OPTRecord
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import org.xbill.DNS.WireParseException
import java.nio.ByteBuffer

internal fun validateDnsRecordWire(
    bytes: ByteArray,
    message: Message,
) {
    val checkHttps =
        !message.header.getFlag(Flags.TC.toInt()) &&
            (Section.ANSWER..Section.ADDITIONAL).any { section ->
                message.getSection(section).any { it.type == Type.HTTPS }
            }
    val opt = message.opt?.takeIf { it.ttl == 0L }
    if (!checkHttps && opt == null) return

    val buffer = ByteBuffer.wrap(bytes)
    val input = DNSInput(buffer)
    input.jump(Header.LENGTH)
    if (message.question != null) {
        Name(input)
        input.readU16()
        input.readU16()
    }

    var restoredOpt: OPTRecord? = null
    for (section in Section.ANSWER..Section.ADDITIONAL) {
        for (record in message.getSection(section)) {
            Name(input)
            input.readU16()
            input.readU16()
            val ttl = input.readU32()
            val length = input.readU16()
            if (checkHttps && record.type == Type.HTTPS) {
                input.setActive(length)
                validateHttpsRdata(input)
                input.clearActive()
            } else {
                buffer.position(buffer.position() + length)
            }

            if (opt != null && record === opt && ttl >= HighTtlBit) {
                restoredOpt =
                    OPTRecord(
                        opt.payloadSize,
                        (ttl shr OptExtendedRcodeShift).toInt(),
                        (ttl shr OptVersionShift).toInt() and OptVersionMask,
                        ttl.toInt() and OptFlagsMask,
                        opt.options,
                    )
            }
        }
    }

    if (restoredOpt != null) {
        val records = message.getSection(Section.ADDITIONAL).map { if (it === opt) restoredOpt else it }
        message.removeAllRecords(Section.ADDITIONAL)
        records.forEach { message.addRecord(it, Section.ADDITIONAL) }
    }
}

internal fun validateHttpsRdata(input: DNSInput) {
    input.readU16()
    val nameStart = input.current()
    val target = Name(input)
    if (input.current() - nameStart != target.length().toInt()) {
        throw WireParseException("Compressed HTTPS target name")
    }
    var previousKey = -1
    while (input.remaining() > 0) {
        val key = input.readU16()
        if (key <= previousKey) throw WireParseException("HTTPS parameter keys must increase")
        input.readByteArray(input.readU16())
        previousKey = key
    }
}

private const val HighTtlBit = 0x80000000L
private const val OptExtendedRcodeShift = 24
private const val OptVersionShift = 16
private const val OptVersionMask = 0xff
private const val OptFlagsMask = 0xffff
