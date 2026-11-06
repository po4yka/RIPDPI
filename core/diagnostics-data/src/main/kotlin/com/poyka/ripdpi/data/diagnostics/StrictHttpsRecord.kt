package com.poyka.ripdpi.data.diagnostics

import org.xbill.DNS.DClass
import org.xbill.DNS.DNSInput
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Name
import org.xbill.DNS.WireParseException

/** Enforce RFC 9460 wire constraints that dnsjava does not check. */
internal class StrictHttpsRecord : HTTPSRecord(Name.root, DClass.IN, 0, 0, Name.root, emptyList()) {
    override fun rrFromWire(input: DNSInput) {
        val start = input.current()
        val activeEnd = input.saveActive()
        input.readU16()
        val nameStart = input.current()
        val target = Name(input)
        if (input.current() - nameStart != target.toWire().size) {
            throw WireParseException("Compressed HTTPS target name")
        }
        var previousKey = -1
        while (input.remaining() > 0) {
            val key = input.readU16()
            if (key <= previousKey) throw WireParseException("HTTPS parameter keys must increase")
            input.readByteArray(input.readU16())
            previousKey = key
        }
        // Name parsing uses DNSInput's single save slot for compression pointers.
        input.jump(start)
        input.restoreActive(activeEnd)
        super.rrFromWire(input)
    }
}
