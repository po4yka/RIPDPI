package com.poyka.ripdpi.diagnostics.dpich

class RuntimeSubnetMetadataLookup(
    private val records: List<SubnetMetadata>,
) : SubnetMetadataLookup {
    override fun subnetsForCountry(countryCode: String): Set<IpRange> =
        records
            .filter { record -> record.country.equals(countryCode, ignoreCase = true) }
            .mapTo(linkedSetOf(), SubnetMetadata::range)

    override fun countryForIp(ip: String): String? =
        records
            .firstOrNull { record -> record.range.contains(ip) }
            ?.country

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

class CompositeSubnetMetadataLookup(
    private val delegates: List<SubnetMetadataLookup>,
) : SubnetMetadataLookup {
    override fun subnetsForCountry(countryCode: String): Set<IpRange> =
        delegates.flatMapTo(linkedSetOf()) { delegate -> delegate.subnetsForCountry(countryCode) }

    override fun countryForIp(ip: String): String? =
        delegates.firstNotNullOfOrNull { delegate -> delegate.countryForIp(ip) }

    override fun subnetsForOrgTerm(term: String): Set<IpRange> =
        delegates.flatMapTo(linkedSetOf()) { delegate -> delegate.subnetsForOrgTerm(term) }

    override fun orgTermsForAsn(asn: Int): Set<String> =
        delegates.flatMapTo(linkedSetOf()) { delegate -> delegate.orgTermsForAsn(asn) }

    override fun orgTermsForIp(ip: String): Set<String> =
        delegates.flatMapTo(linkedSetOf()) { delegate -> delegate.orgTermsForIp(ip) }

    override fun subnetsForAsn(asn: Int): Set<IpRange> =
        delegates.flatMapTo(linkedSetOf()) { delegate -> delegate.subnetsForAsn(asn) }

    override fun asnForIp(ip: String): Int? = delegates.firstNotNullOfOrNull { delegate -> delegate.asnForIp(ip) }

    override fun subnetForIp(ip: String): IpRange? =
        delegates.firstNotNullOfOrNull { delegate -> delegate.subnetForIp(ip) }
}
