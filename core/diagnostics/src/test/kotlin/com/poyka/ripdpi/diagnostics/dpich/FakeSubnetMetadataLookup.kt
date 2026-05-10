package com.poyka.ripdpi.diagnostics.dpich

class FakeSubnetMetadataLookup(
    private val records: List<SubnetMetadata>,
    private val ipToAsn: Map<String, Int> = emptyMap(),
    private val ipToSubnet: Map<String, IpRange> = emptyMap(),
) : SubnetMetadataLookup {
    override fun subnetsForCountry(countryCode: String): Set<IpRange> =
        records
            .filter { record -> record.country.equals(countryCode, ignoreCase = true) }
            .mapTo(mutableSetOf()) { record -> record.range }

    override fun subnetsForOrgTerm(term: String): Set<IpRange> {
        val normalizedTerm = term.lowercase()
        return records
            .filter { record -> record.org.lowercase().contains(normalizedTerm) }
            .mapTo(mutableSetOf()) { record -> record.range }
    }

    override fun orgTermsForAsn(asn: Int): Set<String> =
        records
            .filter { record -> record.asn == asn }
            .mapTo(mutableSetOf()) { record -> record.org }

    override fun orgTermsForIp(ip: String): Set<String> = asnForIp(ip)?.let(::orgTermsForAsn).orEmpty()

    override fun subnetsForAsn(asn: Int): Set<IpRange> =
        records
            .filter { record -> record.asn == asn }
            .mapTo(mutableSetOf()) { record -> record.range }

    override fun asnForIp(ip: String): Int? = ipToAsn[ip]

    override fun subnetForIp(ip: String): IpRange? = ipToSubnet[ip]
}
