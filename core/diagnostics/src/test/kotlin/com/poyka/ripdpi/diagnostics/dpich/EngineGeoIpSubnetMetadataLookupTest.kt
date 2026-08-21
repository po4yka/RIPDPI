package com.poyka.ripdpi.diagnostics.dpich

import com.poyka.ripdpi.core.GeoDatabasePaths
import com.poyka.ripdpi.core.RipDpiGeoIpMetadata
import com.poyka.ripdpi.core.RipDpiProxyBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineGeoIpSubnetMetadataLookupTest {
    @Test
    fun directIpMetadataComesFromNativeGeoipLookup() {
        val bindings =
            RecordingBindings(
                metadata =
                    mapOf(
                        "8.8.8.8" to
                            RipDpiGeoIpMetadata(
                                countryCode = "US",
                                asn = 15169,
                                organization = "Google LLC",
                            ),
                    ),
            )
        val lookup =
            EngineGeoIpSubnetMetadataLookup(
                bindings = bindings,
                paths = GeoDatabasePaths("/geo", "/geo/geoip.db", "/geo/geosite.db"),
            )

        assertEquals(15169, lookup.asnForIp("8.8.8.8"))
        assertEquals(setOf("Google LLC"), lookup.orgTermsForIp("8.8.8.8"))
        assertEquals("US", lookup.countryForIp("8.8.8.8"))
        assertEquals(listOf("8.8.8.8"), bindings.lookups)
    }

    @Test
    fun rangeEnumerationRemainsUnavailableForDatabaseBackedLookup() {
        val lookup =
            EngineGeoIpSubnetMetadataLookup(
                bindings =
                    RecordingBindings(
                        metadata = mapOf("8.8.8.8" to RipDpiGeoIpMetadata(countryCode = "US", asn = 15169)),
                    ),
                paths = GeoDatabasePaths("/geo", "/geo/geoip.db", "/geo/geosite.db"),
            )

        assertEquals(emptySet<IpRange>(), lookup.subnetsForAsn(15169))
        assertEquals(emptySet<IpRange>(), lookup.subnetsForOrgTerm("google"))
        assertEquals(emptySet<IpRange>(), lookup.subnetsForCountry("US"))
        assertNull(lookup.subnetForIp("8.8.8.8"))
    }

    @Test
    fun nativeLookupFailureReturnsNoMetadataSoFallbackDelegatesCanAnswer() {
        val lookup =
            EngineGeoIpSubnetMetadataLookup(
                bindings = ThrowingBindings(),
                paths = GeoDatabasePaths("/geo", "/geo/geoip.db", "/geo/geosite.db"),
            )

        assertNull(lookup.asnForIp("8.8.8.8"))
        assertEquals(emptySet<String>(), lookup.orgTermsForIp("8.8.8.8"))
        assertNull(lookup.countryForIp("8.8.8.8"))
    }

    private class RecordingBindings(
        private val metadata: Map<String, RipDpiGeoIpMetadata?>,
    ) : RipDpiProxyBindings {
        val lookups = mutableListOf<String>()

        override fun create(configJson: String): Long = error("unused")

        override fun start(handle: Long): Int = error("unused")

        override fun stop(handle: Long) = error("unused")

        override fun pollTelemetry(handle: Long): String? = error("unused")

        override fun destroy(handle: Long) = error("unused")

        override fun updateNetworkSnapshot(
            handle: Long,
            snapshotJson: String,
        ) = error("unused")

        override fun geoIpMetadata(
            geoipDbPath: String,
            geositeDbPath: String,
            ip: String,
        ): RipDpiGeoIpMetadata? {
            assertEquals("/geo/geoip.db", geoipDbPath)
            assertEquals("/geo/geosite.db", geositeDbPath)
            lookups += ip
            return metadata[ip]
        }
    }

    private class ThrowingBindings : RipDpiProxyBindings {
        override fun create(configJson: String): Long = error("unused")

        override fun start(handle: Long): Int = error("unused")

        override fun stop(handle: Long) = error("unused")

        override fun pollTelemetry(handle: Long): String? = error("unused")

        override fun destroy(handle: Long) = error("unused")

        override fun updateNetworkSnapshot(
            handle: Long,
            snapshotJson: String,
        ) = error("unused")

        override fun geoIpMetadata(
            geoipDbPath: String,
            geositeDbPath: String,
            ip: String,
        ): RipDpiGeoIpMetadata? = throw IllegalArgumentException("missing geoip database")
    }
}
