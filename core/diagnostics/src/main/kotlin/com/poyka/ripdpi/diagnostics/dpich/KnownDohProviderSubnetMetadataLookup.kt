package com.poyka.ripdpi.diagnostics.dpich

class KnownDohProviderSubnetMetadataLookup : SubnetMetadataLookup {
    private val records = KnownDohProviderSubnets

    override fun subnetsForCountry(countryCode: String): Set<IpRange> =
        records
            .filter { record -> record.country.equals(countryCode, ignoreCase = true) }
            .mapTo(linkedSetOf(), SubnetMetadata::range)

    override fun subnetsForOrgTerm(term: String): Set<IpRange> {
        val normalized = term.trim().lowercase()
        return records
            .filter { record -> record.org.lowercase().contains(normalized) }
            .mapTo(linkedSetOf(), SubnetMetadata::range)
    }

    override fun orgTermsForAsn(asn: Int): Set<String> =
        records
            .filter { record -> record.asn == asn }
            .mapTo(linkedSetOf(), SubnetMetadata::org)

    override fun orgTermsForIp(ip: String): Set<String> =
        records
            .filter { record -> record.range.contains(ip) }
            .mapTo(linkedSetOf(), SubnetMetadata::org)

    override fun subnetsForAsn(asn: Int): Set<IpRange> =
        records
            .filter { record -> record.asn == asn }
            .mapTo(linkedSetOf(), SubnetMetadata::range)

    override fun asnForIp(ip: String): Int? = records.firstOrNull { record -> record.range.contains(ip) }?.asn

    override fun subnetForIp(ip: String): IpRange? = records.firstOrNull { record -> record.range.contains(ip) }?.range
}

private fun IpRange.contains(ip: String): Boolean {
    val (network, prefixText) = cidr.split('/').takeIf { parts -> parts.size == 2 } ?: return false
    val prefix = prefixText.toIntOrNull()?.takeIf { value -> value in Ipv4PrefixRange } ?: return false
    val networkBits = network.toIpv4Bits() ?: return false
    val ipBits = ip.toIpv4Bits() ?: return false
    val mask = if (prefix == 0) 0 else -1 shl (Ipv4BitCount - prefix)
    return networkBits and mask == ipBits and mask
}

private fun String.toIpv4Bits(): Int? {
    val octets = split('.')
    if (octets.size != Ipv4OctetCount) return null
    return octets.fold(0) { acc, octet ->
        val value = octet.toIntOrNull()?.takeIf { parsed -> parsed in Ipv4OctetRange } ?: return null
        (acc shl Byte.SIZE_BITS) or value
    }
}

private val KnownDohProviderSubnets =
    listOf(
        SubnetMetadata(IpRange("8.8.8.0/24"), asn = 15169, org = "Google LLC", country = "US"),
        SubnetMetadata(IpRange("8.8.4.0/24"), asn = 15169, org = "Google LLC", country = "US"),
        SubnetMetadata(IpRange("1.1.1.0/24"), asn = 13335, org = "Cloudflare, Inc.", country = "US"),
        SubnetMetadata(IpRange("1.0.0.0/24"), asn = 13335, org = "Cloudflare, Inc.", country = "US"),
        SubnetMetadata(IpRange("94.140.14.0/24"), asn = 212772, org = "AdGuard", country = "CY"),
        SubnetMetadata(IpRange("94.140.15.0/24"), asn = 212772, org = "AdGuard", country = "CY"),
        SubnetMetadata(IpRange("9.9.9.0/24"), asn = 19281, org = "Quad9", country = "US"),
        SubnetMetadata(IpRange("149.112.112.0/24"), asn = 19281, org = "Quad9", country = "US"),
        SubnetMetadata(IpRange("208.67.222.0/24"), asn = 36692, org = "OpenDNS Cisco", country = "US"),
        SubnetMetadata(IpRange("208.67.220.0/24"), asn = 36692, org = "OpenDNS Cisco", country = "US"),
        SubnetMetadata(IpRange("45.90.28.0/24"), asn = 34939, org = "NextDNS", country = "US"),
        SubnetMetadata(IpRange("45.90.30.0/24"), asn = 34939, org = "NextDNS", country = "US"),
    )

private const val Ipv4BitCount = 32
private const val Ipv4OctetCount = 4
private val Ipv4PrefixRange = 0..32
private val Ipv4OctetRange = 0..255
