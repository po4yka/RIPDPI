package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudflareMasqueGeohashResolverTest {
    @Test
    fun `buildCloudflareMasqueRegionalHint uses regional precision by default`() {
        assertEquals(
            "u4p-GB",
            buildCloudflareMasqueRegionalHint(
                latitude = 57.64911,
                longitude = 10.40744,
                countryCode = "gb",
            ),
        )
    }

    @Test
    fun `buildCloudflareMasqueRegionalHint supports explicit precision override`() {
        assertEquals(
            "u4pr-GB",
            buildCloudflareMasqueRegionalHint(
                latitude = 57.64911,
                longitude = 10.40744,
                countryCode = "GB",
                precision = 4,
            ),
        )
    }

    @Test
    fun `buildCloudflareMasqueRegionalHint returns null for blank country code`() {
        assertNull(
            buildCloudflareMasqueRegionalHint(
                latitude = 57.64911,
                longitude = 10.40744,
                countryCode = " ",
            ),
        )
    }
}
