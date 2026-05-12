package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSubnetMetadataLookupTest {
    @Test
    fun runtimeRecordsSupportDirectIpMetadataLookup() {
        val lookup =
            RuntimeSubnetMetadataLookup(
                records =
                    listOf(
                        SubnetMetadata(IpRange("203.0.113.0/24"), asn = 64500, org = "Example Net", country = "ZZ"),
                    ),
            )

        assertEquals(64500, lookup.asnForIp("203.0.113.10"))
        assertEquals(setOf("Example Net"), lookup.orgTermsForIp("203.0.113.10"))
        assertEquals("ZZ", lookup.countryForIp("203.0.113.10"))
        assertEquals(IpRange("203.0.113.0/24"), lookup.subnetForIp("203.0.113.10"))
    }

    @Test
    fun compositeLookupMergesRuntimeRecordsWithFallbackMetadata() {
        val runtime =
            RuntimeSubnetMetadataLookup(
                records =
                    listOf(
                        SubnetMetadata(IpRange("203.0.113.0/24"), asn = 64500, org = "Example Net", country = "ZZ"),
                    ),
            )
        val fallback =
            RuntimeSubnetMetadataLookup(
                records =
                    listOf(
                        SubnetMetadata(IpRange("198.51.100.0/24"), asn = 64501, org = "Fallback Net", country = "YY"),
                    ),
            )
        val lookup = CompositeSubnetMetadataLookup(listOf(runtime, fallback))

        assertEquals(64500, lookup.asnForIp("203.0.113.10"))
        assertEquals(64501, lookup.asnForIp("198.51.100.7"))
        assertEquals(
            setOf(IpRange("203.0.113.0/24"), IpRange("198.51.100.0/24")),
            lookup.subnetsForOrgTerm("net"),
        )
    }
}
