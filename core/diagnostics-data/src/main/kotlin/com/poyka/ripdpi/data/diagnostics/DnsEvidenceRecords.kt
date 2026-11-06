package com.poyka.ripdpi.data.diagnostics

import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.DNSInput
import org.xbill.DNS.Name
import org.xbill.DNS.OPTRecord
import org.xbill.DNS.SOARecord
import java.net.InetAddress

// dnsjava clamps high-bit TTLs, including the OPT control word. Diagnostics need wire evidence.
internal class EvidenceARecord : ARecord(Name.root, DClass.IN, 0, ByteArray(Ipv4Bytes)) {
    override fun rrFromWire(input: DNSInput) {
        ttl = input.wireTtl(ttl)
        super.rrFromWire(input)
    }
}

internal class EvidenceAaaaRecord :
    AAAARecord(Name.root, DClass.IN, 0, InetAddress.getByAddress(ByteArray(Ipv6Bytes))) {
    override fun rrFromWire(input: DNSInput) {
        ttl = input.wireTtl(ttl)
        super.rrFromWire(input)
    }
}

internal class EvidenceSoaRecord : SOARecord(Name.root, DClass.IN, 0, Name.root, Name.root, 0, 0, 0, 0, 0) {
    override fun rrFromWire(input: DNSInput) {
        ttl = input.wireTtl(ttl)
        super.rrFromWire(input)
    }
}

internal class EvidenceOptRecord : OPTRecord(0, 0, 0, 0) {
    override fun rrFromWire(input: DNSInput) {
        ttl = input.wireTtl(ttl)
        super.rrFromWire(input)
    }
}

private fun DNSInput.wireTtl(fallback: Long): Long {
    val rdataStart = current()
    if (rdataStart < TtlAndLengthBytes) return fallback
    save()
    jump(rdataStart - TtlAndLengthBytes)
    val value = readU32()
    restore()
    return value
}

private const val TtlAndLengthBytes = 6

private const val Ipv4Bytes = 4
private const val Ipv6Bytes = 16
