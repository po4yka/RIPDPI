package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownDohProviderSubnetMetadataLookupTest {
    private val metadata = KnownDohProviderSubnetMetadataLookup()

    @Test
    fun googleBootstrapIpMapsToExpectedAsnAndOrg() {
        assertEquals(15169, metadata.asnForIp("8.8.8.8"))
        assertEquals(setOf("Google LLC"), metadata.orgTermsForIp("8.8.8.8"))
        assertEquals(IpRange("8.8.8.0/24"), metadata.subnetForIp("8.8.8.8"))
    }

    @Test
    fun cloudflareBootstrapIpMatchesProviderFilter() {
        val expectedRanges =
            metadata.subnetsForAsn(13335) + metadata.subnetsForOrgTerm("cloudflare")

        assertTrue(IpRange("1.1.1.0/24") in expectedRanges)
        assertEquals(13335, metadata.asnForIp("1.1.1.1"))
    }

    @Test
    fun unknownIpReturnsNoMetadata() {
        assertEquals(null, metadata.asnForIp("203.0.113.10"))
        assertEquals(null, metadata.subnetForIp("203.0.113.10"))
        assertEquals(emptySet<String>(), metadata.orgTermsForIp("203.0.113.10"))
    }
}
